import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Bank, CalendarCheck, Gift, Repeat, ShieldWarning, CaretRight, ChartBar, FileText, GearSix, SignOut, Sparkle, Tag, Target, Tray, TrendUp, UploadSimple, type Icon as PhIcon } from '@phosphor-icons/react'
import { Link } from 'react-router-dom'
import { api, type Feature, type Settings as S } from '../api'
import { clearSavedData } from '../pwa'
import { SIGNED_OUT_KEY } from './Login'
import { Avatar, Badge, List, PageHeader, Page, Rise } from '../components/ui'

type Item = { to: string; icon: PhIcon; label: string; hint: string; feature?: Feature }

const groups: Item[][] = [
  [
    { to: '/bills', icon: CalendarCheck, label: 'Bills', hint: 'Upcoming, learned from repeating payments', feature: 'bills' },
    { to: '/budgets', icon: Target, label: 'Budgets', hint: 'Monthly limits per category', feature: 'budgets' },
    { to: '/subscriptions', icon: Repeat, label: 'Subscriptions', hint: 'Price rises, double charges, new ones', feature: 'subscriptions' },
    { to: '/unusual', icon: ShieldWarning, label: 'Unusual activity', hint: 'Payments worth a second look, refunds', feature: 'unusual' },
    { to: '/rewards', icon: Gift, label: 'Rewards', hint: 'Cashback and points on your cards', feature: 'rewards' },
    { to: '/networth', icon: TrendUp, label: 'Net worth', hint: 'How it has changed over time', feature: 'networth' },
    { to: '/review', icon: Sparkle, label: 'Year in review', hint: 'Your year at a glance', feature: 'review' },
  ],
  [
    { to: '/accounts', icon: Bank, label: 'Accounts & cards', hint: 'Balances, limits, billing cycles' },
    { to: '/reports', icon: ChartBar, label: 'Insights', hint: 'Where money goes and comes from' },
    { to: '/categories', icon: Tag, label: 'Categories & rules', hint: 'Auto-categorisation' },
  ],
  [
    { to: '/inbox', icon: Tray, label: 'Inbox & alerts', hint: 'Emails needing review, mismatches' },
    { to: '/import', icon: UploadSimple, label: 'Import a statement', hint: 'Add or complete transactions from a PDF' },
    { to: '/statements', icon: FileText, label: 'Statements', hint: 'Saved PDF statements' },
    { to: '/settings', icon: GearSix, label: 'Settings', hint: 'Your name, mailbox sync' },
  ],
]

export default function More() {
  const qc = useQueryClient()
  const s = useQuery({ queryKey: ['settings'], queryFn: () => api<S>('/settings') })
  return (
    <Page>
      <PageHeader back title="Menu" />
      <div className="surface flex items-center gap-4 rounded-[20px] p-4">
        <Avatar name={s.data?.ownerName ?? 'Me'} size={56} />
        <div className="min-w-0 flex-1">
          <p className="truncate text-[17px] font-semibold">{s.data?.ownerName ?? 'Set your name'}</p>
          <div className="mt-1">{s.data?.mail.configured ? <Badge tone="green">Mailbox connected</Badge> : <Badge tone="amber">Mailbox not connected</Badge>}</div>
        </div>
      </div>
      {groups.map((all) => all.filter((x) => !x.feature || s.data?.features?.[x.feature] !== false)).filter((g) => g.length > 0).map((g, i) => (
        <Rise key={i} className="mt-5">
        <List>
          {g.map(({ to, icon: Icon, label, hint }) => (
            <Link key={to} to={to} className="flex items-center gap-3.5 px-4 py-3.5 row">
              <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--surface-2)] text-[var(--ink)]">
                <Icon size={20} />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-[15px] font-medium">{label}</span>
                <span className="ink-2 block truncate text-[12.5px]">{hint}</span>
              </span>
              <CaretRight size={16} className="ink-3" />
            </Link>
          ))}
        </List>
        </Rise>
      ))}
      <button
        className="pressable surface mt-5 flex h-12 w-full items-center justify-center gap-2 rounded-[20px] text-[15px] font-semibold text-[var(--negative)]"
        onClick={async () => {
          await api('/auth/logout', { method: 'POST' }).catch(() => undefined)
          await clearSavedData()
          try {
            sessionStorage.setItem(SIGNED_OUT_KEY, '1') // don't bounce straight back to the identity provider
          } catch {
            // storage unavailable
          }
          // Mark signed out first so the app switches to the sign-in screen, then drop everything else.
          // (qc.clear() would also remove the "me" query the app is watching, so the switch never happened.)
          qc.setQueryData(['me'], null)
          qc.removeQueries({ predicate: (q) => q.queryKey[0] !== 'me' })
        }}
      >
        <SignOut size={17} /> Sign out
      </button>
    </Page>
  )
}
