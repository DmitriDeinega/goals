# Goals App
Personal goals & habits tracker with weekly rewards.

## Project Structure
```
goals/
├── backend/
│   ├── app/
│   │   ├── main.py          # FastAPI app, serves frontend + API, logging setup
│   │   ├── database.py      # MongoDB async connection
│   │   ├── models.py        # Pydantic models
│   │   ├── sequence.py      # Global sequence counter for sync
│   │   ├── broadcaster.py   # SSE fan-out to connected clients
│   │   ├── day_watcher.py   # Background task: detects midnight, enrolls new week
│   │   ├── time_utils.py    # Timezone-aware date helpers
│   │   └── routers/
│   │       ├── goals.py     # CRUD for goals
│   │       ├── logs.py      # Daily log upsert
│   │       ├── settings.py  # App settings
│   │       └── weeks.py     # goal_weeks enrollment + enable/disable
│   ├── pyproject.toml
│   ├── .env                 # local only, never committed
│   ├── .env.example
│   └── Dockerfile
├── frontend/
│   ├── src/
│   │   ├── api/             # API client with toast error handling
│   │   ├── hooks/           # useAppState — goals, logs, SSE, week summary
│   │   ├── components/      # WeekStrip, GoalRow, GoalForm, DatePicker, Toast
│   │   └── App.jsx
│   ├── index.html
│   ├── package.json
│   └── vite.config.js
├── android/                 # Native Android app (see android/README.md)
├── docker/
│   └── docker-compose.yml
├── init_settings.js         # One-time DB seed script
└── README.md
```

## Environment Variables
Create `backend/.env` — never committed to git:
```
APP_ENV=DEV       # DEV shows "Goals-DEV" in UI, PROD shows "Goals"
LOG_LEVEL=INFO    # DEBUG | INFO | WARNING | ERROR
```

## Production Deployment
```bash
# First time — on the server
git clone https://github.com/YOURUSERNAME/goals.git
cd goals
cp backend/.env.example backend/.env  # then edit it
docker compose -f docker/docker-compose.yml up --build -d

# Seed initial settings (run once)
docker exec -i goals-mongo mongosh goals_db < init_settings.js

# Every deploy after that
git pull && docker compose -f docker/docker-compose.yml up --build -d
```

App runs at: `http://your-server:2200`

## Useful Commands
```bash
docker logs goals-backend -f          # live logs
docker logs goals-backend --tail 100  # last 100 lines
docker compose restart backend        # restart without rebuild
docker compose down                   # stop everything
```

## API Endpoints
```
GET    /api/init                            full state load (goals, weeks, logs, settings, seq)

GET    /api/goals/                          list all goals with current week enabled status
POST   /api/goals/                          create goal
PUT    /api/goals/{id}                      update goal
DELETE /api/goals/{id}                      delete goal (hard delete, reorders remaining)
PUT    /api/goals/reorder/batch             reorder goals

POST   /api/logs/                           upsert log entry (toggle a slot)

GET    /api/settings/                       get app settings
PUT    /api/settings/                       update settings

POST   /api/weeks/ensure                    enroll goals for current week (called on app load)
PUT    /api/weeks/{goal_id}/enabled         enable/disable goal for current week

GET    /api/sse                             SSE stream for real-time sync
```

## Goal Types
- **daily** — tracked every day, with optional `times_per_day > 1`
- **weekly_x** — target X completions per week (1–7), you set the number

## Negative Goals
Negative goals (e.g. "No junk food") default to ✓ (avoided). Tap to mark as failed for that day.

## Rewards
Add reward rules per goal. All matching rules pay out.
Example: "5/7 days → ₪3, 7/7 days → ₪5" gives ₪8 for a perfect week.

## Week Enrollment (goal_weeks)
Goals are enrolled per-week in a `goal_weeks` collection with a snapshot of the goal's config at that time. This means:
- You can disable a goal for the current week without losing past logs
- Past weeks reflect the goal config that was active then (name, type, reward rules)
- On each app load, `POST /api/weeks/ensure` enrolls any new goals into the current week

## Real-time Sync
All clients connect to `/api/sse` and receive events:
- `goal_changed` — goal created, updated, deleted, reordered, or enabled toggled
- `log_changed` — a slot was toggled
- `day_changed` — midnight rollover, new day initialized
- `ping` — keepalive every 30s

Each event includes a sequence number (`seq`). If a client's local seq is behind, it reloads.

## Settings
Configurable via the API:
- `first_day_of_week` — `sunday` or `monday`
- `start_date` — earliest date you can navigate to
- `currency` — e.g. `NIS` or `USD`
- `timezone` — IANA timezone string (e.g. `Asia/Jerusalem`)
