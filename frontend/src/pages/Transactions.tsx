import { useInfiniteQuery } from '@tanstack/react-query'
import { MagnifyingGlass, Receipt, Sliders, X } from '@phosphor-icons/react'
import { useMemo, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, qs, type Page as Paged, type Txn } from '../api'
import TxnRow from '../components/TxnRow'
import TxnSheet from '../components/TxnSheet'
import { DatePicker } from '../components/DatePicker'
import { Button, Chip, cx, Empty, Field, Input, List, Segmented, Sheet, Skeleton, Page, Rise, Toggle } from '../components/ui'
import { dateLabel, dayKey, money, money0, ymd, zonedInstant } from '../format'
import { useAccounts, useCategories } from '../hooks'

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
    // Dates are yyyy-mm-dd in the app's time zone, "to" inclusive.
    from: params.get('from') ? zonedInstant(params.get('from')!).toISOString() : undefined,
    to: params.get('to') ? zonedInstant(nextDay(params.get('to')!)).toISOString() : undefined,
    minAmount: params.get('min') ?? undefined,
    maxAmount: params.get('max') ?? undefined,
    sort: params.get('sort') ?? undefined,
  }
  const [filtering, setFiltering] = useState(false)
  const setMany = (kv: Record<string, string | undefined>) => {
    const n = new URLSearchParams(params)
    for (const [k, v] of Object.entries(kv)) (v ? n.set(k, v) : n.delete(k))
    setParams(n, { replace: true })
  }
  const active = describe(params, accounts.data ?? [], cats.data ?? [])
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
        <div className="flex gap-2">
          <form
            className="relative min-w-0 flex-1"
            onSubmit={(e) => {
              e.preventDefault()
              set('q', q.trim() || undefined)
            }}
          >
            <MagnifyingGlass size={17} className="ink-3 absolute left-4 top-1/2 -translate-y-1/2" />
            <input
              className="surface h-11 w-full rounded-full pl-11 pr-10 text-[15px] outline-none transition-[border-color,box-shadow] duration-150 placeholder:text-[var(--ink-3)] hover:border-[var(--line-strong)] focus:shadow-[0_0_0_4px_var(--accent-soft)]"
              placeholder="Search payee, notes, ref no."
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
          <button
            type="button"
            onClick={() => setFiltering(true)}
            className={cx('pressable flex h-11 shrink-0 items-center gap-1.5 rounded-full px-4 text-[14px] font-semibold', active.length ? 'bg-[var(--ink)] text-[var(--bg)]' : 'surface')}
          >
            <Sliders size={16} /> Filter{active.length ? ` · ${active.length}` : ''}
          </button>
        </div>
        {/* Only what's set, each one tap to remove. */}
        {active.length > 0 && (
          <div className="no-scrollbar -mx-4 mt-2.5 flex gap-2 overflow-x-auto px-4 sm:mx-0 sm:flex-wrap sm:overflow-visible sm:px-0">
            {active.map((f) => (
              <Chip key={f.label} active onClick={() => setMany(Object.fromEntries(f.keys.map((k) => [k, undefined])))}>
                {f.label} <X size={13} />
              </Chip>
            ))}
            {active.length > 1 && <Chip onClick={() => setMany(Object.fromEntries(FILTER_KEYS.map((k) => [k, undefined])))}>Clear all</Chip>}
          </div>
        )}
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
        {filtering && <FilterForm params={params} onApply={(kv) => (setMany(kv), setFiltering(false))} />}
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

/** Every filter the sheet sets, so "Clear all" clears them all. */
const FILTER_KEYS = ['accountId', 'categoryId', 'uncategorized', 'direction', 'includeTransfers', 'tag', 'from', 'to', 'min', 'max', 'sort']

/** What's filtered, in words: one chip each, with the params it removes. */
function describe(p: URLSearchParams, accounts: { id: number; name: string }[], cats: { id: number; name: string }[]) {
  const out: { label: string; keys: string[] }[] = []
  const acc = accounts.find((a) => String(a.id) === p.get('accountId'))
  if (acc) out.push({ label: acc.name, keys: ['accountId'] })
  const cat = cats.find((c) => String(c.id) === p.get('categoryId'))
  if (cat) out.push({ label: cat.name, keys: ['categoryId'] })
  if (p.get('uncategorized') === 'true') out.push({ label: 'No category', keys: ['uncategorized'] })
  if (p.get('direction')) out.push({ label: p.get('direction') === 'DEBIT' ? 'Money out' : 'Money in', keys: ['direction'] })
  if (p.get('includeTransfers') === 'true') out.push({ label: 'With moves between my accounts', keys: ['includeTransfers'] })
  if (p.get('tag')) out.push({ label: `#${p.get('tag')}`, keys: ['tag'] })
  const pre = presets().find((x) => x.from === p.get('from') && x.to === p.get('to'))
  if (pre) out.push({ label: pre.label, keys: ['from', 'to'] })
  else if (p.get('from') || p.get('to')) out.push({ label: [p.get('from') ?? '…', p.get('to') ?? '…'].join(' – '), keys: ['from', 'to'] })
  if (p.get('min') || p.get('max'))
    out.push({ label: p.get('min') && p.get('max') ? `₹${p.get('min')}–${p.get('max')}` : p.get('min') ? `₹${p.get('min')} or more` : `Up to ₹${p.get('max')}`, keys: ['min', 'max'] })
  if (p.get('sort') && p.get('sort') !== 'newest') out.push({ label: `${p.get('sort')![0].toUpperCase()}${p.get('sort')!.slice(1)} first`, keys: ['sort'] })
  return out
}

