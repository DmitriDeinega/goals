import asyncpg
import json
import os
import logging
from pathlib import Path

logger = logging.getLogger("goals.database")

pool: asyncpg.Pool = None

SCHEMA_PATH = Path(__file__).parent / "schema.sql"


def _dsn() -> str:
    """Build the connection DSN. DATABASE_URL wins if set; otherwise assemble
    it from the discrete PG* vars so docker-compose can pass them separately."""
    url = os.getenv("DATABASE_URL")
    if url:
        return url
    host = os.getenv("PGHOST", "localhost")
    port = os.getenv("PGPORT", "5432")
    user = os.getenv("PGUSER", "goals")
    password = os.getenv("PGPASSWORD", "goals")
    database = os.getenv("PGDATABASE", "goals_db")
    return f"postgresql://{user}:{password}@{host}:{port}/{database}"


async def _init_connection(conn: asyncpg.Connection):
    """Register a JSONB codec so reward_rules/snapshot round-trip as Python
    objects instead of raw strings — without this, every read site would need
    a json.loads and every write a json.dumps."""
    await conn.set_type_codec(
        "jsonb",
        encoder=json.dumps,
        decoder=json.loads,
        schema="pg_catalog",
    )


async def connect_db():
    global pool
    pool = await asyncpg.create_pool(
        _dsn(),
        min_size=2,
        max_size=10,
        command_timeout=30,
        init=_init_connection,
    )
    logger.info("Connected to PostgreSQL")


async def init_schema():
    """Apply schema.sql. Every statement is idempotent (IF NOT EXISTS / DO
    blocks that swallow duplicate_object), so this is safe on every boot."""
    sql = SCHEMA_PATH.read_text(encoding="utf-8")
    async with pool.acquire() as conn:
        await conn.execute(sql)
    logger.info("Schema ensured")


async def close_db():
    global pool
    if pool:
        await pool.close()
        logger.info("PostgreSQL connection closed")


def get_db() -> asyncpg.Pool:
    return pool
