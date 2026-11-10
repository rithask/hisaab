import { useQuery } from '@tanstack/react-query'
import { Plus, Trash } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, type Category, type Rule } from '../api'
import { categoryIcon, tint } from '../icons'
import { Badge, Button, ErrorText, Field, Input, List, PageHeader, Section, Segmented, Select, Sheet, Page } from '../components/ui'
import { useCategories, useMoneyMutation } from '../hooks'

export default function Categories() {
  const cats = useCategories()
  const rules = useQuery({ queryKey: ['rules'], queryFn: () => api<Rule[]>('/rules') })
  const [tab, setTab] = useState<'categories' | 'rules'>('categories')
  const [editCat, setEditCat] = useState<Category | 'new' | null>(null)
  const [newRule, setNewRule] = useState(false)
  const delRule = useMoneyMutation((id: number) => api(`/rules/${id}`, { method: 'DELETE' }))

  const ruleGroups: [string, Rule[]][] = [
    ['Learned from your corrections', (rules.data ?? []).filter((r) => r.source === 'LEARNED')],
    ['Your rules', (rules.data ?? []).filter((r) => r.source === 'MANUAL')],
    ['Built-in keywords', (rules.data ?? []).filter((r) => r.source === 'SYSTEM')],
  ]

  return (
    <Page>
      <PageHeader back title="Categories" />
      <Segmented<'categories' | 'rules'>
        value={tab}
        onChange={setTab}
        className="mb-2"
        options={[
          { value: 'categories', label: 'Categories' },
          { value: 'rules', label: 'Rules' },
        ]}
      />

      {tab === 'categories' ? (
        <>
          {(['EXPENSE', 'INCOME'] as const).map((k) => (
            <Section key={k} title={k === 'EXPENSE' ? 'Spending' : 'Income'}>
              <List>
                {(cats.data ?? [])
                  .filter((c) => c.kind === k)
                  .map((c) => {
                    const Icon = categoryIcon(c)
                    return (
                      <button key={c.id} onClick={() => setEditCat(c)} className="flex w-full items-center gap-3 px-4 py-2.5 text-left row">
                        <span className="flex h-9 w-9 items-center justify-center rounded-[12px]" style={{ background: tint(c.color), color: c.color }}>
                          <Icon size={18} />
                        </span>
                        <span className="flex-1 text-[15px] font-medium">{c.name}</span>
                      </button>
                    )
                  })}
              </List>
            </Section>
          ))}
          <Button variant="secondary" className="mt-4 w-full" onClick={() => setEditCat('new')}>
            <Plus size={16} /> New category
          </Button>
        </>
      ) : (
        <>
          <p className="ink-2 mt-4 px-1 text-sm">
            New transactions are categorised by the first matching rule: merchants you've corrected first, then keyword rules (longest match wins).
          </p>
          <Button variant="secondary" className="mt-3 w-full" onClick={() => setNewRule(true)}>
            <Plus size={16} /> Keyword rule
          </Button>
          {ruleGroups.map(([title, list]) =>
            list.length === 0 ? null : (
              <Section key={title} title={title}>
                <List>
                  {list.map((r) => (
                    <div key={r.id} className="flex items-center gap-2 px-4 py-2.5 text-sm">
                      <span className="flex-1 truncate">
                        {r.matchType === 'MERCHANT' ? '' : 'contains '}
                        <b>“{r.pattern}”</b>
                      </span>
                      <Badge>{r.category.name}</Badge>
                      {r.hits > 0 && <span className="ink-3 text-xs">{r.hits}×</span>}
                      <button className="ink-3 p-1" onClick={() => delRule.mutate(r.id)} aria-label="Delete rule">
                        <Trash size={15} />
                      </button>
                    </div>
                  ))}
                </List>
              </Section>
            ),
          )}
        </>
      )}

      <Sheet open={!!editCat} onClose={() => setEditCat(null)} title={editCat === 'new' ? 'New category' : 'Edit category'}>
        {editCat && <CategoryForm cat={editCat === 'new' ? undefined : editCat} onDone={() => setEditCat(null)} />}
      </Sheet>
      <Sheet open={newRule} onClose={() => setNewRule(false)} title="Keyword rule">
        {newRule && <RuleForm onDone={() => setNewRule(false)} />}
      </Sheet>
    </Page>
  )
}

function CategoryForm({ cat, onDone }: { cat?: Category; onDone: () => void }) {
  const [name, setName] = useState(cat?.name ?? '')
  const [kind, setKind] = useState<'EXPENSE' | 'INCOME'>(cat?.kind ?? 'EXPENSE')
  const [color, setColor] = useState(cat?.color ?? '#64748b')
  const save = useMoneyMutation(() => api(cat ? `/categories/${cat.id}` : '/categories', { method: cat ? 'PUT' : 'POST', body: { name, kind, color } }), onDone)
  const del = useMoneyMutation(() => api(`/categories/${cat!.id}`, { method: 'DELETE' }), onDone)
  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate(undefined)
      }}
    >
      <Field label="Name">
        <Input required value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="Type">
        <Select value={kind} onChange={(e) => setKind(e.target.value as 'EXPENSE' | 'INCOME')}>
          <option value="EXPENSE">Spending</option>
          <option value="INCOME">Income</option>
        </Select>
      </Field>
      <Field label="Colour">
        <input type="color" value={color} onChange={(e) => setColor(e.target.value)} className="h-11 w-20 cursor-pointer rounded-2xl bg-transparent" />
      </Field>
      <ErrorText error={save.error ?? del.error} />
      <Button type="submit" className="w-full">
        Save
      </Button>
      {cat && (
        <Button type="button" variant="danger" className="w-full" onClick={() => confirm('Delete category? Transactions become uncategorised.') && del.mutate(undefined)}>
          Delete
        </Button>
      )}
    </form>
  )
}

function RuleForm({ onDone }: { onDone: () => void }) {
  const cats = useCategories()
  const [pattern, setPattern] = useState('')
  const [categoryId, setCategoryId] = useState<number | ''>('')
  const save = useMoneyMutation(() => api('/rules', { body: { matchType: 'CONTAINS', pattern, categoryId } }), onDone)
  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate(undefined)
      }}
    >
      <Field label="When merchant or description contains the word(s)">
        <Input required autoFocus value={pattern} onChange={(e) => setPattern(e.target.value)} placeholder="e.g. decathlon" />
      </Field>
      <Field label="Categorise as">
        <Select required value={categoryId} onChange={(e) => setCategoryId(Number(e.target.value))}>
          <option value="">Choose…</option>
          {(cats.data ?? []).map((c) => (
            <option key={c.id} value={c.id}>
              {c.name} ({c.kind === 'EXPENSE' ? 'spending' : 'income'})
            </option>
          ))}
        </Select>
      </Field>
      <ErrorText error={save.error} />
      <Button type="submit" className="w-full">
        Save rule
      </Button>
    </form>
  )
}
