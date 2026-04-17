from fastapi import APIRouter, HTTPException, Request
from bson import ObjectId
import logging
from ..database import get_db
from ..models import GoalCreate, GoalUpdate, GoalOut, GoalWeekOut, GoalChangedPayload, LogOut
from ..time_utils import get_today, get_week_start, get_week_end
from ..broadcaster import broadcast
from ..sequence import increment_sequence, get_sequence
from .logs import get_goal_week_logs, ensure_slots_for_goal, reconcile_slots_for_goal

router = APIRouter()
logger = logging.getLogger("goals.routers.goals")


async def get_settings_cached(db):
    s = await db.settings.find_one({"_id": "global"})
    if not s:
        raise RuntimeError("Settings not found in DB")
    return s["timezone"], s["first_day_of_week"]


def goal_from_doc(doc, enabled: bool = True) -> GoalOut:
    reward_rules = sorted(doc.get("reward_rules", []), key=lambda r: r.get("min_completions", 0))
    return GoalOut(
        id=str(doc["_id"]),
        name=doc["name"],
        type=doc["type"],
        is_negative=doc.get("is_negative", False),
        times_per_week=doc.get("times_per_week"),
        times_per_day=doc.get("times_per_day"),
        reward_rules=reward_rules,
        order=doc.get("order", 0),
        enabled=enabled,
        version=doc.get("version", 0),
    )


def goal_week_from_doc(doc) -> GoalWeekOut:
    return GoalWeekOut(
        goal_id=doc["goal_id"],
        week_start=doc["week_start"],
        enabled=doc.get("enabled", True),
        snapshot=doc.get("snapshot", {}),
    )


async def validate_client_seq(request: Request):
    client_seq = request.headers.get("X-Sequence")
    if client_seq is not None:
        try:
            parsed = int(client_seq)
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid X-Sequence header")
        current_seq = await get_sequence()
        if parsed != current_seq:
            raise HTTPException(status_code=409, detail="Out of sync. Please reload.")


@router.get("/", response_model=list[GoalOut])
async def get_goals():
    try:
        db = get_db()
        tz, first_day = await get_settings_cached(db)
        today = get_today(tz)
        week_start = get_week_start(today, first_day)
        goals = [doc async for doc in db.goals.find({}).sort("order", 1)]
        entries = await db.goal_weeks.find({"week_start": week_start}).to_list(None)
        enabled_map = {e["goal_id"]: e.get("enabled", True) for e in entries}
        return [goal_from_doc(g, enabled_map.get(str(g["_id"]), True)) for g in goals]
    except Exception as e:
        logger.error(f"Failed to get goals: {e}")
        raise


@router.post("/", response_model=GoalChangedPayload)
async def create_goal(goal: GoalCreate, request: Request):
    try:
        db = get_db()
        await validate_client_seq(request)
        tz, first_day = await get_settings_cached(db)
        today = get_today(tz)
        week_start = get_week_start(today, first_day)
        week_end = get_week_end(week_start)

        existing = await db.goals.find_one(
            {"name": goal.name.strip()},
            collation={"locale": "en", "strength": 2},
        )
        if existing:
            raise HTTPException(status_code=422, detail="A goal with this name already exists")

        doc = goal.model_dump()
        doc["version"] = 1
        result = await db.goals.insert_one(doc)
        gid = str(result.inserted_id)
        created = await db.goals.find_one({"_id": result.inserted_id})

        snapshot = {
            "name": created.get("name"),
            "order": created.get("order", 0),
            "type": created.get("type"),
            "is_negative": created.get("is_negative", False),
            "times_per_day": created.get("times_per_day"),
            "times_per_week": created.get("times_per_week"),
            "reward_rules": created.get("reward_rules", []),
        }
        await db.goal_weeks.insert_one({
            "goal_id": gid,
            "week_start": week_start,
            "enabled": True,
            "snapshot": snapshot,
        })

        if goal.type == "daily":
            tpd = goal.times_per_day or 1
            await ensure_slots_for_goal(db, gid, week_start, today, tpd, goal.is_negative)

        goal_week_doc = await db.goal_weeks.find_one({"goal_id": gid, "week_start": week_start})
        goal_logs = await get_goal_week_logs(db, gid, week_start, week_end)
        seq = await increment_sequence()

        payload = GoalChangedPayload(
            action="created",
            goal=goal_from_doc(created, enabled=True),
            goal_week=goal_week_from_doc(goal_week_doc),
            logs=goal_logs,
            week_start=week_start,
            seq=seq,
        )

        logger.info(f"Created goal: {gid} name={goal.name}")
        session_id = request.headers.get("X-Session-ID")
        await broadcast("goal_changed", payload.model_dump(), exclude_session=session_id)
        return payload
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to create goal: {e}")
        raise


