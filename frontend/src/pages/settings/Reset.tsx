import { useQuery } from '@tanstack/react-query'
import { ArrowCounterClockwise } from '@phosphor-icons/react'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type ResetPart, type ResetPreview } from '../../api'
import { clearSavedData } from '../../pwa'
import { Button, Card, cx, ErrorText, Field, Input, Notice, Section, Sheet, Toggle, IconBadge } from '../../components/ui'
import { plural, joinList } from '../../format'
import { useMoneyMutation } from '../../hooks'

/** Each choice in the reset sheet: what it's called and what it would remove right now. */
const RESET_PARTS: { key: ResetPart; label: string; detail: (p: ResetPreview) => string }[] = [
  {
    key: 'DATA',
    label: 'Money data',
    detail: (p) =>
      joinList([
        p.accounts > 0 && plural(p.accounts, 'account'),
        p.transactions > 0 && plural(p.transactions, 'transaction'),
        p.people > 0 && plural(p.people, 'person', 'people'),
        p.statements > 0 && plural(p.statements, 'statement'),
        p.holdings > 0 && plural(p.holdings, 'investment'),
        p.messages > 0 && plural(p.messages, 'saved message'),
      ]) || 'Nothing saved yet',
  },
  { key: 'LEARNED', label: 'What the app learned', detail: (p) => (p.learned > 0 ? `${plural(p.learned, 'thing')} learned from your corrections: rules, payee names, SMS formats` : 'Nothing learned yet') },
  { key: 'CATEGORIES', label: 'Categories', detail: (p) => `${plural(p.categories, 'category', 'categories')} now; the built-in ones come back` },
  { key: 'SETTINGS', label: 'Settings', detail: (p) => (p.settings > 0 ? 'Feature switches, your name and Home options go back to the defaults' : 'Already at the defaults') },
  {
    key: 'CONNECTIONS',
    label: 'Connected apps',
    detail: (p) => joinList([p.tokens > 0 && plural(p.tokens, 'API token'), p.pocketIdLinked && 'the Pocket ID link']) || 'Nothing connected',
  },
]

/** Parts a choice can't go without (same rule as the server): the data takes what was learned, categories need the data. */
const needed = (chosen: Set<ResetPart>) => {
  const s = new Set(chosen)
  if (s.has('CATEGORIES')) s.add('DATA')
  if (s.has('DATA')) s.add('LEARNED')
  return s
}

const lockedBy = (key: ResetPart, chosen: Set<ResetPart>) =>
  key === 'DATA' && chosen.has('CATEGORIES') ? 'Goes with Categories' : key === 'LEARNED' && (chosen.has('DATA') || chosen.has('CATEGORIES')) ? 'Goes with Money data' : null

/** Settings → Import & backups → Reset: choose what to clear, read what it means, type "reset". */
export function ResetCard() {
  const [open, setOpen] = useState(false)
  const [done, setDone] = useState<string | null>(null)
  return (
    <Section title="Reset">
      <Card className="p-4">
        <div className="flex items-center gap-3">
          <IconBadge icon={ArrowCounterClockwise} />
          <div className="min-w-0 flex-1">
            <p className="font-medium">Start again</p>
            <p className="ink-2 text-small">Clear your data, what the app learned, or your settings. You choose which.</p>
          </div>
        </div>
        {/* under the text, like Back up now, so the text keeps the full width */}
        <Button
          variant="danger"
          size="sm"
          className="mt-3"
          onClick={() => {
            setDone(null)
            setOpen(true)
          }}
        >
          Reset
        </Button>
        {done && (
          <div className="mt-3">
            <Notice>{done}</Notice>
          </div>
        )}
      </Card>
      <ResetSheet open={open} onClose={() => setOpen(false)} onDone={setDone} />
    </Section>
  )
}

