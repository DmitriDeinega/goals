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

  // Falls back to all enabled goals for current week when summary isn't loaded yet
  const visibleGoals = weekSummary
    ? goals.filter(g => statsMap[g.id] !== undefined)
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
