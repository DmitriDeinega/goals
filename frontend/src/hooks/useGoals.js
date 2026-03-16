import { useState, useEffect, useCallback } from 'react'
import { getGoals, createGoal, updateGoal, deleteGoal, setGoalEnabled, reorderGoals, ensureWeek } from '../api'
import { toast } from '../components/Toast'

export function useGoals() {
  const [goals, setGoals] = useState([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async (silent = false) => {
    try {
      const data = await getGoals(silent)
      setGoals(data)
    } catch (e) {
      // error toasted by api layer unless silent
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const add = async (data) => {
    await createGoal({ ...data, order: goals.length })
    await ensureWeek()
    await load()
  }

  // data should include version from the caller (GoalsPage tracks it via ref)
  const update = async (id, data) => {
    try {
      await updateGoal(id, data)
      await load()
    } catch (e) {
      if (e.status === 409) {
        toast('This goal was updated on another device. Reloading...')
        await load()
      }
      throw e
    }
  }

  const remove = async (id) => {
    await deleteGoal(id)
    await load()
  }

  const setEnabled = async (id, enabled) => {
    await setGoalEnabled(id, enabled)
    await load()
  }

  const reorder = async (ids) => {
    await reorderGoals(ids)
    await load()
  }

  return { goals, loading, add, update, remove, setEnabled, reorder, reload: load }
}
