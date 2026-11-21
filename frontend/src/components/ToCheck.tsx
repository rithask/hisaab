import { useQuery } from '@tanstack/react-query'
import { CheckCircle, CloudWarning, CreditCard, FileText, HandCoins, LockKey, Question, Repeat, ShieldWarning, Target, Tray, Warning, type Icon as PhIcon } from '@phosphor-icons/react'
import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { api, qs, type Alert, type BackupStatus, type Budget, type Dashboard, type Email, type Page as Paged, type Settlement, type Subscriptions, type Txn, type Unusual } from '../api'
import { money0, moneyAuto, shortDate } from '../format'
import { useCategories, useMoneyMutation, useSettings } from '../hooks'
import { categoryIcon } from '../icons'
import { EmailView } from '../pages/Inbox'
import { alertText } from '../pages/Subscriptions'
import { reasonText } from '../pages/Unusual'
import { TxnSheetById } from './TxnSheet'
import { Button, Card, ErrorText, Sheet, IconBadge, TextButton } from './ui'

/** One decision the app needs from me: a question, and the one-tap answers to it. */
type Item = {
  key: string
  icon: PhIcon
  urgent?: boolean
  title: string
  detail?: ReactNode
  /** The answers. Each returns when it's done, so the card can move on. */
  body: ReactNode
}

/**
 * Everything that needs me, one card at a time: a balance that's off, a repayment to confirm, a payment with no
 * category, a message the app couldn't read, something that looks wrong. Each card asks one question and answers
 * it in one tap; "Later" puts it at the back, and "See all" lists every card at once. When there's nothing, it says so.
 */
