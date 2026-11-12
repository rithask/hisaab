import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { api, type SmsPreview, type TeachResult } from '../api'
import { useAccounts, useCategories, useMoneyMutation } from '../hooks'
import { Button, Chip, cx, ErrorText, Field, Input, Segmented } from './ui'

type Mark = 'amount' | 'payee'
type Tok = { text: string; start: number; end: number }

/** Index range [first, last] of the words a {start, end} span covers, or null. */
function wordsOf(toks: Tok[], span?: number[] | null): [number, number] | null {
  if (!span) return null
  const hit = toks.map((t, i) => (t.start < span[1] && span[0] < t.end ? i : -1)).filter((i) => i >= 0)
  return hit.length ? [hit[0], hit[hit.length - 1]] : null
}

const numberIn = (s: string) => s.match(/\d[\d,]*(?:\.\d{1,2})?/)?.[0].replace(/,/g, '')

/**
 * Correct one message by marking it: tap the amount, tap the first and last word of the payee, pick the account
 * and direction. Marking tells the parser exactly where each part sits, so the next message in this format is
 * read right, including its payee. The payee can be shown under a nicer name. A category can be picked too: it's
 * learned for the payee, or, when the message has no payee, for messages worded like this one.
 */
export default function TeachForm({ id, onDone }: { id: number; onDone: (r: TeachResult) => void }) {
  const accounts = useAccounts()
  const cats = useCategories()
  const preview = useQuery({ queryKey: ['sms-preview', id], queryFn: () => api<SmsPreview>(`/emails/${id}/preview`), staleTime: 0 })
  const [direction, setDirection] = useState<'DEBIT' | 'CREDIT'>('DEBIT')
  const [amount, setAmount] = useState('')
  const [accountId, setAccountId] = useState<number | ''>('')
  const [mark, setMark] = useState<Mark>('amount')
  const [amountWord, setAmountWord] = useState<number | null>(null)
  const [payeeWords, setPayeeWords] = useState<[number, number] | null>(null)
  const [name, setName] = useState('')
  const [nameEdited, setNameEdited] = useState(false)
  const [categoryId, setCategoryId] = useState<number | null>(null)

  const toks = useMemo<Tok[]>(() => [...(preview.data?.flat ?? '').matchAll(/\S+/g)].map((m) => ({ text: m[0], start: m.index!, end: m.index! + m[0].length })), [preview.data?.flat])
  const marked = payeeWords ? toks.slice(payeeWords[0], payeeWords[1] + 1).map((t) => t.text).join(' ').replace(/[.,;:]+$/, '') : ''

  // Start from the parser's own best guess, so I only fix what's wrong.
  useEffect(() => {
    const p = preview.data
    if (!p) return
    if (p.direction) setDirection(p.direction)
    if (p.amount != null) setAmount(String(p.amount))
    if (p.accountId != null) setAccountId(p.accountId)
    setCategoryId(p.categoryId ?? null)
    setAmountWord(wordsOf(toks, p.amountAt)?.[0] ?? null)
    const w = wordsOf(toks, p.payeeAt)
    setPayeeWords(w)
    const text = w ? toks.slice(w[0], w[1] + 1).map((t) => t.text).join(' ').replace(/[.,;:]+$/, '') : ''
    setName(p.counterparty ?? '')
    // a name that isn't the marked text is mine (e.g. an earlier rename): keep it
    setNameEdited(!!p.counterparty && p.counterparty !== text)
    if (!p.amountAt && p.amount == null) setMark('amount')
    else if (!p.payeeAt) setMark('payee')
  }, [preview.data, toks])
  // The shown name follows what's marked until I type my own.
  useEffect(() => {
    if (!nameEdited) setName(marked)
  }, [marked]) // eslint-disable-line react-hooks/exhaustive-deps

  const tap = (i: number) => {
    if (mark === 'amount') {
      const n = numberIn(toks[i].text)
      if (!n) return
      setAmountWord(i)
      setAmount(n)
      if (!payeeWords) setMark('payee')
      return
    }
    // payee: first tap picks a word, a second tap on another word extends to it, tapping the only word clears it
    setPayeeWords((r) => (!r || r[0] !== r[1] ? [i, i] : r[0] === i ? null : [Math.min(r[0], i), Math.max(r[0], i)]))
  }

  const teach = useMoneyMutation(
    () =>
      api<TeachResult>(`/emails/${id}/teach`, {
        body: {
          direction,
          amount: Number(amount),
          accountId,
          counterparty: name.trim() || marked || undefined,
          amountAt: amountWord != null ? [toks[amountWord].start, toks[amountWord].end] : undefined,
          payeeAt: payeeWords ? [toks[payeeWords[0]].start, toks[payeeWords[1]].end] : undefined,
          // only a new choice is sent, so teaching again doesn't count the same choice twice
          categoryId: category && category.id !== preview.data?.categoryId ? category.id : undefined,
        },
      }),
    onDone,
  )
  const active = (accounts.data ?? []).filter((a) => !a.archived)
  const kind = direction === 'DEBIT' ? 'EXPENSE' : 'INCOME'
  const kindCats = (cats.data ?? []).filter((c) => c.kind === kind)
  // a category of the other kind no longer fits once the direction changes
  const category = kindCats.find((c) => c.id === categoryId)
  if (!preview.data) return null

  return (
    <form
      className="anim-pop space-y-3 rounded-2xl bg-[var(--surface-2)] p-4"
      onSubmit={(e) => {
        e.preventDefault()
        teach.mutate(undefined)
      }}
    >
      <div>
        <Segmented<Mark>
          value={mark}
          onChange={setMark}
          className="bg-[var(--surface)]"
          options={[
            { value: 'amount', label: 'Tap the amount' },
            { value: 'payee', label: 'Tap the payee' },
          ]}
        />
        <p className="ink-3 mt-1.5 px-1 text-xs">{mark === 'amount' ? 'Tap the number that was paid or received.' : 'Tap the first word of the name, then the last. Tap it again to clear.'}</p>
      </div>
      <div className="max-h-[38dvh] overflow-auto rounded-xl bg-[var(--surface)] p-2.5 text-[14px] leading-[1.9]" role="group" aria-label="Message words">
        {toks.map((t, i) => {
          const isAmount = i === amountWord
          const isPayee = !!payeeWords && i >= payeeWords[0] && i <= payeeWords[1]
          return (
            <button
              key={t.start}
              type="button"
              onClick={() => tap(i)}
              aria-pressed={isAmount || isPayee}
              className={cx(
                'mr-1 rounded-md px-1 transition-colors duration-150',
                isAmount ? 'bg-[var(--ink)] font-semibold text-[var(--surface)]' : isPayee ? 'bg-[var(--accent-soft)] font-semibold text-[var(--accent)]' : 'hover:bg-[var(--surface-2)]',
              )}
            >
              {t.text}
            </button>
          )
        })}
      </div>
      <div className="ink-2 flex flex-wrap gap-x-4 gap-y-1 px-1 text-xs">
        <span>
          <span className="mr-1 inline-block h-2.5 w-2.5 rounded-sm bg-[var(--ink)] align-middle" />
          Amount
        </span>
        <span>
          <span className="mr-1 inline-block h-2.5 w-2.5 rounded-sm bg-[var(--accent)] align-middle" />
          Payee
        </span>
      </div>

      <Segmented<'DEBIT' | 'CREDIT'>
        value={direction}
        onChange={setDirection}
        className="bg-[var(--surface)]"
        options={[
          { value: 'DEBIT', label: 'Money out' },
          { value: 'CREDIT', label: 'Money in' },
        ]}
      />
      <div className="flex gap-2">
        <div className="w-36 shrink-0">
          <Field label="Amount (₹)">
            <Input inputMode="decimal" required value={amount} onChange={(e) => (setAmount(e.target.value.replace(/[^\d.]/g, '')), setAmountWord(null))} className="bg-[var(--surface)]" />
          </Field>
        </div>
        <Field label="Show payee as" hint={marked && name.trim() && name.trim() !== marked ? `Remembered for "${marked}"` : undefined}>
          <Input value={name} onChange={(e) => (setName(e.target.value), setNameEdited(true))} placeholder={marked || 'Optional'} className="bg-[var(--surface)]" />
        </Field>
      </div>
      <div>
        <span className="ink-2 mb-1.5 block px-1 text-[12.5px] font-medium">Account</span>
        <div className="flex flex-wrap gap-2">
          {active.map((a) => (
            <Chip key={a.id} active={a.id === accountId} onClick={() => setAccountId(a.id)}>
              {a.name}
            </Chip>
          ))}
        </div>
      </div>
      <div>
        <span className="ink-2 mb-1.5 block px-1 text-[12.5px] font-medium">Category (optional)</span>
        <div className="flex flex-wrap gap-2">
          {kindCats.map((c) => (
            <Chip key={c.id} active={c.id === category?.id} onClick={() => setCategoryId(c.id === category?.id ? null : c.id)}>
              {c.name}
            </Chip>
          ))}
        </div>
        {!payeeWords && !name.trim() && (
          <p className="ink-2 mt-1.5 px-1 text-xs">No payee in this message? That's fine. Pick a category and messages worded like it will get it.</p>
        )}
      </div>
      <ErrorText error={teach.error} />
      <Button type="submit" className="w-full" disabled={!Number(amount) || accountId === '' || teach.isPending}>
        {teach.isPending ? 'Learning…' : 'Save & learn'}
      </Button>
    </form>
  )
}
