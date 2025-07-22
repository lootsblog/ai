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
# This class replaces the need for the original utils/redis_client.py file.
# It allows the script to run without a Redis server by simulating a disconnected state.
class StandaloneRedisClient:
    """A dummy Redis client that always reports as disconnected."""
    def __init__(self):
        logger.info("Initialized StandaloneRedisClient. Caching is disabled.")
        self.connected = False

    def is_connected(self) -> bool:
        return self.connected

    def get_cached_schema(self, cache_key: str) -> Optional[Dict[str, Any]]:
        # Always return None as if the cache was missed.
        return None

    def cache_schema(self, cache_key: str, schema_data: Dict[str, Any], ttl_seconds: int = 3600) -> bool:
        # Pretend to cache but do nothing. Always returns False.
        return False
        
    def generate_schema_cache_key(self, table_names: Optional[list] = None, include_samples: bool = False) -> str:
        """Generates a consistent cache key, even though it won't be used."""
        if table_names:
            tables_str = "_".join(sorted(table_names))
            key = f"schema:{tables_str}"
        else:
            key = "schema:all_tables"
        if include_samples:
            key += ":with_samples"
        return key

def get_redis_client() -> StandaloneRedisClient:
    """Returns an instance of our dummy Redis client."""
    return StandaloneRedisClient()
# --- End of Self-Contained Dummy RedisClient ---


def make_json_serializable(data):
    """Recursively convert data to be JSON serializable."""
    if isinstance(data, dict):
        return {key: make_json_serializable(value) for key, value in data.items()}
    elif isinstance(data, list):
        return [make_json_serializable(item) for item in data]
    elif isinstance(data, (datetime, date)):
        return data.isoformat()
    elif isinstance(data, Decimal):
        return float(data)
    elif data is None:
        return None
    elif isinstance(data, (str, int, float, bool)):
        return data
    else:
        return str(data)

