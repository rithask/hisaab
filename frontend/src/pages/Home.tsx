import { useQuery } from '@tanstack/react-query'
import { CaretRight, List as MenuIcon, Sparkle } from '@phosphor-icons/react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, qs, type Dashboard, type Page as Paged, type Settings, type Txn } from '../api'
import ToCheck from '../components/ToCheck'
import TxnRow from '../components/TxnRow'
import TxnSheet from '../components/TxnSheet'
import { BigMoney, Button, Card, cx, Delta, List, Page, PageSkeleton, Section } from '../components/ui'
import { money0, monthKey, shiftMonth } from '../format'

/**
 * Home answers "am I okay?", top to bottom: what I have (cash after card dues), what needs me (one card at a time),
 * what happened lately. Spending, net worth, bills, budgets and accounts are a tap away, not on Home.
 */
export default function Home() {
  const month = monthKey()
  const dash = useQuery({ queryKey: ['dashboard', month], queryFn: () => api<Dashboard>('/dashboard' + qs({ month })) })
  const settings = useQuery({ queryKey: ['settings'], queryFn: () => api<Settings>('/settings') })
  const recent = useQuery({ queryKey: ['transactions', 'recent'], queryFn: () => api<Paged<Txn>>('/transactions' + qs({ size: 6 })) })
  const [open, setOpen] = useState<Txn | null>(null)
  const nav = useNavigate()

  if (!dash.data) return <PageSkeleton />
  const d = dash.data
  const t = d.totals
  const showInvest = settings.data?.showInvestmentsOnHome ?? true

  const header = (
    <header className="flex items-center justify-between pt-1">
      <h1 className="text-heading font-bold tracking-[-0.02em]">Home</h1>
      <Link to="/more" aria-label="Menu" className="pressable flex h-10 w-10 items-center justify-center rounded-full bg-surface-2 lg:hidden">
        <MenuIcon size={20} />
      </Link>
    </header>
  )

  if (d.accounts.length === 0) {
    return (
      <Page>
        {header}
        <Card className="mt-6 p-6">
          <p className="text-subheading font-semibold">Let's set you up</p>
          <p className="ink-2 mt-1.5 text-body leading-relaxed">Add your bank accounts and cards with today's balances. Bank alerts keep them up to date from then on.</p>
          <Button className="mt-5 w-full" onClick={() => nav('/setup')}>
            Start setup
          </Button>
        </Card>
      </Page>
    )
  }

  const prev = d.trend.find((m) => m.month === shiftMonth(month, -1))
  const have = t.cash - t.cardOutstanding
  // Settings → General: investments can be left out of Home's net worth (they stay on the Invest tab).
  const worth = showInvest ? t.netWorth : t.netWorth - t.investments

  return (
    <Page>
      {header}

      {/* The one number: what's really mine to spend, cash after what I owe on cards. */}
      <section className="mt-5">
        <Link to="/accounts" className="block transition-opacity duration-quick active:opacity-60" aria-label="Accounts and cards">
          <p className="ink-2 flex items-center gap-1 text-body">
            You have <CaretRight size={13} className="ink-3" />
          </p>
          <BigMoney value={have} className={cx('mt-0.5 block text-display-sm leading-tight', have < 0 && 'text-negative')} />
          <p className="ink-2 text-small">
            {t.cardOutstanding > 0 ? `${money0(t.cash)} in your accounts, less ${money0(t.cardOutstanding)} on cards` : 'In your accounts'}
          </p>
        </Link>
        <div className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-label">
          <Link to="/reports" className="link flex items-center gap-1">
            <span className="ink-2">Spent this month</span> <span className="tabular font-semibold">{money0(d.month.expense)}</span>
            {prev && <Delta now={d.month.expense} before={prev.expense} goodWhenUp={false} />}
          </Link>
          <Link to="/networth" className="link flex items-center gap-1">
            <span className="ink-2">Net worth</span> <span className="tabular font-semibold">{money0(worth)}</span>
            <CaretRight size={12} className="ink-3" />
          </Link>
        </div>
      </section>

      <ToCheck d={d} />

      {/* Year in review comes to Home at the turn of the year, instead of living in the menu. */}
      {(new Date().getMonth() === 11 || new Date().getMonth() === 0) && (
        <Link to={`/review?year=${new Date().getMonth() === 0 ? new Date().getFullYear() - 1 : new Date().getFullYear()}`} className="pressable surface mt-4 flex items-center gap-3 rounded-card p-4">
          <Sparkle size={22} weight="fill" className="text-accent" />
          <span className="min-w-0 flex-1 text-body font-medium">Your {new Date().getMonth() === 0 ? new Date().getFullYear() - 1 : new Date().getFullYear()} in review</span>
          <CaretRight size={16} className="ink-3" />
        </Link>
      )}

      <Section title="Recent" action={<More to="/transactions">See all</More>}>
        <List>
          {(recent.data?.items ?? []).map((tx) => (
            <TxnRow key={tx.id} t={tx} onClick={() => setOpen(tx)} />
          ))}
          {recent.data?.items.length === 0 && <p className="ink-2 p-6 text-center text-label">No transactions yet. They appear as bank alerts arrive.</p>}
        </List>
      </Section>

      <TxnSheet txn={open} onClose={() => setOpen(null)} />
    </Page>
  )
}

function More({ to, children }: { to: string; children: string }) {
  return (
    <Link to={to} className="link flex items-center gap-0.5 text-label text-accent">
      {children}
      <CaretRight size={12} weight="bold" />
    </Link>
  )
}
