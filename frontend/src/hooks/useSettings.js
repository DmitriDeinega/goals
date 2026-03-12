import { useState, useEffect } from 'react'
import { getSettings, updateSettings } from '../api'

export function useSettings() {
  const [settings, setSettings] = useState(null)

  useEffect(() => {
    getSettings().then(setSettings).catch(() => {})
  }, [])

  const save = async (data) => {
    const updated = await updateSettings(data)
    setSettings(updated)
  }

  return { settings, save }
}
