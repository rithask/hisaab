import { useQuery } from '@tanstack/react-query'
import { api, type Txn } from '../api'
import { Sheet } from './ui'
import { TxnDetail } from './TxnDetail'
export { AddTxnSheet } from './AddTxnForm'

export default function TxnSheet({ txn, onClose }: { txn: Txn | null; onClose: () => void }) {
  return (
    <Sheet open={!!txn} onClose={onClose} title="Transaction">
      {txn && <TxnDetail key={txn.id} txn={txn} onClose={onClose} />}
    </Sheet>
  )
}

/** Opens the transaction sheet for an id (from an alert or a list that doesn't carry the whole transaction). */
export function TxnSheetById({ id, onClose }: { id: number | null; onClose: () => void }) {
  const q = useQuery({ queryKey: ['transaction', id], queryFn: () => api<Txn>(`/transactions/${id}`), enabled: id != null })
  return <TxnSheet txn={id != null ? (q.data ?? null) : null} onClose={onClose} />
}
