export default function WeekSummary({ summary, currency }) {
  if (!summary) return null

  const pct = summary.goals.length > 0
    ? Math.round(summary.goals.reduce((acc, g) => acc + (g.completions / Math.max(g.total_slots, 1)), 0) / summary.goals.length * 100)
    : 0

  const earned = summary.total_earned ?? 0

  const fmtCurrency = (amount) => {
    if (currency === 'NIS') return `₪${amount}`
    if (currency === 'USD') return `$${amount}`
    return `${amount} ${currency}`
  }

  return (
    <div className="progress-section">
      <div className="progress-header">
        <span className="progress-title">This week · {pct}%</span>
        {earned > 0 && <span className="progress-earned">{fmtCurrency(earned)}</span>}
      </div>
      <div className="progress-bar-bg">
        <div className="progress-bar-fill" style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}
