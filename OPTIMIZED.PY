import psycopg2
import psycopg2.extras
from typing import Dict, Any, List, Optional
import logging
import os
import json
from datetime import datetime, date
from decimal import Decimal
from contextlib import contextmanager
from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# --- Self-Contained Dummy RedisClient ---
class StandaloneRedisClient:
    def __init__(self):
        logger.info("Initialized StandaloneRedisClient. Caching is disabled.")
        self.connected = False
    def is_connected(self) -> bool: return self.connected
    def get_cached_schema(self, cache_key: str) -> Optional[Dict[str, Any]]: return None
    def cache_schema(self, cache_key: str, schema_data: Dict[str, Any], ttl_seconds: int = 3600) -> bool: return False
    def generate_schema_cache_key(self, table_names: Optional[list] = None, include_samples: bool = False) -> str:
        key = "schema:all_tables" if not table_names else f"schema:{'_'.join(sorted(table_names))}"
        if include_samples: key += ":with_samples"
        return key

def get_redis_client() -> StandaloneRedisClient:
    return StandaloneRedisClient()

def make_json_serializable(data):
    if isinstance(data, dict): return {key: make_json_serializable(value) for key, value in data.items()}
    if isinstance(data, list): return [make_json_serializable(item) for item in data]
    if isinstance(data, (datetime, date)): return data.isoformat()
    if isinstance(data, Decimal): return float(data)
    if data is None: return None
    if isinstance(data, (str, int, float, bool)): return data
    return str(data)

