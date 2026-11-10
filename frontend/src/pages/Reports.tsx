import { useQuery } from '@tanstack/react-query'
import { CaretLeft, CaretRight, Receipt, X } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, qs, type Page as Paged, type Report, type Txn } from '../api'
import { CategoryList, PeriodBars, SpendCalendar } from '../components/charts'
import TxnRow from '../components/TxnRow'
import TxnSheet from '../components/TxnSheet'
import { Card, cx, Delta, Empty, List, Page, PageHeader, Section, Segmented, Skeleton } from '../components/ui'
import { money, money0, ymd, zonedInstant } from '../format'

type View = 'day' | 'week' | 'month'

// ---- dates (calendar dates in the app's time zone, as yyyy-MM-dd strings) ----------------------------------------------------------

const today = () => ymd()
const asDate = (d: string) => new Date(d + 'T00:00:00Z')
const fmt = (d: Date) => d.toISOString().slice(0, 10)
const addDays = (d: string, n: number) => fmt(new Date(asDate(d).getTime() + n * 864e5))
const startOf = (v: View, d: string) => {
  if (v === 'day') return d
  if (v === 'week') return addDays(d, -((asDate(d).getUTCDay() + 6) % 7)) // Monday
  return d.slice(0, 8) + '01'
}
const endOf = (v: View, start: string) => {
  if (v === 'day') return addDays(start, 1)
  if (v === 'week') return addDays(start, 7)
  const x = asDate(start)
  return fmt(new Date(Date.UTC(x.getUTCFullYear(), x.getUTCMonth() + 1, 1)))
}
const shift = (v: View, start: string, n: number) => {
  if (v === 'day') return addDays(start, n)
  if (v === 'week') return addDays(start, 7 * n)
  const x = asDate(start)
  return fmt(new Date(Date.UTC(x.getUTCFullYear(), x.getUTCMonth() + n, 1)))
}
const instant = (d: string) => zonedInstant(d).toISOString()
const short = (d: string, o: Intl.DateTimeFormatOptions) => asDate(d).toLocaleDateString('en-IN', { timeZone: 'UTC', ...o })
const dayTitle = (d: string) => (d === today() ? 'Today' : d === addDays(today(), -1) ? 'Yesterday' : short(d, { weekday: 'short', day: 'numeric', month: 'short' }))
function periodLabel(v: View, start: string) {
  if (v === 'day') return dayTitle(start)
  if (v === 'month') return short(start, { month: 'long', year: 'numeric' })
  const end = addDays(start, 6)
  const sameMonth = start.slice(0, 7) === end.slice(0, 7)
  return `${short(start, sameMonth ? { day: 'numeric' } : { day: 'numeric', month: 'short' })} – ${short(end, { day: 'numeric', month: 'short' })}`
}
const prevWord: Record<View, string> = { day: 'the day before', week: 'last week', month: 'last month' }

const report = (from: string, to: string) => api<Report>('/reports' + qs({ from: instant(from), to: instant(to) }))

/**
 * Insights for a day, a week or a month. Tapping a date in the calendar or the week chart switches the whole
 * page to that day (totals, its transactions, where the money went); tapping it again returns to the period.
 */
