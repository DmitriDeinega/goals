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

export default function App() {
  const [tab, setTab] = useState('today')
  const [selectedDate, setSelectedDate] = useState(dayjs().format('YYYY-MM-DD'))

  const { goals, add, update, remove, setEnabled, reorder, reload: reloadGoals } = useGoals()
  const { weekSummary, toggle, getLog, reload: reloadLogs } = useLogs(selectedDate)
  const { settings } = useSettings()

  const currency = settings?.currency || 'NIS'
  const isDev = settings?.app_env === 'DEV'
  const appTitle = isDev ? 'Goals-DEV' : 'Goals'

  // SSE — real-time sync across devices
  useEvents({ onGoalsChanged: reloadGoals, onLogsChanged: reloadLogs })

  // Update browser tab title
  useEffect(() => {
    document.title = appTitle
  }, [appTitle])

  // Ensure current week is snapshotted on app load
  useEffect(() => {
    ensureWeek().catch(() => {})
  }, [])

  const handleTabChange = (newTab) => {
    setTab(newTab)
    if (newTab === 'today') {
      reloadGoals()
      reloadLogs()
    }
  }

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
