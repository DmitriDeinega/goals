export default function WeekSummary({ weekSummary, currency }) {
  if (!weekSummary) return null

  const { pct = 0, totalEarned = 0 } = weekSummary

  const fmtCurrency = (amount) => {
    if (currency === 'NIS') return `₪${amount}`
    if (currency === 'USD') return `$${amount}`
    return `${amount} ${currency}`
  }

  return (
    <div className="progress-section">
      <div className="progress-header">
        <span className="progress-title">This week · {pct}%</span>
        {totalEarned > 0 && <span className="progress-earned">{fmtCurrency(totalEarned)}</span>}
      </div>
      <div className="progress-bar-bg">
        <div className="progress-bar-fill" style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}
