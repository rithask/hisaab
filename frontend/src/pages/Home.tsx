import { useQuery } from '@tanstack/react-query'
import { Bank, CaretRight, CreditCard, List as MenuIcon, Wallet } from '@phosphor-icons/react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, qs, type Account, type Bill, type Budget, type Dashboard, type NetWorthPoint, type Page as Paged, type Settings, type Txn } from '../api'
import { CategoryList, Sparkline } from '../components/charts'
import { BillList } from './Bills'
import { BudgetLine } from './Budgets'
import ToCheck from '../components/ToCheck'
import TxnRow from '../components/TxnRow'
import TxnSheet from '../components/TxnSheet'
import { BigMoney, Button, Card, cx, Delta, List, Meter, Page, PageSkeleton, Section } from '../components/ui'
import { money, money0, monthKey, shiftMonth, shortDate } from '../format'

/**
 * Home answers, top to bottom: how much do I have, what needs me (one card at a time), what did I spend this month,
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

  const prev = d.trend.find((m) => m.month === shiftMonth(month, -1))

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

      <ToCheck d={d} />

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