export default function Reports() {
  const [params, setParams] = useSearchParams()
  const view = (params.get('view') as View) || 'month'
  const anchor = params.get('at') ?? (params.get('month') ? params.get('month') + '-01' : today())
  const start = startOf(view, anchor)
  const end = endOf(view, start)
  const selected = view === 'day' ? null : params.get('day')
  const nav = useNavigate()
  const [open, setOpen] = useState<Txn | null>(null)

  const set = (next: { view?: View; at?: string; day?: string | null }) => {
    const p = new URLSearchParams()
    p.set('view', next.view ?? view)
    p.set('at', next.at ?? start)
    const d = next.day === undefined ? selected : next.day
    if (d && (next.view ?? view) !== 'day') p.set('day', d)
    setParams(p, { replace: true })
  }

  const period = useQuery({ queryKey: ['reports', view, start], queryFn: () => report(start, end) })
  const prevStart = shift(view, start, -1)
  const previous = useQuery({ queryKey: ['reports', view, prevStart], queryFn: () => report(prevStart, start) })
  const months = useQuery({ queryKey: ['reports-monthly', start.slice(0, 7)], queryFn: () => api<{ month: string; expense: number; income: number }[]>('/reports/monthly' + qs({ until: start.slice(0, 7), months: 6 })), enabled: view === 'month' })

  // What the details below are about: the chosen day, or the whole period.
  const focusDay = view === 'day' ? start : selected
  const dayReport = useQuery({ queryKey: ['reports', 'day', focusDay], queryFn: () => report(focusDay!, addDays(focusDay!, 1)), enabled: !!focusDay && view !== 'day' })
  const dayTxns = useQuery({
    queryKey: ['transactions', 'day', focusDay],
    queryFn: () => api<Paged<Txn>>('/transactions' + qs({ from: instant(focusDay!), to: instant(addDays(focusDay!, 1)), size: 200 })),
    enabled: !!focusDay,
  })
  const focus = view === 'day' ? period.data : focusDay ? dayReport.data : period.data
  const canNext = end <= today()

  return (
    <Page>
      <PageHeader back title="Insights" />

      <Segmented<View>
        value={view}
        onChange={(v) => set({ view: v, at: v === view ? start : selected ?? (start <= today() && today() < end ? today() : start), day: null })}
        options={[
          { value: 'day', label: 'Day' },
          { value: 'week', label: 'Week' },
          { value: 'month', label: 'Month' },
        ]}
      />

      <div className="mt-3 flex items-center justify-between">
        <button className="pressable flex h-10 w-10 items-center justify-center rounded-full bg-[var(--surface-2)]" onClick={() => set({ at: shift(view, start, -1), day: null })} aria-label="Previous">
          <CaretLeft size={18} />
        </button>
        <span className="text-[16px] font-semibold">{periodLabel(view, start)}</span>
        <button className="pressable flex h-10 w-10 items-center justify-center rounded-full bg-[var(--surface-2)] disabled:opacity-30" disabled={!canNext} onClick={() => set({ at: shift(view, start, 1), day: null })} aria-label="Next">
          <CaretRight size={18} />
        </button>
      </div>

      {/* The chosen day, with one tap back to the whole period */}
      <AnimatePresence initial={false}>
        {selected && (
          <motion.div initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }} transition={{ duration: 0.18 }} className="overflow-hidden">
            <button onClick={() => set({ day: null })} className="pressable mt-3 flex w-full items-center justify-between rounded-2xl bg-[var(--ink)] px-4 py-3 text-left text-[var(--surface)]">
              <span className="text-[15px]">
                Showing <b>{dayTitle(selected)}</b>
              </span>
              <span className="flex items-center gap-1 text-[13px] opacity-80">
                Back to {view === 'week' ? 'the week' : 'the month'} <X size={14} weight="bold" />
              </span>
            </button>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Totals for whatever is in focus */}
      <div className="mt-4 grid grid-cols-3 gap-2">
        <Stat label="Spent" value={focus?.expense} sub={!focusDay || view === 'day' ? previous.data && period.data && <Delta now={period.data.expense} before={previous.data.expense} goodWhenUp={false} /> : null} />
        <Stat label="Received" value={focus?.income} positive />
        <Stat label={focusDay ? 'Net' : 'Saved'} value={focus ? focus.income - focus.expense : undefined} signed />
      </div>
      {(!focusDay || view === 'day') && previous.data && period.data && previous.data.expense > 0 && (
        <p className="ink-3 mt-1.5 px-1 text-[12px]">
          Spending vs {prevWord[view]}: {money0(previous.data.expense)}
        </p>
      )}

      {/* The period at a glance; tap a day to focus on it */}
      {view === 'month' && period.data && (
        <Section title="Spending by day">
          <Card className="p-4">
            <SpendCalendar month={start.slice(0, 7)} daily={period.data.daily} selected={selected} onSelect={(d) => set({ day: d })} />
          </Card>
        </Section>
      )}
      {view === 'week' && period.data && (
        <Section title="Spending by day">
          <Card className="p-4">
            <PeriodBars
              selected={selected}
              onSelect={(d) => set({ day: d })}
              items={Array.from({ length: 7 }, (_, i) => {
                const d = addDays(start, i)
                return { key: d, label: short(d, { weekday: 'narrow' }), sub: short(d, { day: 'numeric' }), value: period.data!.daily.find((x) => x.day === d)?.expense ?? 0, disabled: d > today() }
              })}
            />
            <p className="ink-2 mt-3 text-[12px]">{selected ? `${dayTitle(selected)}: ${money(dayReport.data?.expense ?? 0)} spent` : 'Tap a day to see its transactions'}</p>
          </Card>
        </Section>
      )}

      {/* Details: switch with a short fade when the focus changes */}
      <AnimatePresence mode="wait" initial={false}>
        <motion.div key={focusDay ?? `${view}-${start}`} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: 0.16 }}>
          {focusDay && (
            <Section title={`Transactions${dayTxns.data ? ` (${dayTxns.data.total})` : ''}`}>
              {!dayTxns.data ? (
                <Skeleton className="h-32 rounded-[20px]" />
              ) : dayTxns.data.items.length === 0 ? (
                <Empty icon={Receipt} title="Nothing on this day" />
              ) : (
                <List>
                  {dayTxns.data.items.map((t) => (
                    <TxnRow key={t.id} t={t} onClick={() => setOpen(t)} />
                  ))}
                </List>
              )}
            </Section>
          )}

          {focus && focus.expenseByCategory.length > 0 && (
            <Section title="Where money went">
              <Card className="p-3">
                <CategoryList items={focus.expenseByCategory} kind="expense" limit={focusDay ? 5 : 8} onSelect={(c) => nav(c.categoryId ? `/transactions?categoryId=${c.categoryId}` : '/transactions?uncategorized=true&direction=DEBIT')} />
              </Card>
            </Section>
          )}
          {focus && focus.incomeByCategory.length > 0 && (
            <Section title="Where money came from">
              <Card className="p-3">
                <CategoryList items={focus.incomeByCategory} kind="income" limit={5} onSelect={(c) => nav(c.categoryId ? `/transactions?categoryId=${c.categoryId}` : '/transactions?uncategorized=true&direction=CREDIT')} />
              </Card>
            </Section>
          )}
          {!focusDay && focus && focus.topMerchants.length > 0 && (
            <Section title="Top places">
              <TopList items={focus.topMerchants} />
            </Section>
          )}
          {focus && !focusDay && focus.expense === 0 && focus.income === 0 && (
            <div className="mt-6">
              <Empty icon={Receipt} title="Nothing in this period" />
            </div>
          )}
        </motion.div>
      </AnimatePresence>

      {/* Month view: how this month compares with the ones before; tap a month to open it */}
      {view === 'month' && !selected && months.data && (
        <Section title="Last 6 months">
          <Card className="p-4">
            <PeriodBars
              highlightLast
              selected={null}
              onSelect={(m) => m && set({ at: m + '-01', day: null })}
              items={months.data.map((m) => ({ key: m.month, label: short(m.month + '-01', { month: 'short' }), value: m.expense }))}
            />
            <p className="ink-2 mt-3 text-[12px]">Spending per month. Tap one to open it.</p>
          </Card>
        </Section>
      )}

      <p className="ink-3 mt-6 px-1 text-[12px]">Transfers between your accounts are left out. Split bills count only your share, and repayments aren't income.</p>
      <TxnSheet txn={open} onClose={() => setOpen(null)} />
    </Page>
  )
}

