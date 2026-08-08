from fastapi import HTTPException, Request
from .database import get_db


async def increment_sequence() -> int:
    """Atomically increment and return the new global sequence number."""
    pool = get_db()
    return await pool.fetchval(
        "UPDATE sequence SET seq = seq + 1 WHERE id = TRUE RETURNING seq"
    )


async def get_sequence() -> int:
    """Return the current sequence number without incrementing."""
    pool = get_db()
    seq = await pool.fetchval("SELECT seq FROM sequence WHERE id = TRUE")
    return seq if seq is not None else 0


def parse_client_seq(request: Request) -> int:
    """Extract and validate the client's `X-Sequence` header."""
    client_seq = request.headers.get("X-Sequence")
    if client_seq is None:
        raise HTTPException(status_code=400, detail="Missing X-Sequence header")
    try:
        return int(client_seq)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid X-Sequence header")


async def consume_client_seq(conn, request: Request) -> int:
    """Validate the client's `X-Sequence` and atomically reserve the next
    sequence number. Returns the new sequence for the broadcast payload.

    Takes an explicit `conn` — it must NOT reach back into the pool. Callers
    already hold a connection, and acquiring a second one from inside that
    scope deadlocks the pool once max_size concurrent mutations are in
    flight (each holds one connection and waits forever for another).

    Call this INSIDE the caller's transaction, so a mutation that fails after
    the CAS rolls the sequence back with it. Otherwise the counter advances
    for a request that returned an error, and the client's next write 409s.

    The UPDATE ... WHERE seq = $1 is the CAS: Postgres row-locks the single
    sequence row, so of two concurrent writers only one matches `expected`.
    Holding that lock until commit also serializes the mutations themselves.

    Raises:
      - 400 if `X-Sequence` is missing or non-integer.
      - 409 if the client's seq doesn't match the server's current seq.
    """
    expected = parse_client_seq(request)
    new_seq = await conn.fetchval(
        "UPDATE sequence SET seq = seq + 1 WHERE id = TRUE AND seq = $1 RETURNING seq",
        expected,
    )
    if new_seq is None:
        raise HTTPException(status_code=409, detail="Out of sync. Please reload.")
    return new_seq
