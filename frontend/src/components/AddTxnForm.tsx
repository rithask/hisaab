import { useQuery } from '@tanstack/react-query'
import { ArrowCounterClockwise, Bank, NotePencil, Users, UsersThree, CalendarBlank, CaretDown, ClockCounterClockwise, CreditCard, Sparkle, Storefront, Wallet } from '@phosphor-icons/react'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { api, type Txn } from '../api'
import { dateTime, firstName, fromLocalInput, money, money0, toLocalInput } from '../format'
import { useAccounts, useCategories, useMoneyMutation, usePeople } from '../hooks'
import { categoryIcon } from '../icons'
import { BalanceChange } from './PaidBy'
import { NOT_SHARED, outcomeOf, rememberShared, SharedPicker, type Outcome, type Shared } from './SharedPicker'
import { Button, cx, Dropdown, ErrorText, Input, pill, Segmented, Sheet, Fold, type DropdownOption } from './ui'

export function AddTxnSheet({ open, onClose, defaultAccountId }: { open: boolean; onClose: () => void; defaultAccountId?: number }) {
  return (
    <Sheet open={open} onClose={onClose} title="Add transaction" compact>
      {open && <AddTxnForm onClose={onClose} defaultAccountId={defaultAccountId} />}
    </Sheet>
  )
}

const CURRENCIES = ['INR', 'USD', 'EUR', 'GBP', 'AED', 'SGD']
type Kind = 'DEBIT' | 'CREDIT' | 'TRANSFER'

