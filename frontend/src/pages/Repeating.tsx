import { Navigate } from 'react-router-dom'
import { Page, PageHeader } from '../components/ui'
import { BillsPart } from './Bills'
import { SubscriptionsPart } from './Subscriptions'

/**
 * Everything that repeats, in one place: bills (rent, phone, card dues) with their next due date, then
 * subscriptions and what changed about them.
 */
export default function Repeating() {
  return (
    <Page>
      <PageHeader back title="Repeating" subtitle="Bills and subscriptions, learned from payments that repeat" />
      <BillsPart />
      <div className="mt-8">
        <h2 className="mb-3 px-1 text-subheading font-bold tracking-tight">Subscriptions</h2>
        <SubscriptionsPart />
      </div>
    </Page>
  )
}

/** The old addresses (/bills, /subscriptions) still work. */
export const ToRepeating = () => <Navigate to="/repeating" replace />