class DatabaseOperations:
    def __init__(self):
        self.connection_string = os.getenv("DATABASE_URL")
        if not self.connection_string:
            logger.warning("DATABASE_URL not set, using default.")
            self.connection_string = "postgresql://postgres:password@localhost:5432/postgres"
        self.redis_client = get_redis_client()
        logger.info("DatabaseOperations initialized")
    
    @contextmanager
    def get_connection(self):
        conn = None
        try:
            conn = psycopg2.connect(self.connection_string)
            yield conn
        except Exception as e:
            if conn: conn.rollback()
            logger.error(f"Database connection error: {e}")
            raise
        finally:
            if conn: conn.close()
    
    async def fetch_schema_context(self, table_names: Optional[List[str]] = None, include_samples: bool = False, schema_name: str = 'public') -> Dict[str, Any]:
        try:
            cache_key = self.redis_client.generate_schema_cache_key(table_names, include_samples)
            if self.redis_client.is_connected():
                cached_schema = self.redis_client.get_cached_schema(cache_key)
                if cached_schema: return {"status": "success", "schema_context": cached_schema, "cached": True}

            logger.info(f"Fetching schema from database for schema '{schema_name}', tables: {table_names or 'all'}")
            
            with self.get_connection() as conn:
                with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cursor:
                    schema_context = {
                        "tables": {}, "relationships": [], "entity_mappings": {},
                        "semantic_context": {}, "column_value_analysis": {},
                        "natural_language_guide": {}, "metadata": {
                            "cached": False, "cache_key": cache_key, "inspected_schema": schema_name
                        }
                    }
                    
                    params = [schema_name]
                    table_filter = ""
                    if table_names:
                        placeholders = ",".join(["%s"] * len(table_names))
                        table_filter = f"AND t.table_name IN ({placeholders})"
                        params.extend(table_names)
                    
                    table_query = f"""
                    SELECT t.table_name, c.column_name, c.data_type, c.is_nullable
                    FROM information_schema.tables t
                    JOIN information_schema.columns c ON t.table_name = c.table_name AND t.table_schema = c.table_schema
                    WHERE t.table_schema = %s AND t.table_type = 'BASE TABLE' {table_filter}
                    ORDER BY t.table_name, c.ordinal_position
                    """
                    cursor.execute(table_query, params)
                    for row in cursor.fetchall():
                        if row['table_name'] not in schema_context["tables"]:
                            schema_context["tables"][row['table_name']] = {"columns": {}, "primary_keys": [], "foreign_keys": []}
                        schema_context["tables"][row['table_name']]["columns"][row['column_name']] = {
                            "data_type": row['data_type'], "is_nullable": row['is_nullable'] == 'YES'
                        }
                    
                    pk_query = f"""
                    SELECT tc.table_name, kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
                    WHERE tc.table_schema = %s AND tc.constraint_type = 'PRIMARY KEY'
                    {table_filter.replace('t.table_name', 'tc.table_name') if table_filter else ''}
                    """
                    cursor.execute(pk_query, params)
                    for row in cursor.fetchall():
                        if row['table_name'] in schema_context["tables"]:
                            schema_context["tables"][row['table_name']]["primary_keys"].append(row['column_name'])
                    
                    await self._analyze_column_values(cursor, schema_context, schema_name)
                    await self._build_entity_mappings(schema_context)
                    await self._create_semantic_context(schema_context)
                    await self._generate_natural_language_guide(schema_context)
                    
                    return {"status": "success", "schema_context": schema_context, "cached": False}

        except psycopg2.Error as e:
            return {"status": "error", "message": f"Database error: {str(e)}"}
        except Exception as e:
            return {"status": "error", "message": f"Unexpected error: {str(e)}"}

    # --- REWRITTEN AND ENHANCED FUNCTION ---
    async def _analyze_column_values(self, cursor, schema_context: Dict[str, Any], schema_name: str):
        """
        Analyze column values to find categorical data and provide richer context.
        """
        logger.info("Starting detailed column value analysis...")
        for table_name, table_info in schema_context["tables"].items():
            schema_context["column_value_analysis"][table_name] = {}
            for column_name, column_info in table_info["columns"].items():
                column_analysis = {
                    "is_categorical": False,
                    "unique_values": [],
                    "semantic_type": self._infer_semantic_type(column_name, column_info["data_type"])
                }
                
                # Check for categorical data only in text-like columns
                if column_info["data_type"].lower() in ["text", "varchar", "character varying", "char"]:
                    try:
                        # Query for the number of distinct values
                        query = f"SELECT COUNT(DISTINCT \"{column_name}\") as distinct_count FROM \"{schema_name}\".\"{table_name}\""
                        cursor.execute(query)
                        distinct_count = cursor.fetchone()['distinct_count']
                        
                        # Define a column as categorical if it has a low number of unique values
                        # This threshold can be adjusted.
                        CATEGORICAL_THRESHOLD = 25
                        if distinct_count > 0 and distinct_count <= CATEGORICAL_THRESHOLD:
                            column_analysis["is_categorical"] = True
                            
                            # Fetch the actual unique values
                            query = f"SELECT DISTINCT \"{column_name}\" FROM \"{schema_name}\".\"{table_name}\" ORDER BY 1 LIMIT {CATEGORICAL_THRESHOLD}"
                            cursor.execute(query)
                            # Filter out None values which are not useful for the AI
                            unique_values = [row[column_name] for row in cursor.fetchall() if row[column_name] is not None]
                            column_analysis["unique_values"] = unique_values
                            logger.info(f"  -> Found categorical column: '{table_name}.{column_name}' with values: {unique_values}")
                            
                    except Exception as e:
                        # This can fail if the user lacks permissions, which is fine.
                        logger.warning(f"Could not analyze column '{table_name}.{column_name}': {e}")
                
                schema_context["column_value_analysis"][table_name][column_name] = column_analysis
        logger.info("Column value analysis complete.")

    async def _build_entity_mappings(self, schema_context: Dict[str, Any]):
        """Builds entity mappings from categorical data."""
        entity_mappings = {}
        analysis = schema_context.get("column_value_analysis", {})
        for table_name, columns in analysis.items():
            for column_name, col_analysis in columns.items():
                if col_analysis.get("is_categorical"):
                    for value in col_analysis.get("unique_values", []):
                        if value and isinstance(value, str):
                            # Create mappings for concepts like "active users"
                            entity_key = f"{value.lower()} {table_name}"
                            entity_mappings[entity_key] = {
                                "table": table_name,
                                "filter_condition": f"\"{column_name}\" = '{value}'",
                                "description": f"All '{value}' records from the '{table_name}' table"
                            }
        schema_context["entity_mappings"] = entity_mappings

    async def _create_semantic_context(self, schema_context: Dict[str, Any]):
        pass # This function is fine as a placeholder for now

    async def _generate_natural_language_guide(self, schema_context: Dict[str, Any]):
        pass # This function is fine as a placeholder for now

    # --- ENHANCED SEMANTIC TYPE INFERENCE ---
    def _infer_semantic_type(self, column_name: str, data_type: str) -> str:
        """Infer a more specific semantic type from column name and data type."""
        col = column_name.lower()
        
        # High-priority identifiers
        if col in ['id', 'pk', 'primary_key']: return "primary_key"
        if col.endswith('_id'): return "foreign_key"
        if 'email' in col: return "email"
        if 'phone' in col: return "phone_number"
        if 'url' in col or 'website' in col: return "url"
        
        # Categorical hints
        if col.endswith('_type') or col.endswith('_category') or col == 'role' or col == 'status': return "category"
        
        # Boolean hints
        if col.startswith('is_') or col.startswith('has_'): return "boolean"
        
        # Location hints
        if 'country' in col: return "location_country"
        if 'city' in col: return "location_city"
        if 'state' in col or 'province' in col: return "location_state"
        if 'zip' in col or 'postal' in col: return "location_zipcode"
        if 'address' in col: return "location_address"
        
        # Temporal hints
        if 'timestamp' in data_type or col.endswith('_at'): return "timestamp"
        if 'date' in data_type or col.endswith('_date'): return "date"

        # Personal data hints
        if 'name' in col: return "name"
        if 'price' in col or 'amount' in col or 'cost' in col: return "currency"
        
        # Generic fallback
        if 'id' in col: return "identifier"
        return "unknown"

# Global instance
db_ops = DatabaseOperations()

async def fetch_schema_context(table_names: Optional[List[str]] = None, include_samples: bool = False, schema_name: str = 'public') -> Dict[str, Any]:
    """Convenience function for the main script to call."""
    return await db_ops.fetch_schema_context(table_names, include_samples, schema_name)
