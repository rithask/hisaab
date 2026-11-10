import { useQuery } from '@tanstack/react-query'
import { Bank, Plus, Wallet } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, type Account } from '../api'
import AccountForm from '../components/AccountForm'
import BankCard from '../components/BankCard'
import { Badge, Card, Empty, IconButton, List, PageHeader, PageSkeleton, Section, Sheet, Page } from '../components/ui'
import { money, shortDate } from '../format'


export default function Accounts() {
  const list = useQuery({ queryKey: ['accounts', 'all'], queryFn: () => api<Account[]>('/accounts?includeArchived=true') })
  const [editing, setEditing] = useState<Account | 'new' | null>(null)
  if (!list.data) return <PageSkeleton />
  const active = list.data.filter((a) => !a.archived)
  const cards = active.filter((a) => a.type === 'CREDIT_CARD')
  const banks = active.filter((a) => a.type !== 'CREDIT_CARD')
  const archived = list.data.filter((a) => a.archived)

  return (
    <Page>
      <PageHeader back title="Accounts" action={<IconButton icon={Plus} label="Add account" onClick={() => setEditing('new')} />} />
      {active.length === 0 && <Empty icon={Bank} title="No accounts yet">Add your bank accounts and cards to start tracking.</Empty>}

      {cards.length > 0 && (
        <Section title="Credit cards" className="mt-0">
          <div className="space-y-4">
            {cards.map((a) => (
              <div key={a.id}>
                <BankCard a={a} className="w-full" onClick={() => setEditing(a)} />
                {a.card && (
                  <Card className="mt-2 grid grid-cols-3 gap-2 p-3.5 text-xs">
                    {a.card.dueDate ? (
                      <>
                        <div>
                          <p className="ink-2">This cycle</p>
                          <p className="tabular text-sm font-semibold">{money(a.card.spentThisCycle)}</p>
                        </div>
                        <div>
                          <p className="ink-2">Statement</p>
                          <p className="text-sm font-semibold">{shortDate(a.card.nextStatement!)}</p>
                        </div>
                        <div>
                          <p className="ink-2">Payment due</p>
                          <p className="flex items-center gap-1 text-sm font-semibold">
                            {shortDate(a.card.dueDate)}
                            {a.card.daysToDue <= 5 && a.balance > 0 && <Badge tone="red">{a.card.daysToDue}d</Badge>}
                          </p>
                        </div>
                      </>
                    ) : (
                      <p className="ink-2 col-span-3">Add statement and due days to track billing cycles.</p>
                    )}
                  </Card>
                )}
              </div>
            ))}
          </div>
        </Section>
      )}

      {banks.length > 0 && (
        <Section title="Bank & cash">
          <List>
            {banks.map((a) => (
              <Row key={a.id} a={a} onClick={() => setEditing(a)} />
            ))}
          </List>
        </Section>
      )}

      {archived.length > 0 && (
        <Section title="Archived">
          <List className="opacity-60">
            {archived.map((a) => (
              <Row key={a.id} a={a} onClick={() => setEditing(a)} />
            ))}
          </List>
        </Section>
      )}

      <Sheet open={!!editing} onClose={() => setEditing(null)} title={editing === 'new' ? 'Add account' : 'Edit account'}>
        {editing && <AccountForm account={editing === 'new' ? undefined : editing} onDone={() => setEditing(null)} />}
      </Sheet>
    </Page>
  )
}

function Row({ a, onClick }: { a: Account; onClick: () => void }) {
  const Icon = a.type === 'CASH' ? Wallet : Bank
  return (
    <button onClick={onClick} className="flex w-full items-center gap-3 px-4 py-3.5 text-left row">
      <span className="flex h-11 w-11 items-center justify-center rounded-full bg-[var(--surface-2)] text-[var(--ink)]">
        <Icon size={21} />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[15px] font-semibold">{a.name}</span>
        <span className="ink-2 block text-[13px]">
          {a.institution !== 'OTHER' ? a.institution : a.type === 'CASH' ? 'Cash' : 'Account'}
          {a.last4 ? ` •••• ${a.last4}` : ''}
        </span>
      </span>
      <span className="tabular text-[16px] font-semibold">{money(a.balance)}</span>
    </button>
  )
}