/**
 * Adding a transaction is two decisions: what kind (spent, received, a transfer) and how much. The app fills in the
 * rest as one row of guesses, each a tap to change: the account and category (dropdowns) and when (the phone's own
 * date and time picker); a recent payee fills them from last time. The rare extras (shared with people, a note)
 * are chips that each open their own few fields. It all fits on a phone screen without scrolling.
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
  // When it happened, as "yyyy-MM-ddTHH:mm"; empty means now (the moment it's saved).
  const [when, setWhen] = useState('')
  const [notes, setNotes] = useState('')
  const payeeRef = useRef<HTMLInputElement>(null)
  const [transfer, setTransfer] = useState(false)
  // Once I pick an account myself (or a payee picks it), the default stops following the direction.
  const [accountPicked, setAccountPicked] = useState(defaultAccountId != null)
  const [noting, setNoting] = useState(false)
  // Shared with people: who paid (me or a friend) and who it's split between; one control for all of it.
  const people = usePeople()
  const [sharing, setSharing] = useState(false)
  const [shared, setShared] = useState<Shared>(NOT_SHARED)

  const active = (accounts.data ?? []).filter((a) => !a.archived)
  // The default account: the one used most among recent payments in this direction (newest wins a tie), so
  // spending starts on the usual card and money in on the usual bank account. No history yet: cash, else the first.
  const usual = useMemo(() => {
    const ids = new Set(active.map((a) => a.id))
    const uses = new Map<number, number>()
    for (const t of recent.data?.items ?? []) {
      if (t.transfer || t.paidBy || t.direction !== direction || !t.account || !ids.has(t.account.id)) continue
      uses.set(t.account.id, (uses.get(t.account.id) ?? 0) + 1)
    }
    let best: number | undefined
    for (const [id, n] of uses) if (best === undefined || n > uses.get(best)!) best = id // Map keeps the newest-first order
    return best ?? active.find((a) => a.type === 'CASH')?.id ?? active[0]?.id
  }, [recent.data, direction, active.length]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!accountPicked && usual !== undefined) setAccountId(usual)
  }, [usual, accountPicked])

  const kind = direction === 'DEBIT' ? 'EXPENSE' : 'INCOME'
  const kindCats = (cats.data ?? []).filter((c) => c.kind === kind)
  const value = Number(amount)
  // Sharing applies to money I spent, and not to a move between my own accounts.
  const shareOn = sharing && direction === 'DEBIT' && !transfer
  const outcome: Outcome = shareOn ? outcomeOf(value, shared) : { kind: 'none' }
  const paidByOther = outcome.kind === 'paidBy'
  const share = outcome.kind === 'paidBy' ? outcome.share : 0
  const payer = (people.data ?? []).find((p) => p.id === shared.payer)
  // Lent: I paid and none of it is mine, so it isn't my spending and needs no category
  const lendNow = outcome.kind === 'split' && !outcome.includeMe
  const nobody = shareOn && outcome.kind === 'none'
  const valid = value > 0 && !outcome.problem && !nobody && (paidByOther || accountId !== '')
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
    if (t.account && active.some((a) => a.id === t.account!.id) && defaultAccountId == null) (setAccountId(t.account.id), setAccountPicked(true))
  }
  // A transfer is money moved out of the chosen account into another of mine: not spending, so no category or sharing.
  const switchKind = (k: Kind) => {
    setDirection(k === 'CREDIT' ? 'CREDIT' : 'DEBIT')
    setTransfer(k === 'TRANSFER')
    if (k === 'TRANSFER') setSharing(false)
    setCategoryId('')
  }
  const typeAmount = (raw: string) => {
    let v = raw.replace(/[^\d.]/g, '')
    const dot = v.indexOf('.')
    if (dot >= 0) v = v.slice(0, dot + 1) + v.slice(dot + 1).replace(/\./g, '').slice(0, 2)
    setAmount(v.replace(/^0+(?=\d)/, '').slice(0, 12))
  }

  const occurredAt = () => (when ? fromLocalInput(when) : new Date().toISOString())
  const save = useMoneyMutation(
    () =>
      (outcome.kind === 'paidBy'
        ? api('/transactions/paid-by', { body: { personId: outcome.personId, total: value, share, categoryId: categoryId || null, where: counterparty, occurredAt: occurredAt(), notes } })
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
            outcome.kind === 'split'
              ? api(`/transactions/${t.id}/split`, {
                  method: 'PUT',
                  body: { method: outcome.method, includeMe: outcome.includeMe, participants: outcome.participants },
                })
              : t,
          )
      ).then((r) => (outcome.kind !== 'none' && rememberShared(shared), r)),
    onClose,
  )

  const accountOptions: DropdownOption<number>[] = [...active]
    .sort((x, y) => Number(y.type === 'CREDIT_CARD') - Number(x.type === 'CREDIT_CARD'))
    .map((a) => {
      const Icon = a.type === 'CREDIT_CARD' ? CreditCard : a.type === 'CASH' ? Wallet : Bank
      const card = a.type === 'CREDIT_CARD'
      return {
        value: a.id,
        label: a.name,
        detail: [a.last4 && `•••• ${a.last4}`, card ? `${money0(a.balance)} to pay` : money0(a.balance)].filter(Boolean).join(' · '),
        icon: <Icon size={20} className="ink-2" />,
        group: card ? 'Cards' : 'Bank & cash',
      }
    })
  const categoryOptions: DropdownOption<number | ''>[] = [
    { value: '', label: paidByOther ? 'No category' : 'Let the app guess', detail: paidByOther ? undefined : 'From the payee and your earlier choices', icon: <Sparkle size={20} className="ink-2" /> },
    ...kindCats.map((c) => {
      const Icon = categoryIcon(c)
      return { value: c.id, label: c.name, icon: <Icon size={20} style={{ color: c.color }} /> }
    }),
  ]

  const sign = direction === 'DEBIT' ? '−' : '+'
  const symbol = currency === 'INR' ? '₹' : currency
  const amountText = `${sign}${symbol === '₹' ? '₹' : symbol + ' '}${value.toLocaleString('en-IN', { minimumFractionDigits: Number.isInteger(value) ? 0 : 2, maximumFractionDigits: 2 })}`
  // The button reads back what will be saved, or says what's missing.
  const saveLabel = save.isPending
    ? 'Saving…'
    : !(value > 0)
      ? 'Enter an amount'
      : nobody
        ? 'Pick who it’s split with'
        : outcome.problem
          ? 'Check the split'
          : outcome.kind === 'paidBy'
            ? `Save your share ${money(share)}`
            : outcome.kind === 'split'
              ? `Save ${amountText}, ${outcome.includeMe ? `your share ${money(outcome.myShare)}` : 'lent'}`
              : `Save ${amountText}`
  return (
    // A column that fills the sheet, so Save sits at the bottom of the screen even when the form is short
    <form
      className="flex flex-1 flex-col"
      onSubmit={(e) => {
        e.preventDefault()
        if (valid) save.mutate(undefined)
      }}
    >
      {/* 1. What kind, and how much */}
      <Segmented<Kind>
        value={transfer ? 'TRANSFER' : direction}
        onChange={switchKind}
        className="mx-auto w-full max-w-[320px]"
        options={[
          { value: 'DEBIT', label: 'Spent' },
          { value: 'CREDIT', label: 'Received' },
          { value: 'TRANSFER', label: 'Transfer' },
        ]}
      />
      <label className={cx('mt-5 flex cursor-text items-baseline justify-center', direction === 'CREDIT' && 'text-positive')}>
        {/* The currency is the symbol in front of the amount: tap it to change (not when a friend paid: rupees only). */}
        <span className={cx('relative mr-1.5 flex items-center gap-0.5 text-subheading font-medium', amount ? 'opacity-70' : 'ink-3')} style={{ transform: 'translateY(-0.9em)' }}>
          {symbol}
          {!paidByOther && (
            <>
              <CaretDown size={10} weight="bold" className="ink-3" />
              <select
                aria-label="Currency"
                value={currency}
                onChange={(e) => setCurrency(e.target.value)}
                className="absolute inset-0 cursor-pointer appearance-none opacity-0"
              >
                {CURRENCIES.map((c) => (
                  <option key={c}>{c}</option>
                ))}
              </select>
            </>
          )}
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
            // Return moves on to the payee instead of saving half a payment
            enterKeyHint="next"
            onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), payeeRef.current?.focus())}
            placeholder="0"
            aria-label="Amount"
            className="col-start-1 row-start-1 w-full min-w-0 bg-transparent px-0.5 text-center outline-none placeholder:text-ink-3"
          />
        </span>
      </label>
      {paidByOther && <p className="ink-2 mt-1 text-center text-small">What {payer?.name ?? 'they'} paid altogether</p>}

      {/* 2. Who */}
      <div className="mt-6">
        <div className="flex items-center gap-3 rounded-card bg-surface-2 px-4 focus-within:shadow-[0_0_0_4px_var(--accent-soft)]">
          <Storefront size={19} className="ink-3 shrink-0" />
          <input
            ref={payeeRef}
            enterKeyHint="done"
            onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), e.currentTarget.blur())}
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

      {/* 3. What the app filled in: one line of guesses, each a dropdown to change it. */}
      <ChipRow className="mt-5">
        <Dropdown
          className="max-w-[11rem]"
          label="Account"
          value={paidByOther ? undefined : accountId === '' ? undefined : accountId}
          onChange={(id) => (setAccountId(id), setAccountPicked(true), setShared({ ...shared, payer: 'me' }))}
          options={accountOptions}
        >
          {paidByOther ? <Users size={17} /> : <AccountIcon size={17} />}
          {paidByOther ? `By ${payer ? firstName(payer.name) : 'someone else'}` : `${transfer ? 'From ' : ''}${account?.name ?? 'Account'}`}
        </Dropdown>
        {!transfer && !lendNow && (
          <Dropdown className="max-w-[11rem]" label="Category" value={categoryId} onChange={setCategoryId} options={categoryOptions}>
            <CatIcon size={17} style={category ? { color: category.color } : undefined} />
            {category?.name ?? (paidByOther ? 'No category' : 'Auto')}
          </Dropdown>
        )}
        <WhenPill value={when} onChange={setWhen} />
        {when && (
          <button type="button" onClick={() => setWhen('')} className={pill(false)}>
            <ArrowCounterClockwise size={16} /> Now
          </button>
        )}
      </ChipRow>

      {/* 4. The rare extras, as chips: each opens its own few fields. */}
      <ChipRow wrap className="mt-3">
        {direction === 'DEBIT' && !transfer && (
          <Choice on={sharing} onClick={() => setSharing(!sharing)}>
            <UsersThree size={17} weight={sharing ? 'fill' : 'regular'} /> Shared
          </Choice>
        )}
        <Choice on={noting} onClick={() => setNoting(!noting)}>
          <NotePencil size={17} weight={noting ? 'fill' : 'regular'} /> Note
        </Choice>
      </ChipRow>
      {/* Full width, so its rows of people scroll to the screen's edges like the chips above (a fold clips its sides). */}
      <div className="-mx-5">
        <Fold open={shareOn}>
          <div className="space-y-3 px-5 pt-4">
            <SharedPicker total={value} value={shared} onChange={(v) => (setShared(v), v.payer !== 'me' && setCurrency('INR'))} people={people.data ?? []} />
            {payer && paidByOther && share > 0 && share <= value && <BalanceChange person={payer} change={share} />}
          </div>
        </Fold>
      </div>
      <Fold open={noting}>
        <div className="pt-2.5">
          <Input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Add a note" aria-label="Notes" autoFocus />
        </div>
      </Fold>

      {/* Always-visible action that reads back what will be saved */}
      <div className="sticky bottom-0 z-10 -mx-5 mt-auto bg-[linear-gradient(to_top,var(--surface)_72%,transparent)] px-5 pb-1 pt-5">
        <ErrorText error={save.error} />
        <Button type="submit" className="mt-2 w-full" disabled={!valid || save.isPending}>
          {saveLabel}
        </Button>
      </div>
    </form>
  )
}

