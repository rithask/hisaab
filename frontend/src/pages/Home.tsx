import { useQuery } from '@tanstack/react-query'
import { Bank, CaretRight, CloudWarning, Repeat, ShieldWarning, CreditCard, FileText, HandCoins, List as MenuIcon, LockKey, Target, Tray, Wallet, Warning, type Icon as PhIcon } from '@phosphor-icons/react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, qs, type Account, type BackupStatus, type Bill, type Budget, type Dashboard, type NetWorthPoint, type Page as Paged, type Settings, type Subscriptions, type Txn, type Unusual } from '../api'
import { CategoryList, Sparkline } from '../components/charts'
import { BillList } from './Bills'
import { BudgetLine } from './Budgets'
import { alertText } from './Subscriptions'
import TxnRow from '../components/TxnRow'
import TxnSheet from '../components/TxnSheet'
import { BigMoney, Button, Card, cx, Delta, List, Meter, Page, PageSkeleton, Section } from '../components/ui'
import { money, money0, monthKey, shiftMonth, shortDate } from '../format'

/**
 * Home answers, top to bottom: how much do I have, does anything need me, what did I spend this month,
 * where is my money, what happened lately. Nothing else.
 */
export default function Home() {
  const month = monthKey()
  const dash = useQuery({ queryKey: ['dashboard', month], queryFn: () => api<Dashboard>('/dashboard' + qs({ month })) })
  const settings = useQuery({ queryKey: ['settings'], queryFn: () => api<Settings>('/settings') })
  const recent = useQuery({ queryKey: ['transactions', 'recent'], queryFn: () => api<Paged<Txn>>('/transactions' + qs({ size: 6 })) })
  const [open, setOpen] = useState<Txn | null>(null)
  const nav = useNavigate()
  // Optional features (Settings → Features): each Home section appears only while its feature is on.
  const f = settings.data?.features
  const bills = useQuery({ queryKey: ['bills', 'soon'], queryFn: () => api<Bill[]>('/bills?within=14'), enabled: f?.bills === true })
  const budgets = useQuery({ queryKey: ['budgets'], queryFn: () => api<Budget[]>('/budgets'), enabled: f?.budgets === true })
  const trend = useQuery({ queryKey: ['networth', '1m'], queryFn: () => api<NetWorthPoint[]>('/networth?range=1m'), enabled: f?.networth === true })
  const backup = useQuery({ queryKey: ['backups'], queryFn: () => api<BackupStatus>('/backups'), enabled: f?.backups === true })
  const subs = useQuery({ queryKey: ['subscriptions'], queryFn: () => api<Subscriptions>('/subscriptions'), enabled: f?.subscriptions === true })
  const unusual = useQuery({ queryKey: ['unusual'], queryFn: () => api<Unusual>('/unusual'), enabled: f?.unusual === true })

  if (!dash.data) return <PageSkeleton />
  const d = dash.data
  const t = d.totals
  // Settings → General: investments can be left out of Home entirely (they stay on the Invest tab).
  const showInvest = settings.data?.showInvestmentsOnHome ?? true

  const header = (
    <header className="flex items-center justify-between pt-1">
      <h1 className="text-[28px] font-bold tracking-[-0.02em]">Home</h1>
      <Link to="/more" aria-label="Menu" className="pressable flex h-10 w-10 items-center justify-center rounded-full bg-[var(--surface-2)] lg:hidden">
        <MenuIcon size={20} />
      </Link>
    </header>
  )

  if (d.accounts.length === 0) {
    return (
      <Page>
        {header}
        <Card className="mt-6 p-6">
          <p className="text-[20px] font-semibold">Let's set you up</p>
          <p className="ink-2 mt-1.5 text-[15px] leading-relaxed">Add your bank accounts and cards with today's balances. Bank alerts keep them up to date from then on.</p>
          <Button className="mt-5 w-full" onClick={() => nav('/setup')}>
            Start setup
          </Button>
        </Card>
      </Page>
    )
  }

  const cards = d.accounts.filter((a) => a.type === 'CREDIT_CARD')
  const dues = cards.filter((c) => c.card?.dueDate && c.card.daysToDue <= 10 && c.balance > 0)
  const prev = d.trend.find((m) => m.month === shiftMonth(month, -1))

  // Only things that need a decision; the section disappears when there are none.
  const todo: { to: string; icon: PhIcon; text: string; urgent?: boolean }[] = []
  if (d.counts.alerts) todo.push({ to: '/inbox', icon: Warning, text: `${d.counts.alerts} balance ${d.counts.alerts > 1 ? "don't" : "doesn't"} match the bank`, urgent: true })
  for (const c of dues) todo.push({ to: '/accounts', icon: CreditCard, text: `Pay ${c.name}: ${money0(c.balance)} due ${c.card!.daysToDue === 0 ? 'today' : `in ${c.card!.daysToDue} day${c.card!.daysToDue > 1 ? 's' : ''}`}`, urgent: c.card!.daysToDue <= 3 })
  if (d.counts.suggestedSettlements) todo.push({ to: '/people', icon: HandCoins, text: `Confirm ${d.counts.suggestedSettlements} repayment${d.counts.suggestedSettlements > 1 ? 's' : ''}` })
  if (d.counts.statementsToReview) todo.push({ to: '/import', icon: FileText, text: `Review ${d.counts.statementsToReview} statement${d.counts.statementsToReview > 1 ? 's' : ''} before importing` })
  if (d.counts.statementsLocked) todo.push({ to: '/statements', icon: LockKey, text: `${d.counts.statementsLocked} statement${d.counts.statementsLocked > 1 ? 's need their' : ' needs its'} password` })
  if (f?.backups && backup.data && (backup.data.lastError || backup.data.stale) && (backup.data.lastError || backup.data.lastOk))
    todo.push({ to: '/settings/data', icon: CloudWarning, text: backup.data.lastError ? "Backups are failing. Check Settings" : 'No backup in the last 2 days', urgent: true })
  for (const b of (f?.budgets ? (budgets.data ?? []) : []).filter((x) => x.status === 'OVER')) todo.push({ to: '/budgets', icon: Target, text: `Over budget on ${b.name}`, urgent: true })
  const odd = f?.unusual ? (unusual.data?.items ?? []) : []
  if (odd.length)
    todo.push({ to: '/unusual', icon: ShieldWarning, text: odd.length === 1 ? `Check a payment to ${odd[0].name ?? 'someone'}` : `${odd.length} payments worth a second look`, urgent: odd.some((i) => i.reasons.some((r) => r.kind === 'DOUBLE_CHARGE')) })
  for (const r of f?.unusual ? (unusual.data?.refunds ?? []).filter((x) => x.status === 'LATE') : []) todo.push({ to: '/unusual', icon: ShieldWarning, text: `Refund from ${r.name ?? 'a payment'} hasn't arrived`, urgent: true })
  const subAlerts = f?.subscriptions ? (subs.data?.alerts ?? []) : []
  if (subAlerts.length)
    todo.push({ to: '/subscriptions', icon: Repeat, text: subAlerts.length === 1 ? alertText(subAlerts[0]).title : `${subAlerts.length} subscription changes to check`, urgent: subAlerts.some((a) => a.kind === 'DOUBLE') })
  if (d.counts.emailsToReview) todo.push({ to: '/inbox', icon: Tray, text: `${d.counts.emailsToReview} message${d.counts.emailsToReview > 1 ? 's' : ''} to check` })

  return (
    <Page>
      {header}

      {/* The one number */}
      <section className="mt-5">
        <p className="ink-2 text-[15px]">{showInvest ? 'Net worth' : 'Net worth, without investments'}</p>
        <BigMoney value={showInvest ? t.netWorth : t.netWorth - t.investments} className="mt-0.5 block text-[44px] leading-tight" />
        {f?.networth && showInvest && (trend.data?.length ?? 0) > 2 && (
          <Link to="/networth" className="row -mx-1 mt-1 block rounded-xl px-1" aria-label="Net worth over time">
            <Sparkline values={trend.data!.map((p) => p.netWorth)} />
          </Link>
        )}
        <div className="mt-3 grid grid-cols-3 gap-2">
          <Stat label="Cash" value={t.cash} />
          {showInvest ? <Stat label="Investments" value={t.investments} /> : <Stat label="Owed to you" value={t.receivable} />}
          <Stat label="Card dues" value={-t.cardOutstanding} negative={t.cardOutstanding > 0} />
        </div>
      </section>

      {todo.length > 0 && (
        <Section title="Needs attention">
          <List>
            {todo.map((a, i) => (
              <Link key={i} to={a.to} className="flex items-center gap-3 px-4 py-3.5 row">
                <a.icon size={20} className={a.urgent ? 'text-[var(--negative)]' : 'text-[var(--ink-2)]'} />
                <span className="min-w-0 flex-1 text-[15px]">{a.text}</span>
                <CaretRight size={16} className="ink-3" />
              </Link>
            ))}
          </List>
        </Section>
      )}

      {f?.bills && (bills.data?.length ?? 0) > 0 && (
        <Section title="Upcoming bills" action={<More to="/bills">All bills</More>}>
          <BillList bills={bills.data!.slice(0, 4)} />
        </Section>
      )}

      <div className="lg:grid lg:grid-cols-2 lg:gap-6">
        <div>
          <Section title="This month" action={<More to="/reports">Insights</More>}>
            <Card>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="ink-2 text-[13px]">Spent</p>
                  <p className="display mt-0.5 text-[24px]">{money0(d.month.expense)}</p>
                  {prev && (
                    <p className="mt-1 flex items-center gap-1.5 text-[12px]">
                      <Delta now={d.month.expense} before={prev.expense} goodWhenUp={false} />
                      <span className="ink-3">vs last month</span>
                    </p>
                  )}
                </div>
                <div>
                  <p className="ink-2 text-[13px]">Received</p>
                  <p className="display mt-0.5 text-[24px] text-[var(--positive)]">{money0(d.month.income)}</p>
                </div>
              </div>
              {d.month.expenseByCategory.length > 0 && (
                <div className="mt-4 border-t border-[var(--line)] pt-3">
                  <CategoryList items={d.month.expenseByCategory} kind="expense" limit={3} onSelect={(c) => nav(c.categoryId ? `/transactions?categoryId=${c.categoryId}` : '/transactions?uncategorized=true&direction=DEBIT')} />
                </div>
              )}
            </Card>
          </Section>

          {f?.budgets && (budgets.data?.length ?? 0) > 0 && (
            <Section title="Budgets" action={<More to="/budgets">All budgets</More>}>
              <List>
                {[...budgets.data!].sort((a, b) => b.pct - a.pct).slice(0, 3).map((b) => (
                  <Link key={b.categoryId} to="/budgets" className="row block px-4 py-3">
                    <BudgetLine b={b} />
                  </Link>
                ))}
              </List>
            </Section>
          )}

          <Section title="Accounts" action={<More to="/accounts">Manage</More>}>
            <List>
              {d.accounts.map((a) => (
                <AccountRow key={a.id} a={a} />
              ))}
            </List>
          </Section>
        </div>

        <Section title="Recent" action={<More to="/transactions">See all</More>}>
          <List>
            {(recent.data?.items ?? []).map((tx) => (
              <TxnRow key={tx.id} t={tx} onClick={() => setOpen(tx)} />
            ))}
            {recent.data?.items.length === 0 && <p className="ink-2 p-6 text-center text-sm">No transactions yet. They appear as bank alerts arrive.</p>}
          </List>
        </Section>
      </div>

      <TxnSheet txn={open} onClose={() => setOpen(null)} />
    </Page>
  )
}

