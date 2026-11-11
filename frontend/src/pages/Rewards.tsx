import { useQuery } from '@tanstack/react-query'
import { CreditCard, Gift, Plus, Trash } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, type CardRewards, type RewardCycle, type RewardProgram, type RewardRule } from '../api'
import { DatePicker } from '../components/DatePicker'
import { Button, Card, cx, Empty, ErrorText, FeatureGate, Field, Input, List, Meter, Page, PageHeader, PageSkeleton, Section, Segmented, Select, Sheet } from '../components/ui'
import { count, money0, moneyAuto, shortDate, ymd } from '../format'
import { useCategories, useFeature, useMoneyMutation } from '../hooks'

const points = (p?: RewardProgram) => p?.kind === 'POINTS'
/** "₹164" for cashback, "107 points" for points cards. */
const earned = (p: RewardProgram | undefined, v: number) => (points(p) ? `${count(v)} point${v === 1 ? '' : 's'}` : moneyAuto(Math.round(v * 100) / 100))
const rateText = (p: RewardProgram | undefined, r: number) => (points(p) ? `${r} / ₹100` : `${r}%`)

/** What each credit card should have earned, cycle by cycle, from the reward rules I describe. */
export default function Rewards() {
  const on = useFeature('rewards')
  const q = useQuery({ queryKey: ['rewards'], queryFn: () => api<CardRewards[]>('/rewards'), enabled: on === true })
  const [editing, setEditing] = useState<CardRewards | null>(null)

  return (
    <FeatureGate on={on} name="Rewards">
      <Page>
        <PageHeader back title="Rewards" subtitle="Cashback and points on your cards" />
        {!q.data ? (
          <PageSkeleton />
        ) : q.data.length === 0 ? (
          <Empty icon={CreditCard} title="No credit cards">
            Add a credit card on the Accounts page, then describe how it earns rewards here.
          </Empty>
        ) : (
          <div className="space-y-8">
            {q.data.map((c) => (
              <CardSection key={c.accountId} c={c} onEdit={() => setEditing(c)} />
            ))}
            <p className="ink-3 px-1 text-[13px]">
              Estimates from your card's rules as you entered them. Banks exclude some spending and round in their own ways, so the statement has the final number. Refunds on the card take back what they earned; fees and interest never earn.
            </p>
          </div>
        )}
        <Sheet open={!!editing} onClose={() => setEditing(null)} title={editing ? `${editing.account} rewards` : ''}>
          {editing && <RewardForm c={editing} onDone={() => setEditing(null)} />}
        </Sheet>
      </Page>
    </FeatureGate>
  )
}

function CardSection({ c, onEdit }: { c: CardRewards; onEdit: () => void }) {
  const p = c.program
  const title = `${c.account}${c.last4 ? ` ·· ${c.last4}` : ''}`
  if (!p)
    return (
      <Section title={title} className="mt-0">
        <Card className="flex items-center gap-3 p-4">
          <span className="ink-2 flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[var(--surface-2)]">
            <Gift size={20} />
          </span>
          <p className="ink-2 min-w-0 flex-1 text-[14px]">Tell the app how this card earns, and it'll track each cycle.</p>
          <Button size="sm" variant="secondary" onClick={onEdit}>
            Set up
          </Button>
        </Card>
      </Section>
    )
  const now = c.cycles[0]
  return (
    <Section title={title} className="mt-0" action={<button className="link text-[14px] font-medium text-[var(--accent)]" onClick={onEdit}>Edit</button>}>
      {now && (
        <Card className="p-4">
          <p className="ink-2 text-[13px]">
            This {c.statementCycles ? 'cycle' : 'month'} · {shortDate(now.from)} – {shortDate(now.to)}
          </p>
          <p className="display mt-0.5 text-[28px]">
            {earned(p, now.earned)}
            {points(p) && now.value != null && <span className="ink-2 text-[17px] font-normal"> ≈ {moneyAuto(now.value)}</span>}
          </p>
          <p className="ink-2 text-[13px]">
            on {money0(now.spent)} spent{now.capped ? ' · cap reached' : ''}
          </p>
          <Lines p={p} cycle={now} />
        </Card>
      )}

      <div className="mt-3 grid grid-cols-2 gap-2">
        <div className="surface rounded-2xl px-3 py-2.5">
          <p className="ink-2 text-[12px]">Earned this year</p>
          <p className="tabular mt-0.5 text-[17px] font-semibold">{earned(p, c.yearEarned ?? 0)}</p>
          {points(p) && c.yearValue != null && <p className="ink-3 text-[12px]">≈ {moneyAuto(c.yearValue)}</p>}
        </div>
        {points(p) && c.points ? (
          <div className="surface rounded-2xl px-3 py-2.5">
            <p className="ink-2 text-[12px]">Points balance (est.)</p>
            <p className="tabular mt-0.5 text-[17px] font-semibold">{count(c.points.estimate)}</p>
            <p className="ink-3 text-[12px]">
              {count(c.points.balance)} on {shortDate(c.points.asOf)} + {count(c.points.earnedSince)}
            </p>
          </div>
        ) : (
          <div className="surface rounded-2xl px-3 py-2.5">
            <p className="ink-2 text-[12px]">{points(p) ? 'Redeemed this year' : 'Credited this year'}</p>
            <p className="tabular mt-0.5 text-[17px] font-semibold">{moneyAuto(c.yearReceived ?? 0)}</p>
            <p className="ink-3 text-[12px]">{points(p) ? 'Add your balance in Edit' : 'Cashback the bank paid'}</p>
          </div>
        )}
      </div>

      {c.cycles.length > 1 && (
        <List className="mt-3">
          {c.cycles.slice(1).map((y) => (
            <div key={y.from} className="flex items-center gap-3 px-4 py-3">
              <span className="min-w-0 flex-1">
                <span className="block text-[15px]">
                  {shortDate(y.from)} – {shortDate(y.to)}
                </span>
                <span className="ink-2 block text-[13px]">
                  {money0(y.spent)} spent{y.received > 0 ? ` · ${moneyAuto(y.received)} credited` : ''}
                </span>
              </span>
              <span className="tabular shrink-0 text-[15px] font-semibold">{earned(p, y.earned)}</span>
            </div>
          ))}
        </List>
      )}
    </Section>
  )
}

