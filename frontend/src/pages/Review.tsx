import { useQuery } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, type YearReview } from '../api'
import { CategoryList, PeriodBars } from '../components/charts'
import { BigMoney, Card, Chip, Delta, FeatureGate, List, Page, PageHeader, PageSkeleton, Section } from '../components/ui'
import { formatDay, money, money0, shortDate } from '../format'
import { useFeature } from '../hooks'

const monthName = (m: string) => formatDay(m + '-01', { month: 'long' })

/** The year at a glance. */
export default function Review() {
  const on = useFeature('review')
  const [params, setParams] = useSearchParams()
  const year = Number(params.get('year') ?? new Date().getFullYear())
  const nav = useNavigate()
  const q = useQuery({ queryKey: ['review', year], queryFn: () => api<YearReview>('/review?year=' + year), enabled: on === true })
  const r = q.data

  return (
    <FeatureGate on={on} name="Year in review">
      <Page>
        <PageHeader back title={`${year} in review`} subtitle={r?.partial ? 'So far this year' : undefined} />
        {r && r.years.length > 1 && (
          <div className="no-scrollbar -mx-4 mb-4 flex gap-2 overflow-x-auto px-4">
            {r.years.map((y) => (
              <Chip key={y} active={y === year} onClick={() => setParams({ year: String(y) }, { replace: true })}>
                {y}
              </Chip>
            ))}
          </div>
        )}
        {!r ? (
          <PageSkeleton />
        ) : (
          <>
            <Card className="p-5">
              <p className="ink-2 text-[14px]">You saved</p>
              <BigMoney value={r.saved} paise={false} className="block text-[40px] leading-tight" />
              <p className="ink-2 mt-1 text-[14px]">
                {r.savingsRate != null ? `${r.savingsRate}% of what came in · ` : ''}
                {money0(r.income)} in, {money0(r.expense)} out
              </p>
            </Card>

            <div className="mt-3 grid grid-cols-2 gap-2">
              <Fact label="Transactions" value={String(r.transactions)} />
              <Fact label="Spent per day" value={money0(r.perDay)} />
              <Fact label="No-spend days" value={String(r.noSpendDays)} />
              <Fact label="Busiest day" value={r.busiestWeekday ?? '—'} />
            </div>

            {r.months.length > 0 && (
              <Section title="Month by month">
                <Card className="p-4">
                  <PeriodBars
                    selected={null}
                    onSelect={(m) => m && nav(`/reports?view=month&at=${m}-01`)}
                    items={r.months.map((m) => ({ key: m.month, label: monthName(m.month).slice(0, 3), value: m.expense }))}
                  />
                  <p className="ink-2 mt-3 text-[13px]">
                    {r.bestMonth && <>Best month: <b>{monthName(r.bestMonth)}</b>. </>}
                    {r.costliestMonth && <>Costliest: <b>{monthName(r.costliestMonth)}</b>. </>}Tap a month to open it.
                  </p>
                </Card>
              </Section>
            )}

            {r.categories.length > 0 && (
              <Section title="Where it went">
                <Card className="p-3">
                  <CategoryList items={r.categories} kind="expense" limit={6} onSelect={(c) => nav(c.categoryId ? `/transactions?categoryId=${c.categoryId}` : '/transactions?uncategorized=true')} />
                </Card>
              </Section>
            )}

            <Section title="Highlights">
              <List>
                {r.biggest && <Line label="Biggest expense" value={`${r.biggest.payee ?? '—'} · ${money(r.biggest.amount)}`} sub={shortDate(r.biggest.day)} />}
                {r.mostVisited && <Line label="Most frequent" value={r.mostVisited} sub={`${r.mostVisitedCount} times`} />}
                {r.merchants[0] && <Line label="Spent most at" value={r.merchants[0].name} sub={money0(r.merchants[0].total)} />}
                {r.networthStart != null && r.networthEnd != null && (
                  <Line label="Net worth" value={`${money0(r.networthStart)} → ${money0(r.networthEnd)}`} sub={<Delta now={r.networthEnd} before={r.networthStart} />} />
                )}
              </List>
            </Section>

            {(r.lastYearExpense > 0 || r.lastYearIncome > 0) && (
              <Section title={`Compared with ${year - 1}`}>
                <Card className="grid grid-cols-2 gap-3">
                  <div>
                    <p className="ink-2 text-[13px]">Spent</p>
                    <p className="tabular text-[17px] font-semibold">{money0(r.expense)}</p>
                    <Delta now={r.expense} before={r.lastYearExpense} goodWhenUp={false} />
                  </div>
                  <div>
                    <p className="ink-2 text-[13px]">Received</p>
                    <p className="tabular text-[17px] font-semibold">{money0(r.income)}</p>
                    <Delta now={r.income} before={r.lastYearIncome} />
                  </div>
                </Card>
              </Section>
            )}
          </>
        )}
      </Page>
    </FeatureGate>
  )
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="surface rounded-2xl px-3 py-2.5">
      <p className="ink-2 text-[12px]">{label}</p>
      <p className="tabular mt-0.5 truncate text-[17px] font-semibold">{value}</p>
    </div>
  )
}

function Line({ label, value, sub }: { label: string; value: string; sub?: React.ReactNode }) {
  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <span className="ink-2 w-28 shrink-0 text-[13px]">{label}</span>
      <span className="min-w-0 flex-1 truncate text-right text-[15px] font-medium">{value}</span>
      {sub && <span className="ink-3 shrink-0 text-[12px]">{sub}</span>}
    </div>
  )
}
