import { Check, Plus } from '@phosphor-icons/react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { api, type Person } from '../api'
import { firstName, joinList, money } from '../format'
import { Avatar, cx, ErrorText, FieldLabel, Input, TextButton } from './ui'

/**
 * One shared payment, whoever paid: who paid it, and who it's split between. It covers what used to be three
 * separate choices (split with friends, lent to someone, someone else paid): I paid and we share it, I paid for
 * them, or a friend paid and part of it is mine. Equal shares unless amounts are typed.
 */

export type Member = 'me' | number
export interface Shared {
  payer: Member
  members: Member[]
  /** Typed amounts instead of equal shares: each other person's when I paid, mine when a friend paid. */
  custom: boolean
  amounts: Record<string, string>
}
export const NOT_SHARED: Shared = { payer: 'me', members: ['me'], custom: false, amounts: {} }

/**
 * What a shared payment means for the app: a split of my payment, my share of a friend's, or nothing yet; with
 * `problem` saying what to fix first when it can't be saved as it is.
 */
export type Outcome = (
  | { kind: 'none' }
  | { kind: 'split'; method: 'EQUAL' | 'EXACT'; includeMe: boolean; participants: { personId: number; value: number | null }[]; myShare: number }
  | { kind: 'paidBy'; personId: number; share: number }
) & { problem?: string }

const cents = (n: number) => Math.round(n * 100) / 100

export function outcomeOf(total: number, s: Shared): Outcome {
  const others = s.members.filter((m): m is number => m !== 'me')
  const withMe = s.members.includes('me')
  if (s.payer === 'me') {
    if (others.length === 0) return { kind: 'none' }
    if (!s.custom) {
      const each = total / s.members.length
      return { kind: 'split', method: 'EQUAL', includeMe: withMe, participants: others.map((personId) => ({ personId, value: null })), myShare: withMe ? cents(each) : 0 }
    }
    const values = others.map((id) => Number(s.amounts[id] || 0))
    const theirs = cents(values.reduce((a, b) => a + b, 0))
    const out = { kind: 'split' as const, method: 'EXACT' as const, includeMe: withMe, participants: others.map((personId, i) => ({ personId, value: values[i] })), myShare: cents(total - theirs) }
    if (values.some((v) => !(v > 0))) return { ...out, problem: 'Enter an amount for each person' }
    if (theirs > total) return { ...out, problem: `Their shares add up to ${money(theirs)}, more than ${money(total)}` }
    if (!withMe && theirs !== cents(total)) return { ...out, problem: `Their shares add up to ${money(theirs)}; with you left out they need to make ${money(total)}` }
    return out
  }
  const share = s.custom ? Number(s.amounts.me || 0) : cents(total / Math.max(1, s.members.length))
  const out = { kind: 'paidBy' as const, personId: s.payer, share }
  if (!withMe) return { ...out, problem: "You're not in it, so there's nothing of yours to record" }
  if (s.custom && !(share > 0)) return { ...out, problem: 'Enter your share' }
  if (share > total) return { ...out, problem: `Your share can't be more than ${money(total)}` }
  return out
}

/**
 * Who paid. It never changes who it's split between (the summary line says what that means); typed amounts are
 * cleared, since they're theirs when I paid and mine when a friend did.
 */
export function withPayer(s: Shared, payer: Member): Shared {
  return { ...s, payer, custom: false }
}

// ---- Who I usually share with (this device only: a convenience, not data) --------------------------------

const KEY = 'hisaab.shared'
type Memory = { uses: Record<string, number>; last: number[] }
function memory(): Memory {
  try {
    const m = JSON.parse(localStorage.getItem(KEY) ?? '{}')
    return { uses: m.uses ?? {}, last: m.last ?? [] }
  } catch {
    return { uses: {}, last: [] }
  }
}
/** Call after saving a shared payment, so its people come first next time and the group can be picked in one tap. */
export function rememberShared(s: Shared) {
  const people = [...new Set([...s.members, s.payer].filter((m): m is number => m !== 'me'))]
  if (people.length === 0) return
  const m = memory()
  for (const id of people) m.uses[id] = (m.uses[id] ?? 0) + 1
  m.last = s.members.filter((x): x is number => x !== 'me')
  try {
    localStorage.setItem(KEY, JSON.stringify(m))
  } catch {
    // storage unavailable: nothing to remember
  }
}

