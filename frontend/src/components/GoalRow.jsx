import { useState } from 'react'
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

  // Slots that were just toggled. While the pointer remains over the slot
  // after the click, suppress the :hover preview — otherwise the slot
  // flashes the action-color it just LEFT (e.g. green hover lingering on a
  // freshly-unchecked goal), which reads as "the click didn't take".
  const [justClicked, setJustClicked] = useState(null)

  const cutoff = weekEnd < today ? weekEnd : today
  const weekDays = getDaysUpTo(weekStart, cutoff)
  const { completions, total_slots, earned_reward } = computeGoalStats(goal, logs, weekDays)

  const progress = `${completions}/${total_slots}`
  const earned = earned_reward > 0 ? fmt(currency, earned_reward) : ''

  const logDoc = getLog(goal.id, date)
  const defaultSlotValue = isNeg ? true : false

  const slots = logDoc
    ? logDoc.slots
    : Array(tpd).fill(defaultSlotValue)

  const handleClick = (i, value) => {
    setJustClicked(i)
    onToggle(goal.id, date, i, value)
  }
  const handleMouseLeave = (i) => {
    if (justClicked === i) setJustClicked(null)
  }

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
                className={`toggle-btn ${getStatusClass(value, isNeg)} ${isNeg ? 'neg' : ''} ${justClicked === i ? 'just-clicked' : ''}`}
                onClick={() => handleClick(i, value)}
                onMouseLeave={() => handleMouseLeave(i)}
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
              className={`toggle-btn ${getStatusClass(slots[0], isNeg)} ${isNeg ? 'neg' : ''} ${justClicked === 0 ? 'just-clicked' : ''}`}
              onClick={() => handleClick(0, slots[0])}
              onMouseLeave={() => handleMouseLeave(0)}
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
