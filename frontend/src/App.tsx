import { useQuery, useQueryClient } from '@tanstack/react-query'
import { lazy, useEffect } from 'react'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { api } from './api'
import Layout from './components/Layout'
import { PageSkeleton } from './components/ui'
import Home from './pages/Home'
import Login from './pages/Login'

// Every page but Home and sign-in is its own file, loaded the first time it's opened (Layout shows a skeleton
// meanwhile). The service worker keeps them all for offline use.
const Accounts = lazy(() => import('./pages/Accounts'))
const Categories = lazy(() => import('./pages/Categories'))
const Budgets = lazy(() => import('./pages/Budgets'))
const NetWorth = lazy(() => import('./pages/NetWorth'))
const Review = lazy(() => import('./pages/Review'))
const Rewards = lazy(() => import('./pages/Rewards'))
const Repeating = lazy(() => import('./pages/Repeating'))
const ToRepeating = lazy(() => import('./pages/Repeating').then((m) => ({ default: m.ToRepeating })))
const Unusual = lazy(() => import('./pages/Unusual'))
const ImportStatement = lazy(() => import('./pages/ImportStatement'))
const Inbox = lazy(() => import('./pages/Inbox'))
const Invest = lazy(() => import('./pages/Invest'))
const More = lazy(() => import('./pages/More'))
const People = lazy(() => import('./pages/People'))
const PersonDetail = lazy(() => import('./pages/PersonDetail'))
const Reports = lazy(() => import('./pages/Reports'))
const Settings = lazy(() => import('./pages/Settings'))
const Setup = lazy(() => import('./pages/Setup'))
const Statements = lazy(() => import('./pages/Statements'))
const Transactions = lazy(() => import('./pages/Transactions'))

export default function App() {
  const qc = useQueryClient()
  const me = useQuery({ queryKey: ['me'], queryFn: () => api<{ username: string }>('/auth/me'), retry: false, staleTime: Infinity })

  useEffect(() => {
    const onUnauthorized = () => qc.setQueryData(['me'], null)
    window.addEventListener('hisaab:unauthorized', onUnauthorized)
    return () => window.removeEventListener('hisaab:unauthorized', onUnauthorized)
  }, [qc])

  if (me.isLoading) return <div className="px-4"><PageSkeleton /></div>
  if (!me.data) return <Login />

  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Home />} />
          <Route path="transactions" element={<Transactions />} />
          <Route path="people" element={<People />} />
          <Route path="people/:id" element={<PersonDetail />} />
          <Route path="invest" element={<Invest />} />
          <Route path="more" element={<More />} />
          <Route path="accounts" element={<Accounts />} />
          <Route path="categories" element={<Categories />} />
          <Route path="inbox" element={<Inbox />} />
          <Route path="statements" element={<Statements />} />
          <Route path="repeating" element={<Repeating />} />
          <Route path="bills" element={<ToRepeating />} />
          <Route path="budgets" element={<Budgets />} />
          <Route path="networth" element={<NetWorth />} />
          <Route path="review" element={<Review />} />
          <Route path="subscriptions" element={<ToRepeating />} />
          <Route path="unusual" element={<Unusual />} />
          <Route path="rewards" element={<Rewards />} />
          <Route path="import" element={<ImportStatement />} />
          <Route path="import/:id" element={<ImportStatement />} />
          <Route path="reports" element={<Reports />} />
          <Route path="settings" element={<Settings />} />
          <Route path="settings/:section" element={<Settings />} />
          <Route path="setup" element={<Setup />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
