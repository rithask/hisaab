import { useQuery } from '@tanstack/react-query'
import { FileText, UploadSimple } from '@phosphor-icons/react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError, type Statement, type StatementImport } from '../api'
import { Badge, Button, Empty, ErrorText, List, PageHeader, PageSkeleton, PillSelect, Page } from '../components/ui'
import { dateTime } from '../format'
import { useAccounts, useMoneyMutation } from '../hooks'

export default function Statements() {
  const list = useQuery({ queryKey: ['statements'], queryFn: () => api<Statement[]>('/statements') })
  const accounts = useAccounts()
  const assign = useMoneyMutation(({ id, accountId }: { id: number; accountId: number | null }) => api(`/statements/${id}`, { method: 'PATCH', body: { accountId } }))
  const nav = useNavigate()
  // Saved PDFs: try remembered passwords first, and only ask when none of them opens the file.
  const importSaved = useMoneyMutation(
    async (id: number) => {
      try {
        return await api<StatementImport>(`/statements/${id}/import`, { body: {} })
      } catch (e) {
        if (!(e instanceof ApiError) || e.code !== 'PASSWORD') throw e
        const password = window.prompt('This statement is password protected. Enter its password:')
        if (!password) throw new Error('Import cancelled: the statement needs its password.')
        return api<StatementImport>(`/statements/${id}/import`, { body: { password, remember: true } })
      }
    },
    (r) => nav(`/import/${r.id}`),
  )
  if (!list.data) return <PageSkeleton />
  return (
    <Page>
      <PageHeader
        back
        title="Statements"
        subtitle="Saved statement PDFs"
        action={
          <Button size="sm" onClick={() => nav('/import')}>
            <UploadSimple size={16} /> Import
          </Button>
        }
      />
      <ErrorText error={importSaved.error} />
      {list.data.length === 0 ? (
        <Empty icon={FileText} title="No statements yet">
          Statement emails with PDF attachments are saved here automatically, or import one with the button above.
        </Empty>
      ) : (
        <List>
          {list.data.map((s) => (
            <div key={s.id} className="flex items-center gap-3 px-4 py-3">
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-rose-500/12 text-rose-600 dark:text-rose-400">
                <FileText size={20} />
              </span>
              <a href={`/api/statements/${s.id}/file`} target="_blank" rel="noreferrer" className="min-w-0 flex-1">
                <p className="truncate text-[15px] font-semibold">{s.fileName}</p>
                <p className="ink-2 text-xs">
                  {s.periodLabel} · {s.fromEmail ? 'from email' : 'uploaded'} · {dateTime(s.receivedAt)}
                </p>
                {s.importNote && s.importStatus !== 'REVIEW' && <p className="ink-3 mt-0.5 text-xs">{s.importNote}</p>}
              </a>
              <StatusAction s={s} busy={importSaved.isPending} onImport={() => importSaved.mutate(s.id)} />
              <PillSelect className="max-w-32" value={s.account?.id ?? ''} onChange={(e) => assign.mutate({ id: s.id, accountId: e.target.value ? Number(e.target.value) : null })} aria-label="Account">
                <option value="">Unassigned</option>
                {(accounts.data ?? []).map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
              </PillSelect>
            </div>
          ))}
        </List>
      )}
      <p className="ink-3 mt-4 px-1 text-xs">Bank statement PDFs are usually password protected; they're stored exactly as received.</p>
    </Page>
  )
}

/** What can be done with a saved statement right now, as one small button or label. */
function StatusAction({ s, busy, onImport }: { s: Statement; busy: boolean; onImport: () => void }) {
  if (s.importStatus === 'IMPORTED') return <Badge tone="green">Imported</Badge>
  if (s.importStatus === 'UNSUPPORTED') return <Badge>Not supported</Badge>
  const label = s.importStatus === 'REVIEW' ? 'Review' : s.importStatus === 'NEEDS_PASSWORD' ? 'Unlock' : 'Import'
  return (
    <Button size="sm" variant={s.importStatus === 'REVIEW' || s.importStatus === 'NEEDS_PASSWORD' ? 'primary' : 'secondary'} disabled={busy} onClick={onImport}>
      {label}
    </Button>
  )
}
