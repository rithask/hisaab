import { useQuery } from '@tanstack/react-query'
import { PencilSimple, Trash } from '@phosphor-icons/react'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, type Person, type Settlement, type Txn } from '../api'
import { Avatar, Badge, Button, Card, ErrorText, Field, IconButton, Input, List, Meter, PageHeader, PageSkeleton, Section, Sheet, Page, BigMoney } from '../components/ui'
import { dateTime, fromLocalInput, money, toLocalInput } from '../format'
import { useMoneyMutation } from '../hooks'
import { PersonSheet } from './People'

interface Detail {
  person: Person
  shares: { shareId: number; amount: number; txn: Txn }[]
  settlements: Settlement[]
}

export default function PersonDetail() {
  const { id } = useParams()
  const nav = useNavigate()
  const d = useQuery({ queryKey: ['person', id], queryFn: () => api<Detail>(`/people/${id}`) })
  const [paying, setPaying] = useState(false)
  const [editing, setEditing] = useState(false)
  const remove = useMoneyMutation(() => api(`/people/${id}`, { method: 'DELETE' }), () => nav('/people'))
  const delSettlement = useMoneyMutation((sid: number) => api(`/settlements/${sid}`, { method: 'DELETE' }))

  if (!d.data) return <PageSkeleton />
  const { person: p, shares, settlements } = d.data

  return (
    <Page>
      <PageHeader back title="" action={<IconButton icon={PencilSimple} label="Edit" onClick={() => setEditing(true)} />} />
      <div className="-mt-6 flex flex-col items-center text-center">
        <Avatar name={p.name} size={76} />
        <h1 className="mt-3 text-[24px] font-bold">{p.name}</h1>
        {p.upiName && <p className="ink-2 text-xs">UPI: {p.upiName}</p>}
      </div>

      <Card className="mt-5 p-5 text-center">
        <p className="ink-2 text-[13px] font-medium">{p.outstanding > 0 ? 'Owes you' : p.outstanding < 0 ? 'Overpaid by' : 'All settled'}</p>
        <BigMoney value={Math.abs(p.outstanding)} className="mt-1 text-[40px] leading-tight" />
        {p.owed > 0 && (
          <>
            <Meter pct={(p.paid / p.owed) * 100} className="mt-3 h-2" />
            <p className="ink-2 mt-2 text-xs">
              Paid back {money(p.paid)} of {money(p.owed)}
            </p>
          </>
        )}
        {p.outstanding > 0 && (
          <Button className="mt-4 w-full" onClick={() => setPaying(true)}>
            Record a payment
          </Button>
        )}
      </Card>

      <Section title="Shared expenses">
        <List>
          {shares.length === 0 && <p className="ink-2 p-5 text-center text-sm">Nothing split yet.</p>}
          {shares.map((s) => (
            <div key={s.shareId} className="flex items-center justify-between gap-3 px-4 py-3">
              <div className="min-w-0">
                <p className="truncate text-[15px] font-semibold">{s.txn.counterparty ?? s.txn.description ?? 'Payment'}</p>
                <p className="ink-2 text-xs">
                  {dateTime(s.txn.occurredAt)} · bill {money(s.txn.amount)}
                </p>
              </div>
              <span className="tabular font-semibold">{money(s.amount)}</span>
            </div>
          ))}
        </List>
      </Section>

      <Section title="Payments received">
        <List>
          {settlements.length === 0 && <p className="ink-2 p-5 text-center text-sm">No payments yet.</p>}
          {settlements.map((s) => (
            <div key={s.id} className="flex items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <p className="tabular text-[15px] font-semibold text-[var(--positive)]">+{money(s.amount)}</p>
                <p className="ink-2 truncate text-xs">
                  {dateTime(s.settledAt)}
                  {s.note ? ` · ${s.note}` : ''}
                </p>
              </div>
              <Badge tone={s.status === 'CONFIRMED' ? 'green' : s.status === 'SUGGESTED' ? 'amber' : 'neutral'}>{s.status.toLowerCase()}</Badge>
              <button className="ink-3 p-1" onClick={() => confirm('Remove this payment?') && delSettlement.mutate(s.id)} aria-label="Remove payment">
                <Trash size={16} />
              </button>
            </div>
          ))}
        </List>
      </Section>

      <Button variant="danger" className="mt-8 w-full" onClick={() => confirm(`Delete ${p.name} and their split history?`) && remove.mutate(undefined)}>
        Delete person
      </Button>

      <Sheet open={paying} onClose={() => setPaying(false)} title={`Payment from ${p.name}`}>
        {paying && <PaymentForm onClose={() => setPaying(false)} person={p} />}
      </Sheet>
      <PersonSheet open={editing} onClose={() => setEditing(false)} person={p} />
    </Page>
  )
}

function PaymentForm({ onClose, person }: { onClose: () => void; person: Person }) {
  const [amount, setAmount] = useState(String(person.outstanding))
  const [when, setWhen] = useState(toLocalInput())
  const [note, setNote] = useState('')
  const save = useMoneyMutation(() => api('/settlements', { body: { personId: person.id, amount: Number(amount), settledAt: fromLocalInput(when), note } }), onClose)
  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate(undefined)
      }}
    >
      <Field label="Amount">
        <Input required inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
      </Field>
      <Field label="When">
        <Input type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} />
      </Field>
      <Field label="Note">
        <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Cash, GPay…" />
      </Field>
      <ErrorText error={save.error} />
      <Button type="submit" className="w-full" disabled={save.isPending}>
        Save payment
      </Button>
    </form>
  )
}
