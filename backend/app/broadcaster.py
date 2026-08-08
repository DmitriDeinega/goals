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


async def _push_fcm(event: str, data, exclude_session: str = None):
    try:
        from . import firebase_push
        if not firebase_push.is_enabled():
            return
        from .database import get_db
        pool = get_db()
        if exclude_session:
            # IS DISTINCT FROM (not <>) so rows with a NULL session id still
            # match — a device that never reported one must still get the push.
            rows = await pool.fetch(
                """
                SELECT token FROM devices
                WHERE app_session_id IS DISTINCT FROM $1
                  AND widget_session_id IS DISTINCT FROM $1
                """,
                exclude_session,
            )
        else:
            rows = await pool.fetch("SELECT token FROM devices")
        tokens = [r["token"] for r in rows if r["token"]]
        seq = data.get("seq") if isinstance(data, dict) else 0
        result = await firebase_push.send_refresh_push(
            tokens, event, seq or 0, payload=data
        )
        failed, succeeded = (result if isinstance(result, tuple) else (result or set(), set()))
        if failed:
            await pool.execute("DELETE FROM devices WHERE token = ANY($1::text[])", list(failed))
        if succeeded:
            await pool.execute(
                "UPDATE devices SET last_seen_at = now() WHERE token = ANY($1::text[])",
                list(succeeded),
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