/** A one-line summary of what saving will do: "₹300 each · Priya and Rahul owe you ₹600". */
function summary(total: number, s: Shared, o: Outcome, people: Person[]) {
  const name = (id: Member) => (id === 'me' ? 'you' : firstName(people.find((p) => p.id === id)?.name ?? 'Someone'))
  if (o.problem) return o.problem
  if (o.kind === 'none' || !(total > 0)) return s.payer === 'me' ? 'Pick who it’s split with' : ''
  if (o.kind === 'paidBy') return `Your share ${money(o.share)} · you’ll owe ${name(s.payer)} ${money(o.share)}`
  const owed = cents(total - o.myShare)
  const them = joinList(o.participants.map((p) => name(p.personId)))
  if (!o.includeMe) return `${them} ${o.participants.length > 1 ? 'owe' : 'owes'} you ${money(owed)} · not your spending`
  return `${o.method === 'EQUAL' ? `${money(o.myShare)} each · ` : `Your share ${money(o.myShare)} · `}${them} ${o.participants.length > 1 ? 'owe' : 'owes'} you ${money(owed)}`
}

/**
 * The picker: "Paid by" (one person, me first) and "Split between" (any number, me ticked), each one line that
 * scrolls sideways, most-shared-with people first. The last group is one tap, and someone new can be added here.
 * @param fixedPayer hides "Paid by" when it's already known (an account payment is mine; a friend's is theirs)
 */
export function SharedPicker({ total, value, onChange, people, fixedPayer }: { total: number; value: Shared; onChange: (s: Shared) => void; people: Person[]; fixedPayer?: boolean }) {
  const [{ uses, last }] = useState(memory)
  const ordered = [...people].sort((a, b) => (uses[b.id] ?? 0) - (uses[a.id] ?? 0) || a.name.localeCompare(b.name))
  const lastGroup = last.filter((id) => people.some((p) => p.id === id))
  const lastShown = lastGroup.length > 0 && lastGroup.some((id) => !value.members.includes(id))
  const nameOf = (id: number) => people.find((p) => p.id === id)?.name ?? ''

  const outcome = outcomeOf(total, value)
  const toggle = (m: Member) => onChange({ ...value, members: value.members.includes(m) ? value.members.filter((x) => x !== m) : [...value.members, m] })
  const others = value.members.filter((m): m is number => m !== 'me')

  return (
    <div className="space-y-4">
      {!fixedPayer && (
        <div>
          <FieldLabel>Paid by</FieldLabel>
          <Row>
            <PersonChip name="Me" on={value.payer === 'me'} onClick={() => onChange(withPayer(value, 'me'))} />
            {ordered.map((p) => (
              <PersonChip key={p.id} name={p.name} on={value.payer === p.id} onClick={() => onChange(withPayer(value, p.id))} />
            ))}
          </Row>
        </div>
      )}
      <div>
        <FieldLabel>Split between</FieldLabel>
        <Row>
          {lastShown && (
            <button
              type="button"
              onClick={() => onChange({ ...value, members: [...new Set([...value.members, ...lastGroup])] })}
              className="pressable flex h-11 shrink-0 items-center rounded-full border border-dashed border-line-strong px-4 text-label font-medium"
            >
              Last time: {joinList(lastGroup.map((id) => firstName(nameOf(id))))}
            </button>
          )}
          <PersonChip name="Me" on={value.members.includes('me')} onClick={() => toggle('me')} tick />
          {ordered.map((p) => (
            <PersonChip key={p.id} name={p.name} on={value.members.includes(p.id)} onClick={() => toggle(p.id)} tick />
          ))}
          <AddPerson onAdded={(p) => onChange({ ...value, members: [...value.members, p.id] })} />
        </Row>
      </div>
      {value.custom && (
        <div className="space-y-2">
          {(value.payer === 'me' ? others : ['me' as const]).map((m) => (
            <div key={m} className="flex items-center gap-3">
              <Avatar name={m === 'me' ? 'Me' : nameOf(m)} size={32} />
              <span className="min-w-0 flex-1 truncate text-body">{m === 'me' ? 'Your share' : nameOf(m)}</span>
              <Input
                className="w-28 py-2 text-right"
                inputMode="decimal"
                placeholder="₹"
                aria-label={m === 'me' ? 'Your share' : `${nameOf(m)}'s share`}
                value={value.amounts[m] ?? ''}
                onChange={(e) => onChange({ ...value, amounts: { ...value.amounts, [m]: e.target.value.replace(/[^\d.]/g, '') } })}
              />
            </div>
          ))}
        </div>
      )}
      <div className="flex items-start justify-between gap-3 px-1">
        <p className={cx('text-small', outcome.problem ? 'text-negative' : 'ink-2')}>{summary(total, value, outcome, people)}</p>
        {(value.payer !== 'me' || others.length > 0) && (
          <TextButton className="shrink-0 text-small" onClick={() => onChange({ ...value, custom: !value.custom })}>
            {value.custom ? 'Split equally' : 'Change amounts'}
          </TextButton>
        )}
      </div>
    </div>
  )
}