function Stat({ label, value, sub, positive, signed }: { label: string; value?: number; sub?: React.ReactNode; positive?: boolean; signed?: boolean }) {
  return (
    <div className="surface min-w-0 rounded-2xl px-3 py-2.5">
      <p className="ink-2 truncate text-[12px]">{label}</p>
      {value == null ? (
        <Skeleton className="mt-1 h-5 w-16" />
      ) : (
        <p className={cx('tabular mt-0.5 truncate text-[17px] font-semibold', positive && value > 0 && 'text-[var(--positive)]', signed && value < 0 && 'text-[var(--negative)]')}>
          {signed && value < 0 ? '−' : ''}
          {money0(Math.abs(value))}
        </p>
      )}
      {sub && <div className="mt-1 text-[11px]">{sub}</div>}
    </div>
  )
}

function TopList({ items }: { items: { name: string; total: number; count: number }[] }) {
  const max = Math.max(1, ...items.map((i) => i.total))
  return (
    <List>
      {items.map((m) => (
        <div key={m.name} className="px-4 py-3">
          <div className="flex items-baseline justify-between gap-2">
            <span className="truncate text-[15px]">{m.name}</span>
            <span className="tabular shrink-0 text-[15px] font-semibold">{money(m.total)}</span>
          </div>
          <div className="mt-1.5 flex items-center gap-2">
            <span className="h-1 flex-1 overflow-hidden rounded-full bg-[var(--surface-2)]">
              <span className="block h-full rounded-full bg-[var(--bar)]" style={{ width: `${(m.total / max) * 100}%` }} />
            </span>
            <span className="ink-3 w-8 text-right text-[11px]">×{m.count}</span>
          </div>
        </div>
      ))}
    </List>
  )
}
