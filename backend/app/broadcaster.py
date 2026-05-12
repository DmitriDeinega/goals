import asyncio
import json
import logging
from datetime import datetime, timezone
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


async def _push_fcm(event: str, data, exclude_session: str = None):
    try:
        from . import firebase_push
        if not firebase_push.is_enabled():
            return
        from .database import get_db
        db = get_db()
        query = {}
        if exclude_session:
            query = {
                "$nor": [
                    {"app_session_id": exclude_session},
                    {"widget_session_id": exclude_session},
                ]
            }
        device_docs = await db.devices.find(query).to_list(None)
        tokens = [d.get("token") for d in device_docs if d.get("token")]
        seq = data.get("seq") if isinstance(data, dict) else 0
        result = await firebase_push.send_refresh_push(
            tokens, event, seq or 0, payload=data
        )
        failed, succeeded = (result if isinstance(result, tuple) else (result or set(), set()))
        if failed:
            await db.devices.delete_many({"token": {"$in": list(failed)}})
        if succeeded:
            await db.devices.update_many(
                {"token": {"$in": list(succeeded)}},
                {"$set": {"last_seen_at": datetime.now(timezone.utc)}},
            )
    except Exception as e:
        logger.warning(f"FCM push failed: {e}")


async def broadcast(event: str, data, exclude_session: str = None):
    if _queues:
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

    asyncio.create_task(_push_fcm(event, data, exclude_session))
