from motor.motor_asyncio import AsyncIOMotorClient
import os
import logging

logger = logging.getLogger("goals.database")

client: AsyncIOMotorClient = None
db = None

async def connect_db():
    global client, db
    mongo_uri = os.getenv("MONGO_URI", "mongodb://localhost:27017")
    mongo_db = os.getenv("MONGO_DB", "goals_db")
    client = AsyncIOMotorClient(mongo_uri)
    db = client[mongo_db]
    logger.info(f"Connected to MongoDB: {mongo_db}")

async def close_db():
    global client
    if client:
        client.close()
        logger.info("MongoDB connection closed")

def get_db():
    return db
