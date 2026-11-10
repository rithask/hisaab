import { useQuery } from '@tanstack/react-query'
import { ArrowsClockwise, ChartLineUp, PiggyBank, Plus, Trash, UploadSimple } from '@phosphor-icons/react'
import { useRef, useState } from 'react'
import { api, type Holding, type MfScheme, type Portfolio } from '../api'
import { AllocationBar } from '../components/charts'
import { Badge, Button, Card, cx, Empty, ErrorText, Field, IconButton, Input, List, Notice, PageHeader, PageSkeleton, Section, Segmented, Sheet, Page, BigMoney } from '../components/ui'
import { dateTime, money, money0 } from '../format'
import { useMoneyMutation } from '../hooks'

export default function Invest() {
  const pf = useQuery({ queryKey: ['holdings'], queryFn: () => api<Portfolio>('/holdings') })
  const [adding, setAdding] = useState<'STOCK' | 'MF' | null>(null)
  const [editing, setEditing] = useState<Holding | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const refresh = useMoneyMutation(() => api<{ stocksUpdated: number }>('/prices/refresh', { method: 'POST' }), (r) => setMsg(`Updated ${r.stocksUpdated} stock prices and mutual fund NAVs.`))
  const upload = useMoneyMutation(
    (f: File) => {
      const form = new FormData()
      form.append('file', f)
      form.append('replace', 'true')
      return api<{ imported: number; removed: number; skipped: string[] }>('/holdings/import', { form })
    },
    (r) => setMsg(`Imported ${r.imported} stocks${r.removed ? `, removed ${r.removed} no longer held` : ''}${r.skipped.length ? `. Skipped: ${r.skipped.join('; ')}` : ''}.`),
  )

  if (!pf.data) return <PageSkeleton />
  const p = pf.data
  const stocks = p.holdings.filter((h) => h.assetType === 'STOCK')
  const funds = p.holdings.filter((h) => h.assetType === 'MF')
  const value = (hs: Holding[]) => hs.reduce((s, h) => s + (h.currentValue ?? h.invested), 0)
  const up = p.pnl >= 0

  return (
    <Page>
      <PageHeader
        title="Investments"
        action={<IconButton icon={ArrowsClockwise} label={refresh.isPending ? 'Refreshing…' : 'Refresh prices'} onClick={() => refresh.mutate(undefined)} disabled={refresh.isPending} className={cx(refresh.isPending && 'opacity-50')} />}
      />

      <Card className="p-5">
        <p className="ink-2 text-[13px] font-medium">Current value</p>
        <BigMoney value={p.currentValue} paise={false} className="mt-1 block text-[40px] leading-tight" />
        <div className="mt-1 flex flex-wrap items-center gap-2">
          <Badge tone={up ? 'green' : 'red'} className="text-[12px]">
            {up ? '▲' : '▼'} {money0(Math.abs(p.pnl))} ({Math.abs(p.pnlPct).toFixed(2)}%)
          </Badge>
          {p.dayChange !== 0 && (
            <Badge tone={p.dayChange >= 0 ? 'green' : 'red'} className="text-[12px]">
              Today {p.dayChange >= 0 ? '+' : '−'}
              {money0(Math.abs(p.dayChange))}
            </Badge>
          )}
        </div>
        <div className="mt-5 grid grid-cols-2 gap-3 border-t border-[var(--line)] pt-4 text-sm">
          <div>
            <p className="ink-2 text-xs">Invested</p>
            <p className="tabular font-semibold">{money0(p.invested)}</p>
          </div>
          <div>
            <p className="ink-2 text-xs">Returns</p>
            <p className={cx('tabular font-semibold', up ? 'text-[var(--positive)]' : 'text-[var(--negative)]')}>
              {up ? '+' : '−'}
              {money0(Math.abs(p.pnl))}
            </p>
          </div>
        </div>
        {stocks.length > 0 && funds.length > 0 && (
          <div className="mt-4">
            <AllocationBar
              parts={[
                { label: 'Stocks', value: value(stocks), color: 'var(--c-stock)' },
                { label: 'Mutual funds', value: value(funds), color: 'var(--c-fund)' },
              ]}
            />
          </div>
        )}
      </Card>

      <div className="mt-4 grid grid-cols-3 gap-2">
        <Button variant="secondary" size="sm" className="h-11" onClick={() => setAdding('STOCK')}>
          <Plus size={16} /> Stock
        </Button>
        <Button variant="secondary" size="sm" className="h-11" onClick={() => setAdding('MF')}>
          <Plus size={16} /> Fund
        </Button>
        <Button variant="secondary" size="sm" className="h-11" onClick={() => fileRef.current?.click()} disabled={upload.isPending}>
          <UploadSimple size={16} /> Zerodha
        </Button>
        <input
          ref={fileRef}
          type="file"
          accept=".csv,text/csv"
          className="hidden"
          onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) upload.mutate(f)
            e.target.value = ''
          }}
        />
      </div>
      <p className="ink-3 mt-2 px-1 text-xs">Zerodha: Kite → Holdings → Download (CSV). Replaces your stock list with the file.</p>
      {msg && (
        <div className="mt-3">
          <Notice>{msg}</Notice>
        </div>
      )}
      <ErrorText error={refresh.error ?? upload.error} />

      {p.holdings.length === 0 && (
        <div className="mt-6">
          <Empty icon={PiggyBank} title="No holdings yet">
            Add stocks and mutual funds, or import your Zerodha holdings CSV.
          </Empty>
        </div>
      )}
      {stocks.length > 0 && (
        <Section title={`Stocks · ${stocks.length}`}>
          <HoldingList items={stocks} onPick={setEditing} color="var(--c-stock)" />
        </Section>
      )}
      {funds.length > 0 && (
        <Section title={`Mutual funds · ${funds.length}`}>
          <HoldingList items={funds} onPick={setEditing} color="var(--c-fund)" />
        </Section>
      )}

      <Sheet open={!!adding || !!editing} onClose={() => (setAdding(null), setEditing(null))} title={editing ? (editing.assetType === 'STOCK' ? editing.symbol : 'Mutual fund') : adding === 'MF' ? 'Add mutual fund' : 'Add stock'}>
        {(adding || editing) && <HoldingForm type={editing?.assetType ?? adding!} holding={editing ?? undefined} onClose={() => (setAdding(null), setEditing(null))} />}
      </Sheet>
    </Page>
  )
}

