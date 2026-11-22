// Your handicap over time with one bow style (3.2.24, Pete): what it was after each round, spaced by date, as a step
// line (it holds until the next round changes it). Lower is better. As the app's HandicapChart.kt.
import { ukDate } from './common'
import type { HistoryPoint } from '../domain/handicap'

const W = 320, H = 140, L = 30, T = 8, B = 8

export function HandicapChart({ points }: { points: HistoryPoint[] }) {
  if (points.length < 2) return <div class="sub">Your handicap over time shows here after your next round with this bow style.</div>
  const values = points.map(p => p.handicap)
  let lo = Math.min(...values) - 5, hi = Math.max(...values) + 5
  if (hi - lo < 20) { const mid = Math.round((hi + lo) / 2); lo = mid - 10; hi = mid + 10 }
  const day = (iso: string) => new Date(`${iso}T00:00:00Z`).getTime() / 86_400_000
  const first = day(points[0].entry.shootDate), last = day(points[points.length - 1].entry.shootDate)
  const span = Math.max(1, last - first)
  const x = (i: number) => L + (day(points[i].entry.shootDate) - first) / span * (W - L - 4)
  const y = (v: number) => T + (1 - (v - lo) / (hi - lo)) * (H - T - B)
  let d = `M${x(0)},${y(points[0].handicap)}`
  for (let i = 1; i < points.length; i++) d += ` L${x(i)},${y(points[i - 1].handicap)} L${x(i)},${y(points[i].handicap)}`
  const mid = Math.round((hi + lo) / 2)
  return (
    <div class="hchartwrap">
      <svg class="hchart" viewBox={`0 0 ${W} ${H}`} role="img"
        aria-label={`Handicap from ${points[0].handicap} to ${points[points.length - 1].handicap}`}>
        {[hi, mid, lo].map(v => <><line class="grid" x1={L} x2={W} y1={y(v)} y2={y(v)} /><text x={0} y={y(v) + 4}>{v}</text></>)}
        <path class="line" d={d} vector-effect="non-scaling-stroke" />
        {points.map((p, i) => <circle class="dot" cx={x(i)} cy={y(p.handicap)} r={3} />)}
      </svg>
      <div class="row between sub"><span>{ukDate(points[0].entry.shootDate)}</span><span>{ukDate(points[points.length - 1].entry.shootDate)}</span></div>
      <div class="sub">After each round · lower is better</div>
    </div>
  )
}
