import { useState, useEffect, useCallback } from 'react'

let _addToast = null

export function useToastTrigger() {
  return _addToast
}

export default function ToastContainer() {
  const [toasts, setToasts] = useState([])

  const add = useCallback((message, type = 'error') => {
    const id = Date.now()
    setToasts(t => [...t, { id, message, type }])
    setTimeout(() => setToasts(t => t.filter(x => x.id !== id)), 4000)
  }, [])

  useEffect(() => { _addToast = add; return () => { _addToast = null } }, [add])

  if (!toasts.length) return null

  return (
    <div className="toast-container">
      {toasts.map(t => (
        <div key={t.id} className={`toast toast-${t.type}`}>
          {t.message}
          <button className="toast-close" onClick={() => setToasts(ts => ts.filter(x => x.id !== t.id))}>✕</button>
        </div>
      ))}
    </div>
  )
}

export function toast(message, type = 'error') {
  if (_addToast) _addToast(message, type)
}
