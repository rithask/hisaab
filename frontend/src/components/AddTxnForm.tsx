import { useQuery } from '@tanstack/react-query'
import { ArrowsLeftRight, Bank, HandCoins, NotePencil, Users, UsersThree, CalendarBlank, CaretDown, ClockCounterClockwise, CreditCard, Sparkle, Storefront, Wallet } from '@phosphor-icons/react'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { api, type Txn } from '../api'
import { dateTime, fromLocalInput, money, toLocalInput } from '../format'
import { useAccounts, useCategories, useMoneyMutation, usePeople } from '../hooks'
import { categoryIcon } from '../icons'
import { DateTimePicker } from './DatePicker'
import { BalanceChange, PersonPicker, ShareFields, shareOf, type ShareMode } from './PaidBy'
import { Button, cx, ErrorText, Input, Segmented, Sheet, Avatar, Fold, FieldLabel } from './ui'
import type { Method } from './SplitEditor'

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
 * Adding a transaction is two decisions: how much, and where. The app fills in the rest (account, category,
 * when) as one line of guesses that open only if tapped; a recent payee fills them from last time. The rare
 * extras (someone else paid, splitting, a note, a transfer) are chips that each open their own few fields.
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
  // Which of the guesses (account, category, when) is open for changing; one at a time.
  const [open, setOpen] = useState<'account' | 'category' | 'when' | null>(null)
  const toggle = (k: 'account' | 'category' | 'when') => setOpen((o) => (o === k ? null : k))
  const [noting, setNoting] = useState(false)
  const [splitting, setSplitting] = useState(false)
  const [splitWith, setSplitWith] = useState<number[]>([])
  // how to split while adding: equally (me included), or each person's amount or percent (I keep the rest)
  const [splitMethod, setSplitMethod] = useState<Method>('EQUAL')
  const [splitValues, setSplitValues] = useState<Record<number, string>>({})
  // Lent to someone: the whole payment is theirs, so it's a split without me and needs no category
  const [lending, setLending] = useState(false)
  const [lendTo, setLendTo] = useState<number | ''>('')
  // "Someone else paid": a friend paid; I record my share, with no account of mine
  const people = usePeople()
  const [someone, setSomeone] = useState(false)
  const [personId, setPersonId] = useState<number | ''>('')
  const [shareMode, setShareMode] = useState<ShareMode>('HALF')
  const [exactShare, setExactShare] = useState('')

  const active = (accounts.data ?? []).filter((a) => !a.archived)
  useEffect(() => {
    if (accountId === '' && active.length) setAccountId(active.find((a) => a.type === 'CASH')?.id ?? active[0].id)
  }, [active.length, accountId]) // eslint-disable-line react-hooks/exhaustive-deps

  const kind = direction === 'DEBIT' ? 'EXPENSE' : 'INCOME'
  const kindCats = (cats.data ?? []).filter((c) => c.kind === kind)
  const value = Number(amount)
  const paidByOther = someone && direction === 'DEBIT'
  const share = shareOf(value, shareMode, exactShare)
  const payer = (people.data ?? []).find((p) => p.id === personId)
  const lendNow = lending && !paidByOther && !transfer && direction === 'DEBIT'
  const splitNow = splitting && !paidByOther && !transfer && direction === 'DEBIT' && splitWith.length > 0
  // Each person's share as entered (amounts or percent); checked here so a payment is never saved with a split the server would refuse
  const theirShares = splitWith.map((id) => Number(splitValues[id] || 0))
  const theirTotal =
    splitMethod === 'EXACT' ? theirShares.reduce((a, b) => a + b, 0) : splitMethod === 'PERCENT' ? Math.round(theirShares.reduce((a, b) => a + b, 0) * value) / 100 : 0
  const splitProblem = !splitNow || splitMethod === 'EQUAL'
    ? null
    : theirShares.some((x) => !(x > 0))
      ? splitMethod === 'EXACT' ? 'Enter an amount for each person' : 'Enter a percent for each person'
      : splitMethod === 'PERCENT' && theirShares.reduce((a, b) => a + b, 0) > 100
        ? 'Percentages add up to more than 100%'
        : theirTotal > value
          ? `Shares add up to ${money(theirTotal)}, more than ${money(value)}`
          : null
  const valid = paidByOther ? value > 0 && personId !== '' && share > 0 && share <= value : value > 0 && accountId !== '' && (!lendNow || lendTo !== '') && !splitProblem
  const borrower = (people.data ?? []).find((p) => p.id === lendTo)
  const account = active.find((a) => a.id === accountId)
  const AccountIcon = account?.type === 'CREDIT_CARD' ? CreditCard : account?.type === 'CASH' ? Wallet : Bank
  const category = kindCats.find((c) => c.id === categoryId)
  const CatIcon = category ? categoryIcon(category) : Sparkle

  // People and shops I've paid before (or been paid by): one tap fills payee, category and account.
  const payees = useMemo(() => {
    const seen = new Map<string, Txn>()
    for (const t of recent.data?.items ?? []) {
      if (t.transfer || t.paidBy || t.direction !== direction || !t.counterparty) continue
      const k = t.counterparty.toLowerCase()
      if (!seen.has(k)) seen.set(k, t)
    }
    const q = counterparty.trim().toLowerCase()
    return [...seen.values()].filter((t) => !q || (t.counterparty!.toLowerCase().includes(q) && t.counterparty!.toLowerCase() !== q)).slice(0, 6)
  }, [recent.data, direction, counterparty])

  const pickPayee = (t: Txn) => {
    setCounterparty(t.counterparty!)
    if (t.category) setCategoryId(t.category.id)
    if (t.account && active.some((a) => a.id === t.account!.id) && defaultAccountId == null) setAccountId(t.account.id)
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

  const occurredAt = () => (whenMode === 'custom' ? fromLocalInput(when) : new Date(Date.now() - (whenMode === 'yesterday' ? 864e5 : 0)).toISOString())
  const save = useMoneyMutation(
    () =>
      paidByOther
        ? api('/transactions/paid-by', { body: { personId, total: value, share, categoryId: categoryId || null, where: counterparty, occurredAt: occurredAt(), notes } })
        : api<Txn>('/transactions', {
        body: {
          accountId,
          direction,
          amount: value,
          currency,
          counterparty,
          categoryId: transfer || lendNow ? null : categoryId || null,
          occurredAt: occurredAt(),
          notes,
          transfer,
        },
      }).then((t) =>
        // Split with friends: equally, me included; the split can be changed on the transaction later.
        lendNow
          ? api(`/transactions/${t.id}/split`, { method: 'PUT', body: { method: 'EQUAL', includeMe: false, participants: [{ personId: lendTo, value: null }] } })
          : splitNow
            ? api(`/transactions/${t.id}/split`, {
                method: 'PUT',
                body: { method: splitMethod, includeMe: true, participants: splitWith.map((personId) => ({ personId, value: splitMethod === 'EQUAL' ? null : Number(splitValues[personId] || 0) })) },
              })
            : t,
      ),
    onClose,
  )

  const sign = direction === 'DEBIT' ? '−' : '+'
  const symbol = currency === 'INR' ? '₹' : currency
  return (
    // A column that fills the sheet, so Save sits at the bottom of the screen even when the form is short
    <form
      className="flex flex-1 flex-col"
      onSubmit={(e) => {
        e.preventDefault()
        if (valid) save.mutate(undefined)
      }}
    >
      {/* 1. How much */}
      <Segmented<'DEBIT' | 'CREDIT'>
        value={direction}
        onChange={switchDirection}
        className="mx-auto w-full max-w-[240px]"
        options={[
          { value: 'DEBIT', label: 'Spent' },
          { value: 'CREDIT', label: 'Received' },
        ]}
      />
      <label className={cx('mt-6 flex cursor-text items-baseline justify-center', direction === 'CREDIT' && 'text-positive')}>
        <span className={cx('mr-1.5 text-subheading font-medium', amount ? 'opacity-70' : 'ink-3')} style={{ transform: 'translateY(-0.9em)' }}>
          {symbol}
        </span>
        {/* The hidden twin sizes the input to its content so the symbol hugs the digits. */}
        <span className="display relative inline-grid text-display-lg leading-none">
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
            className="col-start-1 row-start-1 w-full min-w-0 bg-transparent px-0.5 text-center outline-none placeholder:text-ink-3"
          />
        </span>
      </label>
      {paidByOther && <p className="ink-2 mt-1 text-center text-small">What {payer?.name ?? 'they'} paid altogether</p>}
      <div className={cx('mt-2 flex justify-center', paidByOther && 'hidden')}>
        <span className="relative">
          <select
            aria-label="Currency"
            value={currency}
            onChange={(e) => setCurrency(e.target.value)}
            className="ink-2 h-7 appearance-none rounded-full bg-surface-2 pl-3 pr-7 text-micro font-semibold tracking-wide outline-none"
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
        <div className="flex items-center gap-3 rounded-card bg-surface-2 px-4 focus-within:shadow-[0_0_0_4px_var(--accent-soft)]">
          <Storefront size={19} className="ink-3 shrink-0" />
          <input
            value={counterparty}
            onChange={(e) => setCounterparty(e.target.value)}
            placeholder={paidByOther ? 'Where (optional)' : direction === 'DEBIT' ? 'Paid to (optional)' : 'Received from (optional)'}
            aria-label={direction === 'DEBIT' ? 'Paid to' : 'Received from'}
            className="h-[52px] min-w-0 flex-1 bg-transparent text-input outline-none placeholder:text-ink-3"
          />
        </div>
        {payees.length > 0 && (
          <ChipRow className="mt-2.5">
            {payees.map((t) => (
              <button key={t.id} type="button" onClick={() => pickPayee(t)} className="pressable surface flex h-9 shrink-0 items-center gap-1.5 rounded-full pl-2.5 pr-3.5 text-small font-medium">
                <ClockCounterClockwise size={14} className="ink-3" />
                <span className="max-w-[150px] truncate">{t.counterparty}</span>
              </button>
            ))}
          </ChipRow>
        )}
      </div>

      {/* 3. What the app filled in: one line of guesses, each opens only if tapped. */}
      <FieldLabel className="mt-6">{direction === 'DEBIT' ? 'Paid' : 'Received'}</FieldLabel>
      <div className="flex flex-wrap gap-2">
        <Guess on={open === 'account'} onClick={() => toggle('account')}>
          {paidByOther ? <Users size={17} /> : <AccountIcon size={17} />}
          {paidByOther ? (payer ? `By ${payer.name}` : 'By someone else') : (account?.name ?? 'Account')}
        </Guess>
        {!transfer && !lendNow && (
          <Guess on={open === 'category'} onClick={() => toggle('category')}>
            <CatIcon size={17} style={category ? { color: category.color } : undefined} />
            {category?.name ?? (paidByOther ? 'No category' : 'Category: auto')}
          </Guess>
        )}
        <Guess on={open === 'when'} onClick={() => toggle('when')}>
          <CalendarBlank size={17} />
          {whenMode === 'now' ? 'Just now' : whenMode === 'yesterday' ? 'Yesterday' : dateTime(fromLocalInput(when))}
        </Guess>
      </div>
      <Fold open={open === 'account'}>
        <ChipRow className="pt-2.5">
          {active.map((a) => {
            const Icon = a.type === 'CREDIT_CARD' ? CreditCard : a.type === 'CASH' ? Wallet : Bank
            return (
              <Choice key={a.id} on={!paidByOther && a.id === accountId} onClick={() => (setAccountId(a.id), setSomeone(false), setOpen(null))}>
                <Icon size={17} weight={!paidByOther && a.id === accountId ? 'fill' : 'regular'} />
                {a.name}
              </Choice>
            )
          })}
        </ChipRow>
      </Fold>
      <Fold open={open === 'category'}>
        <ChipRow rows={2} className="pt-2.5">
          <Choice on={categoryId === ''} onClick={() => (setCategoryId(''), setOpen(null))}>
            <Sparkle size={17} weight={categoryId === '' ? 'fill' : 'regular'} />
            {paidByOther ? 'None' : 'Auto'}
          </Choice>
          {kindCats.map((c) => {
            const Icon = categoryIcon(c)
            const on = c.id === categoryId
            return (
              <Choice key={c.id} on={on} onClick={() => (setCategoryId(c.id), setOpen(null))}>
                <Icon size={17} weight={on ? 'fill' : 'regular'} style={on ? undefined : { color: c.color }} />
                {c.name}
              </Choice>
            )
          })}
        </ChipRow>
      </Fold>
      <Fold open={open === 'when'}>
        <ChipRow className="pt-2.5">
          <Choice on={whenMode === 'now'} onClick={() => (setWhenMode('now'), setOpen(null))}>
            Just now
          </Choice>
          <Choice on={whenMode === 'yesterday'} onClick={() => (setWhenMode('yesterday'), setOpen(null))}>
            Yesterday
          </Choice>
          <Choice on={whenMode === 'custom'} onClick={() => setWhenMode('custom')}>
            <CalendarBlank size={17} weight={whenMode === 'custom' ? 'fill' : 'regular'} />
            Pick a date
          </Choice>
        </ChipRow>
        {whenMode === 'custom' && (
          <div className="pt-2.5">
            <DateTimePicker value={when} max={toLocalInput()} onChange={setWhen} />
          </div>
        )}
      </Fold>

      {/* 4. The rare extras, as chips: each opens its own few fields. */}
      <FieldLabel className="mt-6">Also</FieldLabel>
      {/* wraps instead of scrolling: only a few, and each should be seen */}
      <ChipRow wrap>
        {direction === 'DEBIT' && (
          <Choice on={someone} onClick={() => (setSomeone(!someone), setSplitting(false), setLending(false), setTransfer(false), setCurrency('INR'), setOpen(null))}>
            <Users size={17} weight={someone ? 'fill' : 'regular'} /> Someone else paid
          </Choice>
        )}
        {direction === 'DEBIT' && !someone && (
          <Choice on={splitting} onClick={() => (setSplitting(!splitting), setLending(false), setTransfer(false))}>
            <UsersThree size={17} weight={splitting ? 'fill' : 'regular'} /> Split with friends
          </Choice>
        )}
        {direction === 'DEBIT' && !someone && (
          <Choice on={lending} onClick={() => (setLending(!lending), setSplitting(false), setTransfer(false), setOpen(null))}>
            <HandCoins size={17} weight={lending ? 'fill' : 'regular'} /> Lent to someone
          </Choice>
        )}
        <Choice on={noting} onClick={() => setNoting(!noting)}>
          <NotePencil size={17} weight={noting ? 'fill' : 'regular'} /> Note
        </Choice>
        {!someone && (
          <Choice on={transfer} onClick={() => (setTransfer(!transfer), setSplitting(false), setLending(false))}>
            <ArrowsLeftRight size={17} weight={transfer ? 'fill' : 'regular'} /> Between my accounts
          </Choice>
        )}
      </ChipRow>
      <Fold open={paidByOther}>
        <div className="space-y-4 pt-4">
          <PersonPicker people={people.data ?? []} value={personId} onChange={setPersonId} />
          <div>
            <FieldLabel>Your share</FieldLabel>
            <ShareFields total={value} mode={shareMode} setMode={setShareMode} exact={exactShare} setExact={setExactShare} />
          </div>
          {payer && share > 0 && share <= value && <BalanceChange person={payer} change={share} />}
        </div>
      </Fold>
      <Fold open={splitting && !paidByOther}>
        <div className="space-y-3 pt-4">
          <PersonPicker multiple people={people.data ?? []} value={splitWith} onChange={setSplitWith} />
          {splitWith.length > 0 && (
            <Segmented<Method>
              value={splitMethod}
              onChange={setSplitMethod}
              options={[
                { value: 'EQUAL', label: 'Equally' },
                { value: 'EXACT', label: 'Amounts' },
                { value: 'PERCENT', label: 'Percent' },
              ]}
            />
          )}
          {splitMethod !== 'EQUAL' &&
            (people.data ?? [])
              .filter((p) => splitWith.includes(p.id))
              .map((p) => (
                <div key={p.id} className="flex items-center gap-3">
                  <Avatar name={p.name} size={32} />
                  <span className="min-w-0 flex-1 truncate text-body">{p.name}</span>
                  <div className="w-28 shrink-0">
                    <Input
                      className="py-2 text-right"
                      inputMode="decimal"
                      placeholder={splitMethod === 'PERCENT' ? '%' : '₹'}
                      aria-label={`${p.name}'s ${splitMethod === 'PERCENT' ? 'percent' : 'amount'}`}
                      value={splitValues[p.id] ?? ''}
                      onChange={(e) => setSplitValues((v) => ({ ...v, [p.id]: e.target.value.replace(/[^\d.]/g, '') }))}
                    />
                  </div>
                </div>
              ))}
          {splitWith.length > 0 && value > 0 && (
            <p className={cx('px-1 text-small', splitProblem ? 'text-negative' : 'ink-2')}>
              {splitProblem ??
                (splitMethod === 'EQUAL'
                  ? `Split equally with you: ${money(Math.floor((value / (splitWith.length + 1)) * 100) / 100)} each. Change it later on the transaction.`
                  : `They owe you ${money(theirTotal)}; your share is ${money(value - theirTotal)}.`)}
            </p>
          )}
        </div>
      </Fold>
      <Fold open={lendNow}>
        <div className="space-y-3 pt-4">
          <PersonPicker people={people.data ?? []} value={lendTo} onChange={setLendTo} />
          {borrower && value > 0 && (
            <p className="ink-2 px-1 text-small">
              {borrower.name} will owe you {money(value)}. Not counted as your spending; when they pay you back, link it to them.
            </p>
          )}
        </div>
      </Fold>
      <Fold open={noting}>
        <div className="pt-2.5">
          <Input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Add a note" aria-label="Notes" autoFocus />
        </div>
      </Fold>

      {/* Always-visible action that reads back what will be saved */}
      <div className="sticky bottom-0 z-10 -mx-5 mt-auto bg-[linear-gradient(to_top,var(--surface)_72%,transparent)] px-5 pb-1 pt-5">
        <ErrorText error={save.error} />
        <Button type="submit" className="mt-2 w-full" disabled={!valid || save.isPending}>
          {save.isPending ? 'Saving…' : valid && paidByOther ? `Save your share ${money(share)}` : valid ? `Save ${sign}${symbol === '₹' ? '₹' : symbol + ' '}${value.toLocaleString('en-IN', { minimumFractionDigits: Number.isInteger(value) ? 0 : 2, maximumFractionDigits: 2 })}${lendNow && borrower ? `, lent to ${borrower.name}` : splitNow ? (splitMethod === 'EQUAL' ? `, split ${splitWith.length + 1} ways` : `, your share ${money(value - theirTotal)}`) : ''}` : lendNow && value > 0 && lendTo === '' ? 'Who did you lend it to?' : value > 0 && splitProblem ? 'Check the split' : 'Enter an amount'}
        </Button>
      </div>
    </form>
  )
}

/** Horizontally scrolling chip strip that bleeds to the sheet edges. */
function ChipRow({ children, className, rows = 1, wrap }: { children: ReactNode; className?: string; rows?: 1 | 2; wrap?: boolean }) {
  if (wrap) return <div className={cx('flex flex-wrap gap-2', className)}>{children}</div>
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
        'pressable flex h-11 shrink-0 items-center gap-2 whitespace-nowrap rounded-full px-4 text-label font-medium transition-colors duration-quick',
        on ? 'bg-ink text-bg' : 'bg-surface-2 text-ink',
      )}
    >
      {children}
    </button>
  )
}

/** One of the app's guesses (account, category, when): shows the choice, tap to change it. */
function Guess({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-expanded={on}
      onClick={onClick}
      className={cx('pressable flex h-11 max-w-full items-center gap-2 rounded-full px-4 text-label font-medium transition-colors duration-quick', on ? 'bg-ink text-bg' : 'bg-surface-2 text-ink')}
    >
      <span className="flex min-w-0 items-center gap-2 truncate">{children}</span>
      <CaretDown size={12} weight="bold" className={cx('shrink-0 transition-transform duration-quick', on ? 'rotate-180' : 'opacity-50')} />
    </button>
  )
}
