import { useQuery } from '@tanstack/react-query'
import { Bank, Check, CreditCard, Wallet } from '@phosphor-icons/react'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type AccountType, type Settings } from '../api'
import AccountForm from '../components/AccountForm'
import { Button, Card, cx, ErrorText, Field, Input, List, PageHeader, Sheet, Page } from '../components/ui'
import { money } from '../format'
import { useAccounts, useMoneyMutation } from '../hooks'

/** First run: start from current balances, no history import needed. */
export default function Setup() {
  const nav = useNavigate()
  const settings = useQuery({ queryKey: ['settings'], queryFn: () => api<Settings>('/settings') })
  const accounts = useAccounts()
  const [step, setStep] = useState(0)
  const [owner, setOwner] = useState<string | null>(null)
  const [adding, setAdding] = useState<AccountType | null>(null)
  const name = owner ?? settings.data?.ownerName ?? ''
  const saveOwner = useMoneyMutation(() => api('/settings', { method: 'PUT', body: { ownerName: name } }), () => setStep(1))

  return (
    <Page>
      <PageHeader back title="Setup" />
      <div className="mb-5 flex gap-2">
        {[0, 1].map((i) => (
          <span key={i} className={cx('h-1.5 flex-1 rounded-full transition-colors duration-200', i <= step ? 'bg-[var(--ink)]' : 'bg-[var(--line-strong)]')} />
        ))}
      </div>
      {step === 0 && (
        <Card className="anim-pop space-y-4 p-5">
          <div>
            <p className="text-[20px] font-semibold">What do banks call you?</p>
            <p className="ink-2 mt-1 text-sm">This lets the app recognise money you move between your own accounts.</p>
          </div>
          <Field label="Your name">
            <Input autoFocus value={name} onChange={(e) => setOwner(e.target.value)} placeholder="ARJUN RAVI K" />
          </Field>
          <ErrorText error={saveOwner.error} />
          <Button className="w-full" onClick={() => (name ? saveOwner.mutate(undefined) : setStep(1))}>
            Continue
          </Button>
        </Card>
      )}
      {step === 1 && (
        <div className="anim-pop">
          <p className="text-[20px] font-semibold">Add your accounts</p>
          <p className="ink-2 mb-4 mt-1 text-sm">Use today's balance (or a card's current outstanding). Alerts keep them up to date from here.</p>
          <div className="grid grid-cols-3 gap-2">
            {(
              [
                ['BANK', Bank, 'Bank'],
                ['CREDIT_CARD', CreditCard, 'Card'],
                ['CASH', Wallet, 'Cash'],
              ] as const
            ).map(([t, Icon, l]) => (
              <button key={t} onClick={() => setAdding(t)} className="surface pressable flex flex-col items-center gap-2 rounded-2xl py-4 text-[13px] font-semibold">
                <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--accent-soft)] text-[var(--accent)]">
                  <Icon size={20} />
                </span>
                {l}
              </button>
            ))}
          </div>
          {(accounts.data ?? []).length > 0 && (
            <List className="mt-4">
              {accounts.data!.map((a) => (
                <div key={a.id} className="flex items-center gap-3 px-4 py-3">
                  <span className="flex h-7 w-7 items-center justify-center rounded-full accent-fill">
                    <Check size={15} />
                  </span>
                  <span className="flex-1 font-semibold">{a.name}</span>
                  <span className="tabular text-sm">{money(a.balance)}</span>
                </div>
              ))}
            </List>
          )}
          <Button className="mt-6 w-full" disabled={!accounts.data?.length} onClick={() => nav('/')}>
            Done
          </Button>
          <p className="ink-3 mt-3 text-center text-xs">Investments and people can be added any time from their tabs.</p>
        </div>
      )}
      <Sheet open={!!adding} onClose={() => setAdding(null)} title="Add account">
        {adding && <AccountForm key={adding} presetType={adding} onDone={() => setAdding(null)} />}
      </Sheet>
    </Page>
  )
}
