import WeekStrip from '../components/WeekStrip'
import GoalRow from '../components/GoalRow'
import WeekSummary from '../components/WeekSummary'
import dayjs from 'dayjs'

export default function TodayPage({
  goals, goalWeeks, logs,
  selectedDate, setSelectedDate,
  getLog, onToggle,
  weekSummary, weekStart,
  settings, currency
}) {
  const today = dayjs().format('YYYY-MM-DD')
  const weekEnd = dayjs(weekStart).add(6, 'day').format('YYYY-MM-DD')

  const hasAnyGoals = goals.length > 0
  const emptyMessage = !hasAnyGoals
    ? <>No goals yet.<br />Head to the Goals tab to add some.</>
    : <>No goals were tracked this week.</>

  const liveGoalMap = {}
  for (const g of goals) liveGoalMap[g.id] = g

  const visibleGoals = goalWeeks.length > 0
    ? goalWeeks
        .filter(gw => gw.enabled)
        .map(gw => {
          const live = liveGoalMap[gw.goal_id]
          const snap = gw.snapshot || {}
          return {
            id: gw.goal_id,
            name: snap.name ?? live?.name,
            type: snap.type ?? live?.type,
            is_negative: snap.is_negative ?? live?.is_negative ?? false,
            times_per_day: snap.times_per_day ?? live?.times_per_day,
            times_per_week: snap.times_per_week ?? live?.times_per_week,
            reward_rules: snap.reward_rules ?? live?.reward_rules ?? [],
            order: snap.order ?? live?.order ?? 0,
          }
        })
        .sort((a, b) => a.order - b.order)
    : goals.filter(g => g.enabled)

  return (
    <>
      <WeekStrip selectedDate={selectedDate} onSelect={setSelectedDate} settings={settings} />
      <WeekSummary weekSummary={weekSummary} currency={currency} />

      {visibleGoals.length === 0 ? (
        <div className="empty-state">{emptyMessage}</div>
      ) : (
        visibleGoals.map(goal => (
          <GoalRow
            key={goal.id}
            goal={goal}
            date={selectedDate}
            logs={logs}
            weekStart={weekStart}
            weekEnd={weekEnd}
            today={today}
            getLog={getLog}
            onToggle={onToggle}
            currency={currency}
          />
        ))
      )}
    </>
  )
}
