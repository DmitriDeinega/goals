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
        const successCount = isNeg
          ? log.slots.filter(s => s).length   // negative: true = avoided
          : log.slots.filter(s => s).length   // positive: true = done
        return acc + (successCount >= tpd ? 1 : 0)
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
    totalPct += completions / Math.max(total_slots, 1)
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

  const [weekGoalWeeks, setWeekGoalWeeks] = useState([])
  const [weekLogs, setWeekLogs] = useState([])
  const [visibleWeekStart, setVisibleWeekStart] = useState(null)

  const load = useCallback(async () => {
    try {
      const data = await getInit()
      setGoals(data.goals)
      setWeekGoalWeeks(data.goal_weeks)
      setWeekLogs(data.logs)
      setVisibleWeekStart(data.goal_weeks[0]?.week_start ?? null)
      setSettings(data.settings)
    } catch (e) {
      // error toasted by api layer
    } finally {
      setLoading(false)
    }
  }, [])

  const loadWeek = useCallback(async (weekStart) => {
    try {
      const data = await getWeekData(weekStart)
      setWeekGoalWeeks(data.goal_weeks)
      setWeekLogs(data.logs)
      setVisibleWeekStart(weekStart)
    } catch (e) {
      // error toasted by api layer
    }
  }, [])

  // ── Log actions ──────────────────────────────────────────────────────────

  const toggle = async (goalId, date, slotIndex, currentValue) => {
    const payload = await upsertLog({
      goal_id: goalId,
      date,
      slot_index: slotIndex,
      value: !currentValue,
    })
    applyLogChanged(payload)
  }

  const applyLogChanged = ({ goal_id, logs: updatedLogs }) => {
    setWeekLogs(prev => {
      const updatedDates = new Set(updatedLogs.map(l => l.date))
      const kept = prev.filter(l => !(l.goal_id === goal_id && updatedDates.has(l.date)))
      return [...kept, ...updatedLogs]
    })
  }

  // ── Goal actions ─────────────────────────────────────────────────────────

  const addGoal = async (data) => {
    const payload = await createGoal({ ...data, order: goals.length })
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
    setGoals(prev => {
      const updated = prev.map(g => {
        const newOrder = orderedIds.indexOf(g.id)
        return newOrder !== -1 ? { ...g, order: newOrder } : g
      })
      return updated.sort((a, b) => a.order - b.order)
    })
    setWeekGoalWeeks(prev => prev.map(gw => {
      const newOrder = orderedIds.indexOf(gw.goal_id)
      if (newOrder === -1) return gw
      return { ...gw, snapshot: { ...gw.snapshot, order: newOrder } }
    }))
    const items = orderedIds.map((goal_id, index) => ({ goal_id, new_order: index }))
    await reorderGoals(items)
  }

  const applyGoalChanged = (payload) => {
    const { action, goal, goal_id, goal_week, logs: goalLogs, new_order, week_start } = payload

    if (action === 'created') {
      setGoals(prev => [...prev, goal])
      if (week_start === visibleWeekStart) {
        if (goal_week) setWeekGoalWeeks(prev => [...prev, goal_week])
        if (goalLogs?.length) setWeekLogs(prev => [...prev, ...goalLogs])
      }
    }

    if (action === 'updated') {
      setGoals(prev => prev.map(g => g.id === goal.id ? goal : g))
      if (week_start === visibleWeekStart) {
        if (goal_week) {
          setWeekGoalWeeks(prev => prev.map(gw =>
            gw.goal_id === goal_week.goal_id ? goal_week : gw
          ))
        }
        if (goalLogs) {
          const updatedDates = new Set(goalLogs.map(l => l.date))
          setWeekLogs(prev => [
            ...prev.filter(l => !(l.goal_id === goal.id && updatedDates.has(l.date))),
            ...goalLogs,
          ])
        }
      }
    }

    if (action === 'deleted') {
      setGoals(prev => prev.filter(g => g.id !== goal_id))
      if (week_start === visibleWeekStart) {
        setWeekGoalWeeks(prev => prev.filter(gw => gw.goal_id !== goal_id))
        setWeekLogs(prev => prev.filter(l => l.goal_id !== goal_id))
      }
    }

    if (action === 'reordered') {
      setGoals(prev =>
        prev.map(g => g.id === goal_id ? { ...g, order: new_order } : g)
            .sort((a, b) => a.order - b.order)
      )
      if (week_start === visibleWeekStart) {
        setWeekGoalWeeks(prev => prev.map(gw =>
          gw.goal_id === goal_id
            ? { ...gw, snapshot: { ...gw.snapshot, order: new_order } }
            : gw
        ))
      }
    }
  }

  const applyDayChanged = (date, newLogs) => {
    setWeekLogs(prev => [
      ...prev.filter(l => l.date !== date),
      ...newLogs,
    ])
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
        setWeekGoalWeeks(prev => prev.map(gw =>
          orderMap[gw.goal_id] !== undefined
            ? { ...gw, snapshot: { ...gw.snapshot, order: orderMap[gw.goal_id] } }
            : gw
        ))
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
