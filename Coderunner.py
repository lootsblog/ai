import asyncio
import json
import os
import sys
from dotenv import load_dotenv

# --- Setup Instructions ---
# 1. Make sure you have a .env file in this directory with:
#    DATABASE_URL=postgresql://username:password@localhost:5432/your_db_name
#    # REDIS_URL is OPTIONAL for this script. If not provided, caching will be disabled.
#    # REDIS_URL=redis://localhost:6379/0
#
# 2. Make sure you have installed the required project dependencies:
#    pip install psycopg2-binary python-dotenv
#    # The 'redis' library is not needed to run this script without caching.
#
# 3. Add the project's 'src' directory to the Python path.
#    This script assumes it is run from the root of the DBAgent project.
# -------------------------

# Add the 'src' directory to the Python path to import our tools
project_root = os.path.dirname(os.path.abspath(__file__))
src_path = os.path.join(project_root, 'src')
if src_path not in sys.path:
    sys.path.insert(0, src_path)

try:
    # This import will work even without Redis, as the redis_client is designed
    # to handle connection failures gracefully.
    from tools.db_ops import fetch_schema_context
except ImportError as e:
    print(f"ERROR: Could not import 'fetch_schema_context'. ({e})")
    print("Please ensure you are running this script from the root of the DBAgent project directory,")
    print("and that the 'src' directory exists and is accessible.")
    sys.exit(1)
except ModuleNotFoundError as e:
    if 'redis' in str(e):
         print("NOTE: The 'redis' library is not installed. Caching will be disabled.")
    else:
        raise e


async def main():
    """
    Connects to the local database defined in the .env file,
    fetches the enhanced schema context, and prints it to the console.
    Works with or without a running Redis instance.
    """
    print("--- Database Schema Inspector ---")
    
    # Load environment variables from .env file
    load_dotenv()

    if not os.getenv("DATABASE_URL"):
        print("\nERROR: DATABASE_URL not found in your .env file.")
        print("Please create a .env file with your database connection string.")
        return

    if not os.getenv("REDIS_URL"):
        print("\nINFO: REDIS_URL not found in .env file. Caching will be disabled.")

    print("\nConnecting to your database to fetch schema context...")
    
    try:
        # Call the actual tool from the project
        # It will automatically handle the case where Redis is unavailable
        schema_result = await fetch_schema_context(table_names=None, include_samples=False)

        if schema_result.get("status") == "success":
            print("✅ Schema context fetched successfully!")
            
            is_cached = schema_result.get('cached', False)
            cache_status = "Yes" if is_cached else "No (Redis not available or cache expired)"
            print(f" (Cached in Redis: {cache_status})\n")
            
            schema_context = schema_result.get("schema_context", {})
            
            # Use json.dumps for pretty-printing the entire dictionary
            pretty_json = json.dumps(schema_context, indent=2, default=str)
            
            print("--- Full Schema Context (JSON) ---")
            print(pretty_json)
            print("----------------------------------")

        else:
            print(f"\n❌ Error fetching schema: {schema_result.get('message')}")

    except Exception as e:
        print(f"\n❌ An unexpected error occurred: {e}")
        print("   Please check your DATABASE_URL and ensure the database is running.")


if __name__ == "__main__":
    asyncio.run(main())
