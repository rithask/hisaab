import { useQuery } from '@tanstack/react-query'
import { ArrowsLeftRight, Bank, CalendarBlank, GraduationCap, CaretDown, ClockCounterClockwise, CreditCard, Envelope, PencilSimple, Sparkle, Storefront, Trash, Wallet } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { api, ApiError, type RefundWatch, type Split, type TeachResult, type Txn } from '../api'
import { dateTime, fromLocalInput, money, shortDate, toLocalInput, ymd } from '../format'
import { useAccounts, useCategories, useFeature, useMoneyMutation, usePeople } from '../hooks'
import { categoryIcon } from '../icons'
import { DatePicker, DateTimePicker } from './DatePicker'
import TeachForm from './TeachForm'
import { Badge, Button, cx, ErrorText, Field, Input, Segmented, Sheet, SPRING, Textarea, Toggle, BigMoney } from './ui'

export default function TxnSheet({ txn, onClose }: { txn: Txn | null; onClose: () => void }) {
  return (
    <Sheet open={!!txn} onClose={onClose} title="Transaction">
      {txn && <TxnDetail key={txn.id} txn={txn} onClose={onClose} />}
    </Sheet>
  )
}

function TxnDetail({ txn, onClose }: { txn: Txn; onClose: () => void }) {
  const cats = useCategories()
  const [t, setT] = useState(txn)
  const [notes, setNotes] = useState(txn.notes ?? '')
  const [tags, setTags] = useState(txn.tags.join(', '))
  const [similar, setSimilar] = useState(0)
  // What the last category choice taught the app, shown under the category grid.
  const [learned, setLearned] = useState<{ payee?: string; category: string; applied?: number } | null>(null)
  const [showEmail, setShowEmail] = useState(false)
  const [renaming, setRenaming] = useState(false)
  const [name, setName] = useState(txn.counterparty ?? '')
  const [renamed, setRenamed] = useState<{ to: string; others: number } | null>(null)
  // Fixing an alert that was read wrong teaches the parser its format
  const [fixing, setFixing] = useState(false)
  const [fixed, setFixed] = useState<TeachResult | null>(null)
  const fromAlert = (t.source === 'EMAIL' || t.source === 'SMS') && !!t.rawEmailId
  const onFixed = async (r: TeachResult) => {
    setFixing(false)
    setFixed(r)
    setT(await api<Txn>(`/transactions/${r.txnId ?? t.id}`))
  }

  const update = useMoneyMutation(
    (body: Record<string, unknown>) => api<{ txn: Txn; similarCount: number; renamedCount: number }>(`/transactions/${t.id}`, { method: 'PATCH', body }),
    (r, body) => {
      setT(r.txn)
      setSimilar(r.similarCount)
      if (typeof body.categoryId === 'number' && r.txn.category) setLearned({ payee: r.txn.counterparty ?? undefined, category: r.txn.category.name })
      if (typeof body.counterparty === 'string') {
        setRenaming(false)
        // Alerts teach the parser; hand-entered transactions are just renamed.
        setRenamed(r.txn.source === 'EMAIL' || r.txn.source === 'SMS' ? { to: body.counterparty, others: r.renamedCount } : null)
      }
    },
  )
  const applySimilar = useMoneyMutation(
    () => api<{ updated: number }>(`/transactions/${t.id}/apply-to-similar`, { method: 'POST' }),
    (r) => {
      setSimilar(0)
      setLearned((l) => (l ? { ...l, applied: r.updated } : l))
    },
  )
  const del = useMoneyMutation(() => api(`/transactions/${t.id}`, { method: 'DELETE' }), onClose)
  const email = useQuery({
    queryKey: ['email', t.rawEmailId],
    queryFn: () => api<{ bodyText?: string; subject?: string }>(`/emails/${t.rawEmailId}`),
    enabled: showEmail && !!t.rawEmailId,
  })

  const kind = t.direction === 'DEBIT' ? 'EXPENSE' : 'INCOME'
  const options = (cats.data ?? []).filter((c) => c.kind === kind)

  return (
    <div className="space-y-5">
      <div className="text-center">
        <span className={cx('inline-flex items-baseline', t.transfer ? 'ink-3' : t.direction === 'CREDIT' ? 'text-[var(--positive)]' : '')}>
          <span className="display mr-1 text-[40px] leading-none">{t.direction === 'CREDIT' ? '+' : '−'}</span>
          <BigMoney value={t.amount} className="text-[56px] leading-none" />
        </span>
        {t.amountEstimated && (
          <p className="mt-1 text-xs text-amber-600">
            Estimated from {t.originalCurrency} {t.originalAmount}. Correct it once the statement arrives.
          </p>
        )}
        {renaming ? (
          <form
            className="mx-auto mt-3 flex max-w-xs items-center gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              if (name.trim() && name.trim() !== t.counterparty) update.mutate({ counterparty: name.trim() })
              else setRenaming(false)
            }}
          >
            <Input autoFocus value={name} onChange={(e) => setName(e.target.value)} aria-label="Payee name" className="h-11 py-2 text-center" />
            <Button type="submit" size="sm" disabled={update.isPending}>
              Save
            </Button>
          </form>
        ) : (
          <button className="pressable mx-auto mt-3 flex items-center gap-1.5 rounded-full px-2 text-[17px] font-semibold" onClick={() => (setName(t.counterparty ?? ''), setRenaming(true))} aria-label="Rename payee">
            {t.counterparty ?? t.description ?? 'Add a name'}
            <PencilSimple size={14} className="ink-3" />
          </button>
        )}
        {renamed && (
          <p className="anim-pop mx-auto mt-2 max-w-xs rounded-2xl bg-[var(--accent-soft)] px-3 py-2 text-xs text-[var(--accent)]">
            Learned. Future alerts from this payee will be named <b>{renamed.to}</b>
            {renamed.others > 0 ? `, and ${renamed.others} earlier one${renamed.others > 1 ? 's were' : ' was'} renamed too` : ''}.
          </p>
        )}
        <p className="ink-2 text-sm">
          {t.account.name} · {dateTime(t.occurredAt)}
        </p>
        <div className="mt-2 flex flex-wrap justify-center gap-1.5">
          <Badge>{t.source === 'EMAIL' ? 'From email' : t.source === 'SMS' ? 'From SMS' : t.source === 'STATEMENT' ? 'From statement' : t.source === 'ADJUSTMENT' ? 'Adjustment' : 'Manual'}</Badge>
          {t.referenceNo && <Badge>Ref {t.referenceNo}</Badge>}
          {t.reportedBalance != null && <Badge tone="blue">Bal {money(t.reportedBalance)}</Badge>}
        </div>
      </div>

      {fromAlert && !fixed && (
        <button className="pressable ink-2 mx-auto -mt-2 flex items-center gap-1.5 rounded-full px-3 py-1.5 text-[13px] font-medium" onClick={() => setFixing(true)}>
          <GraduationCap size={15} /> Wrong amount, direction or account? Fix it
        </button>
      )}
      {fixed && (
        <p className="anim-pop -mt-2 rounded-2xl bg-[var(--accent-soft)] px-3 py-2 text-center text-xs text-[var(--accent)]">
          Fixed.{' '}
          {fixed.learnedFormat
            ? 'Learned from it, so the next message like this is read right.'
            : fixed.conflicts > 0
              ? `Not learned as a format: it would change how ${fixed.conflicts} message${fixed.conflicts > 1 ? 's' : ''} you taught earlier ${fixed.conflicts > 1 ? 'are' : 'is'} read.`
              : ''}
        </p>
      )}
      <Sheet open={fixing} onClose={() => setFixing(false)} title="Fix what it read">
        {fixing && t.rawEmailId && <TeachForm id={t.rawEmailId} onDone={onFixed} />}
      </Sheet>

      {t.transfer ? (
        <div className="flex items-start gap-3 rounded-2xl bg-[var(--surface-2)] p-4 text-sm">
          <ArrowsLeftRight size={18} className="mt-0.5 shrink-0" />
          <div className="flex-1">
            <p className="font-medium">Transfer between my accounts</p>
            <p className="ink-2">
              {t.linkedAccount ? `Matched with ${t.linkedAccount.name}. ` : ''}Not counted as income or spending.
            </p>
            <button className="link mt-2 font-semibold text-[var(--accent)]" onClick={() => update.mutate({ transfer: false })}>
              This isn't a transfer
            </button>
          </div>
        </div>
      ) : (
        <div>
          <p className="ink-2 mb-2 px-1 text-[13px] font-medium">Category</p>
          <div className="grid grid-cols-3 gap-2">
            {options.map((c) => {
              const Icon = categoryIcon(c)
              const on = t.category?.id === c.id
              return (
                <button
                  key={c.id}
                  onClick={() => update.mutate({ categoryId: c.id })}
                  className={cx(
                    'pressable flex flex-col items-center gap-1.5 rounded-2xl px-1 py-2.5 text-[12px] font-medium leading-tight transition-colors duration-150',
                    on ? 'bg-[var(--ink)] text-[var(--surface)]' : 'bg-[var(--surface-2)] text-[var(--ink-2)]',
                  )}
                >
                  <Icon size={20} weight={on ? 'fill' : 'regular'} style={on ? undefined : { color: c.color }} />
                  <span className="line-clamp-2 text-center">{c.name}</span>
                </button>
              )
            })}
          </div>
          {learned && (
            <div className="anim-pop mt-3 rounded-2xl bg-[var(--accent-soft)] p-3 text-sm">
              {learned.payee ? (
                <p>
                  Learned: future payments {t.direction === 'CREDIT' ? 'from' : 'to'} <b>{learned.payee}</b> will be <b>{learned.category}</b>.
                  {learned.applied != null && ` ${learned.applied} earlier one${learned.applied === 1 ? ' was' : 's were'} updated too.`}
                </p>
              ) : (
                <p>Saved. This one has no payee name, so the app can't recognise it next time. Tap the name at the top to add one.</p>
              )}
              {similar > 0 && (
                <div className="mt-2 flex items-center justify-between gap-3">
                  <span className="ink-2">
                    {similar} earlier one{similar > 1 ? 's have' : ' has'} a different or no category.
                  </span>
                  <Button size="sm" className="shrink-0" onClick={() => applySimilar.mutate(undefined)} disabled={applySimilar.isPending}>
                    Update {similar > 1 ? 'them' : 'it'}
                  </Button>
                </div>
              )}
            </div>
          )}
          <button className="ink-2 mt-3 px-1 text-sm font-medium" onClick={() => update.mutate({ transfer: true })}>
            Mark as transfer between my accounts
          </button>
        </div>
      )}

      {t.direction === 'DEBIT' && !t.transfer && <SplitEditor txn={t} />}
      {t.direction === 'DEBIT' && !t.transfer && <RefundWatcher txnId={t.id} />}

      <div className="grid gap-3">
        <Field label="Tags" hint="Comma separated, e.g. trip-goa, office">
          <Input value={tags} onChange={(e) => setTags(e.target.value)} onBlur={() => tags !== t.tags.join(', ') && update.mutate({ tags: tags.split(',') })} />
        </Field>
        <Field label="Notes">
          <Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} onBlur={() => notes !== (t.notes ?? '') && update.mutate({ notes })} />
        </Field>
      </div>

      <ErrorText error={update.error ?? del.error} />

      {t.rawEmailId && (
        <div>
          <Button variant="secondary" size="sm" onClick={() => setShowEmail((s) => !s)}>
            <Envelope size={15} /> {showEmail ? 'Hide' : 'Show'} original email
          </Button>
          {showEmail && (
            <pre className="anim-pop mt-2 max-h-60 overflow-auto whitespace-pre-wrap rounded-[18px] bg-[var(--surface-2)] p-3 font-mono text-[11px] leading-relaxed">
              {email.data ? `${email.data.subject ?? ''}\n\n${email.data.bodyText ?? ''}` : 'Loading…'}
            </pre>
          )}
        </div>
      )}

      <Button variant="danger" className="w-full" onClick={() => confirm('Delete this transaction?') && del.mutate(undefined)}>
        <Trash size={16} /> Delete
      </Button>
    </div>
  )
}

