import { toast } from '../components/Toast'

const BASE = '/api'

export const SESSION_ID = typeof crypto.randomUUID === 'function'
  ? crypto.randomUUID()
  : Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2)

let _lastSeq = 0
export const getLastSeq = () => _lastSeq
export const setLastSeq = (seq) => { _lastSeq = seq }

async function request(path, options = {}) {
  try {
    const res = await fetch(`${BASE}${path}`, {
      headers: {
        'Content-Type': 'application/json',
        'X-Session-ID': SESSION_ID,
        'X-Sequence': String(_lastSeq),
        ...(options.headers || {}),
      },
      ...options,
    })
    if (!res.ok) {
      const detail = await res.json().then(d => d.detail).catch(() => null)
      const msg = detail || `Request failed (${res.status})`
      if (res.status === 409) {
        const err = new Error(msg)
        err.status = 409
        throw err
      }
      toast(msg)
      throw new Error(msg)
    }
    const data = await res.json()
    // Update lastSeq from response — handle both single object and array (reorder)
    if (Array.isArray(data)) {
      const seq = data[data.length - 1]?.seq
      if (seq !== undefined) setLastSeq(seq)
    } else if (data?.seq !== undefined) {
      setLastSeq(data.seq)
    }
    return data
  } catch (e) {
    if (e.status === 409) throw e
    if (e.message === 'Failed to fetch') {
      toast('Could not reach the server. Check your connection.')
    }
    throw e
  }
}

// Init
export const getInit = () => request('/init')
export const getWeekData = (weekStart) => request(`/week-data?week_start=${weekStart}`)

// Goals
export const createGoal = (data) => request('/goals/', { method: 'POST', body: JSON.stringify(data) })
export const updateGoal = (id, data) => request(`/goals/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteGoal = (id) => request(`/goals/${id}`, { method: 'DELETE' })
export const reorderGoals = (items) => request('/goals/reorder/batch', { method: 'PUT', body: JSON.stringify(items) })

// Logs — slot_index and value instead of slot and completed
export const upsertLog = (data) => request('/logs/', { method: 'POST', body: JSON.stringify(data) })

// Weeks
export const ensureWeek = () => request('/weeks/ensure', { method: 'POST' })
export const setGoalEnabled = (goalId, enabled) =>
  request(`/weeks/${goalId}/enabled`, { method: 'PUT', body: JSON.stringify({ enabled }) })
