import { useQuery } from '@tanstack/react-query'
import { CaretRight, SignOut } from '@phosphor-icons/react'
import { Link } from 'react-router-dom'
import { api, type Settings as S } from '../api'
import { secondary, shown } from '../components/Layout'
import { useSignOut } from '../hooks'
import { Avatar, Badge, List, PageHeader, Page, Rise } from '../components/ui'

export default function More() {
  const signOut = useSignOut()
  const s = useQuery({ queryKey: ['settings'], queryFn: () => api<S>('/settings') })
  return (
    <Page>
      <PageHeader back title="Menu" />
      <div className="surface flex items-center gap-4 rounded-card p-4">
        <Avatar name={s.data?.ownerName ?? 'Me'} size={56} />
        <div className="min-w-0 flex-1">
          <p className="truncate text-title font-semibold">{s.data?.ownerName ?? 'Set your name'}</p>
          <div className="mt-1">{s.data?.mail.configured ? <Badge tone="positive">Mailbox connected</Badge> : <Badge tone="attention">Mailbox not connected</Badge>}</div>
        </div>
      </div>
      <Rise className="mt-5">
        <List>
          {secondary.filter((i) => shown(i, s.data?.features)).map(({ to, icon: Icon, label, hint }) => (
            <Link key={to} to={to} className="flex items-center gap-3.5 px-4 py-3.5 row">
              <span className="flex h-10 w-10 items-center justify-center rounded-full bg-surface-2 text-ink">
                <Icon size={20} />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-body font-medium">{label}</span>
                <span className="ink-2 block truncate text-caption">{hint}</span>
              </span>
              <CaretRight size={16} className="ink-3" />
            </Link>
          ))}
        </List>
      </Rise>
      <button
        className="pressable surface mt-5 flex h-12 w-full items-center justify-center gap-2 rounded-card text-body font-semibold text-negative"
        onClick={signOut}
      >
        <SignOut size={17} /> Sign out
      </button>
    </Page>
  )
}