/** One line of chips that scrolls sideways, edge to edge in a sheet. */
function Row({ children }: { children: ReactNode }) {
  return (
    <div className="no-scrollbar -mx-5 overflow-x-auto px-5">
      <div className="flex w-max gap-2">{children}</div>
    </div>
  )
}

function PersonChip({ name, on, onClick, tick }: { name: string; on: boolean; onClick: () => void; tick?: boolean }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      onClick={onClick}
      className={cx('pressable flex h-11 shrink-0 items-center gap-2 rounded-full pl-1.5 pr-4 text-label font-medium transition-colors duration-quick', on ? 'bg-ink text-bg' : 'bg-surface-2')}
    >
      <Avatar name={name} size={32} />
      {name}
      {tick && on && <Check size={14} weight="bold" />}
    </button>
  )
}

/** "+ Add": type a name, press Enter, and they're saved and picked, without leaving the form. */
function AddPerson({ onAdded }: { onAdded: (p: Person) => void }) {
  const qc = useQueryClient()
  const [typing, setTyping] = useState(false)
  const [name, setName] = useState('')
  // Only the people list changes, so only it is updated (a money mutation would reload every screen's data).
  const add = useMutation({
    mutationFn: () => api<Person>('/people', { body: { name: name.trim() } }),
    onSuccess: (p) => {
      qc.setQueryData<Person[]>(['people'], (list) => (list ? [...list, p] : list))
      onAdded(p)
      setName('')
      setTyping(false)
    },
  })
  if (!typing)
    return (
      <button type="button" onClick={() => setTyping(true)} className="pressable flex h-11 shrink-0 items-center gap-1.5 rounded-full bg-surface-2 px-4 text-label font-medium text-accent">
        <Plus size={16} weight="bold" /> Add
      </button>
    )
  return (
    <div className="flex shrink-0 flex-col">
      <Input
        autoFocus
        value={name}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            if (name.trim()) add.mutate(undefined)
          }
          if (e.key === 'Escape') (e.stopPropagation(), setTyping(false))
        }}
        onBlur={() => !name.trim() && setTyping(false)}
        placeholder="Name, then Enter"
        aria-label="New person's name"
        className="h-11 w-48 py-2"
      />
      <ErrorText error={add.error} />
    </div>
  )
}
