import { ArrowsLeftRight, Bank, CalendarCheck, Gift, Repeat, ShieldWarning, ChartBar, ChartLineUp, FileText, GearSix, House, Plus, Sparkle, Tag, Target, Tray, TrendUp, Users, type Icon as PhIcon } from '@phosphor-icons/react'
import { createContext, useContext, useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import type { Feature } from '../api'
import { useSettings } from '../hooks'
import { useOnline } from '../pwa'
import { AddTxnSheet } from './TxnSheet'
import { cx } from './ui'

type Item = { to: string; label: string; icon: PhIcon; match: (p: string) => boolean; feature?: Feature }

const main: Item[] = [
  { to: '/', label: 'Home', icon: House, match: (p) => p === '/' },
  { to: '/transactions', label: 'Activity', icon: ArrowsLeftRight, match: (p) => p.startsWith('/transactions') },
  { to: '/people', label: 'Splits', icon: Users, match: (p) => p.startsWith('/people') },
  { to: '/invest', label: 'Invest', icon: ChartLineUp, match: (p) => p.startsWith('/invest') },
]

/** Shown in the sidebar on wide screens; on phones they live under the profile button on Home. */
const secondary: Item[] = [
  { to: '/accounts', label: 'Accounts', icon: Bank, match: (p) => p.startsWith('/accounts') },
  { to: '/reports', label: 'Insights', icon: ChartBar, match: (p) => p.startsWith('/reports') },
  { to: '/inbox', label: 'Inbox', icon: Tray, match: (p) => p.startsWith('/inbox') },
  { to: '/categories', label: 'Categories', icon: Tag, match: (p) => p.startsWith('/categories') },
  { to: '/statements', label: 'Statements', icon: FileText, match: (p) => p.startsWith('/statements') || p.startsWith('/import') },
  { to: '/settings', label: 'Settings', icon: GearSix, match: (p) => p.startsWith('/settings') || p.startsWith('/more') },
]

/** Optional features (Settings → Features); hidden from the sidebar when switched off. */
const planning: Item[] = [
  { to: '/bills', label: 'Bills', icon: CalendarCheck, match: (p) => p.startsWith('/bills'), feature: 'bills' },
  { to: '/budgets', label: 'Budgets', icon: Target, match: (p) => p.startsWith('/budgets'), feature: 'budgets' },
  { to: '/subscriptions', label: 'Subscriptions', icon: Repeat, match: (p) => p.startsWith('/subscriptions'), feature: 'subscriptions' },
  { to: '/unusual', label: 'Unusual activity', icon: ShieldWarning, match: (p) => p.startsWith('/unusual'), feature: 'unusual' },
  { to: '/rewards', label: 'Rewards', icon: Gift, match: (p) => p.startsWith('/rewards'), feature: 'rewards' },
  { to: '/networth', label: 'Net worth', icon: TrendUp, match: (p) => p.startsWith('/networth'), feature: 'networth' },
  { to: '/review', label: 'Year in review', icon: Sparkle, match: (p) => p.startsWith('/review'), feature: 'review' },
]

const AddContext = createContext<(accountId?: number) => void>(() => {})
export const useAddTransaction = () => useContext(AddContext)

/**
 * Phones: content on top, a standard bottom tab bar with the Add button in the middle.
 * Wide screens: a fixed sidebar with every section, and the content in a readable column.
 */
export default function Layout() {
  const { pathname } = useLocation()
  // The home-screen shortcut "Add transaction" opens /?add.
  const [adding, setAdding] = useState<{ accountId?: number } | null>(() => (new URLSearchParams(window.location.search).has('add') ? {} : null))
  useEffect(() => {
    if (new URLSearchParams(window.location.search).has('add')) window.history.replaceState(null, '', window.location.pathname)
  }, [])
  const online = useOnline()
  const add = () => setAdding({})
  const settings = useSettings()
  const planningOn = planning.filter((i) => settings.data && settings.data.features?.[i.feature!] !== false)

  return (
    <AddContext.Provider value={(accountId) => setAdding({ accountId })}>
      <div className="min-h-dvh lg:flex">
        {/* Sidebar (wide screens) */}
        <aside className="no-scrollbar sticky top-0 hidden h-dvh w-64 shrink-0 flex-col overflow-y-auto border-r border-[var(--line)] bg-[var(--surface)] px-4 py-6 lg:flex [&>*]:shrink-0">
          <p className="px-3 text-[17px] font-bold tracking-tight">Hisaab</p>
          <button onClick={add} className="pressable accent-fill mt-6 flex h-11 items-center justify-center gap-2 rounded-full text-[15px] font-semibold">
            <Plus size={18} weight="bold" /> Add transaction
          </button>
          <nav aria-label="Main" className="mt-6 space-y-0.5">
            {main.map((i) => (
              <SideLink key={i.to} item={i} on={i.match(pathname)} />
            ))}
          </nav>
          {planningOn.length > 0 && (
            <>
              <div className="my-4 border-t border-[var(--line)]" />
              <nav aria-label="Planning" className="space-y-0.5">
                {planningOn.map((i) => (
                  <SideLink key={i.to} item={i} on={i.match(pathname)} />
                ))}
              </nav>
            </>
          )}
          <div className="my-4 border-t border-[var(--line)]" />
          <nav aria-label="More" className="space-y-0.5">
            {secondary.map((i) => (
              <SideLink key={i.to} item={i} on={i.match(pathname)} />
            ))}
          </nav>
        </aside>

        {!online && (
          <div role="status" className="pointer-events-none fixed inset-x-0 top-[max(env(safe-area-inset-top),0.5rem)] z-[60] flex justify-center">
            <span className="rounded-full bg-[var(--ink)] px-3 py-1.5 text-[12.5px] font-medium text-[var(--bg)] shadow-[var(--shadow-float)]">
              Offline · showing what was last loaded
            </span>
          </div>
        )}
        {/* Each page mounts fresh and plays its own short fade (see <Page>). */}
        <main key={pathname} className="mx-auto w-full max-w-2xl px-4 pb-28 pt-[max(env(safe-area-inset-top),1rem)] sm:px-6 lg:max-w-3xl lg:px-10 lg:pb-12 lg:pt-10">
          <Outlet />
        </main>

        {/* Bottom tab bar (phones and tablets) */}
        <nav aria-label="Main" className="glass fixed inset-x-0 bottom-0 z-50 border-t border-[var(--line)] pb-safe lg:hidden">
          <div className="mx-auto flex h-16 max-w-2xl items-stretch">
            {main.slice(0, 2).map((i) => (
              <TabLink key={i.to} item={i} on={i.match(pathname)} />
            ))}
            <div className="flex flex-1 items-center justify-center">
              <button onClick={add} aria-label="Add transaction" className="pressable accent-fill flex h-12 w-12 items-center justify-center rounded-full">
                <Plus size={22} weight="bold" />
              </button>
            </div>
            {main.slice(2).map((i) => (
              <TabLink key={i.to} item={i} on={i.match(pathname)} />
            ))}
          </div>
        </nav>

        <AddTxnSheet open={!!adding} onClose={() => setAdding(null)} defaultAccountId={adding?.accountId} />
      </div>
    </AddContext.Provider>
  )
}

function TabLink({ item: { to, label, icon: Icon }, on }: { item: Item; on: boolean }) {
  return (
    <NavLink to={to} className={cx('flex flex-1 flex-col items-center justify-center gap-1 transition-[color,transform] duration-150 active:scale-95', on ? 'text-[var(--ink)]' : 'text-[var(--ink-3)] hover:text-[var(--ink-2)]')}>
      <Icon size={24} weight={on ? 'fill' : 'regular'} />
      <span className="text-[11px] font-medium">{label}</span>
    </NavLink>
  )
}

function SideLink({ item: { to, label, icon: Icon }, on }: { item: Item; on: boolean }) {
  return (
    <NavLink
      to={to}
      className={cx('flex h-10 items-center gap-3 rounded-xl px-3 text-[15px] transition-colors duration-150', on ? 'bg-[var(--press)] font-semibold text-[var(--ink)]' : 'text-[var(--ink-2)] hover:bg-[var(--hover)] hover:text-[var(--ink)] active:bg-[var(--press)]')}
    >
      <Icon size={20} weight={on ? 'fill' : 'regular'} />
      {label}
    </NavLink>
  )
}
