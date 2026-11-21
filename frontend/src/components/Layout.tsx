import { ArrowsLeftRight, Bank, Repeat, ChartBar, ChartLineUp, FileText, GearSix, House, Plus, Target, Users, type Icon as PhIcon } from '@phosphor-icons/react'
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

/**
 * The rest, in the sidebar on wide screens and under the menu button on phones. Kept short on purpose: the other
 * pages are reached from where they belong (unusual payments from To check, net worth from Home's number, rewards
 * from each card, categories and messages from Settings). Optional features hide when switched off.
 */
export const secondary: (Item & { hint: string })[] = [
  { to: '/accounts', label: 'Accounts & cards', hint: 'Balances, limits, due dates, rewards', icon: Bank, match: (p) => p.startsWith('/accounts') || p.startsWith('/rewards') },
  { to: '/reports', label: 'Insights', hint: 'Where money goes and comes from', icon: ChartBar, match: (p) => p.startsWith('/reports') },
  { to: '/repeating', label: 'Repeating', hint: 'Bills and subscriptions', icon: Repeat, match: (p) => ['/repeating', '/bills', '/subscriptions'].some((x) => p.startsWith(x)) },
  { to: '/budgets', label: 'Budgets', hint: 'Monthly limits per category', icon: Target, match: (p) => p.startsWith('/budgets'), feature: 'budgets' },
  { to: '/statements', label: 'Statements', hint: 'Import a statement PDF, saved ones', icon: FileText, match: (p) => p.startsWith('/statements') || p.startsWith('/import') },
  { to: '/settings', label: 'Settings', hint: 'Mail, categories, backups', icon: GearSix, match: (p) => ['/settings', '/more', '/categories', '/inbox'].some((x) => p.startsWith(x)) },
]

/** Whether a menu item's feature, if it has one, is switched on. */
export const shown = (i: (typeof secondary)[number], f: Partial<Record<Feature, boolean>> | undefined) => !i.feature || f?.[i.feature] !== false

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
  const rest = secondary.filter((i) => settings.data && shown(i, settings.data.features))

  return (
    <AddContext.Provider value={(accountId) => setAdding({ accountId })}>
      <div className="min-h-dvh lg:flex">
        {/* Sidebar (wide screens) */}
        <aside className="no-scrollbar sticky top-0 hidden h-dvh w-64 shrink-0 flex-col overflow-y-auto border-r border-line bg-surface px-4 py-6 lg:flex [&>*]:shrink-0">
          <p className="px-3 text-title font-bold tracking-tight">Hisaab</p>
          <button onClick={add} className="pressable accent-fill mt-6 flex h-11 items-center justify-center gap-2 rounded-full text-body font-semibold">
            <Plus size={18} weight="bold" /> Add transaction
          </button>
          <nav aria-label="Main" className="mt-6 space-y-0.5">
            {main.map((i) => (
              <SideLink key={i.to} item={i} on={i.match(pathname)} />
            ))}
          </nav>
          <div className="my-4 border-t border-line" />
          <nav aria-label="More" className="space-y-0.5">
            {rest.map((i) => (
              <SideLink key={i.to} item={i} on={i.match(pathname)} />
            ))}
          </nav>
        </aside>

        {!online && (
          <div role="status" className="pointer-events-none fixed inset-x-0 top-[max(env(safe-area-inset-top),0.5rem)] z-[60] flex justify-center">
            <span className="rounded-full bg-ink px-3 py-1.5 text-caption font-medium text-bg shadow-[var(--shadow-float)]">
              Offline · showing what was last loaded
            </span>
          </div>
        )}
        {/* Each page mounts fresh and plays its own short fade (see <Page>). */}
        <main key={pathname} className="mx-auto w-full max-w-2xl px-4 pb-28 pt-[max(env(safe-area-inset-top),1rem)] sm:px-6 lg:max-w-3xl lg:px-10 lg:pb-12 lg:pt-10">
          <Outlet />
        </main>

        {/* Bottom tab bar (phones and tablets) */}
        <nav aria-label="Main" className="glass fixed inset-x-0 bottom-0 z-50 border-t border-line pb-safe lg:hidden">
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
    <NavLink to={to} className={cx('flex flex-1 flex-col items-center justify-center gap-1 pressable', on ? 'text-ink' : 'text-ink-3 hover:text-ink-2')}>
      <Icon size={24} weight={on ? 'fill' : 'regular'} />
      <span className="text-micro font-medium">{label}</span>
    </NavLink>
  )
}

function SideLink({ item: { to, label, icon: Icon }, on }: { item: Item; on: boolean }) {
  return (
    <NavLink
      to={to}
      className={cx('flex h-10 items-center gap-3 rounded-field px-3 text-body transition-colors duration-quick', on ? 'bg-press font-semibold text-ink' : 'text-ink-2 hover:bg-hover hover:text-ink active:bg-press')}
    >
      <Icon size={20} weight={on ? 'fill' : 'regular'} />
      {label}
    </NavLink>
  )
}
