import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type NetWorthPoint } from '../api'
import { NetWorthChart } from '../components/charts'
import { BigMoney, Card, Delta, FeatureGate, Page, PageHeader, PageSkeleton, Section, Segmented } from '../components/ui'
import { money0, shortDate } from '../format'
import { useFeature } from '../hooks'

type Range = '1m' | '3m' | '6m' | '1y' | 'all'

/** Net worth over time, from daily snapshots (earlier days rebuilt from transactions). */
export default function NetWorth() {
  const on = useFeature('networth')
  const [range, setRange] = useState<Range>('3m')
  const q = useQuery({ queryKey: ['networth', range], queryFn: () => api<NetWorthPoint[]>('/networth?range=' + range), enabled: on === true })
  const pts = q.data ?? []
  const first = pts[0], last = pts[pts.length - 1]
  const estimated = pts.filter((p) => p.estimated)

  return (
    <FeatureGate on={on} name="Net worth">
      <Page>
        <PageHeader back title="Net worth" />
        {!q.data ? (
          <PageSkeleton />
        ) : !last ? (
          <Card className="p-5 text-[15px]">The first snapshot is taken shortly after the app starts. Check back in a minute.</Card>
        ) : (
          <>
            <p className="ink-2 text-[15px]">Today</p>
            <BigMoney value={last.netWorth} className="block text-[40px] leading-tight" />
            {first && first !== last && (
              <p className="mt-1 flex items-center gap-2 text-[14px]">
                <Delta now={last.netWorth} before={first.netWorth} />
                <span className="ink-2">
                  {last.netWorth >= first.netWorth ? '+' : '−'}
                  {money0(Math.abs(last.netWorth - first.netWorth))} since {shortDate(first.day)}
                </span>
              </p>
            )}
            <Segmented<Range>
              className="mt-5"
              value={range}
              onChange={setRange}
              options={[
                { value: '1m', label: '1M' },
                { value: '3m', label: '3M' },
                { value: '6m', label: '6M' },
                { value: '1y', label: '1Y' },
                { value: 'all', label: 'All' },
              ]}
            />
            <Card className="mt-3 p-3">
              <NetWorthChart points={pts} height={220} />
            </Card>
            {estimated.length > 0 && (
              <p className="ink-3 mt-2 px-1 text-[12px]">
                The dashed part (until {shortDate(estimated[estimated.length - 1].day)}) is rebuilt from your transactions: bank and card balances are exact, investments are shown at their earliest recorded value.
              </p>
            )}
            <Section title="Made up of">
              <Card className="grid grid-cols-2 gap-3">
                <Part label="Cash & bank" value={last.cash} />
                <Part label="Investments" value={last.investments} />
                <Part label="Owed to you" value={last.receivable} />
                <Part label="Card dues" value={-last.cardDues} />
              </Card>
            </Section>
          </>
        )}
      </Page>
    </FeatureGate>
  )
}

function Part({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <p className="ink-2 text-[13px]">{label}</p>
      <p className={'tabular mt-0.5 text-[17px] font-semibold' + (value < 0 ? ' text-[var(--negative)]' : '')}>
        {value < 0 ? '−' : ''}
        {money0(Math.abs(value))}
      </p>
    </div>
  )
}