type Method = 'EQUAL' | 'EXACT' | 'PERCENT'

function SplitEditor({ txn }: { txn: Txn }) {
  const people = usePeople()
  const split = useQuery({ queryKey: ['split', txn.id], queryFn: () => api<Split>(`/transactions/${txn.id}/split`) })
  const [editing, setEditing] = useState(false)
  const [method, setMethod] = useState<Method>('EQUAL')
  const [includeMe, setIncludeMe] = useState(true)
  const [values, setValues] = useState<Record<number, string>>({})

  useEffect(() => {
    if (!split.data || split.data.shares.length === 0) return
    const m = split.data.shares[0].method
    setMethod(m)
    setValues(Object.fromEntries(split.data.shares.map((s) => [s.personId, m === 'EQUAL' ? '' : String(s.input ?? s.amount)])))
  }, [split.data])

  const save = useMoneyMutation(
    () =>
      api<Split>(`/transactions/${txn.id}/split`, {
        method: 'PUT',
        body: {
          method,
          includeMe,
          participants: Object.entries(values).map(([id, v]) => ({ personId: Number(id), value: method === 'EQUAL' ? null : Number(v || 0) })),
        },
      }),
    () => setEditing(false),
  )

  const selected = Object.keys(values).map(Number)
  const toggle = (id: number) =>
    setValues((v) => {
      const n = { ...v }
      if (id in n) delete n[id]
      else n[id] = ''
      return n
    })

  const preview = (() => {
    const n = selected.length + (includeMe ? 1 : 0)
    if (method === 'EQUAL' && n > 0) return `${money(Math.floor((txn.amount / n) * 100) / 100)} each`
    if (method === 'PERCENT') return `${selected.reduce((s, id) => s + Number(values[id] || 0), 0)}% assigned`
    if (method === 'EXACT') return `${money(selected.reduce((s, id) => s + Number(values[id] || 0), 0))} of ${money(txn.amount)} assigned`
    return ''
  })()

  if (!editing) {
    const s = split.data
    return (
      <div className="rounded-2xl bg-[var(--surface-2)] p-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-[15px] font-semibold">Split with others</p>
            {!s?.shares.length && <p className="ink-2 text-xs">Paid for a group? Track who owes you.</p>}
          </div>
          <Button variant="primary" size="sm" onClick={() => setEditing(true)}>
            {s?.shares.length ? 'Edit' : 'Split'}
          </Button>
        </div>
        {s && s.shares.length > 0 && (
          <div className="mt-3 space-y-2 text-sm">
            {s.shares.map((x) => (
              <div key={x.id} className="flex items-center justify-between">
                <span>{x.personName} owes</span>
                <span className="tabular font-semibold">{money(x.amount)}</span>
              </div>
            ))}
            <div className="ink-2 flex justify-between border-t border-[var(--line-strong)] pt-2">
              <span>My share</span>
              <span className="tabular">{money(s.myShare)}</span>
            </div>
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="anim-pop space-y-3 rounded-2xl bg-[var(--surface-2)] p-4">
      <Segmented<Method>
        value={method}
        onChange={setMethod}
        className="bg-[var(--surface)]"
        options={[
          { value: 'EQUAL', label: 'Equally' },
          { value: 'EXACT', label: 'Amounts' },
          { value: 'PERCENT', label: 'Percent' },
        ]}
      />
      {method === 'EQUAL' && <Toggle checked={includeMe} onChange={setIncludeMe} label="Include me in the split" />}
      {(people.data ?? []).length === 0 && <p className="ink-2 text-sm">Add people under the Splits tab first.</p>}
      <div className="space-y-1">
        {(people.data ?? []).map((p) => (
          <div key={p.id} className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => toggle(p.id)}
              className={cx('pressable flex flex-1 items-center gap-3 rounded-2xl px-2 py-2 text-left text-[15px]', p.id in values && 'font-semibold')}
            >
              <span className={cx('flex h-6 w-6 items-center justify-center rounded-full border-2 text-[11px] transition-colors duration-150', p.id in values ? 'border-[var(--ink)] accent-fill' : 'border-[var(--line-strong)]')}>
                {p.id in values && '✓'}
              </span>
              {p.name}
            </button>
            {method !== 'EQUAL' && p.id in values && (
              <Input
                className="w-28 bg-[var(--surface)] py-2 text-right"
                inputMode="decimal"
                placeholder={method === 'PERCENT' ? '%' : '₹'}
                value={values[p.id]}
                onChange={(e) => setValues((v) => ({ ...v, [p.id]: e.target.value }))}
              />
            )}
          </div>
        ))}
      </div>
      <p className="ink-2 px-1 text-xs font-medium">{preview}</p>
      <ErrorText error={save.error} />
      <div className="flex gap-2">
        <Button variant="secondary" className="flex-1" onClick={() => setEditing(false)}>
          Cancel
        </Button>
        <Button className="flex-1" onClick={() => save.mutate(undefined)} disabled={save.isPending}>
          Save split
        </Button>
      </div>
    </div>
  )
}

export function AddTxnSheet({ open, onClose, defaultAccountId }: { open: boolean; onClose: () => void; defaultAccountId?: number }) {
  return (
    <Sheet open={open} onClose={onClose} title="Add transaction">
      {open && <AddTxnForm onClose={onClose} defaultAccountId={defaultAccountId} />}
    </Sheet>
  )
}

const CURRENCIES = ['INR', 'USD', 'EUR', 'GBP', 'AED', 'SGD']
type WhenMode = 'now' | 'yesterday' | 'custom'

/**
 * Adding a transaction is three decisions: how much, which account, what for. Those are all one tap
 * (amount field, chips), recent payees fill everything in at once, and the rare options stay folded away.
 */
function AddTxnForm({ onClose, defaultAccountId }: { onClose: () => void; defaultAccountId?: number }) {
  const accounts = useAccounts()
  const cats = useCategories()
  const recent = useQuery({ queryKey: ['transactions', 'payees'], queryFn: () => api<{ items: Txn[] }>('/transactions?size=40') })
  const [direction, setDirection] = useState<'DEBIT' | 'CREDIT'>('DEBIT')
  const [accountId, setAccountId] = useState<number | ''>(defaultAccountId ?? '')
  const [amount, setAmount] = useState('')
  const [currency, setCurrency] = useState('INR')
  const [counterparty, setCounterparty] = useState('')
  const [categoryId, setCategoryId] = useState<number | ''>('')
  const [whenMode, setWhenMode] = useState<WhenMode>('now')
  const [when, setWhen] = useState(toLocalInput())
  const [notes, setNotes] = useState('')
  const [transfer, setTransfer] = useState(false)
  const [more, setMore] = useState(false)

  const active = (accounts.data ?? []).filter((a) => !a.archived)
  useEffect(() => {
    if (accountId === '' && active.length) setAccountId(active.find((a) => a.type === 'CASH')?.id ?? active[0].id)
  }, [active.length, accountId]) // eslint-disable-line react-hooks/exhaustive-deps

  const kind = direction === 'DEBIT' ? 'EXPENSE' : 'INCOME'
  const kindCats = (cats.data ?? []).filter((c) => c.kind === kind)
  const value = Number(amount)
  const valid = value > 0 && accountId !== ''

  // People and shops I've paid before (or been paid by): one tap fills payee, category and account.
  const payees = useMemo(() => {
    const seen = new Map<string, Txn>()
    for (const t of recent.data?.items ?? []) {
      if (t.transfer || t.direction !== direction || !t.counterparty) continue
      const k = t.counterparty.toLowerCase()
      if (!seen.has(k)) seen.set(k, t)
    }
    const q = counterparty.trim().toLowerCase()
    return [...seen.values()].filter((t) => !q || (t.counterparty!.toLowerCase().includes(q) && t.counterparty!.toLowerCase() !== q)).slice(0, 6)
  }, [recent.data, direction, counterparty])

  const pickPayee = (t: Txn) => {
    setCounterparty(t.counterparty!)
    if (t.category) setCategoryId(t.category.id)
    if (active.some((a) => a.id === t.account.id) && defaultAccountId == null) setAccountId(t.account.id)
  }
  const switchDirection = (d: 'DEBIT' | 'CREDIT') => {
    setDirection(d)
    setCategoryId('')
  }
  const typeAmount = (raw: string) => {
    let v = raw.replace(/[^\d.]/g, '')
    const dot = v.indexOf('.')
    if (dot >= 0) v = v.slice(0, dot + 1) + v.slice(dot + 1).replace(/\./g, '').slice(0, 2)
    setAmount(v.replace(/^0+(?=\d)/, '').slice(0, 12))
  }

  const save = useMoneyMutation(
    () =>
      api('/transactions', {
        body: {
          accountId,
          direction,
          amount: value,
          currency,
          counterparty,
          categoryId: transfer ? null : categoryId || null,
          occurredAt: whenMode === 'custom' ? fromLocalInput(when) : new Date(Date.now() - (whenMode === 'yesterday' ? 864e5 : 0)).toISOString(),
          notes,
          transfer,
        },
      }),
    onClose,
  )

  const sign = direction === 'DEBIT' ? '−' : '+'
  const symbol = currency === 'INR' ? '₹' : currency
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        if (valid) save.mutate(undefined)
      }}
    >
      {/* 1. How much */}
      <Segmented<'DEBIT' | 'CREDIT'>
        value={direction}
        onChange={switchDirection}
        className="mx-auto max-w-[240px]"
        options={[
          { value: 'DEBIT', label: 'Spent' },
          { value: 'CREDIT', label: 'Received' },
        ]}
      />
      <label className={cx('mt-6 flex cursor-text items-baseline justify-center', direction === 'CREDIT' && 'text-[var(--positive)]')}>
        <span className={cx('mr-1.5 text-[24px] font-medium', amount ? 'opacity-70' : 'ink-3')} style={{ transform: 'translateY(-0.9em)' }}>
          {symbol}
        </span>
        {/* The hidden twin sizes the input to its content so the symbol hugs the digits. */}
        <span className="display relative inline-grid text-[72px] leading-none">
          <span aria-hidden className="invisible col-start-1 row-start-1 whitespace-pre px-0.5">
            {amount || '0'}
          </span>
          <input
            inputMode="decimal"
            size={1}
            autoFocus
            value={amount}
            onChange={(e) => typeAmount(e.target.value)}
            placeholder="0"
            aria-label="Amount"
            className="col-start-1 row-start-1 w-full min-w-0 bg-transparent px-0.5 text-center outline-none placeholder:text-[var(--ink-3)]"
          />
        </span>
      </label>
      <div className="mt-2 flex justify-center">
        <span className="relative">
          <select
            aria-label="Currency"
            value={currency}
            onChange={(e) => setCurrency(e.target.value)}
            className="ink-2 h-7 appearance-none rounded-full bg-[var(--surface-2)] pl-3 pr-7 text-[11.5px] font-semibold tracking-wide outline-none"
          >
            {CURRENCIES.map((c) => (
              <option key={c}>{c}</option>
            ))}
          </select>
          <CaretDown size={11} weight="bold" className="ink-3 pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2" />
        </span>
      </div>

      {/* 2. Who */}
      <div className="mt-7">
        <div className="flex items-center gap-3 rounded-[20px] bg-[var(--surface-2)] px-4 focus-within:shadow-[0_0_0_4px_var(--accent-soft)]">
          <Storefront size={19} className="ink-3 shrink-0" />
          <input
            value={counterparty}
            onChange={(e) => setCounterparty(e.target.value)}
            placeholder={direction === 'DEBIT' ? 'Paid to (optional)' : 'Received from (optional)'}
            aria-label={direction === 'DEBIT' ? 'Paid to' : 'Received from'}
            className="h-[52px] min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-[var(--ink-3)]"
          />
        </div>
        {payees.length > 0 && (
          <ChipRow className="mt-2.5">
            {payees.map((t) => (
              <button key={t.id} type="button" onClick={() => pickPayee(t)} className="pressable surface flex h-9 shrink-0 items-center gap-1.5 rounded-full pl-2.5 pr-3.5 text-[13px] font-medium">
                <ClockCounterClockwise size={14} className="ink-3" />
                <span className="max-w-[150px] truncate">{t.counterparty}</span>
              </button>
            ))}
          </ChipRow>
        )}
      </div>

      {/* 3. Which account */}
      <Label>{direction === 'DEBIT' ? 'Paid from' : 'Into'}</Label>
      <ChipRow>
        {active.map((a) => {
          const Icon = a.type === 'CREDIT_CARD' ? CreditCard : a.type === 'CASH' ? Wallet : Bank
          return (
            <Choice key={a.id} on={a.id === accountId} onClick={() => setAccountId(a.id)}>
              <Icon size={17} weight={a.id === accountId ? 'fill' : 'regular'} />
              {a.name}
            </Choice>
          )
        })}
      </ChipRow>

      {/* 4. What for */}
      {!transfer && (
        <>
          <Label>Category</Label>
          <ChipRow rows={2}>
            <Choice on={categoryId === ''} onClick={() => setCategoryId('')}>
              <Sparkle size={17} weight={categoryId === '' ? 'fill' : 'regular'} />
              Auto
            </Choice>
            {kindCats.map((c) => {
              const Icon = categoryIcon(c)
              const on = c.id === categoryId
              return (
                <Choice key={c.id} on={on} onClick={() => setCategoryId(c.id)}>
                  <Icon size={17} weight={on ? 'fill' : 'regular'} style={on ? undefined : { color: c.color }} />
                  {c.name}
                </Choice>
              )
            })}
          </ChipRow>
        </>
      )}

      {/* 5. When */}
      <Label>When</Label>
      <ChipRow>
        <Choice on={whenMode === 'now'} onClick={() => setWhenMode('now')}>
          Just now
        </Choice>
        <Choice on={whenMode === 'yesterday'} onClick={() => setWhenMode('yesterday')}>
          Yesterday
        </Choice>
        <Choice on={whenMode === 'custom'} onClick={() => setWhenMode('custom')}>
          <CalendarBlank size={17} weight={whenMode === 'custom' ? 'fill' : 'regular'} />
          {whenMode === 'custom' ? dateTime(fromLocalInput(when)) : 'Pick a date'}
        </Choice>
      </ChipRow>
      <Fold open={whenMode === 'custom'}>
        <div className="pt-2.5">
          <DateTimePicker value={when} max={toLocalInput()} onChange={setWhen} />
        </div>
      </Fold>

      {/* The rest, folded away */}
      <button type="button" onClick={() => setMore(!more)} aria-expanded={more} className="ink-2 mt-5 flex w-full items-center justify-between rounded-2xl px-1 py-2 text-[13px] font-medium">
        <span>
          More options
          {(notes || transfer) && !more ? <span className="ml-2 text-[var(--accent)]">{[notes && 'note', transfer && 'transfer'].filter(Boolean).join(' · ')}</span> : null}
        </span>
        <motion.span animate={{ rotate: more ? 180 : 0 }} transition={SPRING}>
          <CaretDown size={14} weight="bold" />
        </motion.span>
      </button>
      <Fold open={more}>
        <div className="space-y-2.5 pt-1">
          <Input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Add a note" aria-label="Notes" />
          <div className="rounded-2xl bg-[var(--surface-2)] px-4 py-2.5">
            <Toggle checked={transfer} onChange={setTransfer} label="Transfer between my own accounts" />
          </div>
        </div>
      </Fold>

      {/* Always-visible action that reads back what will be saved */}
      <div className="sticky bottom-0 z-10 -mx-5 mt-4 bg-[linear-gradient(to_top,var(--surface)_72%,transparent)] px-5 pb-1 pt-5">
        <ErrorText error={save.error} />
        <Button type="submit" className="mt-2 w-full" disabled={!valid || save.isPending}>
          {save.isPending ? 'Saving…' : valid ? `Save ${sign}${symbol === '₹' ? '₹' : symbol + ' '}${value.toLocaleString('en-IN', { minimumFractionDigits: Number.isInteger(value) ? 0 : 2, maximumFractionDigits: 2 })}` : 'Enter an amount'}
        </Button>
      </div>
    </form>
  )
}

