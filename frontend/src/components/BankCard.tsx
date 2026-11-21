import { CreditCard } from '@phosphor-icons/react'
import type { Account } from '../api'
import { money0, shortDate } from '../format'
import { cx, Meter } from './ui'

/** A credit card at a glance: what I owe, how much of the limit is used, when it's due. */
export default function BankCard({ a, onClick, className }: { a: Account; onClick?: () => void; className?: string }) {
  const c = a.card
  const util = c?.utilizationPct ?? 0
  const dueSoon = c?.dueDate && c.daysToDue <= 3 && a.balance > 0
  return (
    <button onClick={onClick} className={cx('pressable surface block w-full rounded-card p-4 text-left', className)}>
      <div className="flex items-center gap-3">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-surface-2">
          <CreditCard size={20} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-body font-medium">{a.name}</p>
          <p className="ink-2 text-small">
            {a.institution === 'OTHER' ? 'Credit card' : a.institution}
            {a.last4 ? ` •••• ${a.last4}` : ''}
          </p>
        </div>
        {c?.dueDate && a.balance > 0 && <p className={cx('shrink-0 text-small', dueSoon ? 'font-semibold text-negative' : 'ink-2')}>Due {shortDate(c.dueDate)}</p>}
      </div>

      <p className="ink-2 mt-4 text-small">You owe</p>
      <p className="display text-heading leading-tight">{money0(a.balance)}</p>

      {c?.creditLimit != null ? (
        <>
          <Meter pct={util} status className="mt-3" />
          <div className="ink-2 mt-1.5 flex justify-between text-caption">
            <span>{Math.round(util)}% of limit used</span>
            <span>{money0(c.available)} available</span>
          </div>
        </>
      ) : (
        <p className="ink-3 mt-2 text-caption">Add a credit limit to see how much is available.</p>
      )}
    </button>
  )
}
