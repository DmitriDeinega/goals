from fastapi import HTTPException, Request
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


async def _check_and_increment_sequence(expected: int) -> int | None:
    """Atomic CAS: increment seq only if it equals `expected`. Returns the
    new seq, or None if the CAS missed (client is out of sync)."""
    db = get_db()
    result = await db.sequence.find_one_and_update(
        {"_id": "global", "seq": expected},
        {"$inc": {"seq": 1}},
        return_document=True,
    )
    return result["seq"] if result else None


async def consume_client_seq(request: Request) -> int:
    """Validate the client's `X-Sequence` header and atomically reserve the
    next sequence number in a single round-trip. Returns the new sequence to
    use in the broadcast payload.

    Replaces the older `validate_client_seq` + `increment_sequence` pair —
    that two-step read-then-increment was a TOCTOU race: two concurrent
    writes could both pass validation (same seq read) and both commit. The
    CAS here makes the check-and-bump atomic, so the second write fails
    with 409 instead.

    Raises:
      - 400 if `X-Sequence` is missing or non-integer.
      - 409 if the client's seq doesn't match the server's current seq.

    Note: if the mutation that follows fails AFTER this call, the sequence
    has already advanced — clients will see a gap on the next event and
    must resync (which all three clients already do once this fix lands).
    """
    client_seq = request.headers.get("X-Sequence")
    if client_seq is None:
        raise HTTPException(status_code=400, detail="Missing X-Sequence header")
    try:
        parsed = int(client_seq)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid X-Sequence header")
    new_seq = await _check_and_increment_sequence(parsed)
    if new_seq is None:
        raise HTTPException(status_code=409, detail="Out of sync. Please reload.")
    return new_seq
