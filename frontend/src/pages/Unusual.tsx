import { useQuery } from '@tanstack/react-query'
import { ArrowCounterClockwise, Copy, CurrencyCircleDollar, Moon, ShieldCheck, Storefront, TrendUp } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, type RefundWatch, type Unusual as Data, type UnusualItem, type UnusualReason } from '../api'
import { TxnSheetById } from '../components/TxnSheet'
import { Badge, Card, Empty, FeatureGate, List, Page, PageHeader, PageSkeleton, Section } from '../components/ui'
import { count, dateTime, moneyAuto, shortDate, time } from '../format'
import { useFeature, useMoneyMutation } from '../hooks'

const reasonIcon = { DOUBLE_CHARGE: Copy, BIG_FOR_MERCHANT: TrendUp, NEW_PAYEE_BIG: Storefront, FOREIGN: CurrencyCircleDollar, LATE_NIGHT: Moon } as const

/** Why a payment was flagged, in words. */
export function reasonText(r: UnusualReason, item: UnusualItem): string {
  switch (r.kind) {
    case 'DOUBLE_CHARGE':
      return r.minutesApart ? `The same amount went to the same payee ${r.minutesApart} minute${r.minutesApart === 1 ? '' : 's'} earlier. Charged twice?` : 'The same amount went to the same payee a moment earlier. Charged twice?'
    case 'BIG_FOR_MERCHANT':
      return `Much more than usual here. You normally spend about ${moneyAuto(r.typical)} (${count(r.timesBefore ?? 0)} payments).`
    case 'NEW_PAYEE_BIG':
      return 'A large first payment to someone new.'
    case 'FOREIGN':
      return `Charged in ${r.currency}${r.originalAmount != null ? ` (${new Intl.NumberFormat('en-IN', { style: 'currency', currency: r.currency ?? 'USD' }).format(r.originalAmount)})` : ''}. Expect a forex markup on the bill.`
    case 'LATE_NIGHT':
      return `Paid at ${time(item.at)}, late at night. Was this you?`
  }
}

/** Recent payments that look wrong or risky, judged against my own history, and refunds I'm waiting for. */
export default function Unusual() {
  const on = useFeature('unusual')
  const q = useQuery({ queryKey: ['unusual'], queryFn: () => api<Data>('/unusual'), enabled: on === true })
  const [txn, setTxn] = useState<number | null>(null)
  const d = q.data
  const waiting = d?.refunds.filter((r) => r.status !== 'ARRIVED') ?? []
  const arrived = d?.refunds.filter((r) => r.status === 'ARRIVED') ?? []

  return (
    <FeatureGate on={on} name="Unusual activity">
      <Page>
        <PageHeader back title="Unusual activity" subtitle="Recent payments worth a second look" />
        {!d ? (
          <PageSkeleton />
        ) : (
          <>
            {d.items.length === 0 ? (
              <Empty icon={ShieldCheck} title="Nothing unusual">
                The last 60 days look normal: no double charges, foreign payments or amounts far above your usual.
              </Empty>
            ) : (
              <div className="space-y-2">
                {d.items.map((i) => (
                  <ItemCard key={i.key} item={i} onOpen={() => setTxn(i.txnId)} />
                ))}
              </div>
            )}

            {waiting.length > 0 && (
              <Section title="Refunds you're waiting for">
                <RefundList refunds={waiting} onOpen={setTxn} />
              </Section>
            )}
            {arrived.length > 0 && (
              <Section title="Refunds that arrived">
                <RefundList refunds={arrived} onOpen={setTxn} />
              </Section>
            )}
            <p className="ink-3 mt-5 px-1 text-[13px]">
              Checks the last 60 days against your own history. To watch for a refund, open the payment and tap “I'm expecting a refund for this”.
            </p>
          </>
        )}
        <TxnSheetById id={txn} onClose={() => setTxn(null)} />
      </Page>
    </FeatureGate>
  )
}

function ItemCard({ item, onOpen }: { item: UnusualItem; onOpen: () => void }) {
  const fine = useMoneyMutation(() => api('/watch/dismiss', { body: { key: item.key } }))
  const Icon = reasonIcon[item.reasons[0].kind]
  return (
    <Card className="p-4">
      <div className="flex gap-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-amber-500/14 text-amber-700 dark:text-amber-300">
          <Icon size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-baseline justify-between gap-2">
            <p className="truncate text-[15px] font-semibold">{item.name ?? 'Payment'}</p>
            <p className="tabular shrink-0 text-[15px] font-semibold">−{moneyAuto(item.amount)}</p>
          </div>
          <p className="ink-2 text-[13px]">
            {dateTime(item.at)} · {item.account}
          </p>
          <ul className="mt-1.5 space-y-1">
            {item.reasons.map((r) => (
              <li key={r.kind} className="text-[13.5px] leading-relaxed">
                {reasonText(r, item)}
              </li>
            ))}
          </ul>
          <div className="mt-2 flex gap-4 text-[13px] font-medium">
            <button className="link text-[var(--accent)]" onClick={onOpen}>
              Open payment
            </button>
            <button className="link ink-2" disabled={fine.isPending} onClick={() => fine.mutate(undefined)}>
              Looks fine
            </button>
          </div>
        </div>
      </div>
    </Card>
  )
}

function RefundList({ refunds, onOpen }: { refunds: RefundWatch[]; onOpen: (id: number) => void }) {
  return (
    <List>
      {refunds.map((r) => (
        <button key={r.txnId} className="row flex w-full items-center gap-3 px-4 py-3 text-left" onClick={() => onOpen(r.txnId)}>
          <span className="ink-2 flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[var(--surface-2)]">
            <ArrowCounterClockwise size={18} />
          </span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-[15px] font-medium">{r.name ?? 'Payment'}</span>
            <span className="ink-2 block truncate text-[13px]">
              {r.status === 'ARRIVED' ? `Came back ${shortDate(r.refundedAt!)}` : `Paid ${shortDate(r.paidAt)} · expected by ${shortDate(r.expectedBy)}`}
            </span>
          </span>
          <span className="shrink-0 text-right">
            <span className="tabular block text-[15px] font-semibold">{moneyAuto(r.status === 'ARRIVED' ? r.refunded : r.amount)}</span>
            <Badge tone={r.status === 'ARRIVED' ? 'green' : r.status === 'LATE' ? 'red' : 'amber'}>{r.status === 'ARRIVED' ? 'arrived' : r.status === 'LATE' ? 'late' : 'waiting'}</Badge>
          </span>
        </button>
      ))}
    </List>
  )
}