function Stat({ label, value, negative }: { label: string; value: number; negative?: boolean }) {
  return (
    <div className="surface min-w-0 rounded-2xl px-3 py-2.5">
      <p className="ink-2 truncate text-[12px]">{label}</p>
      <p className={cx('tabular mt-0.5 truncate text-[15px] font-semibold', negative && 'text-[var(--negative)]')}>
        {value < 0 ? '−' : ''}
        {money0(Math.abs(value))}
      </p>
    </div>
  )
}

function More({ to, children }: { to: string; children: string }) {
  return (
    <Link to={to} className="link flex items-center gap-0.5 text-[14px] text-[var(--accent)]">
      {children}
      <CaretRight size={12} weight="bold" />
    </Link>
  )
}

function AccountRow({ a }: { a: Account }) {
  const card = a.type === 'CREDIT_CARD'
  const Icon = card ? CreditCard : a.type === 'CASH' ? Wallet : Bank
  const c = a.card
  const owes = card && a.balance > 0
  return (
    <Link to={`/transactions?accountId=${a.id}`} className="flex items-center gap-3 px-4 py-3 row">
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[var(--surface-2)]">
        <Icon size={20} />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[15px] font-medium">{a.name}</span>
        <span className="ink-2 block truncate text-[13px]">
          {card ? (owes && c?.dueDate ? `Due ${shortDate(c.dueDate)}` : 'Credit card') : a.last4 ? `•••• ${a.last4}` : a.type === 'CASH' ? 'Cash' : 'Bank account'}
        </span>
      </span>
      <span className="shrink-0 text-right">
        <span className={cx('tabular block text-[15px] font-semibold', owes && 'text-[var(--negative)]')}>{owes ? `−${money(a.balance)}` : money(a.balance)}</span>
        {card && c?.creditLimit != null && (
          <span className="mt-1 flex items-center justify-end gap-1.5">
            <Meter pct={c.utilizationPct ?? 0} status className="w-12" />
            <span className="ink-3 tabular whitespace-nowrap text-[11px]">{Math.round(c.utilizationPct ?? 0)}% used</span>
          </span>
        )}
      </span>
    </Link>
  )
}
