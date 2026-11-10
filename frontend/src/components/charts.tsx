import { useEffect, useMemo, useState } from 'react'
import { Area, AreaChart, Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis, type TooltipContentProps } from 'recharts'
import type { CategoryTotal } from '../api'
import { categoryIcon, tint } from '../icons'
import { compact, formatDay, money, money0, ymd } from '../format'
import { cx } from './ui'

/**
 * SVG presentation attributes (fill/stroke) can't use CSS variables, so resolve the theme tokens to real
 * colours and re-resolve when the light/dark scheme changes.
 */
function useChartColors() {
  const read = () => {
    const cs = getComputedStyle(document.documentElement)
    const v = (n: string) => cs.getPropertyValue(n).trim()
    return { income: v('--c-income'), expense: v('--c-expense'), grid: v('--c-grid'), axis: v('--c-axis'), ink: v('--ink'), surface: v('--surface') }
  }
  const [c, setC] = useState(read)
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const on = () => setC(read())
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])
  return c
}

const ANIM = { isAnimationActive: true, animationDuration: 220, animationEasing: 'ease-out' as const }

function ChartTooltip({ active, payload, label }: Partial<TooltipContentProps<number, string>>) {
  if (!active || !payload?.length) return null
  return (
    <div className="glass rounded-2xl px-3 py-2 text-xs shadow-[var(--shadow-float)]">
      <p className="mb-1 font-semibold">{label}</p>
      {payload.map((p) => (
        <p key={String(p.dataKey)} className="flex items-center gap-2">
          <span className="h-2 w-2 rounded-full" style={{ background: p.color }} />
          <span className="ink-2">{p.name}</span>
          <span className="tabular ml-auto pl-3 font-semibold">{money(Number(p.value))}</span>
        </p>
      ))}
    </div>
  )
}

function Legend({ items }: { items: { label: string; color: string }[] }) {
  return (
    <div className="ink-2 flex gap-4 text-xs font-medium">
      {items.map((i) => (
        <span key={i.label} className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full" style={{ background: i.color }} />
          {i.label}
        </span>
      ))}
    </div>
  )
}