function Lines({ p, cycle }: { p: RewardProgram; cycle: RewardCycle }) {
  const shown = cycle.lines.filter((l) => l.spent !== 0 || l.cap != null)
  if (shown.length <= 1) return null
  return (
    <ul className="mt-3 space-y-2.5 border-t border-[var(--line)] pt-3">
      {shown.map((l) => (
        <li key={l.label}>
          <div className="flex items-baseline justify-between gap-2 text-[13.5px]">
            <span className="min-w-0 truncate">
              {l.label} <span className="ink-3">· {l.rate === 0 ? 'no rewards' : rateText(p, l.rate)}</span>
            </span>
            <span className="tabular shrink-0">
              <b>{earned(p, l.earned)}</b>
              {l.cap != null && <span className="ink-3"> / {earned(p, l.cap)}</span>}
            </span>
          </div>
          {l.cap != null && <Meter pct={(l.earned / l.cap) * 100} className="mt-1" color={l.capped ? 'var(--positive)' : 'var(--ink)'} />}
          <p className="ink-3 text-[12px]">{money0(l.spent)} spent{l.capped ? ' · cap reached, more spending here earns nothing this cycle' : ''}</p>
        </li>
      ))}
    </ul>
  )
}

type Draft = { label: string; by: 'merchant' | 'category'; merchantMatch: string; categoryId: string; rate: string; cycleCap: string }
const toDraft = (r: RewardRule): Draft => ({
  label: r.label,
  by: r.categoryId ? 'category' : 'merchant',
  merchantMatch: r.merchantMatch ?? '',
  categoryId: r.categoryId ? String(r.categoryId) : '',
  rate: String(r.rate),
  cycleCap: r.cycleCap != null ? String(r.cycleCap) : '',
})
const num = (v: string) => (v.trim() === '' ? null : Number(v))
const clean = (v: string) => v.replace(/[^\d.]/g, '')

