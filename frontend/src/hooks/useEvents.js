import { useEffect, useRef } from 'react'
import { SESSION_ID, getLastSeq, setLastSeq } from '../api'

export function useEvents({ enabled, onLogChanged, onGoalChanged, onDayChanged, onOutOfSync }) {
  const onLogRef = useRef(onLogChanged)
  const onGoalRef = useRef(onGoalChanged)
  const onDayChangedRef = useRef(onDayChanged)
  const onOutOfSyncRef = useRef(onOutOfSync)

  useEffect(() => { onLogRef.current = onLogChanged }, [onLogChanged])
  useEffect(() => { onGoalRef.current = onGoalChanged }, [onGoalChanged])
  useEffect(() => { onDayChangedRef.current = onDayChanged }, [onDayChanged])
  useEffect(() => { onOutOfSyncRef.current = onOutOfSync }, [onOutOfSync])

  useEffect(() => {
    if (!enabled) return  // wait for init to complete before connecting

    let es
    let retryTimeout
    let retryDelay = 1000

    function handleEvent(data) {
      const seq = Array.isArray(data) ? data[0]?.seq : data?.seq
      if (seq === undefined) return { inSync: true, data }

      const expected = getLastSeq() + 1
      if (seq !== expected) {
        // Gap detected — trigger full reload
        onOutOfSyncRef.current?.()
        return { inSync: false }
      }
      setLastSeq(seq)
      return { inSync: true, data }
    }

    function connect() {
      es = new EventSource(`/api/events/?session_id=${SESSION_ID}`)

      es.addEventListener('log_changed', (e) => {
        const { inSync, data } = handleEvent(JSON.parse(e.data))
        if (inSync) onLogRef.current?.(data)
      })

      es.addEventListener('goal_changed', (e) => {
        const { inSync, data } = handleEvent(JSON.parse(e.data))
        if (inSync) onGoalRef.current?.(data)
      })

      es.addEventListener('day_changed', (e) => {
        const { inSync, data } = handleEvent(JSON.parse(e.data))
        if (inSync) onDayChangedRef.current?.(data)
      })

      es.addEventListener('ping', () => {
        retryDelay = 1000
      })

      es.onerror = () => {
        es.close()
        const jitter = Math.random() * retryDelay * 0.3
        retryTimeout = setTimeout(() => {
          retryDelay = Math.min(retryDelay * 2, 30000)
          connect()
        }, retryDelay + jitter)
      }
    }

    connect()

    return () => {
      es?.close()
      clearTimeout(retryTimeout)
    }
  }, [enabled])
}
