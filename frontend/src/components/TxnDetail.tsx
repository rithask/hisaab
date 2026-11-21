import { useQuery } from '@tanstack/react-query'
import { ArrowCounterClockwise, ArrowsLeftRight, Users, GraduationCap, CaretDown, Envelope, PencilSimple, Trash } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useState } from 'react'
import { api, ApiError, type RefundWatch, type TeachResult, type Txn } from '../api'
import { dateTime, money, shortDate, ymd } from '../format'
import { useCategories, useMoneyMutation } from '../hooks'
import { categoryIcon } from '../icons'
import { DatePicker } from './DatePicker'
import { RepaymentRow } from './Repayment'
import { PaidByForm } from './PaidBy'
import TeachForm from './TeachForm'
import { settledLabel } from './TxnRow'
import { Badge, Button, cx, ErrorText, Field, Input, Sheet, Textarea, BigMoney, MoreRow, Fold, InfoBox, TextButton } from './ui'
import { MOVE } from '../motion'
import { SplitEditor } from './SplitEditor'

/** One transaction in its window: amount, payee, category, and what applies to it (a split, a repayment, a refund). */
export function TxnDetail({ txn, onClose }: { txn: Txn; onClose: () => void }) {
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
        <span className={cx('inline-flex items-baseline', t.transfer ? 'ink-3' : t.direction === 'CREDIT' ? 'text-positive' : '')}>
          <span className="display mr-1 text-display-sm leading-none">{t.direction === 'CREDIT' ? '+' : '−'}</span>
          <BigMoney value={t.amount} className="text-display leading-none" />
        </span>
        {t.amountEstimated && (
          <p className="mt-1 text-caption text-attention">
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
          <button className="pressable mx-auto mt-3 flex items-center gap-1.5 rounded-full px-2 text-title font-semibold" onClick={() => (setName(t.counterparty ?? ''), setRenaming(true))} aria-label="Rename payee">
            {t.counterparty ?? t.description ?? 'Add a name'}
            <PencilSimple size={14} className="ink-3" />
          </button>
        )}
        {renamed && (
          <p className="anim-pop mx-auto mt-2 max-w-xs rounded-box bg-accent-soft px-3 py-2 text-caption text-accent">
            Learned. Future alerts from this payee will be named <b>{renamed.to}</b>
            {renamed.others > 0 ? `, and ${renamed.others} earlier one${renamed.others > 1 ? 's were' : ' was'} renamed too` : ''}.
          </p>
        )}
        <p className="ink-2 text-label">
          {paidBy ? `Paid by ${paidBy.name}` : t.account?.name} · {dateTime(t.occurredAt)}
        </p>
      </div>

      {fixed && (
        <p className="anim-pop rounded-box bg-accent-soft px-3 py-2 text-center text-caption text-accent">
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
        <InfoBox
          icon={Users}
          title={`${paidBy.name} paid ${money(t.paidTotal ?? t.amount)}, your share ${money(t.amount)}`}
          action={<TextButton onClick={() => setEditingShare(true)}>Change amounts</TextButton>}
        >
          Counted in your spending, and taken off what {paidBy.name} owes you. No account of yours was charged.
        </InfoBox>
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
        <InfoBox icon={ArrowsLeftRight} title="Money moved between my accounts" action={<TextButton onClick={() => update.mutate({ transfer: false })}>It wasn't</TextButton>}>
          {t.linkedAccount ? `Matched with ${t.linkedAccount.name}. ` : ''}Not counted as income or spending.
        </InfoBox>
      ) : settled ? null : choosing ? (
        <div>
          <p className="mb-2 flex items-baseline gap-2 px-1 text-small font-medium text-ink-2">
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
                    'pressable flex flex-col items-center gap-1.5 rounded-box px-1 py-2.5 text-caption font-medium leading-tight transition-colors duration-quick',
                    on ? 'bg-ink text-surface' : 'bg-surface-2 text-ink-2',
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
        <div className="flex items-center gap-3 rounded-box bg-surface-2 px-4 py-2.5">
          <CatIcon size={20} style={{ color: cat!.color }} />
          <span className="min-w-0 flex-1">
            <span className="block text-body font-medium">{cat!.name}</span>
            {t.categorySuggested && <span className="ink-2 block text-caption">A guess from your earlier choices</span>}
          </span>
          {t.categorySuggested && (
            <Button size="sm" disabled={update.isPending} onClick={() => update.mutate({ categoryId: cat!.id })}>
              Right
            </Button>
          )}
          <TextButton className="h-9 px-1 text-label" onClick={() => setChangingCategory(true)}>
            Change
          </TextButton>
        </div>
      )}
      {learned && !t.transfer && (
        <div className="anim-pop -mt-2 rounded-box bg-accent-soft p-3 text-label">
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
        <button type="button" onClick={() => setMore(!more)} aria-expanded={more} className="ink-2 flex w-full items-center justify-between rounded-box px-1 py-2 text-label font-medium">
          <span>
            More
            {extras && !more ? <span className="ml-2 text-accent">{extras}</span> : null}
          </span>
          <motion.span animate={{ rotate: more ? 180 : 0 }} transition={MOVE}>
            <CaretDown size={14} weight="bold" />
          </motion.span>
        </button>
        <Fold open={more}>
          <div className="space-y-3 pt-2">
            <div className="surface overflow-hidden rounded-card [&>*+*]:border-t [&>*+*]:border-line">
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
                  <span className="min-w-0 flex-1 text-body">Delete this transaction?</span>
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
              <pre className="max-h-60 overflow-auto whitespace-pre-wrap rounded-card bg-surface-2 p-3 font-mono text-micro leading-relaxed">
                {email.data ? `${email.data.subject ?? ''}\n\n${email.data.bodyText ?? ''}` : 'Loading…'}
              </pre>
            </Fold>
            <Field label="Notes">
              <Textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} onBlur={() => notes !== (t.notes ?? '') && update.mutate({ notes })} />
            </Field>
            <Field label="Tags" hint="Comma separated, e.g. trip-goa, office">
              <Input value={tags} onChange={(e) => setTags(e.target.value)} onBlur={() => tags !== t.tags.join(', ') && update.mutate({ tags: tags.split(',') })} />
            </Field>
            <p className="ink-3 px-1 text-caption">
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
    <div className="rounded-box bg-surface-2 p-4">
      {r && !editing ? (
        <>
          <div className="flex items-center justify-between gap-2">
            <p className="font-medium">Refund</p>
            <Badge tone={r.status === 'ARRIVED' ? 'positive' : r.status === 'LATE' ? 'negative' : 'attention'}>
              {r.status === 'ARRIVED' ? 'arrived' : r.status === 'LATE' ? 'late' : 'waiting'}
            </Badge>
          </div>
          <p className="ink-2 mt-1 text-small">
            {r.status === 'ARRIVED'
              ? `${money(r.refunded ?? 0)} came back on ${shortDate(r.refundedAt!)}.`
              : r.status === 'LATE'
                ? `Expected by ${shortDate(r.expectedBy)}. Worth chasing up.`
                : `Expected by ${shortDate(r.expectedBy)}. You'll be told if it doesn't arrive.`}
          </p>
          <div className="mt-2 flex gap-4 text-small">
            {r.status !== 'ARRIVED' && (
              <TextButton onClick={() => (setBy(r.expectedBy < today ? today : r.expectedBy), setEditing(true))}>
                Change date
              </TextButton>
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