function RewardForm({ c, onDone }: { c: CardRewards; onDone: () => void }) {
  const cats = useCategories()
  const p = c.program
  const [kind, setKind] = useState<'CASHBACK' | 'POINTS'>(p?.kind ?? 'CASHBACK')
  const [base, setBase] = useState(p ? String(p.baseRate) : '1')
  const [cap, setCap] = useState(p?.cycleCap != null ? String(p.cycleCap) : '')
  const [pointValue, setPointValue] = useState(p?.pointValue != null ? String(p.pointValue) : '')
  const [balance, setBalance] = useState(p?.pointsBalance != null ? String(p.pointsBalance) : '')
  const [asOf, setAsOf] = useState(p?.pointsAsOf ?? ymd())
  const [rules, setRules] = useState<Draft[]>(p?.rules.map(toDraft) ?? [])
  const save = useMoneyMutation(
    () =>
      api(`/rewards/${c.accountId}`, {
        method: 'PUT',
        body: {
          kind,
          baseRate: num(base) ?? 0,
          cycleCap: num(cap),
          pointValue: kind === 'POINTS' ? num(pointValue) : null,
          pointsBalance: kind === 'POINTS' ? num(balance) : null,
          pointsAsOf: kind === 'POINTS' && balance ? asOf : null,
          rules: rules.map((r) => ({
            label: r.label || (r.by === 'category' ? cats.data?.find((x) => String(x.id) === r.categoryId)?.name : r.merchantMatch) || '',
            merchantMatch: r.by === 'merchant' ? r.merchantMatch : null,
            categoryId: r.by === 'category' && r.categoryId ? Number(r.categoryId) : null,
            rate: num(r.rate) ?? 0,
            cycleCap: num(r.cycleCap),
          })),
        },
      }),
    onDone,
  )
  const remove = useMoneyMutation(() => api(`/rewards/${c.accountId}`, { method: 'DELETE' }), onDone)
  const unit = kind === 'POINTS' ? 'points per ₹100' : '% cashback'
  const capUnit = kind === 'POINTS' ? 'points' : '₹'
  const set = (i: number, patch: Partial<Draft>) => setRules((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  const expense = (cats.data ?? []).filter((x) => x.kind === 'EXPENSE')

  return (
    <form
      className="space-y-5"
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate(undefined)
      }}
    >
      <Segmented<'CASHBACK' | 'POINTS'>
        value={kind}
        onChange={setKind}
        options={[
          { value: 'CASHBACK', label: 'Cashback' },
          { value: 'POINTS', label: 'Points' },
        ]}
      />
      <div className="flex gap-2">
        <Field label={`Usual rate (${unit})`}>
          <Input inputMode="decimal" value={base} onChange={(e) => setBase(clean(e.target.value))} required />
        </Field>
        <Field label={`Cap per cycle (${capUnit})`} hint="Optional">
          <Input inputMode="decimal" value={cap} onChange={(e) => setCap(clean(e.target.value))} placeholder="No cap" />
        </Field>
      </div>
      {kind === 'POINTS' && (
        <>
          <Field label="One point is worth (₹)" hint="From the card's redemption catalogue, e.g. 0.20. Optional.">
            <Input inputMode="decimal" value={pointValue} onChange={(e) => setPointValue(clean(e.target.value))} />
          </Field>
          <div className="flex gap-2">
            <Field label="Points balance" hint="From your statement. Optional.">
              <Input inputMode="numeric" value={balance} onChange={(e) => setBalance(clean(e.target.value))} />
            </Field>
            <Field label="As of">
              <DatePicker value={asOf} onChange={setAsOf} max={ymd()} disabled={!balance} label="As of" short />
            </Field>
          </div>
        </>
      )}

      <div>
        <p className="mb-1 px-1 text-[15px] font-semibold">Special rates</p>
        <p className="ink-2 mb-3 px-1 text-[13px]">Checked top to bottom; the first match wins. Use 0 for spending that earns nothing (fuel, rent, wallet loads).</p>
        <div className="space-y-3">
          {rules.map((r, i) => (
            <div key={i} className="surface space-y-3 rounded-2xl p-3">
              <div className="flex items-center gap-2">
                <Segmented<'merchant' | 'category'>
                  className="flex-1"
                  value={r.by}
                  onChange={(by) => set(i, { by })}
                  options={[
                    { value: 'merchant', label: 'Merchant' },
                    { value: 'category', label: 'Category' },
                  ]}
                />
                <button type="button" aria-label="Remove this rate" className="pressable ink-2 flex h-10 w-10 items-center justify-center rounded-full" onClick={() => setRules((rs) => rs.filter((_, j) => j !== i))}>
                  <Trash size={18} />
                </button>
              </div>
              {r.by === 'merchant' ? (
                <Field label="Payee contains" hint="e.g. swiggy, amazon">
                  <Input value={r.merchantMatch} onChange={(e) => set(i, { merchantMatch: e.target.value })} required />
                </Field>
              ) : (
                <Field label="Category">
                  <Select value={r.categoryId} onChange={(e) => set(i, { categoryId: e.target.value })} required>
                    <option value="">Choose…</option>
                    {expense.map((x) => (
                      <option key={x.id} value={x.id}>
                        {x.name}
                      </option>
                    ))}
                  </Select>
                </Field>
              )}
              <div className="flex gap-2">
                <Field label={kind === 'POINTS' ? 'Points / ₹100' : 'Cashback %'}>
                  <Input inputMode="decimal" value={r.rate} onChange={(e) => set(i, { rate: clean(e.target.value) })} required />
                </Field>
                <Field label={`Cap (${capUnit})`}>
                  <Input inputMode="decimal" value={r.cycleCap} onChange={(e) => set(i, { cycleCap: clean(e.target.value) })} placeholder="None" />
                </Field>
              </div>
              <Field label="Name (optional)">
                <Input value={r.label} onChange={(e) => set(i, { label: e.target.value })} placeholder={r.by === 'merchant' ? r.merchantMatch || 'e.g. Swiggy' : 'e.g. Fuel'} />
              </Field>
            </div>
          ))}
        </div>
        <Button type="button" variant="secondary" size="sm" className={cx(rules.length > 0 && 'mt-3')} onClick={() => setRules((rs) => [...rs, { label: '', by: 'merchant', merchantMatch: '', categoryId: '', rate: '', cycleCap: '' }])}>
          <Plus size={16} /> Add a special rate
        </Button>
      </div>

      <ErrorText error={save.error ?? remove.error} />
      <Button type="submit" className="w-full" disabled={save.isPending}>
        {save.isPending ? 'Saving…' : 'Save'}
      </Button>
      {p && (
        <Button type="button" variant="secondary" className="w-full" onClick={() => confirm(`Stop tracking rewards on ${c.account}?`) && remove.mutate(undefined)}>
          Stop tracking rewards
        </Button>
      )}
      <p className="ink-3 px-1 text-[12.5px]">Copy the rates from your card's terms; they change from time to time, so check them now and then.</p>
    </form>
  )
}
