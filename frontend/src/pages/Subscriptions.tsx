import { useQuery } from '@tanstack/react-query'
import { ArrowUpRight, Copy, Repeat, Sparkle, TrendDown, TrendUp, Pause } from '@phosphor-icons/react'
import { useState } from 'react'
import { api, type Subscription, type SubscriptionAlert, type Subscriptions as Data } from '../api'
import { TxnSheetById } from '../components/TxnSheet'
import { Badge, Button, Card, Empty, FeatureGate, List, Page, PageHeader, PageSkeleton, Section, Sheet } from '../components/ui'
import { money0, moneyAuto, shortDate } from '../format'
import { useFeature, useMoneyMutation } from '../hooks'

const per = (every: string) => ({ 'Every week': 'a week', 'Every month': 'a month', 'Every 3 months': 'every 3 months', 'Every year': 'a year' })[every] ?? every.toLowerCase()

/** The sentence for an alert. Shared with Home. */
export function alertText(a: SubscriptionAlert): { title: string; detail: string } {
  switch (a.kind) {
    case 'PRICE_UP': {
      const pct = Math.round(((a.amount - a.previous!) / a.previous!) * 100)
      return { title: `${a.name} went up ${pct}%`, detail: `${moneyAuto(a.previous)} → ${moneyAuto(a.amount)} from ${shortDate(a.day)}. That's about ${money0(a.perYear)} more a year.` }
    }
    case 'PRICE_DOWN':
      return { title: `${a.name} got cheaper`, detail: `${moneyAuto(a.previous)} → ${moneyAuto(a.amount)} from ${shortDate(a.day)}.` }
    case 'DOUBLE': {
      const same = a.day === a.otherDay
      const where = a.account && a.otherAccount && a.account !== a.otherAccount ? `, from ${a.otherAccount} and ${a.account}` : ''
      return {
        title: `${a.name} charged twice`,
        detail: `${moneyAuto(a.amount)} ${same ? `twice on ${shortDate(a.day)}` : `on ${shortDate(a.otherDay!)} and again on ${shortDate(a.day)}`}${where}. If one is a mistake, ask them for a refund.`,
      }
    }
    case 'NEW':
      return { title: `New: ${a.name}`, detail: `${moneyAuto(a.amount)} ${per(a.every)} since ${shortDate(a.otherDay!)}. If it started as a free trial, you're paying for it now.` }
    case 'STOPPED':
      return { title: `${a.name} wasn't charged`, detail: `Expected around ${shortDate(a.day)}. Cancelled, or paid from an account the app doesn't see?` }
  }
}

const alertIcon = { PRICE_UP: TrendUp, PRICE_DOWN: TrendDown, DOUBLE: Copy, NEW: Sparkle, STOPPED: Pause } as const

/** Subscriptions learned from fixed-price payments that repeat, and what changed about them. */
export default function Subscriptions() {
  const on = useFeature('subscriptions')
  const q = useQuery({ queryKey: ['subscriptions'], queryFn: () => api<Data>('/subscriptions'), enabled: on === true })
  const [open, setOpen] = useState<Subscription | null>(null)
  const [txn, setTxn] = useState<number | null>(null)
  const unhide = useMoneyMutation((key: string) => api('/subscriptions/unhide', { body: { key } }))
  const d = q.data
  const active = d?.items.filter((s) => s.status !== 'STOPPED') ?? []
  const stopped = d?.items.filter((s) => s.status === 'STOPPED') ?? []

  return (
    <FeatureGate on={on} name="Subscription watch">
      <Page>
        <PageHeader back title="Subscriptions" subtitle="Learned from payments that repeat" />
        {!d ? (
          <PageSkeleton />
        ) : d.items.length === 0 ? (
          <Empty icon={Repeat} title="No subscriptions spotted yet">
            After two payments of the same amount a month (or a week, or a year) apart, a subscription shows up here, and you'll hear when its price changes.
          </Empty>
        ) : (
          <>
            <Card>
              <p className="ink-2 text-[13px]">Subscriptions cost you</p>
              <p className="display mt-0.5 text-[28px]">
                {money0(d.monthly)} <span className="ink-2 text-[17px] font-normal">a month</span>
              </p>
              <p className="ink-2 mt-0.5 text-[13px]">
                {money0(d.yearly)} a year · {active.length} active
              </p>
            </Card>

            {d.alerts.length > 0 && (
              <Section title="Worth a look">
                <div className="space-y-2">
                  {d.alerts.map((a) => (
                    <AlertCard key={a.key} a={a} onOpen={() => setTxn(a.txnIds[a.txnIds.length - 1])} />
                  ))}
                </div>
              </Section>
            )}

            {active.length > 0 && (
              <Section title="Active">
                <SubList items={active} onOpen={setOpen} />
              </Section>
            )}
            {stopped.length > 0 && (
              <Section title="Stopped">
                <SubList items={stopped} onOpen={setOpen} />
              </Section>
            )}
            {d.hidden.length > 0 && (
              <Section title="Not subscriptions">
                <List>
                  {d.hidden.map((h) => (
                    <div key={h.key} className="flex items-center gap-3 px-4 py-3">
                      <span className="min-w-0 flex-1 truncate text-[15px]">{h.name}</span>
                      <button className="link text-[13px] font-medium text-[var(--accent)]" onClick={() => unhide.mutate(h.key)}>
                        Show again
                      </button>
                    </div>
                  ))}
                </List>
              </Section>
            )}
            <p className="ink-3 mt-4 px-1 text-[13px]">
              Fixed-price payments that repeat. Rent, investments and bills that vary (electricity, phone usage) aren't counted; they're on the Bills page.
            </p>
          </>
        )}
        <Sheet open={!!open} onClose={() => setOpen(null)} title={open?.name ?? ''}>
          {open && <SubDetail s={open} onDone={() => setOpen(null)} />}
        </Sheet>
        <TxnSheetById id={txn} onClose={() => setTxn(null)} />
      </Page>
    </FeatureGate>
  )
}

