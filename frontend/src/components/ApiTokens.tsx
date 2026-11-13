import { useQuery } from '@tanstack/react-query'
import { CaretRight, Key, Plus } from '@phosphor-icons/react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type ApiToken, type TokenJob } from '../api'
import { dateTime } from '../format'
import { useAccounts, useCategories, useFeature, useMoneyMutation } from '../hooks'
import { CopyButton } from './SmsPasteSheet'
import { Button, Chip, ErrorText, Field, Input, List, Notice, Segmented, Sheet, Toggle } from './ui'

export const JOBS: { value: TokenJob; label: string; hint: string }[] = [
  { value: 'SEND_SMS', label: 'Send SMS', hint: 'Forward bank SMS from your phone' },
  { value: 'READ_TRANSACTIONS', label: 'Read transactions', hint: 'Payments with payee, category and amount' },
  { value: 'ADD_TRANSACTIONS', label: 'Add transactions', hint: 'Record a spend, e.g. from a Siri shortcut' },
  { value: 'READ_BALANCES', label: 'Read balances', hint: 'Account balances and card dues' },
  { value: 'READ_TOTALS', label: 'Read totals', hint: 'Totals by category or month, no single payments' },
]

const usesCategories = (j: TokenJob) => j === 'READ_TRANSACTIONS' || j === 'ADD_TRANSACTIONS' || j === 'READ_TOTALS'
const usesAccounts = (j: TokenJob) => j !== 'SEND_SMS'
export const tokensKey = ['api-tokens']

/** "Fuel, Travel +2" for a token's limit, or "all …" */
function names(ids: (string | number)[] | null, all: string, lookup: (id: string) => string | undefined) {
  if (ids == null) return all
  const n = ids.map((i) => lookup(String(i))).filter(Boolean) as string[]
  if (!n.length) return 'nothing (what it was limited to was removed)'
  return n.length <= 2 ? n.join(', ') : `${n.slice(0, 2).join(', ')} +${n.length - 2}`
}

/** One line saying what a token can do. */
function useSummary() {
  const cats = useCategories()
  const accounts = useAccounts()
  const cat = (id: string) => (id === 'none' ? 'Uncategorised' : cats.data?.find((c) => String(c.id) === id)?.name)
  const acct = (id: string) => accounts.data?.find((a) => String(a.id) === id)?.name
  return (t: ApiToken) => {
    const c = names(t.categories, 'all categories', cat)
    const a = names(t.accounts, 'all accounts', acct)
    const money = t.moneyIn ? 'money in and out' : 'money out'
    switch (t.job) {
      case 'SEND_SMS':
        return 'Sends SMS'
      case 'READ_TRANSACTIONS':
        return `Reads ${c} · ${a} · ${money}`
      case 'ADD_TRANSACTIONS':
        return `Adds to ${a} · ${c} · ${t.moneyIn ? 'money in or out' : 'money out'}`
      case 'READ_BALANCES':
        return `Reads balances · ${a}`
      case 'READ_TOTALS':
        return `Reads totals · ${c} · ${a} · ${money}`
    }
  }
}

/**
 * The API tokens, with a way to add one. {@code jobs} narrows the list (Mail & SMS shows only Send SMS tokens);
 * {@code newJob} makes new tokens that job without asking.
 */