function Label({ children }: { children: ReactNode }) {
  return <p className="eyebrow ink-3 mb-2.5 mt-6 px-1">{children}</p>
}

/** Horizontally scrolling chip strip that bleeds to the sheet edges. */
function ChipRow({ children, className, rows = 1 }: { children: ReactNode; className?: string; rows?: 1 | 2 }) {
  return (
    <div className={cx('no-scrollbar -mx-5 overflow-x-auto px-5', className)}>
      <div className={cx('w-max gap-2', rows === 2 ? 'grid grid-flow-col grid-rows-2 justify-items-start' : 'flex')}>{children}</div>
    </div>
  )
}

function Choice({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  const ref = useRef<HTMLButtonElement>(null)
  // A chip selected for me (by a recent payee, or the default account) must never sit off-screen.
  useEffect(() => {
    const el = ref.current
    const strip = el?.closest('.overflow-x-auto')
    if (!on || !el || !strip) return
    const s = strip.getBoundingClientRect()
    const r = el.getBoundingClientRect()
    if (r.left < s.left + 16 || r.right > s.right - 16) strip.scrollBy({ left: r.left + r.width / 2 - (s.left + s.width / 2), behavior: 'smooth' })
  }, [on])
  return (
    <button
      ref={ref}
      type="button"
      aria-pressed={on}
      onClick={onClick}
      className={cx(
        'pressable flex h-11 shrink-0 items-center gap-2 whitespace-nowrap rounded-full px-4 text-[14px] font-medium transition-colors duration-150',
        on ? 'bg-[var(--ink)] text-[var(--bg)]' : 'bg-[var(--surface-2)] text-[var(--ink)]',
      )}
    >
      {children}
    </button>
  )
}

/** Smoothly opens and closes extra fields. */
function Fold({ open, children }: { open: boolean; children: ReactNode }) {
  return (
    <AnimatePresence initial={false}>
      {open && (
        <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }} className="overflow-hidden">
          {children}
        </motion.div>
      )}
    </AnimatePresence>
  )
}

