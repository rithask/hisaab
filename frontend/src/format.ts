const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2, minimumFractionDigits: 2 })
const inr0 = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 })

export const money = (v: number | null | undefined) => (v == null ? '—' : inr.format(v))
export const money0 = (v: number | null | undefined) => (v == null ? '—' : inr0.format(v))

export const compact = (v: number) => {
  const a = Math.abs(v)
  if (a >= 1e7) return '₹' + (v / 1e7).toFixed(1) + 'Cr'
  if (a >= 1e5) return '₹' + (v / 1e5).toFixed(1) + 'L'
  if (a >= 1e3) return '₹' + (v / 1e3).toFixed(1) + 'k'
  return '₹' + Math.round(v)
}

// ---- time zone ------------------------------------------------------------------------------------------------
// Days are counted in the server's time zone (TZ), so a date here means the same day the server's totals use, even
// when the phone is set to another zone. main.tsx sets it from /api/health before the first render.
let zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
export const setTimeZone = (z?: string) => {
  if (!z) return
  try {
    new Intl.DateTimeFormat('en', { timeZone: z })
    zone = z
  } catch {
    // unknown to this browser: keep the device's zone
  }
}
export const timeZone = () => zone

/** How far the zone is ahead of UTC at an instant, in ms. */
const offsetAt = (t: number) => {
  const p = new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }).formatToParts(new Date(t))
  const g = (k: string) => Number(p.find((x) => x.type === k)?.value)
  return Date.UTC(g('year'), g('month') - 1, g('day'), g('hour') % 24, g('minute'), g('second')) - Math.floor(t / 1000) * 1000
}
/** The moment a calendar date (and time) happens in the zone, e.g. zonedInstant('2026-11-10') = that midnight. */
export const zonedInstant = (date: string, time = '00:00:00') => {
  const [y, m, d] = date.split('-').map(Number)
  const [hh, mm, ss = 0] = time.split(':').map(Number)
  const wall = Date.UTC(y, m - 1, d, hh, mm, ss)
  const first = wall - offsetAt(wall)
  return new Date(wall - offsetAt(first))                    // second pass settles daylight-saving changes
}
/** yyyy-mm-dd of an instant in the zone (today by default). */
export const ymd = (d: Date = new Date()) => d.toLocaleDateString('en-CA', { timeZone: zone })
/** A calendar date (yyyy-mm-dd) in words. Dates have no zone, so none is applied. */
export const formatDay = (date: string, opts: Intl.DateTimeFormatOptions) => new Date(date + 'T12:00:00Z').toLocaleDateString('en-IN', { ...opts, timeZone: 'UTC' })

export const dateLabel = (iso: string) => {
  const d = new Date(iso)
  const today = ymd()
  const yesterday = ymd(new Date(zonedInstant(today).getTime() - 12 * 36e5))
  const k = ymd(d)
  if (k === today) return 'Today'
  if (k === yesterday) return 'Yesterday'
  return formatDay(k, { weekday: 'short', day: 'numeric', month: 'short', year: k.slice(0, 4) === today.slice(0, 4) ? undefined : 'numeric' })
}
export const dayKey = (iso: string) => ymd(new Date(iso))
export const time = (iso: string) => new Date(iso).toLocaleTimeString('en-IN', { timeZone: zone, hour: 'numeric', minute: '2-digit' })
export const dateTime = (iso: string) => new Date(iso).toLocaleString('en-IN', { timeZone: zone, day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })
export const shortDate = (d: string) => (d.length === 10 ? formatDay(d, { day: 'numeric', month: 'short' }) : new Date(d).toLocaleDateString('en-IN', { timeZone: zone, day: 'numeric', month: 'short' }))

/** yyyy-MM-ddTHH:mm in the zone, for <input type="datetime-local"> */
export const toLocalInput = (iso?: string) => {
  const d = iso ? new Date(iso) : new Date()
  const p = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(d)
  const g = (t: string) => p.find((x) => x.type === t)?.value
  return `${g('year')}-${g('month')}-${g('day')}T${g('hour')}:${g('minute')}`
}
export const fromLocalInput = (v: string) => zonedInstant(v.slice(0, 10), v.slice(11) + ':00').toISOString()

export const monthKey = (d = new Date()) => ymd(d).slice(0, 7)
export const monthLabel = (ym: string) => formatDay(ym + '-01', { month: 'long', year: 'numeric' })
export const shiftMonth = (ym: string, delta: number) => {
  const [y, m] = ym.split('-').map(Number)
  const d = new Date(Date.UTC(y, m - 1 + delta, 1))
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`
}

export const institutionColor: Record<string, string> = {
  HDFC: '#1d4ed8',
  AXIS: '#9f1239',
  SBI: '#0369a1',
  CANARA: '#ca8a04',
  OTHER: '#475569',
}

/** ₹799 for whole rupees, ₹149.50 otherwise. */
export const moneyAuto = (v: number | null | undefined) => (v == null ? '—' : Number.isInteger(Math.round(v * 100) / 100) ? money0(v) : money(v))
/** 1,347 (Indian grouping) for points and counts. */
export const count = (v: number) => new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 }).format(v)
