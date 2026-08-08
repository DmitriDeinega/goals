import { useState, useCallback } from 'react'
import { getInit, upsertLog, createGoal, updateGoal, deleteGoal, reorderGoals, setGoalEnabled, ensureWeek, getWeekData } from '../api'
import { toast } from '../components/Toast'
import dayjs from 'dayjs'

// ─── Client-side calculations ────────────────────────────────────────────────

export function computeGoalStats(goal, weekLogs, weekDays) {
  const isNeg = goal.is_negative ?? false
  // Find log doc for this goal — one per day
  const goalLogs = weekLogs.filter(l => l.goal_id === goal.id)

  let completions = 0
  let total_slots = 7

  if (goal.type === 'daily') {
    const tpd = goal.times_per_day || 1
    if (tpd > 1) {
      completions = weekDays.reduce((acc, d) => {
        const log = goalLogs.find(l => l.date === d)
        if (!log) return acc
        const success = isNeg
          ? log.slots.some(s => s)          // negative: success if at least one avoided (not all failed)
          : log.slots.every(s => s)         // positive: success only if all done
        return acc + (success ? 1 : 0)
      }, 0)
    } else {
      if (isNeg) {
        const failedDays = new Set(
          goalLogs.filter(l => l.slots.some(s => !s)).map(l => l.date)
        )
        completions = weekDays.filter(d => !failedDays.has(d)).length
      } else {
        completions = goalLogs.filter(l => weekDays.includes(l.date) && l.slots.some(s => s)).length
      }
    }
    total_slots = 7
  } else {
    const tpw = goal.times_per_week || 7
    if (isNeg) {
      const failedDays = new Set(
        goalLogs.filter(l => l.slots.some(s => !s)).map(l => l.date)
      )
      completions = weekDays.filter(d => !failedDays.has(d)).length
    } else {
      completions = goalLogs.filter(l => weekDays.includes(l.date) && l.slots.some(s => s)).length
    }
    total_slots = tpw
  }

  const rules = [...(goal.reward_rules || [])].sort((a, b) => a.min_completions - b.min_completions)
  const rewardCompletions = goal.type === 'weekly_x' ? Math.min(completions, goal.times_per_week || 7) : completions
  const earned_reward = rules.reduce((acc, r) => rewardCompletions >= r.min_completions ? acc + r.reward_amount : acc, 0)

  return { completions, total_slots, earned_reward }
}

export function computeWeekSummary(goals, goalWeeks, logs, weekStart, weekEnd, today) {
  const enabledGoalWeeks = goalWeeks.filter(gw => gw.enabled)
  if (enabledGoalWeeks.length === 0) return { pct: 0, totalEarned: 0 }

  const liveGoalMap = {}
  for (const g of goals) liveGoalMap[g.id] = g

  const activeGoals = enabledGoalWeeks.map(gw => {
    const snap = gw.snapshot || {}
    const live = liveGoalMap[gw.goal_id] || {}
    return {
      id: gw.goal_id,
      type: snap.type ?? live.type,
      is_negative: snap.is_negative ?? live.is_negative ?? false,
      times_per_day: snap.times_per_day ?? live.times_per_day,
      times_per_week: snap.times_per_week ?? live.times_per_week,
      reward_rules: snap.reward_rules ?? live.reward_rules ?? [],
    }
  })

  const cutoff = weekEnd < today ? weekEnd : today
  const weekDays = getDaysUpTo(weekStart, cutoff)

  let totalPct = 0
  let totalEarned = 0

  for (const goal of activeGoals) {
    const { completions, total_slots, earned_reward } = computeGoalStats(goal, logs, weekDays)
    totalPct += Math.min(completions, total_slots) / Math.max(total_slots, 1)
    totalEarned += earned_reward
  }

  return {
    pct: Math.round((totalPct / activeGoals.length) * 100),
    totalEarned,
  }
}

export function getDaysUpTo(weekStart, cutoff) {
  const days = []
  let current = dayjs(weekStart)
  const end = dayjs(cutoff)
  while (!current.isAfter(end)) {
    days.push(current.format('YYYY-MM-DD'))
    current = current.add(1, 'day')
  }
  return days
}

// ─── Hook ────────────────────────────────────────────────────────────────────

