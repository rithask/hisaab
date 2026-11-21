import { useQuery } from '@tanstack/react-query'
import { ArrowCounterClockwise, ArrowsLeftRight, Bank, Check, HandCoins, NotePencil, Users, UsersThree, CalendarBlank, GraduationCap, CaretDown, ClockCounterClockwise, CreditCard, Envelope, PencilSimple, Sparkle, Storefront, Trash, Wallet } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { api, ApiError, type RefundWatch, type Split, type TeachResult, type Txn } from '../api'
import { dateTime, fromLocalInput, money, shortDate, toLocalInput, ymd } from '../format'
import { useAccounts, useCategories, useMoneyMutation, usePeople } from '../hooks'
import { categoryIcon } from '../icons'
import { DatePicker, DateTimePicker } from './DatePicker'
import { RepaymentRow } from './Repayment'
import { BalanceChange, PaidByForm, PersonChips, ShareFields, shareOf, type ShareMode } from './PaidBy'
import TeachForm from './TeachForm'
import { settledLabel } from './TxnRow'
import { Badge, Button, cx, ErrorText, Field, Input, Segmented, Sheet, SPRING, Textarea, Toggle, BigMoney, MoreRow, Avatar, Fold } from './ui'

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
  const [learned, setLearned] = useState<{ payee?: string; byWording?: boolean; category: string; applied?: number } | null>(null)
  const [showEmail, setShowEmail] = useState(false)
  const [renaming, setRenaming] = useState(false)
  const [name, setName] = useState(txn.counterparty ?? '')
  const [renamed, setRenamed] = useState<{ to: string; others: number } | null>(null)
  // Fixing an alert that was read wrong teaches the parser its format
  const [fixing, setFixing] = useState(false)
  const [fixed, setFixed] = useState<TeachResult | null>(null)
  const [editingShare, setEditingShare] = useState(false)
  const [changingCategory, setChangingCategory] = useState(false)
  const [more, setMore] = useState(false)
  const [deleting, setDeleting] = useState(false)
  // A friend paid and this is my share: no account, no learning, no splitting or transfers.
  const paidBy = t.paidBy
  const fromAlert = (t.source === 'EMAIL' || t.source === 'SMS') && !!t.rawEmailId
  const onFixed = async (r: TeachResult) => {
    setFixing(false)
    setFixed(r)
    setT(await api<Txn>(`/transactions/${r.txnId ?? t.id}`))
  }

  const update = useMoneyMutation(
    (body: Record<string, unknown>) => api<{ txn: Txn; similarCount: number; renamedCount: number; learnedWording: boolean }>(`/transactions/${t.id}`, { method: 'PATCH', body }),
    (r, body) => {
      setT(r.txn)
      setSimilar(r.similarCount)
      if (typeof body.categoryId === 'number' && r.txn.category && !r.txn.paidBy) setLearned({ payee: r.txn.counterparty ?? undefined, byWording: r.learnedWording, category: r.txn.category.name })
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

  const cat = t.category
  const CatIcon = categoryIcon(cat)
  // Picking a category is open only when there isn't one yet; otherwise it's one row (a guess asks "Right?").
  const choosing = changingCategory || !cat
  // Money I lent or a repayment, none of it mine: the split or repayment below says what it is; no category needed.
  const settled = !t.transfer && !paidBy && t.myAmount === 0 && !cat && !changingCategory && !!settledLabel(t)
  const extras = [notes && 'note', t.tags.length > 0 && `${t.tags.length} tag${t.tags.length > 1 ? 's' : ''}`].filter(Boolean).join(' · ')

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
          {paidBy ? `Paid by ${paidBy.name}` : t.account?.name} · {dateTime(t.occurredAt)}
        </p>
      </div>

      {fixed && (
        <p className="anim-pop rounded-2xl bg-[var(--accent-soft)] px-3 py-2 text-center text-xs text-[var(--accent)]">
          Fixed.{' '}
          {fixed.learnedFormat
            ? 'Learned from it, so the next message like this is read right.'
            : fixed.conflicts > 0
              ? `Not learned: it would change how ${fixed.conflicts} message${fixed.conflicts > 1 ? 's' : ''} you taught earlier ${fixed.conflicts > 1 ? 'are' : 'is'} read.`
              : ''}
        </p>
      )}
      <Sheet open={fixing} onClose={() => setFixing(false)} title="Fix what it read">
        {fixing && t.rawEmailId && <TeachForm id={t.rawEmailId} onDone={onFixed} />}
      </Sheet>

      {/* Only what already applies to this payment shows here; everything you could add is under More. */}
      {paidBy && (
        <div className="flex items-start gap-3 rounded-2xl bg-[var(--surface-2)] p-4 text-sm">
          <Users size={18} className="mt-0.5 shrink-0" />
          <div className="flex-1">
            <p className="font-medium">
              {paidBy.name} paid {money(t.paidTotal ?? t.amount)}, your share {money(t.amount)}
            </p>
            <p className="ink-2">Counted in your spending, and taken off what {paidBy.name} owes you. No account of yours was charged.</p>
            <button className="link mt-2 font-semibold text-[var(--accent)]" onClick={() => setEditingShare(true)}>
              Change amounts
            </button>
          </div>
        </div>
      )}
      <Sheet open={editingShare} onClose={() => setEditingShare(false)} title="Someone else paid">
        {editingShare && (
          <PaidByForm
            txn={t}
            onDone={async () => {
              setEditingShare(false)
              setT(await api<Txn>(`/transactions/${t.id}`))
            }}
          />
        )}
      </Sheet>

      {t.transfer ? (
        <div className="flex items-start gap-3 rounded-2xl bg-[var(--surface-2)] p-4 text-sm">
          <ArrowsLeftRight size={18} className="mt-0.5 shrink-0" />
          <div className="flex-1">
            <p className="font-medium">Money moved between my accounts</p>
            <p className="ink-2">
              {t.linkedAccount ? `Matched with ${t.linkedAccount.name}. ` : ''}Not counted as income or spending.
            </p>
            <button className="link mt-2 font-semibold text-[var(--accent)]" onClick={() => update.mutate({ transfer: false })}>
              It wasn't
            </button>
          </div>
        </div>
      ) : settled ? null : choosing ? (
        <div>
          <p className="ink-2 mb-2 flex items-baseline gap-2 px-1 text-[13px] font-medium">
            {cat ? 'Change category' : 'What was it?'}
          </p>
          <div className="grid grid-cols-3 gap-2">
            {options.map((c) => {
              const Icon = categoryIcon(c)
              const on = cat?.id === c.id
              return (
                <button
                  key={c.id}
                  onClick={() => (update.mutate({ categoryId: c.id }), setChangingCategory(false))}
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
        </div>
      ) : (
        <div className="flex items-center gap-3 rounded-2xl bg-[var(--surface-2)] px-4 py-2.5">
          <CatIcon size={20} style={{ color: cat!.color }} />
          <span className="min-w-0 flex-1">
            <span className="block text-[15px] font-medium">{cat!.name}</span>
            {t.categorySuggested && <span className="ink-2 block text-[12.5px]">A guess from your earlier choices</span>}
          </span>
          {t.categorySuggested && (
            <Button size="sm" disabled={update.isPending} onClick={() => update.mutate({ categoryId: cat!.id })}>
              Right
            </Button>
          )}
          <button className="link h-9 px-1 text-[14px] font-medium text-[var(--accent)]" onClick={() => setChangingCategory(true)}>
            Change
          </button>
        </div>
      )}
      {learned && !t.transfer && (
        <div className="anim-pop -mt-2 rounded-2xl bg-[var(--accent-soft)] p-3 text-sm">
          {learned.payee ? (
            <p>
              Learned: future payments {t.direction === 'CREDIT' ? 'from' : 'to'} <b>{learned.payee}</b> will be <b>{learned.category}</b>.
              {learned.applied != null && ` ${learned.applied} earlier one${learned.applied === 1 ? ' was' : 's were'} updated too.`}
            </p>
          ) : learned.byWording ? (
            <p>
              Learned: messages worded like this one will be <b>{learned.category}</b>.
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

      {!t.transfer && !paidBy && <RepaymentRow txn={t} part="linked" />}
      {t.direction === 'DEBIT' && !t.transfer && !paidBy && <SplitEditor txn={t} part="linked" />}
      {t.direction === 'DEBIT' && !t.transfer && !paidBy && <RefundWatcher txnId={t.id} part="linked" />}

      <ErrorText error={update.error} />

      {/* Everything else, folded away */}
      <div>
        <button type="button" onClick={() => setMore(!more)} aria-expanded={more} className="ink-2 flex w-full items-center justify-between rounded-2xl px-1 py-2 text-[14px] font-medium">
          <span>
            More
            {extras && !more ? <span className="ml-2 text-[var(--accent)]">{extras}</span> : null}
          </span>
          <motion.span animate={{ rotate: more ? 180 : 0 }} transition={SPRING}>
            <CaretDown size={14} weight="bold" />
          </motion.span>
        </button>
        <Fold open={more}>
          <div className="space-y-3 pt-2">
            <div className="surface overflow-hidden rounded-[20px] [&>*+*]:border-t [&>*+*]:border-[var(--line)]">
              {t.direction === 'DEBIT' && !t.transfer && !paidBy && <SplitEditor txn={t} part="ask" />}
              {!t.transfer && !paidBy && <RepaymentRow txn={t} part="ask" />}
              {!t.transfer && !paidBy && (
                <MoreRow icon={ArrowsLeftRight} onClick={() => update.mutate({ transfer: true })}>
                  Money moved between my accounts
                </MoreRow>
              )}
              {t.direction === 'DEBIT' && !t.transfer && !paidBy && <RefundWatcher txnId={t.id} part="ask" />}
              {fromAlert && !fixed && (
                <MoreRow icon={GraduationCap} onClick={() => setFixing(true)}>
                  Wrong amount or account? Fix it
                </MoreRow>
              )}
              {t.rawEmailId && (
                <MoreRow icon={Envelope} onClick={() => setShowEmail((s) => !s)}>
                  {showEmail ? 'Hide' : 'Show'} the original message
                </MoreRow>
              )}
              {/* Asked in place, not with a browser pop-up: those can be blocked (Safari, the installed app). */}
              {deleting ? (
                <div className="anim-pop flex items-center gap-2 px-4 py-2.5">
                  <span className="min-w-0 flex-1 text-[15px]">Delete this transaction?</span>
                  <Button size="sm" variant="secondary" onClick={() => setDeleting(false)}>
                    Cancel
                  </Button>
                  <Button size="sm" variant="danger" disabled={del.isPending} onClick={() => del.mutate(undefined)}>
                    Delete
                  </Button>
                </div>
              ) : (
                <MoreRow icon={Trash} tone="danger" onClick={() => setDeleting(true)}>
                  Delete
                </MoreRow>
              )}
            </div>
            <ErrorText error={del.error} />
            <Fold open={showEmail}>
              <pre className="max-h-60 overflow-auto whitespace-pre-wrap rounded-[18px] bg-[var(--surface-2)] p-3 font-mono text-[11px] leading-relaxed">
                {email.data ? `${email.data.subject ?? ''}\n\n${email.data.bodyText ?? ''}` : 'Loading…'}
              </pre>
            </Fold>
            <Field label="Notes">
              <Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} onBlur={() => notes !== (t.notes ?? '') && update.mutate({ notes })} />
            </Field>
            <Field label="Tags" hint="Comma separated, e.g. trip-goa, office">
              <Input value={tags} onChange={(e) => setTags(e.target.value)} onBlur={() => tags !== t.tags.join(', ') && update.mutate({ tags: tags.split(',') })} />
            </Field>
            <p className="ink-3 px-1 text-[12.5px]">
              {t.source === 'EMAIL' ? 'From a bank email' : t.source === 'SMS' ? 'From a bank SMS' : t.source === 'STATEMENT' ? 'From a statement' : t.source === 'ADJUSTMENT' ? 'Balance correction' : t.source === 'PERSON' ? 'Your share of what someone else paid' : t.addedBy ? `Added by ${t.addedBy}` : 'Added by hand'}
              {t.referenceNo ? ` · Ref ${t.referenceNo}` : ''}
              {t.reportedBalance != null ? ` · Balance after: ${money(t.reportedBalance)}` : ''}
            </p>
          </div>
        </Fold>
      </div>
    </div>
  )
}

type Method = 'EQUAL' | 'EXACT' | 'PERCENT'

/** @param part "linked": only an existing split; "ask": only the offer to split (under More). */
function SplitEditor({ txn, part }: { txn: Txn; part: 'linked' | 'ask' }) {
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
    setIncludeMe(split.data.myShare > 0) // money I lent stays lent when edited
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

  const has = (split.data?.shares.length ?? 0) > 0
  if (!split.data || (part === 'linked' ? !has : has)) return null
  if (!editing && !has)
    return (
      <MoreRow icon={Users} onClick={() => setEditing(true)}>
        Split with friends
      </MoreRow>
    )
  if (!editing) {
    const s = split.data
    return (
      <div className="rounded-2xl bg-[var(--surface-2)] p-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-[15px] font-semibold">{s.shares.length > 0 && s.myShare === 0 ? 'Money you lent' : 'Split with others'}</p>
            {!s?.shares.length && <p className="ink-2 text-xs">Paid for a group? Track who owes you.</p>}
            {s.shares.length > 0 && s.myShare === 0 && <p className="ink-2 text-xs">Not counted as your spending.</p>}
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
            {/* No shrink and no weight change on tap (both made the row jump); only the circle fills and the tick pops in. */}
            <button
              type="button"
              aria-pressed={p.id in values}
              onClick={() => toggle(p.id)}
              className="row flex min-h-11 flex-1 select-none items-center gap-3 rounded-2xl px-2 py-2 text-left text-[15px] [-webkit-touch-callout:none]"
            >
              <span className={cx('flex h-6 w-6 shrink-0 items-center justify-center rounded-full border-2 transition-colors duration-150', p.id in values ? 'border-[var(--ink)] accent-fill' : 'border-[var(--line-strong)]')}>
                <AnimatePresence initial={false}>
                  {p.id in values && (
                    <motion.span key="tick" initial={{ scale: 0.4, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ scale: 0.4, opacity: 0 }} transition={{ duration: 0.15, ease: [0.22, 1, 0.36, 1] }} className="flex">
                      <Check size={13} weight="bold" />
                    </motion.span>
                  )}
                </AnimatePresence>
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
      {paidByOther && <p className="ink-2 mt-1 text-center text-[13px]">What {payer?.name ?? 'they'} paid altogether</p>}
      <div className={cx('mt-2 flex justify-center', paidByOther && 'hidden')}>
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
            placeholder={paidByOther ? 'Where (optional)' : direction === 'DEBIT' ? 'Paid to (optional)' : 'Received from (optional)'}
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

      {/* 3. What the app filled in: one line of guesses, each opens only if tapped. */}
      <Label>{direction === 'DEBIT' ? 'Paid' : 'Received'}</Label>
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
      <Label>Also</Label>
      <ChipRow>
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
          <PersonChips people={people.data ?? []} value={personId} onChange={setPersonId} />
          <div>
            <p className="ink-2 mb-2 px-1 text-[13px] font-medium">Your share</p>
            <ShareFields total={value} mode={shareMode} setMode={setShareMode} exact={exactShare} setExact={setExactShare} />
          </div>
          {payer && share > 0 && share <= value && <BalanceChange person={payer} change={share} />}
        </div>
      </Fold>
      <Fold open={splitting && !paidByOther}>
        <div className="space-y-3 pt-4">
          {(people.data ?? []).length === 0 ? (
            <p className="ink-2 px-1 text-sm">Add people under Splits first.</p>
          ) : (
            <div className="flex flex-wrap gap-2">
              {(people.data ?? []).map((p) => {
                const on = splitWith.includes(p.id)
                return (
                  <button
                    key={p.id}
                    type="button"
                    aria-pressed={on}
                    onClick={() => setSplitWith((w) => (on ? w.filter((x) => x !== p.id) : [...w, p.id]))}
                    className={cx('pressable flex h-11 items-center gap-2 rounded-full pl-1.5 pr-4 text-[14px] font-medium transition-colors duration-150', on ? 'bg-[var(--ink)] text-[var(--bg)]' : 'bg-[var(--surface-2)]')}
                  >
                    <Avatar name={p.name} size={32} />
                    {p.name}
                  </button>
                )
              })}
            </div>
          )}
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
                  <span className="min-w-0 flex-1 truncate text-[15px]">{p.name}</span>
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
            <p className={cx('px-1 text-[13px]', splitProblem ? 'text-[var(--negative)]' : 'ink-2')}>
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
          <PersonChips people={people.data ?? []} value={lendTo} onChange={setLendTo} />
          {borrower && value > 0 && (
            <p className="ink-2 px-1 text-[13px]">
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
      <div className="sticky bottom-0 z-10 -mx-5 mt-4 bg-[linear-gradient(to_top,var(--surface)_72%,transparent)] px-5 pb-1 pt-5">
        <ErrorText error={save.error} />
        <Button type="submit" className="mt-2 w-full" disabled={!valid || save.isPending}>
          {save.isPending ? 'Saving…' : valid && paidByOther ? `Save your share ${money(share)}` : valid ? `Save ${sign}${symbol === '₹' ? '₹' : symbol + ' '}${value.toLocaleString('en-IN', { minimumFractionDigits: Number.isInteger(value) ? 0 : 2, maximumFractionDigits: 2 })}${lendNow && borrower ? `, lent to ${borrower.name}` : splitNow ? (splitMethod === 'EQUAL' ? `, split ${splitWith.length + 1} ways` : `, your share ${money(value - theirTotal)}`) : ''}` : lendNow && value > 0 && lendTo === '' ? 'Who did you lend it to?' : value > 0 && splitProblem ? 'Check the split' : 'Enter an amount'}
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

/** One of the app's guesses (account, category, when): shows the choice, tap to change it. */
function Guess({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-expanded={on}
      onClick={onClick}
      className={cx('pressable flex h-11 max-w-full items-center gap-2 rounded-full px-4 text-[14px] font-medium transition-colors duration-150', on ? 'bg-[var(--ink)] text-[var(--bg)]' : 'bg-[var(--surface-2)] text-[var(--ink)]')}
    >
      <span className="flex min-w-0 items-center gap-2 truncate">{children}</span>
      <CaretDown size={12} weight="bold" className={cx('shrink-0 transition-transform duration-150', on ? 'rotate-180' : 'opacity-50')} />
    </button>
  )
}

/**
 * "I'm expecting money back" for a payment (a return, a cancelled order). Unusual activity warns if it hasn't
 * arrived by the date; it's marked arrived when a matching credit shows up.
 */
function RefundWatcher({ txnId, part }: { txnId: number; part: 'linked' | 'ask' }) {
  const q = useQuery({
    queryKey: ['refund', txnId],
    queryFn: () => api<RefundWatch>(`/refunds/${txnId}`).catch((e) => (e instanceof ApiError && e.status === 404 ? null : Promise.reject(e))),
  })
  const [by, setBy] = useState(() => ymd(new Date(Date.now() + 14 * 864e5)))
  const [editing, setEditing] = useState(false)
  const start = useMoneyMutation(() => api('/refunds', { body: { txnId, expectedBy: by } }), () => setEditing(false))
  const stop = useMoneyMutation(() => api(`/refunds/${txnId}`, { method: 'DELETE' }))
  if (q.data === undefined) return null
  const r = q.data
  if (part === 'linked' ? !r : !!r) return null
  const today = ymd()

  if (!r && !editing)
    return (
      <MoreRow icon={ArrowCounterClockwise} onClick={() => setEditing(true)}>
        I'm expecting a refund
      </MoreRow>
    )
  return (
    <div className="rounded-2xl bg-[var(--surface-2)] p-4">
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
