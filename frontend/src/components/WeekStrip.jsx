import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import DatePicker from './DatePicker'

dayjs.extend(utc)
dayjs.extend(timezone)

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
  const tz = settings?.timezone
  const firstDay = settings?.first_day_of_week || 'sunday'
  const startDate = settings?.start_date || null
  const dayNames = firstDay === 'monday' ? DAY_NAMES_MON : DAY_NAMES_SUN

  // Compute today in the server's timezone
  const todayStr = tz ? dayjs().tz(tz).format('YYYY-MM-DD') : dayjs().format('YYYY-MM-DD')
  const todayDayjs = dayjs(todayStr)

  const currentWeekStart = getWeekStart(todayStr, firstDay)
  const selectedWeekStart = getWeekStart(dayjs(selectedDate), firstDay)
  const weekOffset = selectedWeekStart.diff(currentWeekStart, 'week')

  const weekStart = selectedWeekStart
  const isCurrentWeek = weekOffset === 0

  const prevWeekStart = weekStart.subtract(1, 'week')
  const canGoBack = !startDate || prevWeekStart.format('YYYY-MM-DD') >= startDate || weekStart.format('YYYY-MM-DD') > startDate

  const goBack = () => {
    if (!canGoBack) return
    const newWeekEnd = weekStart.subtract(1, 'day')
    onSelect(newWeekEnd.format('YYYY-MM-DD'))
  }

  const goForward = () => {
    if (isCurrentWeek) return
    const newWeekEnd = weekStart.add(1, 'week').add(6, 'day')
    const capped = newWeekEnd.isAfter(todayDayjs) ? todayDayjs : newWeekEnd
    onSelect(capped.format('YYYY-MM-DD'))
  }

  const handleJump = (val) => {
    const picked = dayjs(val)
    if (!picked.isValid()) return
    const capped = picked.isAfter(todayDayjs) ? todayDayjs : picked
    onSelect(capped.format('YYYY-MM-DD'))
  }

  return (
    <div className="week-strip-wrap">
      <div className="week-jump-row">
        <DatePicker
          value={selectedDate || ''}
          onChange={handleJump}
          placeholder="Jump to week"
          max={todayStr}
          min={startDate || undefined}
          clearable={false}
          showToday={true}
          onTodayReset={() => onSelect(todayStr)}
        />
      </div>

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
