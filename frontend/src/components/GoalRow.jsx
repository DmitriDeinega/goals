import { computeGoalStats, getDaysUpTo } from '../hooks/useAppState'
import dayjs from 'dayjs'

function fmt(currency, amount) {
  if (!currency || currency === 'NIS') return `₪${amount}`
  if (currency === 'USD') return `$${amount}`
  return `${amount} ${currency}`
}

export default function GoalRow({ goal, date, logs, weekStart, weekEnd, today, getLog, onToggle, togglingSlots, currency }) {
  const isNeg = goal.is_negative
  const tpd = goal.times_per_day || 1

  const cutoff = weekEnd < today ? weekEnd : today
  const weekDays = getDaysUpTo(weekStart, cutoff)
  const { completions, total_slots, earned_reward } = computeGoalStats(goal, logs, weekDays)

  const progress = `${completions}/${total_slots}`
  const earned = earned_reward > 0 ? fmt(currency, earned_reward) : ''

  // Get log doc for this goal on this date — contains slots array
  const logDoc = getLog(goal.id, date)
  const defaultSlotValue = isNeg ? true : false

  const slots = logDoc
    ? logDoc.slots
    : Array(tpd).fill(defaultSlotValue)

  return (
    <div className="goal-row">
      <span className="row-earned">{earned}</span>
      <span className="goal-progress">{progress}</span>

      <span className={`goal-name ${isNeg ? 'negative' : ''}`}>
        {goal.name}
        {isNeg && <span className="negative-badge">avoid</span>}
      </span>

      {slots.length > 1 ? (
        <div style={{ display: 'flex', gap: 5, flexShrink: 0 }}>
          {slots.map((value, i) => {
            const toggling = togglingSlots?.has(`${goal.id}:${date}:${i}`)
            return (
              <button
                key={i}
                className={`toggle-btn ${getStatusClass(value, isNeg)}`}
                onClick={() => onToggle(goal.id, date, i, value)}
                disabled={toggling}
                style={toggling ? { opacity: 0.5 } : undefined}
                title={`${i + 1} of ${tpd}`}
              >
                {getIcon(value, isNeg)}
              </button>
            )
          })}
        </div>
      ) : (
        (() => {
          const toggling = togglingSlots?.has(`${goal.id}:${date}:0`)
          return (
            <button
              className={`toggle-btn ${getStatusClass(slots[0], isNeg)}`}
              onClick={() => onToggle(goal.id, date, 0, slots[0])}
              disabled={toggling}
              style={toggling ? { opacity: 0.5 } : undefined}
            >
              {getIcon(slots[0], isNeg)}
            </button>
          )
        })()
      )}
    </div>
  )
}

function getStatusClass(value, isNegative) {
  if (isNegative) return value ? '' : 'fail'
  return value ? 'success' : ''
}

function getIcon(value, isNegative) {
  if (isNegative) return value ? '' : '✗'
  return value ? '✓' : ''
}