export default function ToCheck({ d }: { d: Dashboard }) {
  const s = useSettings()
  const f = s.data?.features
  const cats = useCategories()
  const [later, setLater] = useState<string[]>([])
  const [current, setCurrent] = useState<string | null>(null)
  const [all, setAll] = useState(() => {
    try {
      return localStorage.getItem('toCheckAll') === '1'
    } catch {
      return false
    }
  })
  const [txn, setTxn] = useState<number | null>(null)
  const [email, setEmail] = useState<number | null>(null)

  const since = new Date(Date.now() - 30 * 864e5).toISOString()
  const alerts = useQuery({ queryKey: ['alerts'], queryFn: () => api<Alert[]>('/alerts'), enabled: d.counts.alerts > 0 })
  const repay = useQuery({ queryKey: ['settlements', 'suggested'], queryFn: () => api<Settlement[]>('/settlements/suggested'), enabled: d.counts.suggestedSettlements > 0 })
  const emails = useQuery({
    queryKey: ['emails', 'review'],
    queryFn: async () => [...(await api<Email[]>('/emails?status=NEEDS_REVIEW')), ...(await api<Email[]>('/emails?status=ERROR'))],
    enabled: d.counts.emailsToReview > 0,
  })
  const loose = useQuery({ queryKey: ['transactions', 'uncategorized', since.slice(0, 10)], queryFn: () => api<Paged<Txn>>('/transactions' + qs({ uncategorized: true, direction: 'DEBIT', from: since, size: 10 })) })
  const unusual = useQuery({ queryKey: ['unusual'], queryFn: () => api<Unusual>('/unusual') })
  const subs = useQuery({ queryKey: ['subscriptions'], queryFn: () => api<Subscriptions>('/subscriptions') })
  const budgets = useQuery({ queryKey: ['budgets'], queryFn: () => api<Budget[]>('/budgets'), enabled: f?.budgets === true })
  const backup = useQuery({ queryKey: ['backups'], queryFn: () => api<BackupStatus>('/backups'), enabled: f?.backups === true })

  // The categories I use most, so the right one is usually a single tap.
  const used = d.month.expenseByCategory.map((c) => c.categoryId).filter((id): id is number => id != null)
  const expense = (cats.data ?? []).filter((c) => c.kind === 'EXPENSE')
  const top = [...used.map((id) => expense.find((c) => c.id === id)).filter((c) => c != null), ...expense.filter((c) => !used.includes(c.id))].slice(0, 5)

  const items: Item[] = []
  for (const a of alerts.data ?? []) {
    const off = (a.actual ?? 0) - (a.expected ?? 0)
    items.push({
      key: `alert-${a.id}`,
      icon: Warning,
      urgent: true,
      title: `${a.account?.name ?? 'A balance'} is off by ${moneyAuto(Math.abs(off))}`,
      detail: `The bank says ${moneyAuto(a.actual)}, the app has ${moneyAuto(a.expected)}. A payment may be missing.`,
      body: <AlertAnswers id={a.id} />,
    })
  }
  for (const c of d.accounts.filter((a) => a.type === 'CREDIT_CARD' && a.card?.dueDate && a.card.daysToDue <= 3 && a.balance > 0))
    items.push({ key: `due-${c.id}`, icon: CreditCard, urgent: true, title: `Pay ${c.name}: ${money0(c.balance)}`, detail: c.card!.daysToDue === 0 ? 'Due today.' : `Due in ${c.card!.daysToDue} day${c.card!.daysToDue > 1 ? 's' : ''}.`, body: <Go to="/accounts">See the card</Go> })
  if (f?.backups && backup.data && (backup.data.lastError || backup.data.stale) && (backup.data.lastError || backup.data.lastOk))
    items.push({ key: 'backup', icon: CloudWarning, urgent: true, title: backup.data.lastError ? 'Backups are failing' : 'No backup in 2 days', detail: 'Your data isn\'t being saved anywhere else.', body: <Go to="/settings/data">Fix backups</Go> })
  for (const x of repay.data ?? []) {
    const inn = x.direction === 'IN'
    items.push({
      key: `repay-${x.id}`,
      icon: HandCoins,
      title: inn ? `${x.txnCounterparty ?? x.personName} sent you ${moneyAuto(x.amount)}` : `You sent ${x.txnCounterparty ?? x.personName} ${moneyAuto(x.amount)}`,
      detail: inn ? `Is this ${x.personName} paying you back?` : `Is this you paying ${x.personName} back?`,
      body: <RepayAnswers id={x.id} />,
    })
  }
  for (const i of unusual.data?.items ?? [])
    items.push({
      key: `odd-${i.key}`,
      icon: ShieldWarning,
      urgent: i.reasons.some((r) => r.kind === 'DOUBLE_CHARGE'),
      title: `${moneyAuto(i.amount)} to ${i.name ?? 'someone'}`,
      detail: `${shortDate(i.at)} · ${reasonText(i.reasons[0], i)}`,
      body: <OddAnswers k={i.key} onOpen={() => setTxn(i.txnId)} />,
    })
  for (const r of (unusual.data?.refunds ?? []).filter((x) => x.status === 'LATE'))
    items.push({ key: `refund-${r.txnId}`, icon: ShieldWarning, urgent: true, title: `Refund from ${r.name ?? 'a payment'} is late`, detail: `Expected by ${shortDate(r.expectedBy)}. Worth chasing up.`, body: <Answers><Button size="sm" onClick={() => setTxn(r.txnId)}>Open payment</Button></Answers> })
  for (const e of emails.data ?? [])
    items.push({
      key: `email-${e.id}`,
      icon: Tray,
      title: e.channel === 'SMS' ? "An SMS couldn't be read" : "An email couldn't be read",
      detail: e.subject || e.fromAddress,
      body: <EmailAnswers id={e.id} onOpen={() => setEmail(e.id)} />,
    })
  for (const t of loose.data?.items ?? [])
    items.push({
      key: `cat-${t.id}`,
      icon: Question,
      title: `${moneyAuto(t.amount)} ${t.counterparty ? `at ${t.counterparty}` : `on ${shortDate(t.occurredAt)}`}`,
      detail: 'What was it?',
      body: <CategoryAnswers txn={t} options={top} onOther={() => setTxn(t.id)} />,
    })
  // A subscription charged twice is already a card from Unusual activity; one card is enough.
  const doubled = new Set((unusual.data?.items ?? []).filter((i) => i.reasons.some((r) => r.kind === 'DOUBLE_CHARGE')).map((i) => i.name?.toLowerCase()))
  for (const a of (subs.data?.alerts ?? []).filter((x) => !(x.kind === 'DOUBLE' && doubled.has(x.name.toLowerCase())))) {
    const text = alertText(a)
    items.push({ key: `sub-${a.key}`, icon: Repeat, urgent: a.kind === 'DOUBLE', title: text.title, detail: text.detail, body: <Dismiss k={a.key} /> })
  }
  if (d.counts.statementsToReview) items.push({ key: 'statements', icon: FileText, title: `${d.counts.statementsToReview} statement${d.counts.statementsToReview > 1 ? 's are' : ' is'} ready to import`, detail: 'Check what it found, then add it.', body: <Go to="/import">Review</Go> })
  if (d.counts.statementsLocked) items.push({ key: 'locked', icon: LockKey, title: `${d.counts.statementsLocked} statement${d.counts.statementsLocked > 1 ? 's need their' : ' needs its'} password`, body: <Go to="/statements">Add password</Go> })
  for (const b of f?.budgets ? (budgets.data ?? []).filter((x) => x.status === 'OVER') : []) items.push({ key: `budget-${b.categoryId}`, icon: Target, title: `Over budget on ${b.name}`, detail: `${money0(b.spent)} of ${money0(b.limit)} this month.`, body: <Go to="/budgets">See budget</Go> })

  // Urgent first; the ones I put off go to the back, in the order I put them off. The card on screen stays put
  // until it's answered, so nothing that loads or changes meanwhile can swap it under my finger.
  const ordered = [...items.filter((i) => i.urgent), ...items.filter((i) => !i.urgent)].sort((a, b) => later.indexOf(a.key) - later.indexOf(b.key))
  const held = ordered.find((i) => i.key === current)
  const queue = held ? [held, ...ordered.filter((i) => i !== held)] : ordered
  const shown = queue[0]
  // Nothing shows until every source has answered, for the same reason.
  const loading = [alerts, repay, emails, loose, unusual, subs, budgets, backup, cats].some((q) => q.isLoading) || !s.data
  useEffect(() => {
    if (!loading && shown && shown.key !== current) setCurrent(shown.key)
  }, [loading, shown, current])

  const putOff = () => (setLater((l) => [...l.filter((k) => k !== shown.key), shown.key]), setCurrent(null))
  // Remembered on this device: whether I like to see the cards one at a time or all together.
  const toggleAll = () => {
    setAll(!all)
    try {
      localStorage.setItem('toCheckAll', all ? '0' : '1')
    } catch {
      /* private window: just don't remember */
    }
  }

  return (
    <section className="mt-7">
      <div className="mb-2.5 flex items-baseline justify-between px-1">
        <h2 className="text-title font-semibold">To check</h2>
        {!loading && queue.length > 1 && (
          <TextButton className="text-label" onClick={toggleAll}>
            {all ? 'One at a time' : `See all ${queue.length}`}
          </TextButton>
        )}
      </div>
      {loading ? (
        <Card className="h-[148px] animate-pulse">{null}</Card>
      ) : !shown ? (
        <Card className="flex items-center gap-3 p-4">
          <CheckCircle size={22} weight="fill" className="text-positive" />
          <p className="text-body font-medium">All caught up</p>
        </Card>
      ) : all ? (
        <div className="space-y-2">
          {ordered.map((i) => (
            <ItemCard key={i.key} item={i} />
          ))}
        </div>
      ) : (
        <ItemCard key={shown.key} item={shown} onLater={queue.length > 1 ? putOff : undefined} />
      )}
      <TxnSheetById id={txn} onClose={() => setTxn(null)} />
      <Sheet open={email != null} onClose={() => setEmail(null)} title="Message">
        {email != null && <EmailView id={email} onDone={() => setEmail(null)} />}
      </Sheet>
    </section>
  )
}

