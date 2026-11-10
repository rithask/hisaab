import { useInfiniteQuery } from '@tanstack/react-query'
import { MagnifyingGlass, Receipt, Sliders, X } from '@phosphor-icons/react'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, qs, type Page as Paged, type Txn } from '../api'
import TxnRow from '../components/TxnRow'
import TxnSheet from '../components/TxnSheet'
import { Button, Chip, Empty, Field, Input, List, PillSelect, Segmented, Sheet, Skeleton, Page, Rise } from '../components/ui'
import { dateLabel, dayKey, money, money0, ymd, zonedInstant } from '../format'
import { useAccounts, useCategories, useFeature } from '../hooks'

export default function Transactions() {
  const [params, setParams] = useSearchParams()
  const accounts = useAccounts()
  const cats = useCategories()
  const [q, setQ] = useState(params.get('q') ?? '')
  const [open, setOpen] = useState<Txn | null>(null)

  const filters = {
    accountId: params.get('accountId') ?? undefined,
    categoryId: params.get('categoryId') ?? undefined,
    uncategorized: params.get('uncategorized') ?? undefined,
    direction: params.get('direction') ?? undefined,
    includeTransfers: params.get('includeTransfers') === 'true',
    tag: params.get('tag') ?? undefined,
    q: params.get('q') ?? undefined,
    // Advanced search (Settings → Features): dates are yyyy-mm-dd in the app's time zone, "to" inclusive.
    from: params.get('from') ? zonedInstant(params.get('from')!).toISOString() : undefined,
    to: params.get('to') ? zonedInstant(nextDay(params.get('to')!)).toISOString() : undefined,
    minAmount: params.get('min') ?? undefined,
    maxAmount: params.get('max') ?? undefined,
    sort: params.get('sort') ?? undefined,
  }
  const advanced = useFeature('search')
  const [filtering, setFiltering] = useState(false)
  const setMany = (kv: Record<string, string | undefined>) => {
    const n = new URLSearchParams(params)
    for (const [k, v] of Object.entries(kv)) (v ? n.set(k, v) : n.delete(k))
    setParams(n, { replace: true })
  }
  const activeAdvanced = ['from', 'to', 'min', 'max', 'sort'].filter((k) => params.get(k))
  const set = (k: string, v: string | undefined) => {
    const n = new URLSearchParams(params)
    if (v) n.set(k, v)
    else n.delete(k)
    setParams(n, { replace: true })
  }

  const list = useInfiniteQuery({
    queryKey: ['transactions', filters],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => api<Paged<Txn>>('/transactions' + qs({ ...filters, page: pageParam, size: 50 })),
    getNextPageParam: (last) => (last.hasMore ? last.page + 1 : undefined),
  })

  const { groups, totalIn, totalOut } = useMemo(() => {
    const items = list.data?.pages.flatMap((p) => p.items) ?? []
    const groups: { key: string; label: string; items: Txn[]; out: number }[] = []
    let totalIn = 0,
      totalOut = 0
    // Sorted by amount, days are mixed: one flat list (rows show their date) instead of a header per row.
    const byAmount = filters.sort === 'largest' || filters.sort === 'smallest'
    for (const t of items) {
      const k = byAmount ? 'all' : dayKey(t.occurredAt)
      let g = groups[groups.length - 1]
      if (!g || g.key !== k) groups.push((g = { key: k, label: byAmount ? (filters.sort === 'largest' ? 'Largest first' : 'Smallest first') : dateLabel(t.occurredAt), items: [], out: 0 }))
      g.items.push(t)
      if (t.transfer) continue
      if (t.direction === 'DEBIT') {
        g.out += t.amount
        totalOut += t.amount
      } else totalIn += t.amount
    }
    return { groups, totalIn, totalOut }
  }, [list.data, filters.sort])

  const account = accounts.data?.find((a) => String(a.id) === filters.accountId)
  const total = list.data?.pages[0].total

  return (
    <Page>
      <Rise className="mb-3 pt-2">
        <h1 className="text-[28px] font-bold leading-tight tracking-[-0.02em]">{account ? account.name : 'Activity'}</h1>
        {total != null && (
          <p className="ink-2 mt-1.5 text-sm">
            {total} transaction{total === 1 ? '' : 's'}
            {groups.length > 0 && (
              <>
                {' · '}
                <span className="tabular">−{money0(totalOut)}</span>
                {' · '}
                <span className="tabular text-[var(--positive)]">+{money0(totalIn)}</span>
              </>
            )}
          </p>
        )}
      </Rise>

      {/* Sticky search + filters */}
      <div className="sticky top-0 z-30 -mx-4 px-4 pb-3 sm:-mx-6 sm:px-6 lg:-mx-10 lg:px-10 pt-[max(env(safe-area-inset-top),0.5rem)] backdrop-blur-xl [mask-image:linear-gradient(to_bottom,black_85%,transparent)]">
        <form
          className="relative"
          onSubmit={(e) => {
            e.preventDefault()
            set('q', q.trim() || undefined)
          }}
        >
          <MagnifyingGlass size={17} className="ink-3 absolute left-4 top-1/2 -translate-y-1/2" />
          <input
            className="surface h-11 w-full rounded-full pl-11 pr-10 text-[15px] outline-none transition-[border-color,box-shadow] duration-150 placeholder:text-[var(--ink-3)] hover:border-[var(--line-strong)] focus:shadow-[0_0_0_4px_var(--accent-soft)]"
            placeholder="Search merchant, notes, ref no."
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onBlur={() => set('q', q.trim() || undefined)}
          />
          {q && (
            <button type="button" className="ink-3 absolute right-3 top-1/2 -translate-y-1/2 p-1" onClick={() => (setQ(''), set('q', undefined))} aria-label="Clear search">
              <X size={16} />
            </button>
          )}
        </form>
        <div className="no-scrollbar -mx-4 mt-2.5 flex gap-2 overflow-x-auto px-4 sm:mx-0 sm:flex-wrap sm:overflow-visible sm:px-0">
          <PillSelect active={!!filters.accountId} value={filters.accountId ?? ''} onChange={(e) => set('accountId', e.target.value || undefined)} aria-label="Account">
            <option value="">All accounts</option>
            {(accounts.data ?? []).map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </PillSelect>
          <PillSelect active={!!filters.categoryId} value={filters.categoryId ?? ''} onChange={(e) => set('categoryId', e.target.value || undefined)} aria-label="Category">
            <option value="">All categories</option>
            {(cats.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </PillSelect>
          <Chip active={filters.direction === 'DEBIT'} onClick={() => set('direction', filters.direction === 'DEBIT' ? undefined : 'DEBIT')}>
            Money out
          </Chip>
          <Chip active={filters.direction === 'CREDIT'} onClick={() => set('direction', filters.direction === 'CREDIT' ? undefined : 'CREDIT')}>
            Money in
          </Chip>
          <Chip active={filters.uncategorized === 'true'} onClick={() => set('uncategorized', filters.uncategorized ? undefined : 'true')}>
            Uncategorised
          </Chip>
          <Chip active={filters.includeTransfers} onClick={() => set('includeTransfers', filters.includeTransfers ? undefined : 'true')}>
            Transfers
          </Chip>
          {filters.tag && (
            <Chip active onClick={() => set('tag', undefined)}>
              #{filters.tag} <X size={13} />
            </Chip>
          )}
          {advanced && (
            <Chip active={activeAdvanced.length > 0} onClick={() => setFiltering(true)}>
              <Sliders size={14} /> {activeAdvanced.length ? `Filters · ${describeAdvanced(params)}` : 'More filters'}
            </Chip>
          )}
          {advanced && activeAdvanced.length > 0 && (
            <Chip onClick={() => setMany({ from: undefined, to: undefined, min: undefined, max: undefined, sort: undefined })}>
              <X size={13} /> Clear
            </Chip>
          )}
        </div>
      </div>

      {filters.uncategorized === 'true' && total != null && total > 0 && (
        <p className="ink-2 mt-1 rounded-2xl bg-[var(--surface-2)] px-4 py-3 text-[14px] leading-relaxed">
          Tap a transaction and pick its category. The app remembers your choice for that payee, so future ones are sorted for you.
        </p>
      )}

      <div className="mt-2 space-y-5">
        {list.isLoading && (
          <div className="space-y-3">
            <Skeleton className="h-4 w-24" />
            <Skeleton className="h-64 rounded-[20px]" />
          </div>
        )}
        {!list.isLoading && groups.length === 0 && (
          <Empty icon={Receipt} title="No transactions">
            Try clearing filters, or add one with the + button.
          </Empty>
        )}
        {groups.map((g) => (
          <section key={g.key}>
            <div className="mb-2 flex items-baseline justify-between px-1">
              <span className="text-[13px] font-semibold ink-2">{g.label}</span>
              {g.out > 0 && <span className="tabular ink-3 text-[12.5px] font-medium">−{money(g.out)}</span>}
            </div>
            <List>
              {g.items.map((t) => (
                <TxnRow key={t.id} t={t} onClick={() => setOpen(t)} showDate={g.key === 'all'} />
              ))}
            </List>
          </section>
        ))}
        {list.hasNextPage && (
          <Button variant="secondary" className="w-full" onClick={() => list.fetchNextPage()} disabled={list.isFetchingNextPage}>
            {list.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </Button>
        )}
      </div>

      <TxnSheet txn={open} onClose={() => setOpen(null)} />
      <Sheet open={filtering} onClose={() => setFiltering(false)} title="Filters">
        {filtering && <AdvancedFilters params={params} onApply={(kv) => (setMany(kv), setFiltering(false))} />}
      </Sheet>
    </Page>
  )
}

/** Calendar-date arithmetic on yyyy-mm-dd strings (no time zone involved). */
const day = (y: number, m: number, d: number) => new Date(Date.UTC(y, m, d)).toISOString().slice(0, 10)
const nextDay = (d: string) => {
  const [y, m, dd] = d.split('-').map(Number)
  return day(y, m - 1, dd + 1)
}
type Sort = 'newest' | 'oldest' | 'largest' | 'smallest'

/** Ready-made date ranges, from today in the app's time zone; "This financial year" runs from April, as in India. */
function presets() {
  const today = ymd()
  const [y, m1] = today.split('-').map(Number)
  const m = m1 - 1
  const fyStart = m >= 3 ? y : y - 1
  return [
    { label: 'This month', from: day(y, m, 1), to: today },
    { label: 'Last month', from: day(y, m - 1, 1), to: day(y, m, 0) },
    { label: 'Last 3 months', from: day(y, m - 2, 1), to: today },
    { label: 'This financial year', from: `${fyStart}-04-01`, to: today },
  ]
}

function describeAdvanced(p: URLSearchParams) {
  const bits: string[] = []
  const pre = presets().find((x) => x.from === p.get('from') && x.to === p.get('to'))
  if (pre) bits.push(pre.label.toLowerCase())
  else if (p.get('from') || p.get('to')) bits.push('dates')
  if (p.get('min') || p.get('max')) bits.push(p.get('min') && p.get('max') ? `₹${p.get('min')}–${p.get('max')}` : p.get('min') ? `≥ ₹${p.get('min')}` : `≤ ₹${p.get('max')}`)
  if (p.get('sort') && p.get('sort') !== 'newest') bits.push(p.get('sort')!)
  return bits.join(', ')
}

function AdvancedFilters({ params, onApply }: { params: URLSearchParams; onApply: (kv: Record<string, string | undefined>) => void }) {
  const [from, setFrom] = useState(params.get('from') ?? '')
  const [to, setTo] = useState(params.get('to') ?? '')
  const [min, setMin] = useState(params.get('min') ?? '')
  const [max, setMax] = useState(params.get('max') ?? '')
  const [sort, setSort] = useState<Sort>((params.get('sort') as Sort) || 'newest')
  const num = (v: string) => v.replace(/[^\d.]/g, '')
  return (
    <div className="space-y-5">
      <div>
        <p className="ink-2 mb-2 px-1 text-[13px] font-medium">When</p>
        <div className="flex flex-wrap gap-2">
          <Chip active={!from && !to} onClick={() => (setFrom(''), setTo(''))}>
            Any time
          </Chip>
          {presets().map((p) => (
            <Chip key={p.label} active={from === p.from && to === p.to} onClick={() => (setFrom(p.from), setTo(p.to))}>
              {p.label}
            </Chip>
          ))}
        </div>
        <div className="mt-3 flex gap-2">
          <Field label="From">
            <Input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
          </Field>
          <Field label="To">
            <Input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
          </Field>
        </div>
      </div>
      <div>
        <p className="ink-2 mb-2 px-1 text-[13px] font-medium">Amount (₹)</p>
        <div className="flex gap-2">
          <Input inputMode="decimal" placeholder="At least" value={min} onChange={(e) => setMin(num(e.target.value))} aria-label="Minimum amount" />
          <Input inputMode="decimal" placeholder="At most" value={max} onChange={(e) => setMax(num(e.target.value))} aria-label="Maximum amount" />
        </div>
      </div>
      <div>
        <p className="ink-2 mb-2 px-1 text-[13px] font-medium">Sort by</p>
        <Segmented<Sort>
          value={sort}
          onChange={setSort}
          options={[
            { value: 'newest', label: 'Newest' },
            { value: 'oldest', label: 'Oldest' },
            { value: 'largest', label: 'Largest' },
            { value: 'smallest', label: 'Smallest' },
          ]}
        />
      </div>
      <Button className="w-full" onClick={() => onApply({ from: from || undefined, to: to || undefined, min: min || undefined, max: max || undefined, sort: sort === 'newest' ? undefined : sort })}>
        Show results
      </Button>
    </div>
  )
}
