import { useState, useEffect } from 'react'
import { toast } from './Toast'

const EMPTY = {
  name: '',
  type: 'daily',
  is_negative: false,
  times_per_week: '',
  times_per_day: '',
  reward_rules: [],
  enabled: true,
}

export default function GoalForm({ goal, onSave, onClose, onSetEnabled, onDelete }) {
  const [form, setForm] = useState(EMPTY)
  const [closing, setClosing] = useState(false)

  const handleClose = () => {
    setClosing(true)
    setTimeout(onClose, 220)
  }

  useEffect(() => {
    if (goal) setForm({
      ...EMPTY,
      ...goal,
      times_per_day: goal.times_per_day ?? EMPTY.times_per_day,
      times_per_week: goal.times_per_week ?? EMPTY.times_per_week,
      reward_rules: goal.reward_rules || [],
      enabled: goal.enabled ?? true,
    })
    else setForm(EMPTY)
  }, [goal])

  const set = (key, val) => setForm(f => ({ ...f, [key]: val }))

  const intKeyDown = (e) => {
    const nav = ['Backspace','Delete','Tab','ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Enter']
    if (nav.includes(e.key)) return
    if (!/^\d$/.test(e.key)) e.preventDefault()
  }

  const floatKeyDown = (e) => {
    const nav = ['Backspace','Delete','Tab','ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Enter','.']
    if (nav.includes(e.key)) return
    if (!/^\d$/.test(e.key)) e.preventDefault()
  }

  const addRule = () => set('reward_rules', [...form.reward_rules, { min_completions: '', reward_amount: '' }])
  const updateRule = (i, key, val) => {
    const rules = [...form.reward_rules]
    const raw = String(val).replace(/\D/g, '')
    rules[i] = { ...rules[i], [key]: raw === '' ? '' : parseInt(raw, 10) }
    set('reward_rules', rules)
  }
  const updateRuleFloat = (i, val) => {
    const rules = [...form.reward_rules]
    const clean = String(val).replace(/[^\d.]/g, '')
    rules[i] = { ...rules[i], reward_amount: clean }
    set('reward_rules', rules)
  }
  const removeRule = (i) => set('reward_rules', form.reward_rules.filter((_, idx) => idx !== i))

  const maxCompletions = form.type === 'weekly_x' ? Number(form.times_per_week) : 7

  const validate = () => {
    if (!form.name.trim()) { toast('Name is required'); return false }
    if (form.type === 'weekly_x') {
      const v = Number(form.times_per_week)
      if (!v || v < 1) { toast('Times per week must be at least 1'); return false }
      if (v > 7) { toast('Times per week cannot exceed 7'); return false }
    }
    if (form.type === 'daily') {
      const v = Number(form.times_per_day)
      if (!v || v < 1) { toast('Times per day must be at least 1'); return false }
    }
    const seenCompletions = new Set()
    for (let i = 0; i < form.reward_rules.length; i++) {
      const rule = form.reward_rules[i]
      const mc = Number(rule.min_completions)
      if (!mc || mc < 1) { toast(`Rule ${i + 1}: completions must be at least 1`); return false }
      if (mc > maxCompletions) { toast(`Rule ${i + 1}: completions can't exceed ${maxCompletions}`); return false }
      if (seenCompletions.has(mc)) { toast(`Rule ${i + 1}: duplicate completions value ${mc}`); return false }
      seenCompletions.add(mc)
      const ra = parseFloat(rule.reward_amount)
      if (isNaN(ra) || ra <= 0) { toast(`Rule ${i + 1}: reward must be greater than 0`); return false }
    }
    return true
  }

  const handleSave = async () => {
    if (!validate()) return
    // If editing and enabled changed, call onSetEnabled separately
    if (goal && onSetEnabled && form.enabled !== goal.enabled) {
      await onSetEnabled(goal.id, form.enabled)
    }
    onSave({
      name: form.name.trim(),
      type: form.type,
      is_negative: form.is_negative,
      times_per_week: form.type === 'weekly_x' ? (parseInt(form.times_per_week, 10) || 1) : null,
      times_per_day: form.type === 'daily' ? (parseInt(form.times_per_day, 10) || 1) : null,
      // No `|| 1` / `|| 0` fallback here — `validate()` already rejects 0
      // or blank, so a leaking 0 should never reach this map. The fallback
      // was silently coercing user-entered 0 to 1, diverging from the
      // Windows/Android validators that block save on the same input.
      reward_rules: [...form.reward_rules]
        .map(r => ({ min_completions: parseInt(r.min_completions, 10), reward_amount: parseFloat(r.reward_amount) }))
        .sort((a, b) => a.min_completions - b.min_completions),
    })
  }

  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className={`overlay${closing ? " closing" : ""}`} onMouseDown={e => e.target === e.currentTarget && handleClose()}>
      <div className={`sheet${closing ? " closing" : ""}`}>
        <div className="sheet-title">
          {goal ? 'Edit Goal' : 'New Goal'}
          {goal && (
            <div className="sheet-title-actions">
              <button
                className="btn-delete-goal"
                onClick={() => onDelete(goal.id)}
                title="Delete goal"
              >Delete</button>
              <button
                className={`switch ${form.enabled ? 'on' : ''}`}
                onClick={() => set('enabled', !form.enabled)}
                title={form.enabled ? 'Disable this week' : 'Enable this week'}
              />
            </div>
          )}
        </div>

        <div className="field">
          <label>Name</label>
          <input
            type="text" autoComplete="off"
            value={form.name}
            onChange={e => set('name', e.target.value)}
            placeholder="e.g. Morning run"
          />
        </div>

        <div className="field">
          <label>Type</label>
          <div className="type-options">
            {[
              { val: 'daily', label: 'Daily' },
              { val: 'weekly_x', label: 'Weekly' },
            ].map(opt => (
              <button
                key={opt.val}
                className={`type-option ${form.type === opt.val ? 'active' : ''}`}
                onClick={() => set('type', opt.val)}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        {form.type === 'daily' && (
          <div className="field">
            <label>Times per day</label>
            <input
              type="text" inputMode="numeric" autoComplete="off"
              value={form.times_per_day}
              onKeyDown={intKeyDown}
              onChange={e => { const raw = e.target.value.replace(/\D/g, ''); set('times_per_day', raw === '' ? '' : parseInt(raw, 10)) }}
            />
          </div>
        )}

        {form.type === 'weekly_x' && (
          <div className="field">
            <label>Times per week</label>
            <input
              type="text" inputMode="numeric" autoComplete="off"
              value={form.times_per_week}
              onKeyDown={intKeyDown}
              onChange={e => { const raw = e.target.value.replace(/\D/g, ''); set('times_per_week', raw === '' ? '' : parseInt(raw, 10)) }}
            />
          </div>
        )}

        <div className="field">
          <div className="toggle-field">
            <span>Negative goal (avoid habit)</span>
            <button
              className={`switch ${form.is_negative ? 'on' : ''}`}
              onClick={() => set('is_negative', !form.is_negative)}
            />
          </div>
        </div>

        <div className="field">
          <label>Reward Rules (optional)</label>
          <div className="reward-rules">
            {form.reward_rules.map((rule, i) => (
              <div key={i} className="reward-rule-row">
                <input
                  type="text" inputMode="numeric" autoComplete="off"
                  value={rule.min_completions}
                  onKeyDown={intKeyDown}
                  onChange={e => updateRule(i, 'min_completions', e.target.value)}
                  placeholder="min"
                />
                <span className="rule-label">/ {maxCompletions} →</span>
                <input
                  type="text" inputMode="decimal" autoComplete="off"
                  value={rule.reward_amount}
                  onKeyDown={floatKeyDown}
                  onChange={e => updateRuleFloat(i, e.target.value)}
                  placeholder="reward"
                />
                <button className="icon-btn danger" onClick={() => removeRule(i)}>×</button>
              </div>
            ))}
            <button className="add-rule-btn" onClick={addRule}>+ Add Rule</button>
          </div>
        </div>

        <div className="sheet-actions">
          <button className="btn-secondary" onClick={handleClose}>Cancel</button>
          <button className="btn-primary" onClick={handleSave}>Save</button>
        </div>
      </div>
    </div>
  )
}
