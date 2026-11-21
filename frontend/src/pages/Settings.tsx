import { useQuery } from '@tanstack/react-query'
import { CaretRight, Database, Envelope, GraduationCap, LockSimple, SlidersHorizontal, Tag, ToggleRight, Tray, type Icon as PhIcon } from '@phosphor-icons/react'
import { type ReactNode } from 'react'
import { Link, Navigate, useParams } from 'react-router-dom'
import { api, type BackupStatus } from '../api'
import { Badge, cx, List, PageHeader, Page, IconBadge } from '../components/ui'
import { useSettings } from '../hooks'
import { GeneralSection } from './settings/General'
import { FeaturesCard } from './settings/Features'
import { MailSection } from './settings/Mail'
import { LearnedCard } from './settings/Learned'
import { SecuritySection } from './settings/Security'
import { DataSection, backupFailing } from './settings/Data'

type SectionKey = 'general' | 'features' | 'mail' | 'teaching' | 'security' | 'data'

const SECTIONS: { key: SectionKey; label: string; icon: PhIcon; hint: string }[] = [
  { key: 'general', label: 'General', icon: SlidersHorizontal, hint: 'Your name, Home, install the app' },
  { key: 'features', label: 'Features', icon: ToggleRight, hint: 'Turn optional parts of the app on or off' },
  { key: 'mail', label: 'Mail & SMS', icon: Envelope, hint: 'Alerts mailbox, forwarding bank SMS' },
  { key: 'teaching', label: 'What it learned', icon: GraduationCap, hint: 'From your corrections: wordings, payee names' },
  { key: 'security', label: 'Security', icon: LockSimple, hint: 'Sign-in, API tokens, signing out' },
  { key: 'data', label: 'Import & backups', icon: Database, hint: 'Older emails, nightly backups, reset' },
]

/** Pages of their own that belong with the settings, listed after the sections. */
const PAGES: { to: string; label: string; icon: PhIcon; hint: string }[] = [
  { to: '/categories', label: 'Categories & rules', icon: Tag, hint: 'Your categories and auto-filing' },
  { to: '/inbox', label: 'Messages', icon: Tray, hint: 'Bank emails and SMS, paste one' },
]

/**
 * Settings is a menu of sections. On phones the menu and each section are separate pages
 * (/settings, /settings/mail, …); on wide screens the menu stays on the left and the section shows beside it.
 */
export default function Settings() {
  const { section } = useParams()
  const current = SECTIONS.find((x) => x.key === section)
  if (section && !current) return <Navigate to="/settings" replace />
  const shown = current ?? SECTIONS[0]
  return (
    <Page key={section ?? ''}>
      <div className="lg:grid lg:grid-cols-[300px_minmax(0,1fr)] lg:items-start lg:gap-8">
        <div className={cx(section && 'hidden lg:block')}>
          <PageHeader back title="Settings" />
          <SectionMenu active={shown.key} />
          <AppVersion />
        </div>
        <div className={cx(!section && 'hidden lg:block', '[&>section:first-child]:mt-0')}>
          <PageHeader back title={shown.label} />
          {shown.key === 'general' && <GeneralSection />}
          {shown.key === 'features' && <FeaturesCard />}
          {shown.key === 'mail' && <MailSection />}
          {shown.key === 'teaching' && <LearnedCard />}
          {shown.key === 'security' && <SecuritySection />}
          {shown.key === 'data' && <DataSection />}
        </div>
      </div>
    </Page>
  )
}

/** The list of sections, with a badge where something needs attention (mailbox not set up, backups failing). */
function SectionMenu({ active }: { active: SectionKey }) {
  const s = useSettings()
  const backupsOn = s.data?.features?.backups !== false
  const backups = useQuery({ queryKey: ['backups'], queryFn: () => api<BackupStatus>('/backups'), enabled: !!s.data && backupsOn })
  const b = backups.data
  const badge: Partial<Record<SectionKey, ReactNode>> = {
    mail: s.data && !s.data.mail.configured ? <Badge tone="attention">not set up</Badge> : null,
    data: b && backupFailing(b) ? <Badge tone="negative">failing</Badge> : null,
  }
  return (
    <List>
      {SECTIONS.map(({ key, icon: Icon, label, hint }) => (
        <Link key={key} to={`/settings/${key}`} aria-current={key === active ? 'page' : undefined} className="row flex items-center gap-3.5 px-4 py-3.5 lg:aria-[current=page]:bg-surface-2">
          <IconBadge icon={Icon} />
          <span className="min-w-0 flex-1">
            <span className="block text-body font-medium">{label}</span>
            <span className="ink-2 block truncate text-caption">{hint}</span>
          </span>
          {badge[key]}
          <CaretRight size={16} className="ink-3 shrink-0" />
        </Link>
      ))}
      {PAGES.map(({ to, icon: Icon, label, hint }) => (
        <Link key={to} to={to} className="row flex items-center gap-3.5 px-4 py-3.5">
          <IconBadge icon={Icon} />
          <span className="min-w-0 flex-1">
            <span className="block text-body font-medium">{label}</span>
            <span className="ink-2 block truncate text-caption">{hint}</span>
          </span>
          <CaretRight size={16} className="ink-3 shrink-0" />
        </Link>
      ))}
    </List>
  )
}

function AppVersion() {
  const h = useQuery({ queryKey: ['health'], queryFn: () => api<{ version?: string }>('/health'), staleTime: Infinity })
  return <p className="ink-3 mt-8 text-center text-caption">Hisaab {h.data?.version ?? ''}</p>
}
