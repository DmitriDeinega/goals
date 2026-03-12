import { useState, useEffect } from 'react'
import dayjs from 'dayjs'
import { useGoals } from './hooks/useGoals'
import { useLogs } from './hooks/useLogs'
import { useSettings } from './hooks/useSettings'
import { useEvents } from './hooks/useEvents'
import { ensureWeek } from './api'
import TodayPage from './pages/TodayPage'
import GoalsPage from './pages/GoalsPage'
import ToastContainer from './components/Toast'

const TABS = ['today', 'goals']

export default function App() {
  const [tab, setTab] = useState(() => sessionStorage.getItem('goals_tab') || 'today')
  const [selectedDate, setSelectedDate] = useState(dayjs().format('YYYY-MM-DD'))

  const { goals, add, update, remove, setEnabled, reorder, reload: reloadGoals } = useGoals()
  const { weekSummary, toggle, getLog, reload: reloadLogs } = useLogs(selectedDate)
  const { settings } = useSettings()

  const currency = settings?.currency || 'NIS'
  const isDev = settings?.app_env === 'DEV'
  const appTitle = isDev ? 'Goals DEV' : 'Goals'

  useEvents({ onGoalsChanged: reloadGoals, onLogsChanged: reloadLogs })

  useEffect(() => { document.title = appTitle }, [appTitle])
  useEffect(() => { ensureWeek().catch(() => {}) }, [])

  const handleTabChange = (newTab) => {
    if (newTab === tab) return
    setTab(newTab)
    sessionStorage.setItem('goals_tab', newTab)
    if (newTab === 'today') { reloadGoals(); reloadLogs() }
  }

  // Swipe left/right to switch tabs
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
            selectedDate={selectedDate}
            setSelectedDate={setSelectedDate}
            getLog={getLog}
            onToggle={toggle}
            weekSummary={weekSummary}
            settings={settings}
            currency={currency}
          />
        ) : (
          <GoalsPage
            goals={goals}
            onAdd={add}
            onUpdate={update}
            onDelete={remove}
            onSetEnabled={setEnabled}
            onReorder={reorder}
          />
        )}
      </div>
    </div>
  )
}
