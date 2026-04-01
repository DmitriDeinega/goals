import { useState, useEffect } from 'react'
import dayjs from 'dayjs'
import { useAppState, computeWeekSummary } from './hooks/useAppState'
import { useEvents } from './hooks/useEvents'
import { ensureWeek } from './api'
import TodayPage from './pages/TodayPage'
import GoalsPage from './pages/GoalsPage'
import ToastContainer from './components/Toast'

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
  const [selectedDate, setSelectedDate] = useState(dayjs().format('YYYY-MM-DD'))
  const [sseEnabled, setSseEnabled] = useState(false)
  const [today, setToday] = useState(dayjs().format('YYYY-MM-DD'))

  const {
    goals, goalWeeks, logs, settings, loading, load, loadWeek, visibleWeekStart,
    toggle, addGoal, editGoal, removeGoal, setEnabled, reorder,
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

  const firstDay = settings?.first_day_of_week || 'sunday'
  const weekStart = getWeekStart(selectedDate, firstDay)
  const weekEnd = dayjs(weekStart).add(6, 'day').format('YYYY-MM-DD')
  const weekReady = visibleWeekStart === weekStart

  useEffect(() => {
    if (loading) return
    if (weekReady) return
    loadWeek(weekStart)
  }, [weekStart, weekReady, loading])

  // Only compute summary when week data is ready — prevents flash of wrong %
  const weekSummary = weekReady
    ? computeWeekSummary(goals, goalWeeks, logs, weekStart, weekEnd, today)
    : null

  const onDayChanged = ({ date, logs: newLogs }) => {
    setToday(date)
    const newDateWeekStart = getWeekStart(date, firstDay)
    if (newDateWeekStart === visibleWeekStart) {
      applyDayChanged(date, newLogs)
    }
  }

  const handleOutOfSync = () => load()

  useEffect(() => {
    function onVisible() {
      if (document.visibilityState === 'visible') load()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [load])

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
