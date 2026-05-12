import asyncio
import json
import logging
import os
from typing import Iterable, List, Set, Tuple

logger = logging.getLogger("goals.firebase_push")

_initialized = False
_messaging = None
_init_failure_count = 0


def init_firebase():
    """Initialize Firebase Admin SDK. Safe to call repeatedly; only the first
    successful call has effect. Returns True on success, False otherwise."""
    global _initialized, _messaging, _init_failure_count
    if _initialized:
        return True
    cred_path = os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    if not cred_path or not os.path.exists(cred_path):
        # Deliberate disable: no creds path set. Don't retry.
        if _init_failure_count == 0:
            logger.warning("FCM disabled: GOOGLE_APPLICATION_CREDENTIALS not set or file missing")
        _init_failure_count += 1
        return False
    try:
        import firebase_admin
        from firebase_admin import credentials, messaging
        cred = credentials.Certificate(cred_path)
        firebase_admin.initialize_app(cred)
        _messaging = messaging
        _initialized = True
        logger.info("Firebase Admin initialized")
        return True
    except Exception as e:
        level = logger.warning if _init_failure_count == 0 else logger.debug
        level(f"Failed to init Firebase Admin: {e}")
        _init_failure_count += 1
        return False


def is_enabled() -> bool:
    return _initialized and _messaging is not None


def has_creds_env() -> bool:
    """True iff GOOGLE_APPLICATION_CREDENTIALS is set to an existing path.
    Drives whether we should keep retrying init on failure."""
    cred_path = os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    return bool(cred_path) and os.path.exists(cred_path)


async def init_retry_loop(interval_seconds: int = 300):
    """Background task: retry init every `interval_seconds` until success.
    Stops if the env var is not set (treated as deliberate disable)."""
    while not _initialized:
        if not has_creds_env():
            return  # nothing to retry against
        await asyncio.sleep(interval_seconds)
        if _initialized:
            return
        init_firebase()


def _chunks(items: List[str], size: int):
    for i in range(0, len(items), size):
        yield items[i : i + size]


async def send_refresh_push(
    tokens: Iterable[str], event_type: str, seq: int, payload=None
) -> Tuple[Set[str], Set[str]]:
    """Send the data-only push. Returns (failed_tokens, succeeded_tokens).
    Caller uses successes to bump last_seen_at and failures to prune."""
    if not is_enabled():
        return set(), set()
    token_list: List[str] = list({t for t in tokens if t})
    if not token_list:
        return set(), set()
    data = {"event": event_type, "seq": str(seq or 0)}
    if payload is not None:
        try:
            serialized = json.dumps(payload)
            byte_len = len(serialized.encode("utf-8"))
            if byte_len < 3500:
                data["payload"] = serialized
            else:
                logger.info(
                    f"FCM payload too large ({byte_len}b) for {event_type}; client will refetch"
                )
        except Exception:
            pass

    failed: Set[str] = set()
    succeeded: Set[str] = set()

    # FCM multicast accepts up to 500 tokens per call.
    for batch in _chunks(token_list, 500):
        try:
            message = _messaging.MulticastMessage(
                data=data,
                tokens=batch,
                android=_messaging.AndroidConfig(priority="high"),
            )
            response = _messaging.send_each_for_multicast(message)
            for i, resp in enumerate(response.responses):
                tok = batch[i]
                if resp.success:
                    succeeded.add(tok)
                else:
                    err_code = getattr(resp.exception, "code", "") if resp.exception else ""
                    code_str = str(err_code).upper()
                    if "UNREGISTERED" in code_str or "INVALID_ARGUMENT" in code_str:
                        failed.add(tok)
        except Exception as e:
            logger.warning(f"FCM batch send failed: {e}")

    return failed, succeeded