/**
 * "I'm expecting money back" for a payment (a return, a cancelled order). Unusual activity warns if it hasn't
 * arrived by the date; it's marked arrived when a matching credit shows up. Only with Unusual activity on.
 */
function RefundWatcher({ txnId }: { txnId: number }) {
  const on = useFeature('unusual')
  const q = useQuery({
    queryKey: ['refund', txnId],
    queryFn: () => api<RefundWatch>(`/refunds/${txnId}`).catch((e) => (e instanceof ApiError && e.status === 404 ? null : Promise.reject(e))),
    enabled: on === true,
  })
  const [by, setBy] = useState(() => ymd(new Date(Date.now() + 14 * 864e5)))
  const [editing, setEditing] = useState(false)
  const start = useMoneyMutation(() => api('/refunds', { body: { txnId, expectedBy: by } }), () => setEditing(false))
  const stop = useMoneyMutation(() => api(`/refunds/${txnId}`, { method: 'DELETE' }))
  if (on !== true || q.data === undefined) return null
  const r = q.data
  const today = ymd()

  if (!r && !editing)
    return (
      <button className="ink-2 mt-3 block px-1 text-sm font-medium" onClick={() => setEditing(true)}>
        I'm expecting a refund for this
      </button>
    )
  return (
    <div className="surface mt-4 rounded-2xl p-4">
      {r && !editing ? (
        <>
          <div className="flex items-center justify-between gap-2">
            <p className="font-medium">Refund</p>
            <Badge tone={r.status === 'ARRIVED' ? 'green' : r.status === 'LATE' ? 'red' : 'amber'}>
              {r.status === 'ARRIVED' ? 'arrived' : r.status === 'LATE' ? 'late' : 'waiting'}
            </Badge>
          </div>
          <p className="ink-2 mt-1 text-[13px]">
            {r.status === 'ARRIVED'
              ? `${money(r.refunded ?? 0)} came back on ${shortDate(r.refundedAt!)}.`
              : r.status === 'LATE'
                ? `Expected by ${shortDate(r.expectedBy)}. Worth chasing up.`
                : `Expected by ${shortDate(r.expectedBy)}. You'll be told if it doesn't arrive.`}
          </p>
          <div className="mt-2 flex gap-4 text-[13px]">
            {r.status !== 'ARRIVED' && (
              <button className="link font-medium text-[var(--accent)]" onClick={() => (setBy(r.expectedBy < today ? today : r.expectedBy), setEditing(true))}>
                Change date
              </button>
            )}
            <button className="link ink-2 font-medium" onClick={() => stop.mutate(undefined)}>
              {r.status === 'ARRIVED' ? 'Remove' : 'Stop waiting'}
            </button>
          </div>
        </>
      ) : (
        <>
          <Field label="Expect the money back by">
            <DatePicker value={by} min={today} onChange={setBy} label="Expect the money back by" />
          </Field>
          <ErrorText error={start.error} />
          <div className="mt-3 flex gap-2">
            <Button size="sm" disabled={!by || start.isPending} onClick={() => start.mutate(undefined)}>
              Watch for it
            </Button>
            <Button size="sm" variant="secondary" onClick={() => setEditing(false)}>
              Cancel
            </Button>
          </div>
        </>
      )}
    </div>
  )
}

/** Opens the transaction sheet for an id (from an alert or a list that doesn't carry the whole transaction). */
export function TxnSheetById({ id, onClose }: { id: number | null; onClose: () => void }) {
  const q = useQuery({ queryKey: ['transaction', id], queryFn: () => api<Txn>(`/transactions/${id}`), enabled: id != null })
  return <TxnSheet txn={id != null ? (q.data ?? null) : null} onClose={onClose} />
}
