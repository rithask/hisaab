import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { api } from './api'
import Layout from './components/Layout'
import { PageSkeleton } from './components/ui'
import Accounts from './pages/Accounts'
import Categories from './pages/Categories'
import Bills from './pages/Bills'
import Budgets from './pages/Budgets'
import Home from './pages/Home'
import NetWorth from './pages/NetWorth'
import Review from './pages/Review'
import Rewards from './pages/Rewards'
import Subscriptions from './pages/Subscriptions'
import Unusual from './pages/Unusual'
import ImportStatement from './pages/ImportStatement'
import Inbox from './pages/Inbox'
import Invest from './pages/Invest'
import Login from './pages/Login'
import More from './pages/More'
import People from './pages/People'
import PersonDetail from './pages/PersonDetail'
import Reports from './pages/Reports'
import Settings from './pages/Settings'
import Setup from './pages/Setup'
import Statements from './pages/Statements'
import Transactions from './pages/Transactions'

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
          <Route path="bills" element={<Bills />} />
          <Route path="budgets" element={<Budgets />} />
          <Route path="networth" element={<NetWorth />} />
          <Route path="review" element={<Review />} />
          <Route path="subscriptions" element={<Subscriptions />} />
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
