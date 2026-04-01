from .database import get_db


async def increment_sequence() -> int:
    """Atomically increment and return the new global sequence number."""
    db = get_db()
    result = await db.sequence.find_one_and_update(
        {"_id": "global"},
        {"$inc": {"seq": 1}},
        upsert=True,
        return_document=True,
    )
    return result["seq"]


async def get_sequence() -> int:
    """Return the current sequence number without incrementing."""
    db = get_db()
    doc = await db.sequence.find_one({"_id": "global"})
    return doc["seq"] if doc else 0