export function TokenList({ jobs, newJob, newLabel = 'New token' }: { jobs?: TokenJob[]; newJob?: TokenJob; newLabel?: string }) {
  const q = useQuery({ queryKey: tokensKey, queryFn: () => api<ApiToken[]>('/tokens') })
  const summary = useSummary()
  const dataApi = useFeature('data_api')
  const [editing, setEditing] = useState<ApiToken | 'new' | null>(null)
  const list = (q.data ?? []).filter((t) => !jobs || jobs.includes(t.job))
  const blocked = dataApi === false && list.some((t) => t.job !== 'SEND_SMS')
  return (
    <>
      {blocked && (
        <div className="mb-3">
          <Notice tone="amber">
            The Data API is off, so only Send SMS tokens work right now. Turn it on in{' '}
            <Link className="link font-semibold" to="/settings/features">
              Features
            </Link>
            .
          </Notice>
        </div>
      )}
      <List>
        {list.map((t) => (
          <button key={t.id} className="row flex w-full items-center gap-3 px-4 py-3 text-left" onClick={() => setEditing(t)}>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[15px] font-medium">{t.name}</span>
              <span className="ink-2 block truncate text-[12.5px]">{summary(t)}</span>
              <span className="ink-3 block text-[12px]">{t.lastUsedAt ? `Last used ${dateTime(t.lastUsedAt)}` : 'Not used yet'}</span>
            </span>
            <CaretRight size={16} className="ink-3 shrink-0" />
          </button>
        ))}
        <button className="row flex w-full items-center gap-2 px-4 py-3.5 text-[15px] font-semibold text-[var(--accent)]" onClick={() => setEditing('new')}>
          <Plus size={18} /> {newLabel}
        </button>
      </List>
      <Sheet open={editing != null} onClose={() => setEditing(null)} title={editing === 'new' || editing == null ? 'New token' : editing.name}>
        {editing != null && <TokenForm key={editing === 'new' ? 'new' : editing.id} token={editing === 'new' ? null : editing} fixedJob={newJob} onDone={() => setEditing(null)} />}
      </Sheet>
    </>
  )
}

type Limit<T> = T[] | null

/** Chips for "All" plus each choice; tapping a choice narrows from All, tapping the last one off goes back to All. */
function LimitChips<T extends string | number>({ label, value, onChange, options }: { label: string; value: Limit<T>; onChange: (v: Limit<T>) => void; options: { value: T; label: string }[] }) {
  const toggle = (v: T) => {
    const now = value ?? []
    const next = now.includes(v) ? now.filter((x) => x !== v) : [...now, v]
    onChange(next.length ? next : null)
  }
  return (
    <div>
      <span className="ink-2 mb-1.5 block px-1 text-[12.5px] font-medium">{label}</span>
      <div className="flex flex-wrap gap-2">
        <Chip active={value == null} onClick={() => onChange(null)}>
          All
        </Chip>
        {options.map((o) => (
          <Chip key={o.value} active={!!value?.includes(o.value)} onClick={() => toggle(o.value)}>
            {o.label}
          </Chip>
        ))}
      </div>
    </div>
  )
}