/** A strip of chips that scrolls sideways to the sheet's edges, or wraps onto more lines. */
function ChipRow({ children, className, wrap }: { children: ReactNode; className?: string; wrap?: boolean }) {
  if (wrap) return <div className={cx('flex flex-wrap gap-2', className)}>{children}</div>
  return (
    <div className={cx('no-scrollbar -mx-5 overflow-x-auto px-5', className)}>
      <div className="flex w-max gap-2">{children}</div>
    </div>
  )
}

function Choice({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      onClick={onClick}
      className={pill(on)}
    >
      {children}
    </button>
  )
}

/**
 * When it happened: "Now", or the moment picked. The pill is the phone's own date-and-time picker (an invisible
 * native field over it), so one tap opens the iPhone wheel or Android's dialog; desktop browsers open theirs on
 * click. Nothing in the future.
 */
function WhenPill({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const now = toLocalInput()
  return (
    <label className={pill(false, 'relative has-[:focus-visible]:shadow-[0_0_0_3px_var(--accent-soft)]')}>
      <CalendarBlank size={17} />
      {value ? dateTime(fromLocalInput(value)) : 'Now'}
      <input
        type="datetime-local"
        aria-label="When"
        value={value || now}
        max={now}
        onChange={(e) => onChange(e.target.value && e.target.value > now ? now : e.target.value)}
        onClick={(e) => {
          try {
            e.currentTarget.showPicker?.()
          } catch {
            // the browser opens it itself, or can't from here
          }
        }}
        className="absolute inset-0 cursor-pointer appearance-none text-input opacity-0"
      />
    </label>
  )
}
