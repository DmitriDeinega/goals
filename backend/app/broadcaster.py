import asyncio
import json
import logging
from typing import Set

logger = logging.getLogger("goals.broadcaster")


class Queue:
    def __init__(self, session_id: str = None):
        self.q = asyncio.Queue()
        self.session_id = session_id

    def put_nowait(self, item):
        self.q.put_nowait(item)

    async def get(self):
        return await self.q.get()


_queues: Set[Queue] = set()


def register(session_id: str = None) -> Queue:
    q = Queue(session_id=session_id)
    _queues.add(q)
    logger.debug(f"SSE client connected session={session_id}. Total: {len(_queues)}")
    return q


def unregister(q: Queue):
    _queues.discard(q)
    logger.debug(f"SSE client disconnected. Total: {len(_queues)}")


async def broadcast(event: str, data, exclude_session: str = None):
    if not _queues:
        return
    message = f"event: {event}\ndata: {json.dumps(data)}\n\n"
    dead = set()
    for q in _queues:
        if exclude_session and q.session_id == exclude_session:
            continue
        try:
            q.put_nowait(message)
        except asyncio.QueueFull:
            dead.add(q)
    for q in dead:
        unregister(q)
