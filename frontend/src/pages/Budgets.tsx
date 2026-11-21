import { useQuery } from '@tanstack/react-query'
import { Target } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, type Budget } from '../api'
import { Button, Card, Empty, FeatureGate, Input, List, Page, PageHeader, PageSkeleton, Section, Sheet, IconBadge } from '../components/ui'
import { money, money0 } from '../format'
import { categoryIcon } from '../icons'
import { useCategories, useFeature, useMoneyMutation } from '../hooks'

const barColor = (b: Budget) => (b.status === 'OVER' ? 'var(--negative)' : b.status === 'WARN' ? 'var(--attention)' : 'var(--positive)')

/** Monthly limits per spending category, with how this month is going. */
export default function Budgets() {
  const on = useFeature('budgets')
  const q = useQuery({ queryKey: ['budgets'], queryFn: () => api<Budget[]>('/budgets'), enabled: on === true })
  const [editing, setEditing] = useState<{ categoryId?: number; limit?: number } | null>(null)
  const list = q.data ?? []
  const limit = list.reduce((s, b) => s + b.limit, 0)
  const spent = list.reduce((s, b) => s + b.spent, 0)

  return (
    <FeatureGate on={on} name="Budgets">
      <Page>
        <PageHeader back title="Budgets" subtitle="This month" action={<Button size="sm" onClick={() => setEditing({})}>Add</Button>} />
        {!q.data ? (
          <PageSkeleton />
        ) : list.length === 0 ? (
          <Empty icon={Target} title="No budgets yet" action={<Button onClick={() => setEditing({})}>Set a budget</Button>}>
            Pick a category and a monthly limit. Home shows how you're doing and warns you before you go over.
          </Empty>
        ) : (
          <>
            <Card>
              <p className="ink-2 text-small">Spent of all budgets</p>
              <p className="display mt-0.5 text-heading">
                {money0(spent)} <span className="ink-3 text-title font-medium">of {money0(limit)}</span>
              </p>
            </Card>
            <Section title="Categories">
              <List>
                {list.map((b) => (
                  <button key={b.categoryId} onClick={() => setEditing({ categoryId: b.categoryId, limit: b.limit })} className="row block w-full px-4 py-3 text-left">
                    <BudgetLine b={b} />
                  </button>
                ))}
              </List>
              <p className="ink-3 mt-2 px-1 text-small">Tap one to change or remove it. Only your share of split bills counts, and money moved between your own accounts never does.</p>
            </Section>
          </>
        )}
        <Sheet open={!!editing} onClose={() => setEditing(null)} title={editing?.categoryId ? 'Edit budget' : 'New budget'}>
          {editing && <BudgetForm initial={editing} taken={list.map((b) => b.categoryId)} onDone={() => setEditing(null)} />}
        </Sheet>
      </Page>
    </FeatureGate>
  )
}

export function BudgetLine({ b }: { b: Budget }) {
  const Icon = categoryIcon(b)
  return (
    <div className="flex items-center gap-3">
      <IconBadge icon={Icon} color={b.color} />
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline justify-between gap-2">
          <span className="truncate text-body font-medium">{b.name}</span>
          <span className="tabular shrink-0 text-label">
            <b>{money0(b.spent)}</b> <span className="ink-3">/ {money0(b.limit)}</span>
          </span>
        </div>
        <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-surface-2">
          <div className="h-full rounded-full transition-[width] duration-base" style={{ width: `${Math.min(100, b.pct)}%`, background: barColor(b) }} />
        </div>
        <p className="ink-2 mt-1 text-caption">
          {b.status === 'OVER'
            ? `${money0(-b.remaining)} over`
            : `${money0(b.remaining)} left${b.projected > b.limit ? ` · on pace for ${money0(b.projected)}` : ''}`}
        </p>
      </div>
    </div>
  )
}

function BudgetForm({ initial, taken, onDone }: { initial: { categoryId?: number; limit?: number }; taken: number[]; onDone: () => void }) {
  const cats = useCategories()
  const [categoryId, setCategoryId] = useState<number | undefined>(initial.categoryId)
  const [limit, setLimit] = useState(initial.limit ? String(initial.limit) : '')
  const save = useMoneyMutation(() => api(`/budgets/${categoryId}`, { method: 'PUT', body: { limit: Number(limit) } }), onDone)
  const remove = useMoneyMutation(() => api(`/budgets/${initial.categoryId}`, { method: 'DELETE' }), onDone)
  const options = (cats.data ?? []).filter((c) => c.kind === 'EXPENSE' && (!taken.includes(c.id) || c.id === initial.categoryId))
  return (
    <div className="space-y-4">
      {!initial.categoryId && (
        <div className="flex flex-wrap gap-2">
          {options.map((c) => (
            <button
              key={c.id}
              onClick={() => setCategoryId(c.id)}
              className={'pressable h-10 rounded-full px-4 text-label ' + (categoryId === c.id ? 'bg-ink text-surface' : 'bg-surface-2')}
            >
              {c.name}
            </button>
          ))}
        </div>
      )}
      <Input inputMode="decimal" placeholder="Monthly limit (₹)" value={limit} onChange={(e) => setLimit(e.target.value.replace(/[^\d.]/g, ''))} autoFocus />
      <Button className="w-full" disabled={!categoryId || !Number(limit) || save.isPending} onClick={() => save.mutate(undefined)}>
        Save {Number(limit) ? money(Number(limit)) + ' a month' : ''}
      </Button>
      {initial.categoryId && (
        <Button variant="danger" className="w-full" onClick={() => remove.mutate(undefined)}>
          Remove budget
        </Button>
      )}
    </div>
  )
}