export function useAppState() {
  const [goals, setGoals] = useState([])
  const [settings, setSettings] = useState(null)
  const [loading, setLoading] = useState(true)

  // Single state object for week data — prevents double render on week switch
  const [weekState, setWeekState] = useState({ goalWeeks: [], logs: [], weekStart: null })
  const weekGoalWeeks = weekState.goalWeeks
  const weekLogs = weekState.logs
  const visibleWeekStart = weekState.weekStart

  // `/api/init` always returns the CURRENT week — it has no notion of which
  // week the user is viewing. So its week payload is only applied when it
  // matches the visible week (or nothing is loaded yet). Overwriting
  // unconditionally made every refresh flash the current week's data and then
  // snap back once the effect in App.jsx re-fetched the real one.
  const load = useCallback(async () => {
    try {
      const data = await getInit()
      setGoals(data.goals)
      setSettings(data.settings)
      setWeekState(prev => {
        const initWeekStart = data.goal_weeks[0]?.week_start ?? null
        if (prev.weekStart !== null && prev.weekStart !== initWeekStart) {
          // Viewing a different week — keep it; App.jsx refreshes it separately.
          return prev
        }
        return {
          goalWeeks: data.goal_weeks,
          logs: data.logs,
          weekStart: initWeekStart,
        }
      })
    } catch (e) {
      // error toasted by api layer
    } finally {
      setLoading(false)
    }
  }, [])

  const loadWeek = useCallback(async (weekStart) => {
    try {
      const data = await getWeekData(weekStart)
      setWeekState({
        goalWeeks: data.goal_weeks,
        logs: data.logs,
        weekStart,
      })
    } catch (e) {
      // error toasted by api layer
    }
  }, [])

  // ── Log actions ──────────────────────────────────────────────────────────

  const [togglingSlots, setTogglingSlots] = useState(new Set())

  const toggle = async (goalId, date, slotIndex, currentValue) => {
    const key = `${goalId}:${date}:${slotIndex}`
    if (togglingSlots.has(key)) return
    setTogglingSlots(prev => new Set([...prev, key]))

    // Pessimistic: only apply state when server confirms via applyLogChanged.
    const newValue = !currentValue
    try {
      const payload = await upsertLog({ goal_id: goalId, date, slot_index: slotIndex, value: newValue })
      applyLogChanged(payload)
    } catch {
      // No revert needed — state was never mutated.
    } finally {
      setTogglingSlots(prev => { const s = new Set(prev); s.delete(key); return s })
    }
  }

  const applyLogChanged = ({ goal_id, logs: updatedLogs }) => {
    setWeekState(prev => {
      const updatedDates = new Set(updatedLogs.map(l => l.date))
      const kept = prev.logs.filter(l => !(l.goal_id === goal_id && updatedDates.has(l.date)))
      return { ...prev, logs: [...kept, ...updatedLogs] }
    })
  }

  // ── Goal actions ─────────────────────────────────────────────────────────

  const addGoal = async (data) => {
    const maxOrder = goals.length > 0 ? Math.max(...goals.map(g => g.order)) : -1
    const payload = await createGoal({ ...data, order: maxOrder + 1 })
    await ensureWeek()
    applyGoalChanged(payload)
  }

  const editGoal = async (id, data) => {
    try {
      const payload = await updateGoal(id, data)
      applyGoalChanged(payload)
    } catch (e) {
      if (e.status === 409) {
        toast('This goal was updated on another device. Reloading...')
        await load()
      }
      throw e
    }
  }

  const removeGoal = async (id) => {
    const payload = await deleteGoal(id)
    applyGoalChanged(payload)
  }

  const setEnabled = async (id, enabled) => {
    const payload = await setGoalEnabled(id, enabled)
    applyGoalChanged(payload)
  }

  const reorder = async (orderedIds) => {
    // Snapshot prior order so we can revert if the server rejects. Previously
    // a 409/network failure left the UI silently diverged from the backend.
    const priorGoals = goals
    const priorGoalWeeks = weekState.goalWeeks
    setGoals(prev => {
      const updated = prev.map(g => {
        const newOrder = orderedIds.indexOf(g.id)
        return newOrder !== -1 ? { ...g, order: newOrder } : g
      })
      return updated.sort((a, b) => a.order - b.order)
    })
    setWeekState(prev => ({
      ...prev,
      goalWeeks: prev.goalWeeks.map(gw => {
        const newOrder = orderedIds.indexOf(gw.goal_id)
        if (newOrder === -1) return gw
        return { ...gw, snapshot: { ...gw.snapshot, order: newOrder } }
      })
    }))
    const items = orderedIds.map((goal_id, index) => ({ goal_id, new_order: index }))
    try {
      await reorderGoals(items)
    } catch (e) {
      if (e.status === 409) {
        // Another client moved meanwhile — fetch authoritative state.
        toast('Reorder out of sync. Reloading...')
        await load()
      } else {
        // Network/5xx: restore prior order.
        setGoals(priorGoals)
        setWeekState(prev => ({ ...prev, goalWeeks: priorGoalWeeks }))
        toast(`Reorder failed: ${e.message || 'network error'}`)
      }
    }
  }

  const applyGoalChanged = (payload) => {
    const { action, goal, goal_id, goal_week, logs: goalLogs, new_order, week_start, reordered_goals } = payload

    if (action === 'created') {
      setGoals(prev => [...prev, goal].sort((a, b) => a.order - b.order))
      if (week_start === visibleWeekStart) {
        setWeekState(prev => ({
          ...prev,
          goalWeeks: goal_week ? [...prev.goalWeeks, goal_week] : prev.goalWeeks,
          logs: goalLogs?.length ? [...prev.logs, ...goalLogs] : prev.logs,
        }))
      }
    }

    if (action === 'updated') {
      setGoals(prev => prev.map(g => g.id === goal.id ? goal : g).sort((a, b) => a.order - b.order))
      if (week_start === visibleWeekStart) {
        setWeekState(prev => {
          const updatedDates = goalLogs ? new Set(goalLogs.map(l => l.date)) : null
          return {
            ...prev,
            goalWeeks: goal_week
              ? prev.goalWeeks.map(gw => gw.goal_id === goal_week.goal_id ? goal_week : gw)
              : prev.goalWeeks,
            logs: goalLogs
              ? [...prev.logs.filter(l => !(l.goal_id === goal.id && updatedDates.has(l.date))), ...goalLogs]
              : prev.logs,
          }
        })
      }
    }

    if (action === 'deleted') {
      setGoals(prev => {
        const remaining = prev.filter(g => g.id !== goal_id)
        if (reordered_goals?.length) {
          const orderMap = {}
          for (const { goal_id: gid, new_order: ord } of reordered_goals) orderMap[gid] = ord
          return remaining.map(g => ({ ...g, order: orderMap[g.id] ?? g.order })).sort((a, b) => a.order - b.order)
        }
        return remaining.sort((a, b) => a.order - b.order).map((g, i) => ({ ...g, order: i }))
      })
      if (week_start === visibleWeekStart) {
        setWeekState(prev => {
          const orderMap = {}
          if (reordered_goals?.length) {
            for (const { goal_id: gid, new_order: ord } of reordered_goals) orderMap[gid] = ord
          }
          return {
            ...prev,
            goalWeeks: prev.goalWeeks
              .filter(gw => gw.goal_id !== goal_id)
              .map(gw => orderMap[gw.goal_id] !== undefined
                ? { ...gw, snapshot: { ...gw.snapshot, order: orderMap[gw.goal_id] } }
                : gw
              ),
            logs: prev.logs.filter(l => l.goal_id !== goal_id),
          }
        })
      }
    }

    if (action === 'reordered') {
      setGoals(prev =>
        prev.map(g => g.id === goal_id ? { ...g, order: new_order } : g)
            .sort((a, b) => a.order - b.order)
      )
      if (week_start === visibleWeekStart) {
        setWeekState(prev => ({
          ...prev,
          goalWeeks: prev.goalWeeks.map(gw =>
            gw.goal_id === goal_id
              ? { ...gw, snapshot: { ...gw.snapshot, order: new_order } }
              : gw
          ),
        }))
      }
    }
  }

  const applyDayChanged = (date, newLogs) => {
    setWeekState(prev => ({
      ...prev,
      logs: [...prev.logs.filter(l => l.date !== date), ...newLogs],
    }))
  }

  // ── SSE handlers ─────────────────────────────────────────────────────────

  const handleLogChanged = (data) => applyLogChanged(data)

  const handleGoalChanged = (data) => {
    if (Array.isArray(data)) {
      setGoals(prev => {
        let updated = [...prev]
        for (const { goal_id, new_order } of data) {
          updated = updated.map(g => g.id === goal_id ? { ...g, order: new_order } : g)
        }
        return updated.sort((a, b) => a.order - b.order)
      })
      if (data[0]?.week_start === visibleWeekStart) {
        const orderMap = {}
        for (const { goal_id, new_order } of data) orderMap[goal_id] = new_order
        setWeekState(prev => ({
          ...prev,
          goalWeeks: prev.goalWeeks.map(gw =>
            orderMap[gw.goal_id] !== undefined
              ? { ...gw, snapshot: { ...gw.snapshot, order: orderMap[gw.goal_id] } }
              : gw
          ),
        }))
      }
    } else {
      applyGoalChanged(data)
    }
  }

  // getLog returns the slots array for a goal on a date
  const getLog = (goalId, date) => {
    return weekLogs.find(l => l.goal_id === goalId && l.date === date)
  }

  return {
    goals,
    goalWeeks: weekGoalWeeks,
    logs: weekLogs,
    settings,
    loading,
    load,
    loadWeek,
    visibleWeekStart,
    toggle,
    togglingSlots,
    addGoal,
    editGoal,
    removeGoal,
    setEnabled,
    reorder,
    getLog,
    handleLogChanged,
    handleGoalChanged,
    applyDayChanged,
  }
}
