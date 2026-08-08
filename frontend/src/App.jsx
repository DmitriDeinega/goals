import { useState, useEffect, useRef } from 'react'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import { useAppState, computeWeekSummary } from './hooks/useAppState'
import { useEvents } from './hooks/useEvents'
import { ensureWeek } from './api'
import TodayPage from './pages/TodayPage'
import GoalsPage from './pages/GoalsPage'
import ToastContainer from './components/Toast'

dayjs.extend(utc)
dayjs.extend(timezone)

const TABS = ['today', 'goals']

function getWeekStart(date, firstDay = 'sunday') {
  const d = dayjs(date)
  if (firstDay === 'monday') {
    const dow = d.day()
    const diff = dow === 0 ? -6 : 1 - dow
    return d.add(diff, 'day').format('YYYY-MM-DD')
  }
  return d.startOf('week').format('YYYY-MM-DD')
}

export default function App() {
  const [tab, setTab] = useState(() => sessionStorage.getItem('goals_tab') || 'today')
  // Initial values come from the browser timezone — replaced as soon as
  // `settings.timezone` arrives so week math agrees with the server. Without
  // this fix, a user whose browser tz differs from settings tz could land on
  // the wrong week on first paint (matches Android/Windows behaviour).
  const [selectedDate, setSelectedDate] = useState(dayjs().format('YYYY-MM-DD'))
  const [sseEnabled, setSseEnabled] = useState(false)
  const [today, setToday] = useState(dayjs().format('YYYY-MM-DD'))
  const tzAppliedRef = useRef(false)

  const {
    goals, goalWeeks, logs, settings, loading, load, loadWeek, visibleWeekStart,
    toggle, togglingSlots, addGoal, editGoal, removeGoal, setEnabled, reorder,
    getLog, handleLogChanged, handleGoalChanged, applyDayChanged,
  } = useAppState()

  const currency = settings?.currency || 'NIS'
  const isDev = settings?.app_env === 'DEV'
  const appTitle = isDev ? 'Goals DEV' : 'Goals'

  useEffect(() => { document.title = appTitle }, [appTitle])

  useEffect(() => {
    ensureWeek().catch(() => {})
    load().then(() => setSseEnabled(true))
  }, [])

  // Re-align today/selectedDate to settings timezone once it's known. We only
  // bump selectedDate the FIRST time so a user who's already navigated to a
  // different day doesn't get snapped back to today.
  useEffect(() => {
    const tz = settings?.timezone
    if (!tz) return
    const tzToday = dayjs().tz(tz).format('YYYY-MM-DD')
    setToday(tzToday)
    if (!tzAppliedRef.current) {
      setSelectedDate(tzToday)
      tzAppliedRef.current = true
    }
  }, [settings?.timezone])

  const firstDay = settings?.first_day_of_week || 'sunday'
  const weekStart = getWeekStart(selectedDate, firstDay)
  const weekEnd = dayjs(weekStart).add(6, 'day').format('YYYY-MM-DD')
  const weekReady = visibleWeekStart === weekStart

  useEffect(() => {
    if (loading) return
    if (weekReady) return
    loadWeek(weekStart)
  }, [weekStart, weekReady, loading])

  // Compute against the week that's actually loaded — prevents double-update flash
  const summaryWeekStart = weekReady ? weekStart : (visibleWeekStart ?? weekStart)
  const summaryWeekEnd = dayjs(summaryWeekStart).add(6, 'day').format('YYYY-MM-DD')
  const weekSummary = computeWeekSummary(goals, goalWeeks, logs, summaryWeekStart, summaryWeekEnd, today)

  const onDayChanged = ({ date, logs: newLogs }) => {
    setToday(date)
    const newDateWeekStart = getWeekStart(date, firstDay)
    if (newDateWeekStart === visibleWeekStart) {
      applyDayChanged(date, newLogs)
    }
  }

  const handleOutOfSync = () => load()

  // Refresh on tab return (SSE may have missed events while hidden). `load()`
  // refreshes goals/settings and the current week; if the user is viewing a
  // different week, that one needs its own fetch — `load()` deliberately
  // won't touch it.
  useEffect(() => {
    function onVisible() {
      if (document.visibilityState !== 'visible') return
      load()
      if (visibleWeekStart && visibleWeekStart !== getWeekStart(dayjs().format('YYYY-MM-DD'), firstDay)) {
        loadWeek(visibleWeekStart)
      }
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [load, loadWeek, visibleWeekStart, firstDay])

  useEvents({
    enabled: sseEnabled,
    onLogChanged: handleLogChanged,
    onGoalChanged: handleGoalChanged,
    onDayChanged,
    onOutOfSync: handleOutOfSync,
  })

  const handleTabChange = (newTab) => {
    if (newTab === tab) return
    setTab(newTab)
    sessionStorage.setItem('goals_tab', newTab)
  }

  useEffect(() => {
    let startX = null, startY = null
    const onTouchStart = (e) => {
      startX = e.touches[0].clientX
      startY = e.touches[0].clientY
    }
    const onTouchEnd = (e) => {
      if (startX === null) return
      const dx = e.changedTouches[0].clientX - startX
      const dy = e.changedTouches[0].clientY - startY
      if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 2) {
        const cur = TABS.indexOf(tab)
        if (dx < 0 && cur < TABS.length - 1) handleTabChange(TABS[cur + 1])
        if (dx > 0 && cur > 0) handleTabChange(TABS[cur - 1])
      }
      startX = null; startY = null
    }
    document.addEventListener('touchstart', onTouchStart, { passive: true })
    document.addEventListener('touchend', onTouchEnd, { passive: true })
    return () => {
      document.removeEventListener('touchstart', onTouchStart)
      document.removeEventListener('touchend', onTouchEnd)
    }
  }, [tab])

  if (loading) return null

  return (
    <div className="app">
      <ToastContainer />
      <div className="header">
        <div className="header-top">
          <span className="app-title">
            <img src="/icon.png" className="app-icon" alt="" />
            {appTitle}
          </span>
        </div>
      </div>
      <div className="tabs">
        <button className={`tab ${tab === 'today' ? 'active' : ''}`} onClick={() => handleTabChange('today')}>
          Today
        </button>
        <button className={`tab ${tab === 'goals' ? 'active' : ''}`} onClick={() => handleTabChange('goals')}>
          Goals
        </button>
      </div>
      <div className="content">
        {tab === 'today' ? (
          <TodayPage
            goals={goals}
            goalWeeks={goalWeeks}
            logs={logs}
            selectedDate={selectedDate}
            setSelectedDate={setSelectedDate}
            getLog={getLog}
            onToggle={toggle}
            togglingSlots={togglingSlots}
            weekSummary={weekSummary}
            weekStart={weekReady ? weekStart : (visibleWeekStart || weekStart)}
            settings={settings}
            currency={currency}
          />
        ) : (
          <GoalsPage
            goals={goals}
            onAdd={addGoal}
            onUpdate={editGoal}
            onDelete={removeGoal}
            onSetEnabled={setEnabled}
            onReorder={reorder}
          />
        )}
      </div>
    </div>
  )
}