function ItemCard({ item, onLater }: { item: Item; onLater?: () => void }) {
  return (
    <Card className="anim-pop p-4">
      <div className="flex gap-3">
        <IconBadge icon={item.icon} tone={item.urgent ? 'negative' : 'neutral'} />
        <div className="min-w-0 flex-1">
          <p className="text-body font-semibold leading-snug">{item.title}</p>
          {item.detail && <p className="ink-2 mt-0.5 text-label leading-relaxed">{item.detail}</p>}
        </div>
      </div>
      <div className="mt-3.5">{item.body}</div>
      {onLater && (
        <button className="link ink-3 mt-3 text-small font-medium" onClick={onLater}>
          Later
        </button>
      )}
    </Card>
  )
}

function Answers({ children, error }: { children: ReactNode; error?: unknown }) {
  return (
    <>
      <div className="flex flex-wrap gap-2">{children}</div>
      <ErrorText error={error} />
    </>
  )
}

function Go({ to, children }: { to: string; children: string }) {
  return (
    <Answers>
      <Link to={to} className="pressable accent-fill inline-flex h-9 items-center rounded-full px-4 text-label font-semibold">
        {children}
      </Link>
    </Answers>
  )
}

function AlertAnswers({ id }: { id: number }) {
  const adjust = useMoneyMutation(() => api(`/alerts/${id}/adjust`, { method: 'POST' }))
  const dismiss = useMoneyMutation(() => api(`/alerts/${id}/dismiss`, { method: 'POST' }))
  const busy = adjust.isPending || dismiss.isPending
  return (
    <Answers error={adjust.error ?? dismiss.error}>
      <Button size="sm" disabled={busy} onClick={() => adjust.mutate(undefined)}>
        Use the bank's balance
      </Button>
      <Button size="sm" variant="secondary" disabled={busy} onClick={() => dismiss.mutate(undefined)}>
        Ignore
      </Button>
    </Answers>
  )
}