function HoldingList({ items, onPick, color }: { items: Holding[]; onPick: (h: Holding) => void; color: string }) {
  return (
    <List>
      {items.map((h) => {
        const up = (h.pnl ?? 0) >= 0
        const label = h.assetType === 'STOCK' ? h.symbol.slice(0, 4) : h.name.replace(/[^A-Za-z ]/g, '').split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('')
        return (
          <button key={h.id} onClick={() => onPick(h)} className="flex w-full items-center gap-3 px-4 py-3.5 text-left row">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-[10.5px] font-semibold tracking-wide" style={{ background: `color-mix(in oklab, ${color} 18%, transparent)`, color: `color-mix(in oklab, ${color} 62%, var(--ink))` }}>
              {h.assetType === 'MF' && !label ? <ChartLineUp size={18} /> : label}
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[15px] font-semibold">{h.assetType === 'STOCK' ? h.symbol : h.name}</span>
              <span className="ink-2 block truncate text-[12.5px]">
                {Number(h.quantity)} {h.assetType === 'MF' ? (Number(h.quantity) === 1 ? 'unit' : 'units') : Number(h.quantity) === 1 ? 'share' : 'shares'} · avg {money(h.avgPrice)}
              </span>
            </span>
            <span className="shrink-0 text-right">
              <span className="tabular block text-[15px] font-semibold">{money0(h.currentValue ?? h.invested)}</span>
              {h.pnl != null ? (
                <span className={cx('tabular block text-[12px] font-semibold', up ? 'text-[var(--positive)]' : 'text-[var(--negative)]')}>
                  {up ? '+' : '−'}
                  {Math.abs(h.pnlPct ?? 0).toFixed(1)}%
                </span>
              ) : (
                <span className="ink-3 block text-[11px]">no price yet</span>
              )}
            </span>
          </button>
        )
      })}
    </List>
  )
}

