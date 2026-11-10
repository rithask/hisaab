import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, CheckCircle, FilePdf, Question, UploadSimple, WarningCircle } from '@phosphor-icons/react'
import { useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, type ImportLine, type ImportResult, type LineDecision, type StatementImport } from '../api'
import { Badge, Button, Card, Chip, cx, ErrorText, Field, Input, List, Page, PageHeader, PageSkeleton, Section, Toggle } from '../components/ui'
import { formatDay, money, shortDate, timeZone } from '../format'
import { useAccounts, useMoneyMutation } from '../hooks'

const dayLabel = (d: string) => formatDay(d, { day: 'numeric', month: 'short' })
const timeLabel = (t?: string) => (t ? new Date(`2000-01-01T${t}`).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit' }) : null)
const signed = (l: { direction: string; amount: number }) => (l.direction === 'CREDIT' ? '+' : '−') + money(l.amount)
/** Details worth telling me about; the bank's raw description is saved too, but quietly. */
const visibleFills = (l: ImportLine) => l.fills.filter((f) => f.field !== 'description')

/** /import: pick a statement PDF (and its password). /import/:id: review it before anything is saved. */
export default function ImportStatement() {
  const { id } = useParams()
  return id ? <Review id={Number(id)} /> : <Upload />
}

// ---- step 1: upload -------------------------------------------------------------------------------------

function Upload() {
  const nav = useNavigate()
  const fileRef = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [password, setPassword] = useState('')
  const [remember, setRemember] = useState(true)
  const pending = useQuery({ queryKey: ['statement-imports'], queryFn: () => api<StatementImport[]>('/statement-imports') })

  const upload = useMoneyMutation(
    () => {
      const f = new FormData()
      f.append('file', file!)
      if (password) f.append('password', password)
      f.append('remember', String(remember))
      return api<StatementImport>('/statement-imports', { form: f })
    },
    (r) => nav(`/import/${r.id}`, { replace: true }),
  )
  const err = upload.error
  const needPassword = err instanceof ApiError && err.code === 'PASSWORD'

  return (
    <Page>
      <PageHeader back title="Import statement" subtitle="Add or complete transactions from a bank or card statement" />

      <Card className="p-5">
        <input ref={fileRef} type="file" accept="application/pdf,.pdf" className="hidden" onChange={(e) => (setFile(e.target.files?.[0] ?? null), upload.reset())} />
        <button onClick={() => fileRef.current?.click()} className="pressable flex w-full items-center gap-3 rounded-2xl border border-dashed border-[var(--line-strong)] p-4 text-left">
          <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-[var(--surface-2)]">{file ? <FilePdf size={22} /> : <UploadSimple size={22} />}</span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-[15px] font-medium">{file ? file.name : 'Choose a statement PDF'}</span>
            <span className="ink-2 block text-[13px]">{file ? `${Math.round(file.size / 1024)} KB · tap to change` : 'Canara, Axis bank, Axis card or HDFC card'}</span>
          </span>
        </button>

        <div className="mt-4 space-y-3">
          <Field label="PDF password" hint={needPassword ? undefined : 'Leave empty if the file has no password, or if you saved it before.'}>
            <Input value={password} onChange={(e) => setPassword(e.target.value)} type="password" autoComplete="off" placeholder={needPassword ? 'This statement needs its password' : 'Optional'} />
          </Field>
          <Toggle checked={remember} onChange={setRemember} label="Remember the password for this account" />
        </div>
        <div className="mt-4">
          <ErrorText error={err} />
        </div>
        <Button className="mt-3 w-full" disabled={!file || upload.isPending} onClick={() => upload.mutate(undefined)}>
          {upload.isPending ? 'Reading statement…' : 'Read statement'}
        </Button>
        <p className="ink-3 mt-3 text-center text-[13px]">Nothing is saved until you review and confirm.</p>
      </Card>

      {(pending.data ?? []).length > 0 && (
        <Section title="Waiting for your review">
          <List>
            {pending.data!.map((p) => (
              <Link key={p.id} to={`/import/${p.id}`} className="flex items-center gap-3 px-4 py-3 row">
                <FilePdf size={20} className="ink-2" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[15px] font-medium">{p.label}</span>
                  <span className="ink-2 block text-[13px]">
                    {p.lines.length} transactions{p.from ? ` · ${shortDate(p.from)} – ${shortDate(p.to!)}` : ''}
                  </span>
                </span>
                <ArrowRight size={16} className="ink-3" />
              </Link>
            ))}
          </List>
        </Section>
      )}
    </Page>
  )
}

// ---- step 2: review -------------------------------------------------------------------------------------

function Review({ id }: { id: number }) {
  const qc = useQueryClient()
  const key = ['statement-import', id]
  const q = useQuery({ queryKey: key, queryFn: () => api<StatementImport>(`/statement-imports/${id}`) })
  const [fixStart, setFixStart] = useState(false)
  const [result, setResult] = useState<ImportResult | null>(null)
  const nav = useNavigate()
  const put = (v: StatementImport) => qc.setQueryData(key, v)

  const decide = useMoneyMutation(({ line, decision }: { line: number; decision: LineDecision }) => api<StatementImport>(`/statement-imports/${id}/lines/${line}`, { method: 'PUT', body: { decision } }), put)
  const commit = useMoneyMutation(() => api<ImportResult>(`/statement-imports/${id}/commit`, { body: { fixStart } }), setResult)
  const cancel = useMoneyMutation(() => api(`/statement-imports/${id}`, { method: 'DELETE' }), () => nav('/import', { replace: true }))

  const d = q.data
  const groups = useMemo(() => {
    const ls = d?.lines ?? []
    return {
      ask: ls.filter((l) => l.kind === 'POSSIBLE' || (l.uncertain && l.kind === 'NEW')),
      fresh: ls.filter((l) => l.kind === 'NEW' && !l.uncertain),
      known: ls.filter((l) => l.kind === 'MATCH'),
    }
  }, [d])

  if (result) return <Done result={result} account={d?.account?.name} accountId={d?.account?.id} />
  if (!d) return <PageSkeleton />
  if (d.status !== 'REVIEW')
    return (
      <Page>
        <PageHeader back title="Import statement" />
        <Card className="p-5 text-[15px]">This statement was already {d.status.toLowerCase()}.</Card>
      </Page>
    )

  const unanswered = d.lines.filter((l) => !l.decision).length
  const adding = d.lines.filter((l) => l.decision === 'ADD').length
  const filling = d.lines.filter((l) => l.decision === 'MERGE' && visibleFills(l).length > 0).length
  const checksOk = d.checks.every((c) => c.ok)
  const allBefore = d.account != null && d.lines.every((l) => l.beforeStart)

  // What the app will show at the statement's end once the chosen lines are added.
  const projected =
    d.balance &&
    d.balance.appNow + d.lines.filter((l) => l.decision === 'ADD' && !l.beforeStart && l.date <= d.balance!.at).reduce((s, l) => s + (l.balanceEffect ?? 0), 0)
  const gap = d.balance && projected != null ? Math.round((d.balance.statement - projected) * 100) / 100 : 0

  return (
    <Page className="pb-40 lg:pb-0">
      <PageHeader back title="Review statement" subtitle={`${d.label}${d.last4 ? ` ••${d.last4}` : ''}${d.from ? ` · ${shortDate(d.from)} – ${shortDate(d.to!)}` : ''}`} />

      {/* Did we read it right? */}
      <Card className="flex items-start gap-3 p-4">
        {checksOk ? <CheckCircle size={22} weight="fill" className="mt-0.5 shrink-0 text-[var(--positive)]" /> : <WarningCircle size={22} weight="fill" className="mt-0.5 shrink-0 text-[var(--negative)]" />}
        <div className="min-w-0 text-[14px]">
          <p className="font-medium">{checksOk ? `Read all ${d.lines.length} transactions` : 'Some rows may have been misread'}</p>
          <p className="ink-2 mt-0.5">
            {checksOk ? 'The totals match the ones printed on the statement.' : 'The totals below don\'t match the statement. Check the rows before importing.'}
          </p>
          {!checksOk &&
            d.checks
              .filter((c) => !c.ok)
              .map((c) => (
                <p key={c.label} className="mt-1 text-[var(--negative)]">
                  {c.label}: {c.detail}
                </p>
              ))}
        </div>
      </Card>

      {!d.account ? (
        <AccountPicker imp={d} onPicked={put} />
      ) : (
        <>
          {/* One-line summary of what will happen */}
          <p className="ink-2 mt-5 px-1 text-[15px] leading-relaxed">
            <b className="text-[var(--ink)]">{d.account.name}</b>: {groups.known.length} already recorded
            {filling > 0 ? ` (${filling} will get missing details)` : ''}, {groups.fresh.length} new
            {groups.ask.length > 0 ? `, ${groups.ask.length} need${groups.ask.length === 1 ? 's' : ''} your answer` : ''}.
            {allBefore && ` Everything is before your starting balance (${shortDate(d.accountStart!)}), so balances won't change.`}
          </p>

          {groups.ask.length > 0 && (
            <Section title={`Needs your answer (${groups.ask.length})`}>
              <div className="space-y-3">
                {groups.ask.map((l) => (
                  <AskCard key={l.id} l={l} onDecide={(decision) => decide.mutate({ line: l.id, decision })} />
                ))}
              </div>
            </Section>
          )}

          {groups.fresh.length > 0 && (
            <Section
              title={`New (${groups.fresh.length})`}
              action={
                <button
                  className="link text-[14px] text-[var(--accent)]"
                  onClick={() => {
                    const all = groups.fresh.every((l) => l.decision === 'ADD')
                    groups.fresh.forEach((l) => decide.mutate({ line: l.id, decision: all ? 'SKIP' : 'ADD' }))
                  }}
                >
                  {groups.fresh.every((l) => l.decision === 'ADD') ? 'Skip all' : 'Add all'}
                </button>
              }
            >
              <List>
                {groups.fresh.map((l) => (
                  <Row key={l.id} l={l} on={l.decision === 'ADD'} onToggle={() => decide.mutate({ line: l.id, decision: l.decision === 'ADD' ? 'SKIP' : 'ADD' })} />
                ))}
              </List>
            </Section>
          )}

          {groups.known.length > 0 && (
            <Section title={`Already recorded (${groups.known.length})`}>
              <List>
                {groups.known.map((l) => (
                  <Row
                    key={l.id}
                    l={l}
                    on={l.decision === 'MERGE'}
                    onToggle={() => decide.mutate({ line: l.id, decision: l.decision === 'MERGE' ? 'ADD' : 'MERGE' })}
                    known
                  />
                ))}
              </List>
              <p className="ink-3 mt-2 px-1 text-[13px]">Untick one if it isn't really the same transaction; it'll be added separately instead.</p>
            </Section>
          )}

          {d.notInStatement.length > 0 && (
            <Section title="In the app, not on this statement">
              <List>
                {d.notInStatement.map((t) => (
                  <div key={t.id} className="flex items-center gap-3 px-4 py-3">
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[15px]">{t.counterparty ?? 'Unnamed'}</span>
                      <span className="ink-2 block text-[13px]">
                        {dayLabel(t.occurredAt.slice(0, 10))} · from {t.source.toLowerCase()}
                      </span>
                    </span>
                    <span className="tabular text-[15px]">{signed(t)}</span>
                  </div>
                ))}
              </List>
              <p className="ink-3 mt-2 px-1 text-[13px]">Nothing is changed for these. A card transaction can show up on the next statement; otherwise check for a duplicate.</p>
            </Section>
          )}

          {d.balance && projected != null && (
            <Section title="Balance check">
              <Card>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <p className="ink-2 text-[13px]">Statement on {shortDate(d.balance.at)}</p>
                    <p className="display mt-0.5 text-[20px]">{money(d.balance.statement)}</p>
                  </div>
                  <div>
                    <p className="ink-2 text-[13px]">App after import</p>
                    <p className={cx('display mt-0.5 text-[20px]', gap !== 0 && 'text-[var(--negative)]')}>{money(projected)}</p>
                  </div>
                </div>
                {gap === 0 ? (
                  <p className="mt-3 flex items-center gap-1.5 text-[14px] text-[var(--positive)]">
                    <CheckCircle size={16} weight="fill" /> They agree.
                  </p>
                ) : (
                  <div className="mt-3 border-t border-[var(--line)] pt-3">
                    <p className="ink-2 text-[14px] leading-relaxed">
                      They differ by <b className="text-[var(--ink)]">{money(Math.abs(gap))}</b>. Usually the starting balance entered at setup was off, or a transaction is missing.
                    </p>
                    <div className="mt-2">
                      <Toggle checked={fixStart} onChange={setFixStart} label={`Correct my starting balance so they agree`} />
                    </div>
                  </div>
                )}
              </Card>
            </Section>
          )}
        </>
      )}

      {/* Always-visible action */}
      <div className="glass fixed inset-x-0 bottom-[calc(4rem+max(env(safe-area-inset-bottom),0.5rem))] z-40 border-t border-[var(--line)] px-4 py-3 lg:static lg:mt-8 lg:border-0 lg:bg-transparent lg:p-0 lg:backdrop-blur-none">
        <div className="mx-auto max-w-2xl">
          <ErrorText error={commit.error ?? decide.error} />
          <div className="flex gap-2">
            <Button variant="secondary" onClick={() => confirm('Discard this import? Nothing will be saved.') && cancel.mutate(undefined)} disabled={cancel.isPending}>
              Cancel
            </Button>
            <Button className="flex-1" disabled={!d.account || unanswered > 0 || commit.isPending} onClick={() => commit.mutate(undefined)}>
              {commit.isPending
                ? 'Importing…'
                : !d.account
                  ? 'Choose the account first'
                  : unanswered > 0
                    ? `Answer ${unanswered} question${unanswered > 1 ? 's' : ''} first`
                    : adding + filling === 0
                      ? 'Finish'
                      : `Import${adding ? ` ${adding} new` : ''}${adding && filling ? ' ·' : ''}${filling ? ` update ${filling}` : ''}`}
            </Button>
          </div>
        </div>
      </div>
    </Page>
  )
}

function Row({ l, on, onToggle, known }: { l: ImportLine; on: boolean; onToggle: () => void; known?: boolean }) {
  return (
    <button onClick={onToggle} className="flex w-full items-center gap-3 px-4 py-3 text-left row">
      <Check on={on} />
      <span className={cx('min-w-0 flex-1', !on && 'opacity-50')}>
        <span className="block truncate text-[15px]">{l.counterparty ?? l.description}</span>
        <span className="ink-2 block truncate text-[13px]">
          {dayLabel(l.date)}
          {l.time ? `, ${timeLabel(l.time)}` : ''}
          {known && visibleFills(l).length > 0 && on ? ` · adds ${visibleFills(l).map((f) => f.label.toLowerCase()).join(', ')}` : ''}
          {known && visibleFills(l).length === 0 ? ' · nothing new' : ''}
          {!known && l.beforeStart ? ' · history only' : ''}
        </span>
      </span>
      <span className={cx('tabular shrink-0 text-[15px] font-medium', l.direction === 'CREDIT' && 'text-[var(--positive)]', !on && 'opacity-50')}>{signed(l)}</span>
    </button>
  )
}

function Check({ on }: { on: boolean }) {
  return (
    <span className={cx('flex h-6 w-6 shrink-0 items-center justify-center rounded-full border-2 transition-colors duration-150', on ? 'border-[var(--ink)] bg-[var(--ink)] text-[var(--surface)]' : 'border-[var(--line-strong)]')}>
      {on && (
        <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden>
          <path d="M2.5 6.2 5 8.5l4.5-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      )}
    </span>
  )
}

/** One possible match (or an unreadable row), side by side, with the three possible answers. */
function AskCard({ l, onDecide }: { l: ImportLine; onDecide: (d: LineDecision) => void }) {
  const m = l.match
  return (
    <Card className="p-4">
      <p className="flex items-start gap-2 text-[14px]">
        <Question size={18} className="mt-0.5 shrink-0 text-[var(--accent)]" />
        <span>{m ? 'Is this the same transaction?' : l.uncertain}</span>
      </p>
      <div className={cx('mt-3 grid gap-2', m && 'grid-cols-2')}>
        <Side title="Statement" name={l.counterparty ?? l.description} when={`${dayLabel(l.date)}${l.time ? `, ${timeLabel(l.time)}` : ''}`} amount={signed(l)} />
        {m && <Side title={`In the app (${m.source.toLowerCase()})`} name={m.counterparty ?? 'Unnamed'} when={new Date(m.occurredAt).toLocaleString('en-IN', { timeZone: timeZone(), day: 'numeric', month: 'short', ...(m.timeEstimated ? {} : { hour: 'numeric', minute: '2-digit' }) })} amount={signed(m)} />}
      </div>
      {l.matchReason && <p className="ink-2 mt-2 text-[13px]">{l.matchReason}.</p>}
      <div className="mt-3 flex flex-wrap gap-2">
        {m && (
          <Chip active={l.decision === 'MERGE'} onClick={() => onDecide('MERGE')}>
            Same one
          </Chip>
        )}
        <Chip active={l.decision === 'ADD'} onClick={() => onDecide('ADD')}>
          {m ? 'Different, add it' : 'Add it'}
        </Chip>
        <Chip active={l.decision === 'SKIP'} onClick={() => onDecide('SKIP')}>
          Skip
        </Chip>
      </div>
    </Card>
  )
}

function Side({ title, name, when, amount }: { title: string; name?: string; when: string; amount: string }) {
  return (
    <div className="min-w-0 rounded-xl bg-[var(--surface-2)] p-3">
      <p className="ink-3 text-[12px]">{title}</p>
      <p className="mt-0.5 truncate text-[14px] font-medium">{name ?? '—'}</p>
      <p className="ink-2 text-[12.5px]">{when}</p>
      <p className="tabular mt-1 text-[14px] font-semibold">{amount}</p>
    </div>
  )
}

/** The statement's account isn't set up: pick one, or create it from what the statement says. */
function AccountPicker({ imp, onPicked }: { imp: StatementImport; onPicked: (v: StatementImport) => void }) {
  const accounts = useAccounts()
  const card = imp.accountType === 'CREDIT_CARD'
  const [creating, setCreating] = useState(true)
  const [name, setName] = useState(imp.label.replace(/ statement$/i, ''))
  const [balance, setBalance] = useState(imp.closing != null ? String(imp.closing) : '')
  const [limit, setLimit] = useState(imp.creditLimit != null ? String(imp.creditLimit) : '')
  const pick = useMoneyMutation((accountId: number) => api<StatementImport>(`/statement-imports/${imp.id}/account`, { method: 'PUT', body: { accountId } }), onPicked)
  const create = useMoneyMutation(
    () =>
      api<{ id: number }>('/accounts', {
        body: {
          name: name.trim(),
          type: imp.accountType,
          institution: imp.institution,
          last4: imp.last4 ?? null,
          openingBalance: Number(balance || 0),
          openingDate: new Date().toISOString(),
          creditLimit: card && limit ? Number(limit) : null,
        },
      }),
    (a) => pick.mutate(a.id),
  )
  const options = (accounts.data ?? []).filter((a) => !a.archived && (a.type === 'CREDIT_CARD') === card)

  return (
    <Section title="Which account is this?">
      <Card className="p-4">
        <p className="ink-2 text-[14px] leading-relaxed">
          No {card ? 'card' : 'account'} ending <b className="text-[var(--ink)]">{imp.last4 ?? '????'}</b> is set up yet.
        </p>
        <div className="mt-3 flex flex-wrap gap-2">
          <Chip active={creating} onClick={() => setCreating(true)}>
            Create it
          </Chip>
          {options.map((a) => (
            <Chip key={a.id} active={false} onClick={() => pick.mutate(a.id)}>
              {a.name}
            </Chip>
          ))}
        </div>
        {creating && (
          <div className="mt-4 space-y-3">
            <Field label="Name">
              <Input value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
            <Field
              label={card ? 'Outstanding today (₹)' : 'Balance today (₹)'}
              hint={card ? `The statement says ${money(imp.closing ?? 0)} was due. Check your bank app for today's figure; payments since then aren't on the statement.` : undefined}
            >
              <Input inputMode="decimal" value={balance} onChange={(e) => setBalance(e.target.value.replace(/[^\d.]/g, ''))} />
            </Field>
            {card && (
              <Field label="Credit limit (₹)">
                <Input inputMode="decimal" value={limit} onChange={(e) => setLimit(e.target.value.replace(/[^\d.]/g, ''))} />
              </Field>
            )}
            <ErrorText error={create.error ?? pick.error} />
            <Button className="w-full" disabled={!name.trim() || create.isPending || pick.isPending} onClick={() => create.mutate(undefined)}>
              Create and continue
            </Button>
            <p className="ink-3 text-[13px]">The statement's transactions are older than today, so they'll be added as history and won't change this balance.</p>
          </div>
        )}
      </Card>
    </Section>
  )
}

function Done({ result, account, accountId }: { result: ImportResult; account?: string; accountId?: number }) {
  const parts: ReactNode[] = []
  if (result.added) parts.push(`${result.added} added`)
  if (result.updated) parts.push(`${result.updated} completed with missing details`)
  if (result.skipped) parts.push(`${result.skipped} skipped`)
  return (
    <Page>
      <div className="flex flex-col items-center pt-16 text-center">
        <CheckCircle size={56} weight="fill" className="text-[var(--positive)]" />
        <h1 className="mt-4 text-[24px] font-bold">Statement imported</h1>
        <p className="ink-2 mt-1.5 max-w-xs text-[15px]">{parts.join(', ') || 'Nothing to change'}.</p>
        {result.startingBalance != null && (
          <p className="ink-2 mt-1 max-w-xs text-[15px]">
            Starting balance corrected to <b className="text-[var(--ink)]">{money(result.startingBalance)}</b>.
          </p>
        )}
        <div className="mt-6 flex w-full max-w-xs flex-col gap-2">
          {accountId && (
            <Link to={`/transactions?accountId=${accountId}`}>
              <Button className="w-full">See {account} activity</Button>
            </Link>
          )}
          <Link to="/import">
            <Button variant="secondary" className="w-full">
              Import another
            </Button>
          </Link>
        </div>
        <Badge className="mt-6">The PDF is saved under Statements</Badge>
      </div>
    </Page>
  )
}