function ResetSheet({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: (msg: string) => void }) {
  const nav = useNavigate()
  const [step, setStep] = useState<'choose' | 'confirm'>('choose')
  const [chosen, setChosen] = useState<Set<ResetPart>>(new Set())
  const [word, setWord] = useState('')
  const [withoutBackup, setWithoutBackup] = useState(false)
  const q = useQuery({ queryKey: ['reset'], queryFn: () => api<ResetPreview>('/reset'), enabled: open })
  const p = q.data
  const parts = needed(chosen)
  const run = useMoneyMutation(
    () => api<{ backup?: string }>('/reset', { body: { parts: [...parts], word, withoutBackup } }),
    async (r) => {
      await clearSavedData()
      close()
      onDone(r.backup ? `Reset done. A backup from just before is saved as ${r.backup}.` : 'Reset done.')
      if (parts.has('DATA')) nav('/')
    },
  )

  // start fresh each time it opens
  useEffect(() => {
    if (open) {
      setStep('choose')
      setChosen(new Set())
      setWord('')
      setWithoutBackup(false)
      run.reset()
    }
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  function close() {
    if (!run.isPending) onClose()
  }
  const toggle = (k: ResetPart, on: boolean) =>
    setChosen((c) => {
      const n = new Set(c)
      if (on) n.add(k)
      else n.delete(k)
      return n
    })

  const shown = RESET_PARTS.filter((x) => parts.has(x.key))
  const backupOk = !!p?.backupsOn || withoutBackup
  const ready = word.trim().toLowerCase() === 'reset' && backupOk

  return (
    <Sheet open={open} onClose={close} title="Reset">
      {!p ? (
        <div className="py-6">{q.error ? <ErrorText error={q.error} /> : <p className="ink-2 text-label">Checking what's saved…</p>}</div>
      ) : step === 'choose' ? (
        <div className="flex flex-1 flex-col gap-4 pb-2">
          <p className="ink-2 text-label">Choose what to clear. Your sign-in, your backups and where mail reading got to are always kept.</p>
          <Card className="divide-y divide-line px-4">
            {RESET_PARTS.map((x) => {
              const lock = lockedBy(x.key, chosen)
              return (
                <div key={x.key} className={cx('py-3', lock && 'pointer-events-none opacity-60')}>
                  <Toggle
                    checked={parts.has(x.key)}
                    onChange={(v) => toggle(x.key, v)}
                    label={
                      <span className="block">
                        <span className="block font-medium">{x.label}</span>
                        <span className="ink-2 block text-small">{lock ?? x.detail(p)}</span>
                      </span>
                    }
                  />
                </div>
              )
            })}
          </Card>
          <Button className="mt-auto w-full" variant="danger" disabled={parts.size === 0} onClick={() => setStep('confirm')}>
            Continue
          </Button>
        </div>
      ) : (
        <div className="flex flex-1 flex-col gap-4 pb-2">
          <div className="anim-pop rounded-box bg-[color-mix(in_oklab,var(--negative)_13%,transparent)] px-4 py-3 text-label text-negative">
            <p className="font-semibold">This can't be undone.</p>
            <ul className="mt-1.5 space-y-1">
              {shown.map((x) => (
                <li key={x.key}>
                  <b>{x.label}:</b> {x.detail(p)}
                </li>
              ))}
            </ul>
          </div>
          {p.backupsOn ? (
            <p className="ink-2 text-small">A backup is taken first. If it fails, nothing is reset.</p>
          ) : (
            <Card className="px-4 py-3">
              <Toggle
                checked={withoutBackup}
                onChange={setWithoutBackup}
                label={
                  <span className="block">
                    <span className="block font-medium">Reset without a backup</span>
                    <span className="ink-2 block text-small">Backups are off, so there won't be a copy to go back to.</span>
                  </span>
                }
              />
            </Card>
          )}
          <Field label="Type “reset” to confirm">
            <Input value={word} onChange={(e) => setWord(e.target.value)} autoCapitalize="none" autoCorrect="off" autoComplete="off" spellCheck={false} enterKeyHint="done" />
          </Field>
          <ErrorText error={run.error} />
          <div className="mt-auto flex gap-2">
            <Button variant="secondary" className="flex-1" disabled={run.isPending} onClick={() => setStep('choose')}>
              Back
            </Button>
            <Button variant="danger" className="flex-1" disabled={!ready || run.isPending} onClick={() => run.mutate(undefined)}>
              {run.isPending ? (p.backupsOn ? 'Backing up…' : 'Resetting…') : 'Reset now'}
            </Button>
          </div>
        </div>
      )}
    </Sheet>
  )
}
