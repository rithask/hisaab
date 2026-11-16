import { CaretRight, HandCoins, UserPlus, Users } from '@phosphor-icons/react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Person } from '../api'
import { SettleSheet } from './PersonDetail'
import { Avatar, Button, Card, cx, Empty, ErrorText, Field, IconButton, Input, List, Meter, PageHeader, PageSkeleton, Section, Sheet, Page, BigMoney } from '../components/ui'
import { money, money0 } from '../format'
import { useMoneyMutation, usePeople } from '../hooks'

/**
 * How much of what someone owed me is settled. It builds up with their shares of what I paid (and with what I
 * paid them back); it's cleared by what they paid me back and by my shares of what they paid for.
 */
const progress = (p: Person) => {
  const due = p.owed + p.paidBack
  return { due, cleared: Math.min(due, p.paid + p.paidForMe) }
}

export default function People() {
  const people = usePeople()
  const [adding, setAdding] = useState(false)
  const [settling, setSettling] = useState<Person | null>(null)

  if (!people.data) return <PageSkeleton />
  const list = [...people.data].sort((a, b) => b.outstanding - a.outstanding)
  const owed = list.reduce((s, p) => s + Math.max(0, p.outstanding), 0)
  const iOwe = list.reduce((s, p) => s + Math.max(0, -p.outstanding), 0)
  const shared = list.reduce((s, p) => s + progress(p).due, 0)
  const paid = list.reduce((s, p) => s + progress(p).cleared, 0)

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
              <span className="tabular font-semibold text-[var(--ink)]">{money0(paid)}</span> of {money0(shared)} settled
            </p>
          </>
        )}
        {iOwe > 0 && (
          <p className="ink-2 mt-3 border-t border-[var(--line)] pt-3 text-[13px]">
            You owe others <span className="tabular font-semibold text-[var(--negative)]">{money0(iOwe)}</span>
          </p>
        )}
      </Card>

      <Section title="People">
        {list.length === 0 ? (
          <Empty icon={Users} title="No one here yet" action={<Button onClick={() => setAdding(true)}>Add a person</Button>}>
            Add the people you split bills with, then open any payment and tap “Split”.
          </Empty>
        ) : (
          <List>
            {list.map((p) => {
              const v = Math.round(p.outstanding * 100) / 100
              return (
                <div key={p.id} className="row flex items-center gap-3 px-4 py-3.5">
                  <Link to={`/people/${p.id}`} className="flex min-w-0 flex-1 items-center gap-3">
                    <Avatar name={p.name} size={44} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-[15px] font-semibold">{p.name}</p>
                      {/* One sentence: where you stand. */}
                      <p className={cx('truncate text-[13.5px]', v > 0 ? 'text-[var(--ink)]' : v < 0 ? 'text-[var(--negative)]' : 'ink-3')}>
                        {v > 0 ? `owes you ${money(v)}` : v < 0 ? `you owe ${money(-v)}` : p.owed > 0 || p.paidForMe > 0 ? 'all square' : 'no splits yet'}
                      </p>
                      {progress(p).due > 0 && v > 0 && <Meter pct={(progress(p).cleared / progress(p).due) * 100} className="mt-1.5 max-w-[140px]" />}
                    </div>
                  </Link>
                  {v !== 0 ? (
                    <Button size="sm" variant="secondary" className="shrink-0" onClick={() => setSettling(p)}>
                      Settle up
                    </Button>
                  ) : (
                    <CaretRight size={16} className="ink-3 shrink-0" />
                  )}
                </div>
              )
            })}
          </List>
        )}
      </Section>
      <PersonSheet open={adding} onClose={() => setAdding(false)} />
      <SettleSheet person={settling} onClose={() => setSettling(null)} />
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