function TokenForm({ token, fixedJob, onDone }: { token: ApiToken | null; fixedJob?: TokenJob; onDone: () => void }) {
  const cats = useCategories()
  const accounts = useAccounts()
  const dataApi = useFeature('data_api')
  const [name, setName] = useState(token?.name ?? (fixedJob === 'SEND_SMS' ? 'iPhone SMS shortcut' : ''))
  const [job, setJob] = useState<TokenJob>(token?.job ?? fixedJob ?? 'READ_TRANSACTIONS')
  const [categories, setCategories] = useState<Limit<string>>(token?.categories ?? null)
  const [accountIds, setAccountIds] = useState<Limit<number>>(token?.accounts ?? null)
  const [moneyIn, setMoneyIn] = useState(token?.moneyIn ?? false)
  const [notes, setNotes] = useState(token?.includeNotes ?? true)
  const [fresh, setFresh] = useState<string | null>(null)
  useEffect(() => {
    // a category of the other kind can't match once money in is off
    if (!moneyIn && categories && cats.data) setCategories((c) => (c ? c.filter((id) => id === 'none' || cats.data!.find((x) => String(x.id) === id)?.kind !== 'INCOME') : c))
  }, [moneyIn]) // eslint-disable-line react-hooks/exhaustive-deps

  const body = { name, job, categories: usesCategories(job) ? categories : null, accounts: usesAccounts(job) ? accountIds : null, moneyIn, includeNotes: notes }
  const save = useMoneyMutation(
    async (): Promise<string | null> => (token ? (await api<ApiToken>(`/tokens/${token.id}`, { method: 'PUT', body }), null) : (await api<{ token: string }>('/tokens', { body })).token),
    (made) => (made ? setFresh(made) : onDone()),
  )
  const revoke = useMoneyMutation(() => api(`/tokens/${token!.id}`, { method: 'DELETE' }), onDone)

  if (fresh) {
    return (
      <div className="space-y-4">
        <div className="anim-pop rounded-[18px] bg-[var(--accent-soft)] p-3">
          <p className="flex items-center gap-1.5 text-xs font-semibold text-[var(--accent)]">
            <Key size={14} /> Copy this token now. You won't see it again.
          </p>
          <code className="mt-1.5 block break-all font-mono text-[12.5px] leading-relaxed">{fresh}</code>
          <div className="mt-2">
            <CopyButton value={fresh} label="Copy token" />
          </div>
        </div>
        <p className="ink-2 text-sm">
          Send it as <code className="rounded bg-[var(--surface-2)] px-1">Authorization: Bearer &lt;token&gt;</code>.{' '}
          {job === 'SEND_SMS' ? 'Settings → Mail & SMS shows how to set up your phone.' : 'The endpoints are described in docs/api.md.'}
        </p>
        <Button className="w-full" onClick={onDone}>
          Done
        </Button>
      </div>
    )
  }

  const kindCats = (cats.data ?? []).filter((c) => moneyIn || c.kind === 'EXPENSE')
  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate(undefined)
      }}
    >
      <Field label="Name" hint="So you can tell it apart, e.g. the app that uses it">
        <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Mileage app" maxLength={60} />
      </Field>

      {token || fixedJob ? (
        <p className="ink-2 px-1 text-sm">
          {JOBS.find((j) => j.value === job)?.label}: {JOBS.find((j) => j.value === job)?.hint.toLowerCase()}.{token && ` Token ending …${token.hint}, made ${dateTime(token.createdAt)}.`}
        </p>
      ) : (
        <div>
          <span className="ink-2 mb-1.5 block px-1 text-[12.5px] font-medium">What it's for</span>
          <div className="flex flex-wrap gap-2">
            {JOBS.map((j) => (
              <Chip key={j.value} active={j.value === job} onClick={() => setJob(j.value)}>
                {j.label}
              </Chip>
            ))}
          </div>
          <p className="ink-3 mt-1.5 px-1 text-xs">{JOBS.find((j) => j.value === job)?.hint}. A token does this one thing only.</p>
        </div>
      )}

      {usesCategories(job) && (
        <Segmented<'out' | 'both'>
          value={moneyIn ? 'both' : 'out'}
          onChange={(v) => setMoneyIn(v === 'both')}
          options={[
            { value: 'out', label: 'Money out only' },
            { value: 'both', label: 'Money in and out' },
          ]}
        />
      )}
      {usesCategories(job) && (
        <LimitChips<string>
          label={job === 'ADD_TRANSACTIONS' ? 'Categories it may use' : 'Categories'}
          value={categories}
          onChange={setCategories}
          options={[...kindCats.map((c) => ({ value: String(c.id), label: c.name })), { value: 'none', label: 'Uncategorised' }]}
        />
      )}
      {usesAccounts(job) && (
        <LimitChips<number>
          label={job === 'ADD_TRANSACTIONS' ? 'Accounts it may add to' : 'Accounts'}
          value={accountIds}
          onChange={setAccountIds}
          options={(accounts.data ?? []).filter((a) => !a.archived).map((a) => ({ value: a.id, label: a.name }))}
        />
      )}
      {job === 'READ_TRANSACTIONS' && <Toggle checked={notes} onChange={setNotes} label="Include notes and tags" />}

      {job !== 'SEND_SMS' && dataApi === false && <Notice tone="amber">The Data API is off in Settings → Features. Turn it on before the app uses this token.</Notice>}
      <ErrorText error={save.error ?? revoke.error} />
      <Button type="submit" className="w-full" disabled={!name.trim() || save.isPending}>
        {token ? 'Save' : 'Create token'}
      </Button>
      {token && (
        <button
          type="button"
          className="w-full text-center text-sm font-medium text-[var(--negative)]"
          disabled={revoke.isPending}
          onClick={() => confirm(`Revoke "${token.name}"? Whatever uses it stops working straight away.`) && revoke.mutate(undefined)}
        >
          Revoke token
        </button>
      )}
    </form>
  )
}