/** Income vs spending per period: two series, one axis, legend + hover tooltip. */
export function InOutBars({ data, height = 170 }: { data: { label: string; income: number; expense: number }[]; height?: number }) {
  const c = useChartColors()
  return (
    <div>
      <Legend items={[{ label: 'Income', color: 'var(--c-income)' }, { label: 'Spending', color: 'var(--c-expense)' }]} />
      <ResponsiveContainer width="100%" height={height}>
        <BarChart data={data} barGap={2} barCategoryGap="30%" margin={{ top: 12, right: 0, left: -14, bottom: 0 }}>
          <CartesianGrid vertical={false} stroke={c.grid} />
          <XAxis dataKey="label" tickLine={false} axisLine={false} tick={{ fill: c.axis, fontSize: 11 }} />
          <YAxis tickLine={false} axisLine={false} tick={{ fill: c.axis, fontSize: 11 }} tickFormatter={compact} width={50} />
          <Tooltip content={<ChartTooltip />} cursor={{ fill: c.grid }} />
          <Bar dataKey="income" name="Income" fill={c.income} radius={[4, 4, 0, 0]} maxBarSize={14} {...ANIM} />
          <Bar dataKey="expense" name="Spending" fill={c.expense} radius={[4, 4, 0, 0]} maxBarSize={14} {...ANIM} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

/**
 * Ranked categories. Bar length carries magnitude in one hue; the category's own colour is only used
 * for its icon tile (identity), never to encode value.
 */
export function CategoryList({ items, kind, limit = 5, onSelect }: { items: CategoryTotal[]; kind: 'expense' | 'income'; limit?: number; onSelect?: (c: CategoryTotal) => void }) {
  const [expanded, setExpanded] = useState(false)
  if (items.length === 0) return <p className="ink-3 py-6 text-center text-sm">Nothing here yet.</p>
  const shown = expanded ? items : items.slice(0, limit)
  const rest = expanded ? [] : items.slice(limit)
  // The folded "N more" row expands the list in place; it is not a category of its own.
  const MORE = '__more__'
  const rows = rest.length
    ? [...shown, { categoryId: undefined, name: `${rest.length} more`, color: '#a1a1aa', total: rest.reduce((s, x) => s + x.total, 0), count: rest.reduce((s, x) => s + x.count, 0), key: MORE }]
    : shown
  const max = Math.max(...rows.map((r) => r.total))
  const sum = items.reduce((s, x) => s + x.total, 0)
  return (
    <div className="space-y-1">
      {rows.map((r) => {
        const Icon = categoryIcon(r.categoryId ? r : null)
        return (
          <button
            key={r.name}
            className="row flex w-full items-center gap-3 rounded-2xl p-1.5 text-left"
            title={`${r.name}: ${money(r.total)} · ${r.count} transaction${r.count === 1 ? '' : 's'}`}
            onClick={() => ('key' in r && r.key === MORE ? setExpanded(true) : onSelect?.(r))}
          >
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full" style={{ background: tint(r.color), color: r.color }}>
              <Icon size={19} strokeWidth={2.2} />
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex items-baseline justify-between gap-2">
                <span className="truncate text-[15px] font-medium">{r.name}</span>
                <span className="tabular shrink-0 text-[15px] font-semibold">{money0(r.total)}</span>
              </span>
              <span className="mt-1.5 flex items-center gap-2">
                <span className="h-1 flex-1 overflow-hidden rounded-full bg-[var(--line-strong)]">
                  <span
                    className="block h-full rounded-full transition-[width] duration-[240ms] ease-[var(--ease-out-quint)]"
                    style={{ width: `${(r.total / max) * 100}%`, background: kind === 'expense' ? 'var(--bar)' : 'var(--positive)' }}
                  />
                </span>
                <span className="tabular ink-3 w-8 text-right text-[11px] font-medium">{Math.round((r.total / sum) * 100)}%</span>
              </span>
            </span>
          </button>
        )
      })}
    </div>
  )
}

/**
 * Calendar heatmap of daily spending for one month (sequential single-hue ramp).
 * Pass `selected`/`onSelect` to let the page react to the chosen day; otherwise it just shows the day's total.
 */
export function SpendCalendar({ month, daily, selected, onSelect }: { month: string; daily: { day: string; expense: number }[]; selected?: string | null; onSelect?: (day: string | null) => void }) {
  const [own, setOwn] = useState<string | null>(null)
  const picked = onSelect ? (selected ?? null) : own
  const pick = (d: string | null) => (onSelect ? onSelect(d) : setOwn(d))
  const { cells, thresholds, byDay } = useMemo(() => {
    const [y, m] = month.split('-').map(Number)
    const days = new Date(Date.UTC(y, m, 0)).getUTCDate()
    const firstDow = (new Date(Date.UTC(y, m - 1, 1)).getUTCDay() + 6) % 7 // Monday = 0
    const byDay = new Map(daily.map((d) => [d.day, d.expense]))
    const vals = [...byDay.values()].filter((v) => v > 0).sort((a, b) => a - b)
    const q = (p: number) => vals[Math.min(vals.length - 1, Math.floor(p * vals.length))] ?? 0
    const thresholds = [q(0.25), q(0.5), q(0.8)]
    const cells: ({ key: string; d: number } | null)[] = Array.from({ length: firstDow }, () => null)
    for (let d = 1; d <= days; d++) cells.push({ key: `${month}-${String(d).padStart(2, '0')}`, d })
    return { cells, thresholds, byDay }
  }, [month, daily])

  const level = (v: number) => (v <= 0 ? 0 : v <= thresholds[0] ? 1 : v <= thresholds[1] ? 2 : v <= thresholds[2] ? 3 : 4)
  const today = ymd()
  const pickedVal = picked ? (byDay.get(picked) ?? 0) : null

  return (
    <div>
      <div className="ink-3 mb-1.5 grid grid-cols-7 gap-1.5 text-center text-[10px] font-semibold">
        {['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d, i) => (
          <span key={i}>{d}</span>
        ))}
      </div>
      <div className="grid grid-cols-7 gap-1.5">
        {cells.map((c, i) => {
          if (!c) return <span key={`e${i}`} />
          const v = byDay.get(c.key) ?? 0
          const lv = level(v)
          const future = c.key > today
          return (
            <button
              key={c.key}
              disabled={future}
              aria-pressed={picked === c.key}
              aria-label={`${c.d}: ${money(v)} spent`}
              onClick={() => pick(picked === c.key ? null : c.key)}
              className={cx(
                'pressable relative flex aspect-square items-center justify-center rounded-[10px] text-[11px] font-semibold transition-[box-shadow,opacity] duration-150',
                future && 'opacity-35',
                picked && picked !== c.key && 'opacity-45',
                picked === c.key && 'ring-2 ring-[var(--ink)] ring-offset-2 ring-offset-[var(--surface)]',
                c.key === today && picked !== c.key && 'ring-1 ring-[var(--ink-3)]',
              )}
              style={{ background: `var(--heat-${lv})`, color: lv >= 3 ? '#fff' : 'var(--ink-2)' }}
            >
              {c.d}
            </button>
          )
        })}
      </div>
      <div className="mt-3 flex items-center justify-between text-xs">
        <span className="ink-2 min-h-4">
          {picked ? (
            <>
              {formatDay(picked, { weekday: 'short', day: 'numeric', month: 'short' })} ·{' '}
              <b className="tabular text-[var(--ink)]">{money(pickedVal!)}</b>
            </>
          ) : onSelect ? (
            'Tap a day to see its transactions'
          ) : (
            'Tap a day'
          )}
        </span>
        <span className="ink-3 flex items-center gap-1">
          Less
          {[0, 1, 2, 3, 4].map((l) => (
            <span key={l} className="h-3 w-3 rounded-[4px]" style={{ background: `var(--heat-${l})` }} />
          ))}
          More
        </span>
      </div>
    </div>
  )
}

/**
 * Tappable spending bars: one per day of a week, or one per month. A single series, so no legend; the value of
 * the chosen bar is written under the chart. Choosing a bar dims the others; choosing it again clears it.
 */
export function PeriodBars({ items, selected, onSelect, highlightLast }: { items: { key: string; label: string; sub?: string; value: number; disabled?: boolean }[]; selected?: string | null; onSelect: (key: string | null) => void; highlightLast?: boolean }) {
  const max = Math.max(1, ...items.map((i) => i.value))
  return (
    <div className="flex h-40 items-end gap-2">
      {items.map((it, idx) => {
        const on = selected === it.key || (!selected && highlightLast && idx === items.length - 1)
        const dim = selected != null && selected !== it.key
        return (
          <button
            key={it.key}
            disabled={it.disabled}
            aria-pressed={selected === it.key}
            aria-label={`${it.label}: ${money(it.value)} spent`}
            onClick={() => onSelect(selected === it.key ? null : it.key)}
            className="group flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-1.5 rounded-lg transition-transform duration-120 active:scale-95 disabled:opacity-35"
          >
            <span className={cx('tabular truncate text-[10.5px] font-medium transition-opacity duration-150', on ? 'text-[var(--ink)]' : 'ink-3', dim && 'opacity-40')}>{it.value > 0 && (items.length <= 7 || on) ? compact(it.value) : ''}</span>
            <span className="flex w-full flex-1 items-end">
              <span
                className={cx('block w-full rounded-t-[4px] rounded-b-[2px] transition-[height,opacity] duration-200 ease-[var(--ease-out-quint)]', dim ? 'opacity-30 group-hover:opacity-60' : on ? 'opacity-100' : 'opacity-70 group-hover:opacity-100')}
                style={{ height: `${Math.max(it.value > 0 ? 4 : 2, (it.value / max) * 100)}%`, background: it.value > 0 ? 'var(--c-expense)' : 'var(--line-strong)' }}
              />
            </span>
            <span className={cx('text-[11px] font-medium', selected === it.key ? 'text-[var(--ink)]' : 'ink-2')}>{it.label}</span>
            {it.sub && <span className="ink-3 -mt-1 text-[10px]">{it.sub}</span>}
          </button>
        )
      })}
    </div>
  )
}

/** Two-part allocation bar (stocks vs funds) with a 2px gap and direct labels. */
export function AllocationBar({ parts }: { parts: { label: string; value: number; color: string }[] }) {
  const total = parts.reduce((s, p) => s + p.value, 0)
  if (total <= 0) return null
  return (
    <div>
      <div className="flex h-3 gap-[2px] overflow-hidden rounded-full">
        {parts
          .filter((p) => p.value > 0)
          .map((p) => (
            <span key={p.label} title={`${p.label}: ${money0(p.value)}`} className="h-full transition-[flex-grow] duration-[240ms]" style={{ flexGrow: p.value, background: p.color }} />
          ))}
      </div>
      <div className="mt-2.5 flex flex-wrap gap-x-5 gap-y-1 text-xs">
        {parts.map((p) => (
          <span key={p.label} className="flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full" style={{ background: p.color }} />
            <span className="ink-2">{p.label}</span>
            <span className="tabular font-semibold">{Math.round((p.value / total) * 100)}%</span>
          </span>
        ))}
      </div>
    </div>
  )
}

/** Single-series daily bars (used on Reports). */
export function DailyBars({ data, name }: { data: { label: string; value: number }[]; name: string }) {
  const c = useChartColors()
  return (
    <ResponsiveContainer width="100%" height={150}>
      <BarChart data={data} margin={{ top: 8, right: 0, left: -14, bottom: 0 }}>
        <CartesianGrid vertical={false} stroke={c.grid} />
        <XAxis dataKey="label" tickLine={false} axisLine={false} tick={{ fill: c.axis, fontSize: 10 }} interval="preserveStartEnd" minTickGap={16} />
        <YAxis tickLine={false} axisLine={false} tick={{ fill: c.axis, fontSize: 11 }} tickFormatter={compact} width={50} />
        <Tooltip content={<ChartTooltip />} cursor={{ fill: c.grid }} />
        <Bar dataKey="value" name={name} fill={c.expense} radius={[4, 4, 0, 0]} maxBarSize={12} {...ANIM} />
      </BarChart>
    </ResponsiveContainer>
  )
}

/**
 * Net worth over time: one series, so no legend. Real snapshots are a solid line; days rebuilt from transactions
 * (investments estimated) are dashed. Hover/tap shows the day's breakdown.
 */
export function NetWorthChart({ points, height = 200 }: { points: { day: string; netWorth: number; cash: number; cardDues: number; investments: number; estimated: boolean }[]; height?: number }) {
  const c = useChartColors()
  // Two overlapping series so the estimated stretch can be dashed; the boundary point belongs to both.
  const lastEstimated = points.reduce((idx, p, i) => (p.estimated ? i : idx), -1)
  const data = points.map((p, i) => ({ ...p, real: i >= lastEstimated ? p.netWorth : null, est: i <= lastEstimated + 1 && lastEstimated >= 0 ? p.netWorth : null }))
  const label = (d: string) => formatDay(d, { day: 'numeric', month: 'short' })
  return (
    <ResponsiveContainer width="100%" height={height}>
      <AreaChart data={data} margin={{ top: 8, right: 4, left: -8, bottom: 0 }}>
        <defs>
          <linearGradient id="nwFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={c.ink} stopOpacity={0.12} />
            <stop offset="100%" stopColor={c.ink} stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid vertical={false} stroke={c.grid} />
        <XAxis dataKey="day" tickFormatter={label} tickLine={false} axisLine={false} tick={{ fill: c.axis, fontSize: 11 }} minTickGap={40} />
        <YAxis tickFormatter={compact} tickLine={false} axisLine={false} tick={{ fill: c.axis, fontSize: 11 }} width={56} domain={['auto', 'auto']} />
        <Tooltip
          cursor={{ stroke: c.axis, strokeWidth: 1 }}
          content={({ active, payload }) => {
            const p = active && payload?.[0]?.payload
            if (!p) return null
            return (
              <div className="glass rounded-2xl px-3 py-2 text-xs shadow-[var(--shadow-float)]">
                <p className="font-semibold">{formatDay(p.day, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })}</p>
                <p className="tabular mt-1 text-[14px] font-semibold">{money0(p.netWorth)}</p>
                <p className="ink-2 tabular">Cash {money0(p.cash)} · Cards −{money0(p.cardDues)} · Investments {money0(p.investments)}</p>
                {p.estimated && <p className="ink-3 mt-1">Investments estimated for this day</p>}
              </div>
            )
          }}
        />
        <Area type="monotone" dataKey="est" stroke={c.ink} strokeOpacity={0.45} strokeWidth={2} strokeDasharray="4 4" fill="url(#nwFill)" connectNulls={false} dot={false} activeDot={{ r: 4, strokeWidth: 2, stroke: c.surface }} isAnimationActive={false} />
        <Area type="monotone" dataKey="real" stroke={c.ink} strokeWidth={2} fill="url(#nwFill)" connectNulls={false} dot={false} activeDot={{ r: 4, strokeWidth: 2, stroke: c.surface }} {...ANIM} />
      </AreaChart>
    </ResponsiveContainer>
  )
}

/** Tiny trend line for Home: no axes, no interaction. */
export function Sparkline({ values, height = 36 }: { values: number[]; height?: number }) {
  const c = useChartColors()
  if (values.length < 2) return null
  return (
    <ResponsiveContainer width="100%" height={height}>
      <AreaChart data={values.map((v, i) => ({ i, v }))} margin={{ top: 2, right: 0, left: 0, bottom: 2 }}>
        <YAxis hide domain={['dataMin', 'dataMax']} />
        <Area type="monotone" dataKey="v" stroke={c.ink} strokeWidth={1.5} fill={c.ink} fillOpacity={0.06} dot={false} isAnimationActive={false} />
      </AreaChart>
    </ResponsiveContainer>
  )
}
