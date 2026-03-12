import dayjs from 'dayjs'
import DatePicker from './DatePicker'

const DAY_NAMES_SUN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const DAY_NAMES_MON = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

function getWeekStart(date, firstDay = 'sunday') {
  const d = dayjs(date)
  if (firstDay === 'monday') {
    const dow = d.day()
    const diff = dow === 0 ? -6 : 1 - dow
    return d.add(diff, 'day')
  }
  return d.startOf('week')
}

export default function WeekStrip({ selectedDate, onSelect, settings }) {
  const today = dayjs()

  const firstDay = settings?.first_day_of_week || 'sunday'
  const startDate = settings?.start_date || null
  const dayNames = firstDay === 'monday' ? DAY_NAMES_MON : DAY_NAMES_SUN

  // Derive week from selectedDate, not from local offset state
  const currentWeekStart = getWeekStart(today, firstDay)
  const selectedWeekStart = getWeekStart(dayjs(selectedDate), firstDay)
  const weekOffset = selectedWeekStart.diff(currentWeekStart, 'week')

  const weekStart = selectedWeekStart
  const isCurrentWeek = weekOffset === 0

  const prevWeekStart = weekStart.subtract(1, 'week')
  const canGoBack = !startDate || prevWeekStart.format('YYYY-MM-DD') >= startDate || weekStart.format('YYYY-MM-DD') > startDate

  const selectedDow = dayjs(selectedDate).day() // 0=Sun..6=Sat

  const goBack = () => {
    if (!canGoBack) return
    const newWeekStart = weekStart.subtract(1, 'week')
    const newWeekStartDow = newWeekStart.day()
    const diff = (selectedDow - newWeekStartDow + 7) % 7
    onSelect(newWeekStart.add(diff, 'day').format('YYYY-MM-DD'))
  }

  const goForward = () => {
    if (isCurrentWeek) return
    const newWeekStart = weekStart.add(1, 'week')
    const newWeekStartDow = newWeekStart.day()
    const diff = (selectedDow - newWeekStartDow + 7) % 7
    const candidate = newWeekStart.add(diff, 'day')
    const capped = candidate.isAfter(today) ? today : candidate
    onSelect(capped.format('YYYY-MM-DD'))
  }

  const handleJump = (val) => {
    const picked = dayjs(val)
    if (!picked.isValid()) return
    // Select the picked date itself, capped at today
    const capped = picked.isAfter(today) ? today : picked
    onSelect(capped.format('YYYY-MM-DD'))
  }

  const todayStr = today.format('YYYY-MM-DD')

  return (
    <div className="week-strip-wrap">
      {/* Date jump picker sits above the strip */}
      <div className="week-jump-row">
        <DatePicker
          value={selectedDate || ''}
          onChange={handleJump}
          placeholder="Jump to week"
          max={todayStr}
          min={startDate || undefined}
          clearable={false}
        />
      </div>

      {/* Days row with nav arrows flanking */}
      <div className="week-strip">
        <button
          className={`week-nav-btn ${!canGoBack ? 'disabled' : ''}`}
          onClick={goBack}
          disabled={!canGoBack}
        >‹</button>

        {Array.from({ length: 7 }, (_, i) => {
          const day = weekStart.add(i, 'day')
          const dateStr = day.format('YYYY-MM-DD')
          const isToday = dateStr === todayStr
          const isSelected = dateStr === selectedDate
          const isFuture = dateStr > todayStr
          const isBefore = startDate && dateStr < startDate

          return (
            <button
              key={dateStr}
              className={`day-btn ${isToday ? 'today' : ''} ${isSelected ? 'selected' : ''} ${isFuture || isBefore ? 'future' : ''}`}
              onClick={() => { if (!isFuture && !isBefore) onSelect(dateStr) }}
              disabled={isFuture || isBefore}
            >
              <span className="day-name">{dayNames[i]}</span>
              <span className="day-num">{day.format('D')}</span>
            </button>
          )
        })}

        <button
          className={`week-nav-btn ${isCurrentWeek ? 'disabled' : ''}`}
          onClick={goForward}
          disabled={isCurrentWeek}
        >›</button>
      </div>
    </div>
  )
}