function RepayAnswers({ id }: { id: number }) {
  const yes = useMoneyMutation(() => api(`/settlements/${id}/confirm`, { method: 'POST' }))
  const no = useMoneyMutation(() => api(`/settlements/${id}/reject`, { method: 'POST' }))
  const busy = yes.isPending || no.isPending
  return (
    <Answers error={yes.error ?? no.error}>
      <Button size="sm" className="min-w-20" disabled={busy} onClick={() => yes.mutate(undefined)}>
        Yes
      </Button>
      <Button size="sm" variant="secondary" className="min-w-20" disabled={busy} onClick={() => no.mutate(undefined)}>
        No
      </Button>
    </Answers>
  )
}

function OddAnswers({ k, onOpen }: { k: string; onOpen: () => void }) {
  const fine = useMoneyMutation(() => api('/watch/dismiss', { body: { key: k } }))
  return (
    <Answers error={fine.error}>
      <Button size="sm" disabled={fine.isPending} onClick={() => fine.mutate(undefined)}>
        Looks fine
      </Button>
      <Button size="sm" variant="secondary" onClick={onOpen}>
        Open payment
      </Button>
    </Answers>
  )
}

function Dismiss({ k }: { k: string }) {
  const ok = useMoneyMutation(() => api('/watch/dismiss', { body: { key: k } }))
  return (
    <Answers error={ok.error}>
      <Button size="sm" disabled={ok.isPending} onClick={() => ok.mutate(undefined)}>
        Got it
      </Button>
    </Answers>
  )
}

function EmailAnswers({ id, onOpen }: { id: number; onOpen: () => void }) {
  const skip = useMoneyMutation(() => api(`/emails/${id}/not-a-transaction`, { method: 'POST' }))
  return (
    <Answers error={skip.error}>
      <Button size="sm" onClick={onOpen}>
        Tell me what it is
      </Button>
      <Button size="sm" variant="secondary" disabled={skip.isPending} onClick={() => skip.mutate(undefined)}>
        Not a payment
      </Button>
    </Answers>
  )
}

/** Picking a category here is the same as picking it on the transaction: the app learns the payee from it. */
function CategoryAnswers({ txn, options, onOther }: { txn: Txn; options: { id: number; name: string; color: string; kind: 'EXPENSE' | 'INCOME' }[]; onOther: () => void }) {
  const set = useMoneyMutation((categoryId: number) => api(`/transactions/${txn.id}`, { method: 'PATCH', body: { categoryId } }))
  return (
    <>
      <div className="grid grid-cols-3 gap-2">
        {options.map((c) => {
          const Icon = categoryIcon(c)
          return (
            <button key={c.id} disabled={set.isPending} onClick={() => set.mutate(c.id)} className="pressable flex flex-col items-center gap-1.5 rounded-box bg-surface-2 px-1 py-2.5 text-caption font-medium leading-tight disabled:opacity-40">
              <Icon size={20} style={{ color: c.color }} />
              <span className="line-clamp-1 text-center">{c.name}</span>
            </button>
          )
        })}
        <button onClick={onOther} className="pressable ink-2 flex flex-col items-center justify-center gap-1.5 rounded-box bg-surface-2 px-1 py-2.5 text-caption font-medium">
          <span className="text-title leading-5">…</span>
          Something else
        </button>
      </div>
      <ErrorText error={set.error} />
    </>
  )
}