/** All the filters in one sheet; nothing changes until "Show results". */
function FilterForm({ params, onApply }: { params: URLSearchParams; onApply: (kv: Record<string, string | undefined>) => void }) {
  const accounts = useAccounts()
  const cats = useCategories()
  const [accountId, setAccountId] = useState(params.get('accountId') ?? '')
  // '' any, 'none' no category, or a category id
  const [category, setCategory] = useState(params.get('uncategorized') === 'true' ? 'none' : (params.get('categoryId') ?? ''))
  const [direction, setDirection] = useState<'' | 'DEBIT' | 'CREDIT'>((params.get('direction') as 'DEBIT' | 'CREDIT') ?? '')
  const [moves, setMoves] = useState(params.get('includeTransfers') === 'true')
  const [from, setFrom] = useState(params.get('from') ?? '')
  const [to, setTo] = useState(params.get('to') ?? '')
  const [min, setMin] = useState(params.get('min') ?? '')
  const [max, setMax] = useState(params.get('max') ?? '')
  const [sort, setSort] = useState<Sort>((params.get('sort') as Sort) || 'newest')
  const num = (v: string) => v.replace(/[^\d.]/g, '')
  const kinds = direction === 'CREDIT' ? ['INCOME'] : direction === 'DEBIT' ? ['EXPENSE'] : ['EXPENSE', 'INCOME']
  return (
    <div className="space-y-5">
      <Group label="Money">
        <Segmented<'' | 'DEBIT' | 'CREDIT'>
          value={direction}
          onChange={setDirection}
          className="w-full"
          options={[
            { value: '', label: 'All' },
            { value: 'DEBIT', label: 'Out' },
            { value: 'CREDIT', label: 'In' },
          ]}
        />
      </Group>
      <Group label="Account">
        <Chip active={!accountId} onClick={() => setAccountId('')}>
          Any
        </Chip>
        {(accounts.data ?? []).map((a) => (
          <Chip key={a.id} active={accountId === String(a.id)} onClick={() => setAccountId(String(a.id))}>
            {a.name}
          </Chip>
        ))}
      </Group>
      <Group label="Category">
        <Chip active={!category} onClick={() => setCategory('')}>
          Any
        </Chip>
        <Chip active={category === 'none'} onClick={() => setCategory('none')}>
          No category
        </Chip>
        {(cats.data ?? []).filter((c) => kinds.includes(c.kind)).map((c) => (
          <Chip key={c.id} active={category === String(c.id)} onClick={() => setCategory(String(c.id))}>
            {c.name}
          </Chip>
        ))}
      </Group>
      <Group label="When">
        <Chip active={!from && !to} onClick={() => (setFrom(''), setTo(''))}>
          Any time
        </Chip>
        {presets().map((p) => (
          <Chip key={p.label} active={from === p.from && to === p.to} onClick={() => (setFrom(p.from), setTo(p.to))}>
            {p.label}
          </Chip>
        ))}
        <div className="mt-1 flex w-full gap-2">
          <Field label="From">
            <DatePicker value={from} max={to || undefined} onChange={setFrom} label="From" placeholder="Any" short clearable />
          </Field>
          <Field label="To">
            <DatePicker value={to} min={from || undefined} onChange={setTo} label="To" placeholder="Any" short clearable />
          </Field>
        </div>
      </Group>
      <Group label="Amount (₹)">
        <div className="flex w-full gap-2">
          <Input inputMode="decimal" placeholder="At least" value={min} onChange={(e) => setMin(num(e.target.value))} aria-label="Minimum amount" />
          <Input inputMode="decimal" placeholder="At most" value={max} onChange={(e) => setMax(num(e.target.value))} aria-label="Maximum amount" />
        </div>
      </Group>
      <Group label="Sort by">
        <Segmented<Sort>
          value={sort}
          onChange={setSort}
          className="w-full"
          options={[
            { value: 'newest', label: 'Newest' },
            { value: 'oldest', label: 'Oldest' },
            { value: 'largest', label: 'Largest' },
            { value: 'smallest', label: 'Smallest' },
          ]}
        />
      </Group>
      <div className="rounded-2xl bg-[var(--surface-2)] px-4 py-2.5">
        <Toggle checked={moves} onChange={setMoves} label="Include money moved between my accounts" />
      </div>
      <Button
        className="w-full"
        onClick={() =>
          onApply({
            accountId: accountId || undefined,
            categoryId: category && category !== 'none' ? category : undefined,
            uncategorized: category === 'none' ? 'true' : undefined,
            direction: direction || undefined,
            includeTransfers: moves ? 'true' : undefined,
            from: from || undefined,
            to: to || undefined,
            min: min || undefined,
            max: max || undefined,
            sort: sort === 'newest' ? undefined : sort,
          })
        }
      >
        Show results
      </Button>
    </div>
  )
}

function Group({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <p className="ink-2 mb-2 px-1 text-[13px] font-medium">{label}</p>
      <div className="flex flex-wrap gap-2">{children}</div>
    </div>
  )
}
