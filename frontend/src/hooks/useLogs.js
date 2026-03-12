import { useState, useEffect, useCallback } from 'react'
import { getLogs, upsertLog, getWeekSummary } from '../api'
import dayjs from 'dayjs'

export function useLogs(selectedDate) {
  const [logs, setLogs] = useState([])
  const [weekSummary, setWeekSummary] = useState(null)

  // Week is Sun–Sat (dayjs startOf('week') = Sunday by default)
  const weekStart = dayjs(selectedDate).startOf('week')
  const weekEnd = weekStart.add(6, 'day')

  const today = dayjs().format('YYYY-MM-DD')

  const load = useCallback(async () => {
    try {
      const [logsData, summary, todaySummary] = await Promise.all([
        getLogs({ week_start: weekStart.format('YYYY-MM-DD'), week_end: weekEnd.format('YYYY-MM-DD') }),
        getWeekSummary(weekStart.format('YYYY-MM-DD'), weekEnd.format('YYYY-MM-DD'), selectedDate),
        getWeekSummary(weekStart.format('YYYY-MM-DD'), weekEnd.format('YYYY-MM-DD'), today),
      ])
      setLogs(logsData)
      // Use selectedDate summary for row stats (x/y), but today's total_earned for reward
      setWeekSummary({ ...summary, total_earned: todaySummary.total_earned })
    } catch (e) {
      // error already toasted by api layer
    }
  }, [selectedDate])

  useEffect(() => { load() }, [load])

  const toggle = async (goalId, date, slot, currentDone) => {
    // currentDone is the visual state (true = ok/success, false = failed/unmarked)
    // We always store the explicit new state
    await upsertLog({ goal_id: goalId, date, slot, completed: !currentDone })
    await load()
  }

  const getLog = (goalId, date, slot = 0) => {
    return logs.find(l => l.goal_id === goalId && l.date === date && l.slot === slot)
  }

  return { logs, weekSummary, toggle, getLog, reload: load }
}