@router.put("/{goal_id}", response_model=GoalChangedPayload)
async def update_goal(goal_id: str, goal: GoalUpdate, request: Request):
    try:
        db = get_db()
        await validate_client_seq(request)

        current = await db.goals.find_one({"_id": ObjectId(goal_id)})
        if not current:
            raise HTTPException(status_code=404, detail="Goal not found")

        client_version = goal.version
        db_version = current.get("version", 0)
        effective_client_version = client_version if client_version is not None else db_version
        if db_version != effective_client_version:
            logger.warning(f"Version conflict on goal {goal_id}: client={client_version} db={db_version}")
            raise HTTPException(status_code=409, detail="Out of sync. Please reload.")

        name_val = goal.model_dump().get("name")
        if name_val:
            existing = await db.goals.find_one(
                {"name": name_val.strip(), "_id": {"$ne": ObjectId(goal_id)}},
                collation={"locale": "en", "strength": 2},
            )
            if existing:
                raise HTTPException(status_code=422, detail="A goal with this name already exists")

        update_data = {}
        for k, v in goal.model_dump().items():
            if k == "version":
                continue
            if k in ("reward_rules", "times_per_week", "times_per_day"):
                if v is not None:
                    update_data[k] = v
            elif v is not None:
                update_data[k] = v

        if not update_data:
            raise HTTPException(status_code=400, detail="No fields to update")

        # Clear irrelevant field when type changes
        new_type = update_data.get("type") or current.get("type")
        if new_type == "daily":
            update_data["times_per_week"] = None
        elif new_type == "weekly_x":
            update_data["times_per_day"] = None

        update_data["version"] = effective_client_version + 1

        # Separate None values for $unset
        unset_data = {k: "" for k, v in update_data.items() if v is None}
        set_data = {k: v for k, v in update_data.items() if v is not None}

        mongo_update = {"$set": set_data}
        if unset_data:
            mongo_update["$unset"] = unset_data

        await db.goals.update_one({"_id": ObjectId(goal_id)}, mongo_update)
        updated = await db.goals.find_one({"_id": ObjectId(goal_id)})

        tz, first_day = await get_settings_cached(db)
        week_start = get_week_start(get_today(tz), first_day)
        week_end = get_week_end(week_start)

        entry = await db.goal_weeks.find_one({"goal_id": goal_id, "week_start": week_start})
        enabled = entry.get("enabled", True) if entry else True

        snapshot = {
            "name": updated.get("name"),
            "order": updated.get("order", 0),
            "type": updated.get("type"),
            "is_negative": updated.get("is_negative", False),
            "times_per_day": updated.get("times_per_day"),
            "times_per_week": updated.get("times_per_week"),
            "reward_rules": updated.get("reward_rules", []),
        }
        await db.goal_weeks.update_one(
            {"goal_id": goal_id, "week_start": week_start},
            {"$set": {"snapshot": snapshot}},
        )

        old_tpd = current.get("times_per_day")
        old_type = current.get("type")
        new_tpd = updated.get("times_per_day")
        new_type = updated.get("type")
        new_is_negative = updated.get("is_negative", False)

        if new_type == "daily":
            if old_type != "daily":
                # Type changed to daily — reconcile handles existing slots correctly
                await reconcile_slots_for_goal(db, goal_id, week_start, get_today(tz), new_tpd or 1, new_is_negative)
            elif old_tpd != new_tpd and new_tpd:
                # times_per_day changed — reconcile existing slots
                await reconcile_slots_for_goal(db, goal_id, week_start, week_end, new_tpd, new_is_negative)
        elif new_type == "weekly_x" and old_type == "daily":
            # Type changed to weekly_x — collapse slots to single slot per day
            from datetime import date as Date, timedelta
            current_date = Date.fromisoformat(week_start)
            end_date = Date.fromisoformat(week_end)
            while current_date <= end_date:
                date_str = current_date.isoformat()
                doc = await db.logs.find_one({"goal_id": goal_id, "date": date_str})
                if doc and len(doc["slots"]) > 1:
                    slots = doc["slots"]
                    if new_is_negative:
                        # Negative: failed if ANY slot was false
                        collapsed = [all(slots)]
                    else:
                        # Positive: succeeded if ANY slot was true
                        collapsed = [any(slots)]
                    await db.logs.update_one(
                        {"goal_id": goal_id, "date": date_str},
                        {"$set": {"slots": collapsed}}
                    )
                current_date += timedelta(days=1)

        goal_week_doc = await db.goal_weeks.find_one({"goal_id": goal_id, "week_start": week_start})
        goal_logs = await get_goal_week_logs(db, goal_id, week_start, week_end)
        seq = await increment_sequence()

        payload = GoalChangedPayload(
            action="updated",
            goal=goal_from_doc(updated, enabled),
            goal_week=goal_week_from_doc(goal_week_doc),
            logs=goal_logs,
            week_start=week_start,
            seq=seq,
        )

        logger.info(f"Updated goal: {goal_id} version={update_data['version']}")
        session_id = request.headers.get("X-Session-ID")
        await broadcast("goal_changed", payload.model_dump(), exclude_session=session_id)
        return payload
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to update goal {goal_id}: {e}")
        raise


