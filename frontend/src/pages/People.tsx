import { useQuery } from '@tanstack/react-query'
import { CaretRight, Check, HandCoins, UserPlus, Users, X } from '@phosphor-icons/react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Settlement } from '../api'
import { Avatar, Button, Card, Empty, ErrorText, Field, IconButton, Input, List, Meter, PageHeader, PageSkeleton, Section, Sheet, Page, BigMoney } from '../components/ui'
import { dateTime, money, money0 } from '../format'
import { useMoneyMutation, usePeople } from '../hooks'

export default function People() {
  const people = usePeople()
  const suggested = useQuery({ queryKey: ['settlements', 'suggested'], queryFn: () => api<Settlement[]>('/settlements/suggested') })
  const [adding, setAdding] = useState(false)
  const confirm = useMoneyMutation((s: Settlement) => api(`/settlements/${s.id}/confirm`, { method: 'POST' }))
  const reject = useMoneyMutation((s: Settlement) => api(`/settlements/${s.id}/reject`, { method: 'POST' }))

  if (!people.data) return <PageSkeleton />
  const list = [...people.data].sort((a, b) => b.outstanding - a.outstanding)
  const owed = list.reduce((s, p) => s + Math.max(0, p.outstanding), 0)
  const shared = list.reduce((s, p) => s + p.owed, 0)
  const paid = list.reduce((s, p) => s + p.paid, 0)

  return (
    <Page>
      <PageHeader title="Splits" action={<IconButton icon={UserPlus} label="Add person" onClick={() => setAdding(true)} />} />

      <Card className="p-5">
        <p className="ink-2 text-[13px] font-medium">People owe you</p>
        <BigMoney value={owed} paise={false} className="mt-1 block text-[40px] leading-tight" />
        {shared > 0 && (
          <>
            <Meter pct={(paid / shared) * 100} className="mt-3 h-2" />
            <p className="ink-2 mt-2 text-xs">
              <span className="tabular font-semibold text-[var(--ink)]">{money0(paid)}</span> of {money0(shared)} paid back
            </p>
          </>
        )}
      </Card>

      {(suggested.data ?? []).length > 0 && (
        <Section title="Did they pay you back?">
          <div className="space-y-2">
            {suggested.data!.map((s) => (
              <Card key={s.id} className="anim-pop flex items-center gap-3">
                <Avatar name={s.personName} size={44} />
                <div className="min-w-0 flex-1">
                  <p className="font-semibold">
                    {s.personName} · <span className="tabular">{money(s.amount)}</span>
                  </p>
                  <p className="ink-2 truncate text-xs">
                    {s.txnCounterparty ? `From “${s.txnCounterparty}” · ` : ''}
                    {dateTime(s.settledAt)}
                  </p>
                </div>
                <IconButton icon={X} label="Not a repayment" onClick={() => reject.mutate(s)} />
                <button className="pressable flex h-10 w-10 items-center justify-center rounded-full accent-fill" onClick={() => confirm.mutate(s)} aria-label="Confirm repayment">
                  <Check size={19} />
                </button>
              </Card>
            ))}
          </div>
        </Section>
      )}

      <Section title="People">
        {list.length === 0 ? (
          <Empty icon={Users} title="No one here yet" action={<Button onClick={() => setAdding(true)}>Add a person</Button>}>
            Add the people you split bills with, then open any payment and tap “Split”.
          </Empty>
        ) : (
          <List>
            {list.map((p) => (
              <Link key={p.id} to={`/people/${p.id}`} className="flex items-center gap-3 px-4 py-3.5 row">
                <Avatar name={p.name} size={44} />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[15px] font-semibold">{p.name}</p>
                  {p.owed > 0 ? <Meter pct={(p.paid / p.owed) * 100} className="mt-1.5 max-w-[140px]" /> : <p className="ink-3 text-xs">No splits yet</p>}
                </div>
                <div className="text-right">
                  <p className={p.outstanding > 0 ? 'tabular text-[15px] font-bold' : 'tabular ink-3 text-[15px] font-semibold'}>{money(Math.abs(p.outstanding))}</p>
                  <p className="ink-3 text-[11px] font-medium">{p.outstanding > 0 ? 'owes you' : p.outstanding < 0 ? 'overpaid' : 'settled'}</p>
                </div>
                <CaretRight size={16} className="ink-3" />
              </Link>
            ))}
          </List>
        )}
      </Section>
      <PersonSheet open={adding} onClose={() => setAdding(false)} />
    </Page>
  )
}

type PersonLite = { id: number; name: string; upiName?: string; phone?: string }

export function PersonSheet({ open, onClose, person }: { open: boolean; onClose: () => void; person?: PersonLite }) {
  return (
    <Sheet open={open} onClose={onClose} title={person ? 'Edit person' : 'Add person'}>
      {open && <PersonForm onClose={onClose} person={person} />}
    </Sheet>
  )
}

function PersonForm({ onClose, person }: { onClose: () => void; person?: PersonLite }) {
  const [name, setName] = useState(person?.name ?? '')
  const [upiName, setUpiName] = useState(person?.upiName ?? '')
  const [phone, setPhone] = useState(person?.phone ?? '')
  const save = useMoneyMutation(() => api(person ? `/people/${person.id}` : '/people', { method: person ? 'PUT' : 'POST', body: { name, upiName, phone } }), onClose)
  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate(undefined)
      }}
    >
      <div className="flex justify-center">
        <Avatar name={name || '?'} size={64} />
      </div>
      <Field label="Name">
        <Input required autoFocus value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="Name in UPI alerts (optional)" hint="Used to auto-match their repayments, e.g. ARUN KUMAR S">
        <Input value={upiName} onChange={(e) => setUpiName(e.target.value)} />
      </Field>
      <Field label="Phone (optional)">
        <Input inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} />
      </Field>
      <ErrorText error={save.error} />
      <Button type="submit" className="w-full" disabled={save.isPending}>
        <HandCoins size={17} /> Save
      </Button>
    </form>
  )
}