class DatabaseOperations:
    """Database operations handler for PostgreSQL"""
    
    def __init__(self):
        self.connection_string = os.getenv("DATABASE_URL")
        if not self.connection_string:
            logger.warning("DATABASE_URL not set, using default connection parameters")
            self.connection_string = "postgresql://postgres:password@localhost:5432/postgres"
        else:
            logger.info("DATABASE_URL loaded from environment")
        
        # Initialize Redis client for caching
        self.redis_client = get_redis_client()
        
        logger.info("DatabaseOperations initialized")
    
    @contextmanager
    def get_connection(self):
        """Get a database connection with proper cleanup"""
        conn = None
        try:
            conn = psycopg2.connect(self.connection_string)
            yield conn
        except Exception as e:
            if conn:
                conn.rollback()
            logger.error(f"Database connection error: {e}")
            raise
        finally:
            if conn:
                conn.close()
    
    async def fetch_schema_context(self, table_names: Optional[List[str]] = None, include_samples: bool = False) -> Dict[str, Any]:
        """Fetches enhanced database schema context."""
        try:
            cache_key = self.redis_client.generate_schema_cache_key(table_names, include_samples)
            if self.redis_client.is_connected():
                cached_schema = self.redis_client.get_cached_schema(cache_key)
                if cached_schema:
                    return {"status": "success", "schema_context": cached_schema, "cached": True}

            logger.info(f"Fetching schema from database for tables: {table_names or 'all'}")
            
            with self.get_connection() as conn:
                with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cursor:
                    schema_context = {
                        "tables": {}, "relationships": [], "entity_mappings": {},
                        "semantic_context": {}, "column_value_analysis": {},
                        "natural_language_guide": {}, "metadata": {
                            "cached": False, "cache_key": cache_key
                        }
                    }
                    
                    table_filter = ""
                    params = []
                    if table_names:
                        placeholders = ",".join(["%s"] * len(table_names))
                        table_filter = f"AND t.table_name IN ({placeholders})"
                        params = table_names
                    
                    # Query for table and column info
                    table_query = f"""
                    SELECT t.table_name, c.column_name, c.data_type, c.is_nullable
                    FROM information_schema.tables t
                    JOIN information_schema.columns c ON t.table_name = c.table_name
                    WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE' {table_filter}
                    ORDER BY t.table_name, c.ordinal_position
                    """
                    cursor.execute(table_query, params)
                    for row in cursor.fetchall():
                        if row['table_name'] not in schema_context["tables"]:
                            schema_context["tables"][row['table_name']] = {"columns": {}, "primary_keys": [], "foreign_keys": []}
                        schema_context["tables"][row['table_name']]["columns"][row['column_name']] = {
                            "data_type": row['data_type'],
                            "is_nullable": row['is_nullable'] == 'YES'
                        }
                    
                    # Query for primary keys
                    pk_query = f"""
                    SELECT tc.table_name, kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                    WHERE tc.table_schema = 'public' AND tc.constraint_type = 'PRIMARY KEY'
                    {table_filter.replace('t.table_name', 'tc.table_name') if table_filter else ''}
                    """
                    cursor.execute(pk_query, params)
                    for row in cursor.fetchall():
                        if row['table_name'] in schema_context["tables"]:
                            schema_context["tables"][row['table_name']]["primary_keys"].append(row['column_name'])
                    
                    # ... (Other schema queries like foreign keys would go here) ...

                    await self._analyze_column_values(cursor, schema_context, table_filter, params)
                    await self._build_entity_mappings(schema_context)
                    await self._create_semantic_context(schema_context)
                    await self._generate_natural_language_guide(schema_context)
                    
                    if self.redis_client.is_connected():
                        self.redis_client.cache_schema(cache_key, schema_context)
                        schema_context["metadata"]["cached"] = True

                    return {"status": "success", "schema_context": schema_context, "cached": schema_context["metadata"]["cached"]}

        except psycopg2.Error as e:
            return {"status": "error", "message": f"Database error: {str(e)}"}
        except Exception as e:
            return {"status": "error", "message": f"Unexpected error: {str(e)}"}

    async def _analyze_column_values(self, cursor, schema_context: Dict[str, Any], table_filter: str, params: List[str]):
        """Analyze column values to understand data patterns and possible values"""
        for table_name, table_info in schema_context["tables"].items():
            try:
                for column_name, column_info in table_info["columns"].items():
                    column_analysis = {
                        "is_categorical": False,
                        "unique_values": [],
                        "semantic_type": self._infer_semantic_type(column_name, column_info["data_type"])
                    }
                    if column_info["data_type"] in ["text", "varchar", "character varying"]:
                        try:
                            cursor.execute(f"SELECT DISTINCT \"{column_name}\" FROM \"{table_name}\" LIMIT 20")
                            values = [row[column_name] for row in cursor.fetchall()]
                            cursor.execute(f"SELECT COUNT(DISTINCT \"{column_name}\") as c FROM \"{table_name}\"")
                            distinct_count = cursor.fetchone()['c']
                            if distinct_count <= 10:
                                column_analysis["is_categorical"] = True
                                column_analysis["unique_values"] = values
                        except Exception:
                            pass # Ignore errors on specific columns
                    
                    if table_name not in schema_context["column_value_analysis"]:
                        schema_context["column_value_analysis"][table_name] = {}
                    schema_context["column_value_analysis"][table_name][column_name] = column_analysis
            except Exception:
                pass # Ignore errors on specific tables

    async def _build_entity_mappings(self, schema_context: Dict[str, Any]):
        """Build intelligent entity mappings for natural language understanding"""
        entity_mappings = {}
        for table_name, table_info in schema_context["tables"].items():
            for column_name, column_info in table_info["columns"].items():
                analysis = schema_context.get("column_value_analysis", {}).get(table_name, {}).get(column_name, {})
                if analysis.get("is_categorical"):
                    for value in analysis.get("unique_values", []):
                        if value and isinstance(value, str):
                            entity_key = f"{value.lower()}s" if not value.lower().endswith('s') else value.lower()
                            entity_mappings[entity_key] = {
                                "table": table_name,
                                "filter_condition": f"\"{column_name}\" = '{value}'",
                                "description": f"All {value}s from {table_name} table"
                            }
        schema_context["entity_mappings"] = entity_mappings

    async def _create_semantic_context(self, schema_context: Dict[str, Any]):
        """Create semantic context for better AI understanding"""
        schema_context["semantic_context"] = {"table_purposes": {}}
        for table_name, table_info in schema_context["tables"].items():
            purpose = f"Stores {table_name} information"
            cols = [c.lower() for c in table_info["columns"].keys()]
            if "email" in cols and "password" in cols:
                purpose = "User authentication and profile data"
            schema_context["semantic_context"]["table_purposes"][table_name] = purpose

    async def _generate_natural_language_guide(self, schema_context: Dict[str, Any]):
        """Generate natural language guide for AI"""
        guide = {"available_tables": list(schema_context["tables"].keys()), "entity_resolution": {}}
        for entity, mapping in schema_context.get("entity_mappings", {}).items():
            guide["entity_resolution"][entity] = {
                "maps_to": f"SELECT * FROM \"{mapping['table']}\" WHERE {mapping['filter_condition']}",
                "description": mapping.get("description", "")
            }
        schema_context["natural_language_guide"] = guide

    def _infer_semantic_type(self, column_name: str, data_type: str) -> str:
        """Infer semantic type from column name and data type"""
        column_lower = column_name.lower()
        if "email" in column_lower: return "email"
        if "phone" in column_lower: return "phone"
        if "password" in column_lower: return "password"
        if "status" in column_lower: return "status"
        if "role" in column_lower: return "role"
        if "_at" in column_lower: return "timestamp"
        if "id" in column_lower: return "identifier"
        if "name" in column_lower: return "name"
        return "unknown"

# Global instance
db_ops = DatabaseOperations()

# Convenience function for the main script to call
async def fetch_schema_context(table_names: Optional[List[str]] = None, include_samples: bool = False) -> Dict[str, Any]:
    """Fetch database schema context - tool function"""
    return await db_ops.fetch_schema_context(table_names, include_samples)