@router.delete("/{goal_id}", response_model=GoalChangedPayload)
async def delete_goal(goal_id: str, request: Request):
    try:
        db = get_db()
        await validate_client_seq(request)
        tz, first_day = await get_settings_cached(db)
        today = get_today(tz)
        week_start = get_week_start(today, first_day)
        week_end = get_week_end(week_start)

        # Hard delete the goal
        await db.goals.delete_one({"_id": ObjectId(goal_id)})
        # Delete current week goal_weeks only — past weeks keep their snapshot
        await db.goal_weeks.delete_many({"goal_id": goal_id, "week_start": week_start})
        # Delete current week logs only — past weeks keep their logs
        await db.logs.delete_many({"goal_id": goal_id, "date": {"$gte": week_start, "$lte": week_end}})

        # Reorder remaining goals to fill the gap — only write if order changed
        remaining = await db.goals.find({}).sort("order", 1).to_list(None)
        reordered_goals = []
        for i, g in enumerate(remaining):
            gid = str(g["_id"])
            if g.get("order") != i:
                await db.goals.update_one({"_id": g["_id"]}, {"$set": {"order": i}})
                await db.goal_weeks.update_one(
                    {"goal_id": gid, "week_start": week_start},
                    {"$set": {"snapshot.order": i}},
                )
            reordered_goals.append({"goal_id": gid, "new_order": i})

        seq = await increment_sequence()
        payload = GoalChangedPayload(
            action="deleted",
            goal_id=goal_id,
            week_start=week_start,
            seq=seq,
            reordered_goals=reordered_goals,
        )

        logger.info(f"Deleted goal: {goal_id}, reordered {len(reordered_goals)} remaining goals")
        session_id = request.headers.get("X-Session-ID")
        await broadcast("goal_changed", payload.model_dump(), exclude_session=session_id)
        return payload
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to delete goal {goal_id}: {e}")
        raise


@router.put("/reorder/batch")
async def reorder_goals(body: list[dict], request: Request):
    try:
        db = get_db()
        await validate_client_seq(request)
        tz, first_day = await get_settings_cached(db)
        week_start = get_week_start(get_today(tz), first_day)

        payloads = []
        for item in body:
            gid = item["goal_id"]
            new_order = item["new_order"]
            await db.goals.update_one({"_id": ObjectId(gid)}, {"$set": {"order": new_order}})
            await db.goal_weeks.update_one(
                {"goal_id": gid, "week_start": week_start},
                {"$set": {"snapshot.order": new_order}},
            )
            payloads.append(GoalChangedPayload(
                action="reordered",
                goal_id=gid,
                new_order=new_order,
                week_start=week_start,
                seq=0,  # filled below
            ))

        seq = await increment_sequence()
        for p in payloads:
            p.seq = seq

        session_id = request.headers.get("X-Session-ID")
        await broadcast("goal_changed", [p.model_dump() for p in payloads], exclude_session=session_id)
        logger.info(f"Reordered {len(payloads)} goals")
        return [p.model_dump() for p in payloads]
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to reorder goals: {e}")
        raise