export function AlertCard({ a, onOpen }: { a: SubscriptionAlert; onOpen: () => void }) {
  const dismiss = useMoneyMutation(() => api('/watch/dismiss', { body: { key: a.key } }))
  const Icon = alertIcon[a.kind]
  const t = alertText(a)
  const bad = a.kind === 'PRICE_UP' || a.kind === 'DOUBLE'
  return (
    <Card className="p-4">
      <div className="flex gap-3">
        <span className={'flex h-9 w-9 shrink-0 items-center justify-center rounded-full ' + (bad ? 'bg-amber-500/14 text-amber-700 dark:text-amber-300' : 'ink-2 bg-[var(--surface-2)]')}>
          <Icon size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[15px] font-semibold">{t.title}</p>
          <p className="ink-2 mt-0.5 text-[13px] leading-relaxed">{t.detail}</p>
          <div className="mt-2 flex gap-4 text-[13px] font-medium">
            <button className="link text-[var(--accent)]" onClick={onOpen}>
              Open payment
            </button>
            <button className="link ink-2" disabled={dismiss.isPending} onClick={() => dismiss.mutate(undefined)}>
              Got it
            </button>
          </div>
        </div>
      </div>
    </Card>
  )
}

function SubList({ items, onOpen }: { items: Subscription[]; onOpen: (s: Subscription) => void }) {
  return (
    <List>
      {items.map((s) => (
        <button key={s.key} className="row flex w-full items-center gap-3 px-4 py-3 text-left" onClick={() => onOpen(s)}>
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full" style={{ background: `color-mix(in oklab, ${s.categoryColor ?? 'var(--ink-3)'} 16%, transparent)`, color: s.categoryColor ?? 'var(--ink-2)' }}>
            <Repeat size={18} />
          </span>
          <span className="min-w-0 flex-1">
            <span className="flex items-center gap-2">
              <span className="truncate text-[15px] font-medium">{s.name}</span>
              {s.status === 'NEW' && <Badge tone="blue">new</Badge>}
              {s.priceChange && s.priceChange.to > s.priceChange.from && <Badge tone="amber">price up</Badge>}
            </span>
            <span className="ink-2 block truncate text-[13px]">
              {s.status === 'STOPPED' ? `Last paid ${shortDate(s.lastPaid)}` : `Next ${shortDate(s.nextDue)}`} · {s.account}
            </span>
          </span>
          <span className="shrink-0 text-right">
            <span className="tabular block text-[15px] font-semibold">{moneyAuto(s.amount)}</span>
            <span className="ink-3 block text-[12px]">{per(s.every)}</span>
          </span>
        </button>
      ))}
    </List>
  )
}

function SubDetail({ s, onDone }: { s: Subscription; onDone: () => void }) {
  const hide = useMoneyMutation(() => api('/subscriptions/hide', { body: { key: s.key } }), onDone)
  const rows: [string, string][] = [
    ['Costs', `${moneyAuto(s.amount)} ${per(s.every)}${s.every !== 'Every month' ? ` (${money0(s.monthly)} a month)` : ''}`],
    ['A year', money0(s.yearly)],
    ['Paid this year', money0(s.paidThisYear)],
    ['Since', `${shortDate(s.since)} · ${s.timesPaid} payments`],
    [s.status === 'STOPPED' ? 'Was due' : 'Next payment', shortDate(s.nextDue)],
    ['Paid from', s.account ?? '—'],
  ]
  if (s.priceChange) rows.splice(1, 0, ['Price change', `${moneyAuto(s.priceChange.from)} → ${moneyAuto(s.priceChange.to)} on ${shortDate(s.priceChange.on)}`])
  return (
    <div className="space-y-4">
      <List>
        {rows.map(([k, v]) => (
          <div key={k} className="flex items-center justify-between gap-3 px-4 py-3 text-[15px]">
            <span className="ink-2">{k}</span>
            <span className="tabular text-right font-medium">{v}</span>
          </div>
        ))}
      </List>
      <Button variant="secondary" className="w-full" onClick={() => hide.mutate(undefined)}>
        <ArrowUpRight size={16} /> Not a subscription
      </Button>
      <p className="ink-3 px-1 text-[12.5px]">Hiding it here doesn't change Bills or anything else.</p>
    </div>
  )
}
