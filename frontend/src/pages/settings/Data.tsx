import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CloudArrowUp, DownloadSimple } from '@phosphor-icons/react'
import { useEffect, useState } from 'react'
import { api, type BackupStatus, type MailImport } from '../../api'
import { DatePicker } from '../../components/DatePicker'
import { Badge, Button, Card, Chip, ErrorText, Meter, Section, IconBadge } from '../../components/ui'
import { count, dateTime, shortDate, ymd, fileSize } from '../../format'
import { useMoneyMutation, useSettings } from '../../hooks'
import { ResetCard } from './Reset'

export function DataSection() {
  const s = useSettings()
  return (
    <>
      <ImportOlderMail configured={!!s.data?.mail.configured} />
      {s.data?.features?.backups !== false && <BackupsCard />}
      <ResetCard />
    </>
  )
}

export const backupFailing = (b: BackupStatus) => !!b.lastError && (!b.lastOk || (b.lastErrorAt ?? '') > b.lastOk)

function BackupsCard() {
  const q = useQuery({ queryKey: ['backups'], queryFn: () => api<BackupStatus>('/backups') })
  const run = useMoneyMutation(() => api('/backups', { method: 'POST' }))
  const b = q.data
  const failing = !!b && backupFailing(b)
  return (
    <Section title="Backups">
      <Card className="p-4">
        <div className="flex items-center gap-3">
          <IconBadge icon={CloudArrowUp} />
          <div className="min-w-0 flex-1">
            <p className="font-medium">Every night at 3:30 am</p>
            <p className="ink-2 truncate text-small">{b?.lastOk ? `Last backup ${dateTime(b.lastOk)}` : 'No backup yet'} · keeps {b?.keep ?? 14}</p>
          </div>
          {failing ? <Badge tone="negative">failing</Badge> : b?.lastOk ? <Badge tone="positive">ok</Badge> : null}
        </div>
        {failing && <p className="mt-3 rounded-field bg-[color-mix(in_oklab,var(--negative)_10%,transparent)] px-3 py-2 text-small text-negative">{b!.lastError}</p>}
        <ErrorText error={run.error} />
        <Button variant="secondary" size="sm" className="mt-3" disabled={run.isPending} onClick={() => run.mutate(undefined)}>
          {run.isPending ? 'Backing up…' : 'Back up now'}
        </Button>
        {(b?.backups.length ?? 0) > 0 && (
          <ul className="mt-3 divide-y divide-line border-t border-line">
            {b!.backups.slice(0, 5).map((x) => (
              <li key={x.file}>
                <a href={`/api/backups/${x.file}`} className="row -mx-2 flex items-center gap-2 rounded-lg px-2 py-2 text-small">
                  <DownloadSimple size={16} className="ink-2" />
                  <span className="min-w-0 flex-1 truncate">{dateTime(x.createdAt)}</span>
                  <span className="ink-3 tabular">{fileSize(x.sizeBytes)}</span>
                </a>
              </li>
            ))}
          </ul>
        )}
        <p className="ink-3 mt-3 text-caption">Each backup is one zip with the database and saved statements, plus restore steps inside. They're kept on the server at {b?.dir ?? 'the backups folder'}; copy them elsewhere too for safety.</p>
      </Card>
    </Section>
  )
}

/** The same day n months ago (clamped to the month's length), as yyyy-mm-dd. */
const monthsAgo = (n: number) => {
  const [y, m, d] = ymd().split('-').map(Number)
  const last = new Date(Date.UTC(y, m - 1 - n + 1, 0)).getUTCDate()
  return new Date(Date.UTC(y, m - 1 - n, Math.min(d, last))).toISOString().slice(0, 10)
}

/**
 * Pull in older alerts from the mailbox. The import runs on the server, so its progress comes from there: leaving
 * the page (or reloading) and coming back shows where it really is, and the button stays busy until it's done.
 */
