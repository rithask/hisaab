import { useQuery } from '@tanstack/react-query'
import { GraduationCap, X } from '@phosphor-icons/react'
import { api, type Learned } from '../../api'
import { Card, Confirm, IconBadge, IconButton, FieldLabel } from '../../components/ui'
import { dateTime } from '../../format'
import { useMoneyMutation } from '../../hooks'

/** Everything the SMS parser has picked up, with a way to make it forget a mistake. */
export function LearnedCard() {
  const learned = useQuery({ queryKey: ['sms-learned'], queryFn: () => api<Learned>('/sms/learned') })
  const forgetFormat = useMoneyMutation((id: number) => api(`/sms/formats/${id}`, { method: 'DELETE' }))
  const resumeFormat = useMoneyMutation((id: number) => api(`/sms/formats/${id}/resume`, { method: 'POST' }))
  const forgetPayee = useMoneyMutation((id: number) => api(`/sms/payees/${id}`, { method: 'DELETE' }))
  const l = learned.data
  const empty = !l || (l.formats.length === 0 && l.payees.length === 0)
  return (
    <Card className="p-5">
      <div className="flex items-center gap-3">
        <IconBadge icon={GraduationCap} />
        <div className="min-w-0 flex-1">
          <p className="font-semibold">What it has learned</p>
          <p className="ink-2 text-caption">
            {l ? `${l.formats.length} message wording${l.formats.length === 1 ? '' : 's'} · ${l.payees.length} payee name${l.payees.length === 1 ? '' : 's'} · ${l.senders} sender${l.senders === 1 ? '' : 's'}` : ' '}
          </p>
        </div>
      </div>
      {empty ? (
        <p className="ink-2 mt-4 text-label leading-relaxed">
          Nothing yet. It learns when you correct a message (Settings → Messages), mark one as not a transaction, rename a payee on a transaction, or pick the account for a message it couldn't place.
        </p>
      ) : (
        <div className="mt-4 space-y-4">
          {l!.formats.length > 0 && (
            <div>
              <FieldLabel>How messages are worded</FieldLabel>
              <ul className="space-y-2">
                {l!.formats.map((f) => (
                  <li key={f.id} className="flex items-start gap-2 rounded-box bg-surface-2 p-3">
                    <Confirm ask="Forget this wording?" yes="Forget" busy={forgetFormat.isPending} onYes={() => forgetFormat.mutate(f.id)}>
                    {(ask) => (<>
                    <div className="min-w-0 flex-1">
                      <p className="break-words font-mono text-micro leading-relaxed">{f.skeleton}</p>
                      <p className="ink-3 mt-1 text-micro">
                        {f.auto ? 'Learned automatically · ' : ''}
                        {f.channel === 'EMAIL' ? 'Email · ' : ''}
                        {f.kind === 'SKIP' ? 'not a transaction' : `${f.institution} · ${f.direction === 'DEBIT' ? 'money out' : 'money in'}`} · used {f.hits} time{f.hits === 1 ? '' : 's'}
                        {f.lastUsedAt ? ` · last ${dateTime(f.lastUsedAt)}` : ''}
                        {f.corrections > 0 ? ` · corrected ${f.corrections === 1 ? 'once' : `${f.corrections} times`}` : ''}
                      </p>
                      {f.paused && (
                        <p className="mt-1.5 flex items-center gap-2 text-micro text-attention">
                          Paused: it kept being corrected, so its messages wait for review.
                          <button className="link font-semibold" onClick={() => resumeFormat.mutate(f.id)}>
                            Resume
                          </button>
                        </p>
                      )}
                    </div>
                    <IconButton icon={X} label="Forget this wording" size="xs" variant="plain" onClick={ask} />
                    </>)}
                    </Confirm>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {l!.payees.length > 0 && (
            <div>
              <FieldLabel>Payee names</FieldLabel>
              <ul className="space-y-2">
                {l!.payees.map((p) => (
                  <li key={p.id} className="flex items-center gap-2 rounded-box bg-surface-2 px-3 py-2.5 text-small">
                    <Confirm ask="Forget this name?" yes="Forget" busy={forgetPayee.isPending} onYes={() => forgetPayee.mutate(p.id)}>
                      {(ask) => (
                        <>
                          <span className="ink-2 min-w-0 flex-1 truncate font-mono text-micro uppercase">{p.rawKey}</span>
                          <span className="ink-3">→</span>
                          <span className="min-w-0 flex-1 truncate font-medium">{p.displayName}</span>
                          <IconButton icon={X} label="Forget this name" size="xs" variant="plain" onClick={ask} />
                        </>
                      )}
                    </Confirm>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </Card>
  )
}
