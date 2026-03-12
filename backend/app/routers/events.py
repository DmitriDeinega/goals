from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse
import asyncio
import logging
from ..broadcaster import register, unregister

router = APIRouter()
logger = logging.getLogger("goals.routers.events")


@router.get("/")
async def sse(request: Request):
    q = register()

    async def stream():
        try:
            # Send initial ping so client knows connection is alive
            yield "event: ping\ndata: {}\n\n"
            while True:
                if await request.is_disconnected():
                    break
                try:
                    message = await asyncio.wait_for(q.get(), timeout=30)
                    yield message
                except asyncio.TimeoutError:
                    # Send keepalive ping every 30s to prevent proxy timeouts
                    yield "event: ping\ndata: {}\n\n"
        finally:
            unregister(q)

    return StreamingResponse(
        stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",  # disable nginx buffering
        },
    )
