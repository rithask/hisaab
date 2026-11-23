import { useQuery } from '@tanstack/react-query'
import { Users } from '@phosphor-icons/react'
import { useEffect, useState } from 'react'
import { api, type Split, type Txn } from '../api'
import { money } from '../format'
import { useMoneyMutation, usePeople } from '../hooks'
import { NOT_SHARED, outcomeOf, rememberShared, SharedPicker, type Shared } from './SharedPicker'
import { Button, ErrorText, MoreRow } from './ui'

/**
 * Splitting a payment I made, after it's recorded: the same picker as when adding one, with "Paid by" left out
 * (it came from my account). Leaving only me in it removes the split.
 * @param part "linked": only an existing split; "ask": only the offer to split (under More).
 */
export function SplitEditor({ txn, part }: { txn: Txn; part: 'linked' | 'ask' }) {
  const people = usePeople()
  const split = useQuery({ queryKey: ['split', txn.id], queryFn: () => api<Split>(`/transactions/${txn.id}/split`) })
  const [editing, setEditing] = useState(false)
  const [shared, setShared] = useState<Shared>(NOT_SHARED)

  // Start from the split as saved; amounts as they came out, whichever way they were entered.
  useEffect(() => {
    const s = split.data
    if (!s || s.shares.length === 0) return setShared(NOT_SHARED)
    setShared({
      payer: 'me',
      members: [...(s.myShare > 0 ? (['me'] as const) : []), ...s.shares.map((x) => x.personId)],
      custom: s.shares[0].method !== 'EQUAL',
      amounts: Object.fromEntries(s.shares.map((x) => [x.personId, String(x.amount)])),
    })
  }, [split.data])

  const outcome = outcomeOf(txn.amount, shared)
  const save = useMoneyMutation(
    () =>
      api<Split>(`/transactions/${txn.id}/split`, {
        method: 'PUT',
        body:
          outcome.kind === 'split'
            ? { method: outcome.method, includeMe: outcome.includeMe, participants: outcome.participants }
            : { method: 'EQUAL', includeMe: true, participants: [] },
      }),
    () => (rememberShared(shared), setEditing(false)),
  )

  const has = (split.data?.shares.length ?? 0) > 0
  if (!split.data || (part === 'linked' ? !has : has)) return null
  if (!editing && !has)
    return (
      <MoreRow icon={Users} onClick={() => setEditing(true)}>
        Split with friends
      </MoreRow>
    )
  if (!editing) {
    const s = split.data
    return (
      <div className="rounded-box bg-surface-2 p-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-body font-semibold">{s.shares.length > 0 && s.myShare === 0 ? 'Money you lent' : 'Split with others'}</p>
            {!s?.shares.length && <p className="ink-2 text-caption">Paid for a group? Track who owes you.</p>}
            {s.shares.length > 0 && s.myShare === 0 && <p className="ink-2 text-caption">Not counted as your spending.</p>}
          </div>
          <Button variant="primary" size="sm" onClick={() => setEditing(true)}>
            {s?.shares.length ? 'Edit' : 'Split'}
          </Button>
        </div>
        {s && s.shares.length > 0 && (
          <div className="mt-3 space-y-2 text-label">
            {s.shares.map((x) => (
              <div key={x.id} className="flex items-center justify-between">
                <span>{x.personName} owes</span>
                <span className="tabular font-semibold">{money(x.amount)}</span>
              </div>
            ))}
            <div className="ink-2 flex justify-between border-t border-line-strong pt-2">
              <span>My share</span>
              <span className="tabular">{money(s.myShare)}</span>
            </div>
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="anim-pop space-y-3">
      <SharedPicker total={txn.amount} value={shared} onChange={setShared} people={people.data ?? []} fixedPayer />
      <ErrorText error={save.error} />
      <div className="flex gap-2">
        <Button variant="secondary" className="flex-1" onClick={() => setEditing(false)}>
          Cancel
        </Button>
        <Button className="flex-1" onClick={() => save.mutate(undefined)} disabled={save.isPending || !!outcome.problem}>
          {outcome.kind === 'none' && (split.data?.shares.length ?? 0) > 0 ? 'Remove split' : 'Save split'}
        </Button>
      </div>
    </div>
  )
}
