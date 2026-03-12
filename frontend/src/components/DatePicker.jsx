import { useState, useRef, useEffect } from 'react'
import dayjs from 'dayjs'

export default function DatePicker({ value, onChange, placeholder = 'Pick date', min = null, max = null, clearable = true }) {
  const [open, setOpen] = useState(false)
  const [view, setView] = useState(null) // dayjs of month being shown
  const ref = useRef(null)

  const selected = value ? dayjs(value) : null

  useEffect(() => {
    setView(selected ? selected.startOf('month') : dayjs().startOf('month'))
  }, [value])

  // Close on outside click
  useEffect(() => {
    if (!open) return
    const handler = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  const prevMonth = () => setView(v => v.subtract(1, 'month'))
  const nextMonth = () => setView(v => v.add(1, 'month'))

  const select = (dateStr) => {
    onChange(dateStr)
    setOpen(false)
  }

  const clear = (e) => {
    e.stopPropagation()
    onChange('')
    setOpen(false)
  }

  const buildDays = () => {
    if (!view) return []
    const startOfMonth = view.startOf('month')
    const endOfMonth = view.endOf('month')
    // pad start to Sunday
    const startPad = startOfMonth.day() // 0=Sun
    const days = []
    for (let i = 0; i < startPad; i++) days.push(null)
    let d = startOfMonth
    while (d.isBefore(endOfMonth) || d.isSame(endOfMonth, 'day')) {
      days.push(d)
      d = d.add(1, 'day')
    }
    return days
  }

  const isDisabled = (d) => {
    if (!d) return true
    const ds = d.format('YYYY-MM-DD')
    if (min && ds < min) return true
    if (max && ds > max) return true
    return false
  }

  return (
    <div className="dp-wrap" ref={ref}>
      <div className="dp-input" onClick={() => setOpen(o => !o)}>
        <span className={selected ? 'dp-value' : 'dp-placeholder'}>
          {selected ? selected.format('DD MMM YYYY') : placeholder}
        </span>
        {selected && clearable
          ? <button className="dp-clear" onMouseDown={clear}>×</button>
          : <span className="dp-icon">▾</span>
        }
      </div>

      {open && view && (
        <div className="dp-popup">
          <div className="dp-header">
            <button className="dp-nav" onClick={prevMonth}>‹</button>
            <span className="dp-month">{view.format('MMM YYYY')}</span>
            <button className="dp-nav" onClick={nextMonth}>›</button>
          </div>
          <div className="dp-grid">
            {['Su','Mo','Tu','We','Th','Fr','Sa'].map(d => (
              <span key={d} className="dp-dow">{d}</span>
            ))}
            {buildDays().map((d, i) => {
              if (!d) return <span key={`e${i}`} />
              const ds = d.format('YYYY-MM-DD')
              const isSel = selected && ds === selected.format('YYYY-MM-DD')
              const dis = isDisabled(d)
              return (
                <button
                  key={ds}
                  className={`dp-day ${isSel ? 'sel' : ''} ${dis ? 'dis' : ''}`}
                  onClick={() => !dis && select(ds)}
                  disabled={dis}
                >
                  {d.date()}
                </button>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
