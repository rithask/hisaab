import { useQuery } from '@tanstack/react-query'
import { CalendarCheck, CreditCard, Repeat } from '@phosphor-icons/react'
import { api, type Bill } from '../api'
import { Badge, Card, cx, Empty, FeatureGate, List, Page, PageHeader, PageSkeleton, Section } from '../components/ui'
import { money, shortDate } from '../format'
import { useFeature, useMoneyMutation } from '../hooks'

export const whenLabel = (b: Bill) =>
  b.overdue ? `${-b.daysLeft} day${b.daysLeft === -1 ? '' : 's'} late` : b.daysLeft === 0 ? 'Today' : b.daysLeft === 1 ? 'Tomorrow' : `In ${b.daysLeft} days`

/** Bills the app has learned from repeating payments, plus card due dates. Nothing to set up. */
export default function Bills() {
  const on = useFeature('bills')
  const q = useQuery({ queryKey: ['bills'], queryFn: () => api<Bill[]>('/bills'), enabled: on === true })
  const ignore = useMoneyMutation((key: string) => api('/bills/ignore', { body: { key } }))
  const soon = (q.data ?? []).filter((b) => b.daysLeft <= 14)
  const later = (q.data ?? []).filter((b) => b.daysLeft > 14)
  const total = soon.reduce((s, b) => s + b.amount, 0)

  return (
    <FeatureGate on={on} name="Bills">
      <Page>
        <PageHeader back title="Bills" subtitle="Learned from payments that repeat" />
        {!q.data ? (
          <PageSkeleton />
        ) : q.data.length === 0 ? (
          <Empty icon={Repeat} title="No bills spotted yet">
            When you pay the same payee at a steady interval (rent, phone, subscriptions), it shows up here with its next due date.
          </Empty>
        ) : (
          <>
            <Card>
              <p className="ink-2 text-[13px]">Due in the next 14 days</p>
              <p className="display mt-0.5 text-[28px]">{money(total)}</p>
              <p className="ink-2 mt-0.5 text-[13px]">
                {soon.length} bill{soon.length === 1 ? '' : 's'}
              </p>
            </Card>
            {soon.length > 0 && (
              <Section title="Coming up">
                <BillList bills={soon} onIgnore={(k) => ignore.mutate(k)} />
              </Section>
            )}
            {later.length > 0 && (
              <Section title="Later">
                <BillList bills={later} onIgnore={(k) => ignore.mutate(k)} />
              </Section>
            )}
            <p className="ink-3 mt-4 px-1 text-[13px]">A payee counts as a bill after a few payments at a regular interval with similar amounts. Tap "Not a bill" to stop tracking one.</p>
          </>
        )}
      </Page>
    </FeatureGate>
  )
}

export function BillList({ bills, onIgnore }: { bills: Bill[]; onIgnore?: (key: string) => void }) {
  return (
    <List>
      {bills.map((b) => (
        <div key={b.key} className="flex items-center gap-3 px-4 py-3">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[var(--surface-2)]">
            {b.card ? <CreditCard size={20} /> : <CalendarCheck size={20} />}
          </span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-[15px] font-medium">{b.name}</span>
            <span className="ink-2 block truncate text-[13px]">
              {shortDate(b.due)} · {b.every}
              {b.account && !b.card ? ` · ${b.account}` : ''}
            </span>
          </span>
          <span className="shrink-0 text-right">
            <span className="tabular block text-[15px] font-semibold">
              {b.amountVaries ? '~' : ''}
              {money(b.amount)}
            </span>
            <span className="mt-0.5 flex items-center justify-end gap-2">
              <Badge tone={b.overdue ? 'red' : b.daysLeft <= 3 ? 'amber' : 'neutral'}>{whenLabel(b)}</Badge>
              {onIgnore && !b.card && (
                <button className={cx('link ink-3 text-[12px]')} onClick={() => onIgnore(b.key)}>
                  Not a bill
                </button>
              )}
            </span>
          </span>
        </div>
      ))}
    </List>
  )
}
