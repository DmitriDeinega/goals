import { toast } from '../components/Toast'

const BASE = '/api'

async function request(path, options = {}) {
  try {
    const res = await fetch(`${BASE}${path}`, {
      headers: { 'Content-Type': 'application/json' },
      ...options,
    })
    if (!res.ok) {
      const detail = await res.json().then(d => d.detail).catch(() => null)
      const msg = detail || `Request failed (${res.status})`
      if (res.status === 409) {
        // Conflict — throw without toasting, caller handles it
        const err = new Error(msg)
        err.status = 409
        throw err
      }
      toast(msg)
      throw new Error(msg)
    }
    return res.json()
  } catch (e) {
    if (e.status === 409) throw e  // pass through conflict errors
    if (e.message === 'Failed to fetch') {
      toast('Could not reach the server. Check your connection.')
    }
    throw e
  }
}

// Goals
export const getGoals = () => request('/goals/')
export const createGoal = (data) => request('/goals/', { method: 'POST', body: JSON.stringify(data) })
export const updateGoal = (id, data) => request(`/goals/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteGoal = (id) => request(`/goals/${id}`, { method: 'DELETE' })
export const reorderGoals = (ids) => request('/goals/reorder/batch', { method: 'PUT', body: JSON.stringify(ids) })

// Settings
export const getSettings = () => request('/settings/')
export const updateSettings = (data) => request('/settings/', { method: 'PUT', body: JSON.stringify(data) })

// Logs
export const getLogs = (params) => {
  const q = new URLSearchParams(params).toString()
  return request(`/logs/?${q}`)
}
export const upsertLog = (data) => request('/logs/', { method: 'POST', body: JSON.stringify(data) })
export const getWeekSummary = (weekStart, weekEnd, selectedDate = null) => {
  const params = `week_start=${weekStart}&week_end=${weekEnd}${selectedDate ? `&selected_date=${selectedDate}` : ''}`
  return request(`/logs/week-summary?${params}`)
}

// Weeks
export const ensureWeek = () => request('/weeks/ensure', { method: 'POST' })
export const setGoalEnabled = (goalId, enabled) =>
  request(`/weeks/${goalId}/enabled`, { method: 'PUT', body: JSON.stringify({ enabled }) })
