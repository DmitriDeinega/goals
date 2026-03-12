import asyncio
import json
import logging
from typing import Set

logger = logging.getLogger("goals.broadcaster")

# Set of active SSE queues — one per connected client
_queues: Set[asyncio.Queue] = set()


def register() -> asyncio.Queue:
    q: asyncio.Queue = asyncio.Queue()
    _queues.add(q)
    logger.debug(f"SSE client connected. Total: {len(_queues)}")
    return q


def unregister(q: asyncio.Queue):
    _queues.discard(q)
    logger.debug(f"SSE client disconnected. Total: {len(_queues)}")


async def broadcast(event: str, data: dict = {}):
    if not _queues:
        return
    message = f"event: {event}\ndata: {json.dumps(data)}\n\n"
    dead = set()
    for q in _queues:
        try:
            q.put_nowait(message)
        except asyncio.QueueFull:
            dead.add(q)
    for q in dead:
        unregister(q)
