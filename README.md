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
│   │   ├── time_utils.py    # Timezone-aware date helpers
│   │   └── routers/
│   │       ├── goals.py     # CRUD for goals
│   │       ├── logs.py      # Daily logs + week summary
│   │       ├── settings.py  # App settings
│   │       └── weeks.py     # goal_weeks enrollment + enable/disable
│   ├── pyproject.toml
│   ├── .env                 # local only, never committed
│   └── Dockerfile
├── frontend/
│   ├── src/
│   │   ├── api/             # API client with toast error handling
│   │   ├── hooks/           # useGoals, useLogs, useSettings
│   │   ├── components/      # WeekStrip, GoalRow, GoalForm, Toast, etc.
│   │   ├── pages/           # TodayPage, GoalsPage
│   │   └── App.jsx
│   ├── index.html
│   ├── package.json
│   └── vite.config.js
├── docker-compose.yml
├── init_settings.js         # one-time DB seed script
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
docker compose up --build -d

# Seed initial settings (run once)
docker exec -i goals-mongo mongosh goals_db < init_settings.js

# Every deploy after that
git pull && docker compose up --build -d
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
GET    /api/goals/                          list all active goals with enabled status
POST   /api/goals/                          create goal
PUT    /api/goals/{id}                      update goal
DELETE /api/goals/{id}                      soft delete goal
PUT    /api/goals/reorder/batch             reorder goals

GET    /api/logs/                           logs for a date or week range
POST   /api/logs/                           upsert log entry
GET    /api/logs/week-summary               weekly stats & rewards

GET    /api/settings/                       get app settings (includes app_env)
PUT    /api/settings/                       update settings

POST   /api/weeks/ensure                    enroll goals for current week (called on app load)
PUT    /api/weeks/{goal_id}/enabled         enable/disable goal for current week
```

## Goal Types
- **daily** — tracked every day, with optional times_per_day > 1
- **weekly_x** — target X completions per week, you set the number

## Negative Goals
Negative goals (e.g. "No junk food") default to ✓ (success). Tap to mark as failed for that day.

## Rewards
Add reward rules per goal. Rules accumulate — all matching rules pay out.
Example: "5/7 days → ₪3, 7/7 days → ₪5" gives ₪8 for a perfect week.

## Week Enrollment (goal_weeks)
Goals are enrolled per-week in a `goal_weeks` collection. This means:
- Goals only appear in weeks where they were active
- You can disable a goal mid-week without losing past logs
- Past weeks only show goals that were actually tracked then
- On each app load, `POST /api/weeks/ensure` enrolls any new goals into the current week

## Settings
Configurable via the API (settings UI planned):
- `first_day_of_week` — `sunday` or `monday`
- `start_date` — earliest date you can navigate to
- `currency` — `NIS` or `USD`
- `timezone` — IANA timezone string (e.g. `Asia/Jerusalem`) — determines when the server considers a new day to start
