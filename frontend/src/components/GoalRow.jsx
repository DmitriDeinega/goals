function fmt(currency, amount) {
  if (!currency || currency === 'NIS') return `₪${amount}`
  if (currency === 'USD') return `$${amount}`
  return `${amount} ${currency}`
}

export default function GoalRow({ goal, date, getLog, onToggle, stats, currency }) {
  const isNeg = goal.is_negative
  const tpd = goal.times_per_day || 1

  const progress = stats
    ? `${stats.completions}/${stats.total_slots}`
    : null

  // Always render earned column — empty string holds the space when no reward yet
  const earned = stats?.earned_reward > 0 ? fmt(currency, stats.earned_reward) : ''

  const toggles = goal.type === 'daily' && tpd > 1
    ? Array.from({ length: tpd }, (_, i) => {
        const log = getLog(goal.id, date, i)
        return { slot: i, done: log ? log.completed : (isNeg ? true : false) }
      })
    : null

  const singleLog = getLog(goal.id, date, 0)
  const singleDone = singleLog ? singleLog.completed : (isNeg ? true : false)

  return (
    <div className="goal-row">
      {/* Fixed left column: earned reward */}
      <span className="row-earned">{earned}</span>

      {/* Fixed: x/y progress */}
      {progress && <span className="goal-progress">{progress}</span>}

      {/* Flexible: goal name */}
      <span className={`goal-name ${isNeg ? 'negative' : ''}`}>
        {goal.name}
        {isNeg && <span className="negative-badge">avoid</span>}
      </span>

      {/* Right: toggle(s) */}
      {toggles ? (
        <div style={{ display: 'flex', gap: 5, flexShrink: 0 }}>
          {toggles.map(({ slot, done }) => (
            <button
              key={slot}
              className={`toggle-btn ${getStatusClass(done, isNeg)}`}
              onClick={() => onToggle(goal.id, date, slot, done)}
              title={`${slot + 1} of ${tpd}`}
            >
              {getIcon(done, isNeg)}
            </button>
          ))}
        </div>
      ) : (
        <button
          className={`toggle-btn ${getStatusClass(singleDone, isNeg)}`}
          onClick={() => onToggle(goal.id, date, 0, singleDone)}
        >
          {getIcon(singleDone, isNeg)}
        </button>
      )}
    </div>
  )
}

function getStatusClass(done, isNegative) {
  if (isNegative) return done ? '' : 'fail'
  return done ? 'success' : ''
}

function getIcon(done, isNegative) {
  if (isNegative) return done ? '' : '✗'
  return done ? '✓' : ''
}
