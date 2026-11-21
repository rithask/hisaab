import { Users } from '@phosphor-icons/react'
import type { Txn } from '../api'
import { dateTime, time, joinNames } from '../format'
import { txnIcon } from '../icons'
import { Amount, cx, IconBadge } from './ui'

/**
 * What a payment that isn't spending or income is, in place of a category: money I lent, or a repayment either
 * way. Null when it's (partly) mine and so has, or needs, a category.
 */
export function settledLabel(t: Txn): string | null {
  if (t.transfer || t.paidBy) return null
  if (t.lentTo?.length) return `Lent to ${joinNames(t.lentTo)}`
  if (t.category) return null
  if (t.paidBackBy?.length) return `Paid back by ${joinNames(t.paidBackBy)}`
  if (t.paidBackTo?.length) return `Paid back to ${joinNames(t.paidBackTo)}`
  return null
}

export default function TxnRow({ t, onClick, showDate }: { t: Txn; onClick: () => void; showDate?: boolean }) {
  const title = t.counterparty ?? t.description ?? (t.direction === 'CREDIT' ? 'Money received' : 'Payment')
  const Icon = txnIcon(t)
  const color = t.transfer ? 'var(--ink-3)' : (t.category?.color ?? (t.direction === 'CREDIT' ? 'var(--positive)' : 'var(--ink-3)'))
  const sub = t.transfer ? `Between my accounts${t.linkedAccount ? ` · ${t.linkedAccount.name}` : ''}` : (settledLabel(t) ?? t.category?.name ?? 'No category')
  return (
    <button onClick={onClick} className={cx('flex w-full items-center gap-3 px-4 py-3 text-left row', t.transfer && 'opacity-60')}>
      <IconBadge icon={Icon} color={color} />
      <span className="min-w-0 flex-1">
        <span className="block truncate text-body font-medium">{title}</span>
        <span className="ink-2 block truncate text-small">
          {sub} · {t.paidBy ? `Paid by ${t.paidBy.name}` : t.account?.name}
        </span>
      </span>
      <span className="shrink-0 text-right">
        <Amount value={t.amount} direction={t.direction} muted={t.transfer} className="block text-body" />
        <span className="ink-3 flex items-center justify-end gap-1 text-caption">
          {t.sharedAmount && !t.lentTo?.length ? (
            <span className="flex items-center gap-0.5 text-accent">
              <Users size={11} /> split ·
            </span>
          ) : t.amountEstimated ? (
            <span className="text-attention">≈{t.originalCurrency} ·</span>
          ) : null}
          {showDate ? dateTime(t.occurredAt) : time(t.occurredAt)}
        </span>
      </span>
    </button>
  )
}
