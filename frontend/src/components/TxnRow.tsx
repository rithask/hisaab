import { Users } from '@phosphor-icons/react'
import type { Txn } from '../api'
import { dateTime, time } from '../format'
import { tint, txnIcon } from '../icons'
import { Amount, cx } from './ui'

const names = (p: { name: string }[]) => (p.length <= 1 ? (p[0]?.name ?? '') : `${p.slice(0, -1).map((x) => x.name).join(', ')} and ${p[p.length - 1].name}`)

/**
 * What a payment that isn't spending or income is, in place of a category: money I lent, or a repayment either
 * way. Null when it's (partly) mine and so has, or needs, a category.
 */
export function settledLabel(t: Txn): string | null {
  if (t.transfer || t.paidBy) return null
  if (t.lentTo?.length) return `Lent to ${names(t.lentTo)}`
  if (t.category) return null
  if (t.paidBackBy?.length) return `Paid back by ${names(t.paidBackBy)}`
  if (t.paidBackTo?.length) return `Paid back to ${names(t.paidBackTo)}`
  return null
}

export default function TxnRow({ t, onClick, showDate }: { t: Txn; onClick: () => void; showDate?: boolean }) {
  const title = t.counterparty ?? t.description ?? (t.direction === 'CREDIT' ? 'Money received' : 'Payment')
  const Icon = txnIcon(t)
  const color = t.transfer ? '#8a8a92' : (t.category?.color ?? (t.direction === 'CREDIT' ? '#16a34a' : '#8a8a92'))
  const sub = t.transfer ? `Between my accounts${t.linkedAccount ? ` · ${t.linkedAccount.name}` : ''}` : (settledLabel(t) ?? t.category?.name ?? 'No category')
  return (
    <button onClick={onClick} className={cx('flex w-full items-center gap-3 px-4 py-3 text-left row', t.transfer && 'opacity-60')}>
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full" style={{ background: tint(color, 0.13), color }}>
        <Icon size={20} />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[15px] font-medium">{title}</span>
        <span className="ink-2 block truncate text-[13px]">
          {sub} · {t.paidBy ? `Paid by ${t.paidBy.name}` : t.account?.name}
        </span>
      </span>
      <span className="shrink-0 text-right">
        <Amount value={t.amount} direction={t.direction} muted={t.transfer} className="block text-[15px]" />
        <span className="ink-3 flex items-center justify-end gap-1 text-[12px]">
          {t.sharedAmount && !t.lentTo?.length ? (
            <span className="flex items-center gap-0.5 text-[var(--accent)]">
              <Users size={11} /> split ·
            </span>
          ) : t.amountEstimated ? (
            <span className="text-amber-600">≈{t.originalCurrency} ·</span>
          ) : null}
          {showDate ? dateTime(t.occurredAt) : time(t.occurredAt)}
        </span>
      </span>
    </button>
  )
}
