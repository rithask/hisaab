import { Navigate } from 'react-router-dom'
import { FeatureGate, Page, PageHeader } from '../components/ui'
import { useFeature } from '../hooks'
import { BillsPart } from './Bills'
import { SubscriptionsPart } from './Subscriptions'

/**
 * Everything that repeats, in one place: bills (rent, phone, card dues) with their next due date, then
 * subscriptions and what changed about them. Each part shows while its feature is on (Settings → Features).
 */
export default function Repeating() {
  const bills = useFeature('bills')
  const subs = useFeature('subscriptions')
  const on = bills === undefined || subs === undefined ? undefined : bills || subs
  return (
    <FeatureGate on={on} name="Bills and subscriptions">
      <Page>
        <PageHeader back title="Repeating" subtitle="Bills and subscriptions, learned from payments that repeat" />
        {bills && <BillsPart />}
        {subs && (
          <div className={bills ? 'mt-8' : undefined}>
            {bills && <h2 className="mb-3 px-1 text-[20px] font-bold tracking-tight">Subscriptions</h2>}
            <SubscriptionsPart />
          </div>
        )}
      </Page>
    </FeatureGate>
  )
}

/** The old addresses (/bills, /subscriptions) still work. */
export const ToRepeating = () => <Navigate to="/repeating" replace />