function HoldingForm({ type, holding, onClose }: { type: 'STOCK' | 'MF'; holding?: Holding; onClose: () => void }) {
  const [symbol, setSymbol] = useState(holding?.symbol ?? '')
  const [name, setName] = useState(holding?.name ?? '')
  const [exchange, setExchange] = useState<'NSE' | 'BSE'>((holding?.exchange as 'NSE' | 'BSE') ?? 'NSE')
  const [quantity, setQuantity] = useState(holding ? String(holding.quantity) : '')
  const [avgPrice, setAvgPrice] = useState(holding ? String(holding.avgPrice) : '')
  const [search, setSearch] = useState('')
  const schemes = useQuery({
    queryKey: ['mf', search],
    queryFn: () => api<MfScheme[]>('/mf/search?q=' + encodeURIComponent(search)),
    enabled: type === 'MF' && !holding && search.trim().length >= 3,
  })
  const save = useMoneyMutation(
    () =>
      api(holding ? `/holdings/${holding.id}` : '/holdings', {
        method: holding ? 'PUT' : 'POST',
        body: { assetType: type, symbol, exchange: type === 'STOCK' ? exchange : null, name, quantity: Number(quantity), avgPrice: Number(avgPrice) },
      }),
    onClose,
  )
  const del = useMoneyMutation(() => api(`/holdings/${holding!.id}`, { method: 'DELETE' }), onClose)

  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate(undefined)
      }}
    >
      {holding && (
        <div className="rounded-[20px] bg-[var(--surface-2)] p-4">
          <p className="font-semibold">{holding.name}</p>
          <div className="mt-2 grid grid-cols-3 gap-2 text-xs">
            <div>
              <p className="ink-2">Last price</p>
              <p className="tabular text-sm font-semibold">{money(holding.lastPrice)}</p>
            </div>
            <div>
              <p className="ink-2">Value</p>
              <p className="tabular text-sm font-semibold">{money0(holding.currentValue)}</p>
            </div>
            <div>
              <p className="ink-2">P&L</p>
              <p className={cx('tabular text-sm font-semibold', (holding.pnl ?? 0) >= 0 ? 'text-[var(--positive)]' : 'text-[var(--negative)]')}>{money0(holding.pnl)}</p>
            </div>
          </div>
          {holding.priceAsOf && <p className="ink-3 mt-2 text-[11px]">Price as of {dateTime(holding.priceAsOf)}</p>}
        </div>
      )}
      {type === 'STOCK' ? (
        !holding && (
          <>
            <Field label="Symbol" hint="NSE trading symbol, e.g. RELIANCE, TCS, NIFTYBEES">
              <Input required autoFocus value={symbol} onChange={(e) => setSymbol(e.target.value.toUpperCase())} />
            </Field>
            <Segmented<'NSE' | 'BSE'> value={exchange} onChange={setExchange} options={[{ value: 'NSE', label: 'NSE' }, { value: 'BSE', label: 'BSE' }]} />
          </>
        )
      ) : (
        !holding && (
          <div>
            <Field label="Search fund" hint='e.g. "parag flexi direct growth"'>
              <Input autoFocus value={search} onChange={(e) => setSearch(e.target.value)} />
            </Field>
            {symbol && <Notice>Selected: {name}</Notice>}
            <div className="mt-2 max-h-56 space-y-1 overflow-y-auto">
              {(schemes.data ?? []).map((s) => (
                <button
                  type="button"
                  key={s.code}
                  onClick={() => (setSymbol(s.code), setName(s.name), setSearch(''))}
                  className="pressable block w-full rounded-2xl px-3 py-2.5 text-left text-sm hover:bg-[var(--surface-2)]"
                >
                  <span className="font-medium">{s.name}</span>
                  <span className="ink-2 block text-xs">
                    NAV {s.nav} · {s.navDate}
                  </span>
                </button>
              ))}
            </div>
          </div>
        )
      )}
      <div className="flex gap-3">
        <Field label={type === 'MF' ? 'Units' : 'Quantity'}>
          <Input required inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        </Field>
        <Field label={type === 'MF' ? 'Avg NAV' : 'Avg price'}>
          <Input required inputMode="decimal" value={avgPrice} onChange={(e) => setAvgPrice(e.target.value)} />
        </Field>
      </div>
      <ErrorText error={save.error ?? del.error} />
      <Button type="submit" className="w-full" disabled={save.isPending || !symbol}>
        Save
      </Button>
      {holding && (
        <Button type="button" variant="danger" className="w-full" onClick={() => confirm('Remove this holding?') && del.mutate(undefined)}>
          <Trash size={16} /> Remove
        </Button>
      )}
    </form>
  )
}