function ImportOlderMail({ configured }: { configured: boolean }) {
  const qc = useQueryClient()
  const [since, setSince] = useState('')
  const status = useQuery({
    queryKey: ['mail-import'],
    queryFn: () => api<MailImport>('/mail/import'),
    // check every second while it runs; otherwise only when the page opens
    refetchInterval: (q) => (q.state.data?.state === 'CONNECTING' || q.state.data?.state === 'RUNNING' ? 1000 : false),
  })
  const s = status.data
  const running = s?.state === 'CONNECTING' || s?.state === 'RUNNING'
  const start = useMoneyMutation((d: string) => api<MailImport>('/mail/import', { body: { since: d } }), (r) => qc.setQueryData(['mail-import'], r))
  const cancel = useMoneyMutation(() => api<MailImport>('/mail/import/cancel', { method: 'POST' }), (r) => qc.setQueryData(['mail-import'], r))

  // When an import finishes, what it added should show everywhere (inbox, transactions, totals).
  const finishedAt = s?.finishedAt
  useEffect(() => {
    if (finishedAt) qc.invalidateQueries({ predicate: (q) => q.queryKey[0] !== 'me' && q.queryKey[0] !== 'mail-import' })
  }, [finishedAt, qc])

  const pct = s?.total ? (s.done / s.total) * 100 : 0
  return (
    <Section title="Import older emails">
      <Card className="space-y-4 p-5">
        <p className="ink-2 text-label">Only mail that arrives after setup is read automatically. Pick a start date to pull in older alerts. Balances only count transactions after each account's “as of” date.</p>
        <div>
          <p className="ink-2 mb-1.5 px-1 text-small font-medium">Since</p>
          <DatePicker label="Import emails since" value={running ? (s?.since ?? since) : since} max={ymd()} disabled={running} onChange={setSince} />
          {!running && (
            <div className="mt-2.5 flex flex-wrap gap-2">
              {[
                [1, '1 month ago'],
                [3, '3 months ago'],
                [6, '6 months ago'],
                [12, '1 year ago'],
              ].map(([n, text]) => {
                const d = monthsAgo(n as number)
                return (
                  <Chip key={d} active={since === d} onClick={() => setSince(d)}>
                    {text}
                  </Chip>
                )
              })}
            </div>
          )}
        </div>

        {running && s && (
          <div role="status" aria-live="polite">
            <Meter pct={pct} />
            <div className="mt-1.5 flex justify-between gap-3 text-small">
              <span className="ink-2">
                {s.state === 'CONNECTING' ? 'Signing in and finding emails…' : `${count(s.done)} of ${count(s.total ?? 0)} emails`}
              </span>
              <span className="ink-2 tabular">{s.state === 'RUNNING' ? `${count(s.added)} new · ${Math.floor(pct)}%` : ''}</span>
            </div>
          </div>
        )}
        {!running && s && s.finishedAt && (
          <p className={'text-small ' + (s.state === 'FAILED' ? 'text-negative' : 'ink-2')}>
            {s.state === 'FAILED'
              ? `The import since ${shortDate(s.since!)} stopped: ${s.error}`
              : `${s.state === 'CANCELLED' ? 'Stopped' : 'Finished'} ${dateTime(s.finishedAt)}: ${count(s.added)} new of ${count(s.done)} emails since ${shortDate(s.since!)}${s.state === 'CANCELLED' ? ` (${count((s.total ?? 0) - s.done)} not looked at)` : ''}.`}
          </p>
        )}
        <ErrorText error={start.error ?? cancel.error} />

        <div className="flex gap-2">
          <Button variant="secondary" disabled={!since || running || start.isPending || !configured} onClick={() => start.mutate(since)}>
            {running ? 'Importing…' : 'Import'}
          </Button>
          {running && (
            <Button variant="secondary" disabled={cancel.isPending} onClick={() => cancel.mutate(undefined)}>
              Stop
            </Button>
          )}
        </div>
      </Card>
    </Section>
  )
}
