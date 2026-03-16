import WeekStrip from '../components/WeekStrip'
import GoalRow from '../components/GoalRow'
import WeekSummary from '../components/WeekSummary'

export default function TodayPage({ goals, selectedDate, setSelectedDate, getLog, onToggle, weekSummary, settings, currency }) {
  const statsMap = {}
  if (weekSummary?.goals) {
    for (const g of weekSummary.goals) {
      statsMap[g.goal_id] = g
    }
  }

  // Distinguish between "no goals ever" vs "no goals this week"
  const hasAnyGoals = goals.length > 0
  const emptyMessage = !hasAnyGoals
    ? <>No goals yet.<br />Head to the Goals tab to add some.</>
    : <>No goals were tracked this week.</>

  // Build goal rows from snapshot (past weeks) or live goals (current week)
  // Snapshot preserves name, type, order, is_negative as they were that week
  const liveGoalMap = {}
  for (const g of goals) liveGoalMap[g.id] = g

  const visibleGoals = weekSummary
    ? weekSummary.goals
        .map(s => {
          const live = liveGoalMap[s.goal_id]
          // Use snapshot fields if available, fall back to live for id/toggling
          return {
            id: s.goal_id,
            name: s.goal_name ?? live?.name,
            type: s.type ?? live?.type,
            is_negative: s.is_negative ?? live?.is_negative ?? false,
            times_per_day: s.times_per_day ?? live?.times_per_day,
            times_per_week: s.times_per_week ?? live?.times_per_week,
            order: s.order ?? live?.order ?? 0,
          }
        })
        .sort((a, b) => a.order - b.order)
    : goals.filter(g => g.enabled)

  return (
    <>
      <WeekStrip selectedDate={selectedDate} onSelect={setSelectedDate} settings={settings} />
      <WeekSummary summary={weekSummary} currency={currency} />

      {visibleGoals.length === 0 ? (
        <div className="empty-state">{emptyMessage}</div>
      ) : (
        visibleGoals.map(goal => (
          <GoalRow
            key={goal.id}
            goal={goal}
            date={selectedDate}
            getLog={getLog}
            onToggle={onToggle}
            stats={statsMap[goal.id]}
            currency={currency}
          />
        ))
      )}
    </>
  )
}
