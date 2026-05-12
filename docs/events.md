# Goals — server event contract

Single source of truth for SSE events and FCM data pushes. The backend's Pydantic models in `backend/app/models.py` are canonical Python; this doc mirrors them for client consumers (Android, Windows, future).

Every event carries:
- `seq` (int): monotonically increasing global sequence number from the backend.
- `event` / `type` (str): one of the values below.

When delivered via SSE the body is the `data:` field of the SSE frame. When delivered via FCM, the payload is sent inside the FCM data dict under the `payload` key (JSON-encoded string) along with `event` and `seq`. FCM is **best-effort** and may drop the `payload` if it exceeds the size budget (~3.5 KB serialized) — clients should fall back to refetching `/api/init` in that case.

The backend's `broadcaster.broadcast()` suppresses the event for the originating session (`X-Session-ID`) on both SSE and FCM paths.

---

## `log_changed`

Fires when a single goal's logs for a given week are updated (typically from a slot toggle).

```json
{
  "goal_id": "65ab12...",
  "logs": [
    { "goal_id": "65ab12...", "date": "2026-05-04", "slots": [true] },
    { "goal_id": "65ab12...", "date": "2026-05-05", "slots": [false, true] }
  ],
  "seq": 1234
}
```

- `logs` contains every log row for the affected goal within the affected week (not just the changed slot). Clients merge by `(goal_id, date)`.

## `goal_changed`

Fires when a goal or its week-snapshot changes. The `action` field discriminates between four sub-events.

### `action: "created"`

```json
{
  "action": "created",
  "goal":      { /* full Goal */ },
  "goal_week": { /* full GoalWeek for the current week */ },
  "logs":      [ /* freshly-created logs for daily goals on today */ ],
  "seq": 1235
}
```

### `action: "updated"`

```json
{
  "action": "updated",
  "goal":      { /* full Goal with new version */ },
  "goal_week": { /* GoalWeek with updated snapshot for current week */ },
  "logs":      [ /* logs reconciled for the new times_per_day */ ] | null,
  "seq": 1236
}
```

### `action: "deleted"`

```json
{
  "action": "deleted",
  "goal_id": "65ab12...",
  "reordered_goals": [
    { "goal_id": "65ab10...", "new_order": 0 },
    { "goal_id": "65ab11...", "new_order": 1 }
  ],
  "seq": 1237
}
```

- `reordered_goals` lists the goals whose `order` changed as a result of the delete. Each entry is `{ "goal_id": String, "new_order": Int }` — **not** the full Goal object.

### `action: "enabled_changed"`

```json
{
  "action": "enabled_changed",
  "goal_week": { /* GoalWeek with new enabled value */ },
  "seq": 1238
}
```

### Bulk reorder via SSE

A successful POST to `/api/goals/reorder/batch` emits an array of small entries on SSE (not wrapped in a `goal_changed` envelope):

```json
[
  { "action": "reordered", "goal_id": "65ab10...", "new_order": 0, "seq": 1239 },
  { "action": "reordered", "goal_id": "65ab11...", "new_order": 1, "seq": 1240 }
]
```

Clients detect this by checking whether `data` parses as a JSON array.

## `day_changed`

Fires when the server's local date rolls over (and on the initial `/api/init` if today's logs didn't exist).

```json
{
  "date": "2026-05-12",
  "logs": [ /* logs for the new "today" */ ],
  "seq": 1241
}
```

## `ping`

Heartbeat. No payload. Ignore.

---

## FCM data envelope

When the backend pushes via FCM, the data dict looks like:

```json
{
  "event": "log_changed",
  "seq": "1234",
  "payload": "{\"goal_id\":\"65ab12...\",\"logs\":[...],\"seq\":1234}"
}
```

- All values are strings (FCM data limitation).
- `payload` is the same JSON body as the SSE `data:` field.
- If serialized size approaches the FCM 4 KB limit, the backend omits `payload`. Clients should treat a missing/null `payload` as "refresh from `/api/init`".

## Session ID semantics

- `X-Session-ID` header on writes identifies the originating client session.
- Backend suppresses the resulting event for any subscriber whose `session_id` equals the originator's, **and** suppresses FCM pushes to devices whose `app_session_id` or `widget_session_id` matches.
- Android persists separate `app_session_id` and `widget_session_id` so the widget receives events from app writes and vice-versa.
- Windows / new clients should generate their own persistent session id.
