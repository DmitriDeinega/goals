import { useEffect, useRef } from 'react'

export function useEvents({ onGoalsChanged, onLogsChanged }) {
  const onGoalsRef = useRef(onGoalsChanged)
  const onLogsRef = useRef(onLogsChanged)

  useEffect(() => { onGoalsRef.current = onGoalsChanged }, [onGoalsChanged])
  useEffect(() => { onLogsRef.current = onLogsChanged }, [onLogsChanged])

  useEffect(() => {
    let es
    let retryTimeout
    let retryDelay = 1000

    function connect() {
      es = new EventSource('/api/events/')

      es.addEventListener('goals_changed', () => {
        onGoalsRef.current?.()
      })

      es.addEventListener('logs_changed', () => {
        onLogsRef.current?.()
      })

      es.addEventListener('ping', () => {
        retryDelay = 1000 // reset backoff on successful ping
      })

      es.onerror = () => {
        es.close()
        retryTimeout = setTimeout(() => {
          retryDelay = Math.min(retryDelay * 2, 30000) // exponential backoff, max 30s
          connect()
        }, retryDelay)
      }
    }

    connect()

    // Refetch on tab becoming visible (covers SSE gap while hidden)
    function onVisible() {
      if (document.visibilityState === 'visible') {
        onGoalsRef.current?.()
        onLogsRef.current?.()
      }
    }
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      es?.close()
      clearTimeout(retryTimeout)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])
}
