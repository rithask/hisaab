import { useState } from 'react'
import { api, type Account, type AccountType, type Institution } from '../api'
import { fromLocalInput, toLocalInput } from '../format'
import { useMoneyMutation } from '../hooks'
import { DateTimePicker } from './DatePicker'
import { CaretDown } from '@phosphor-icons/react'
import { Button, Chip, cx, ErrorText, Field, Input, Segmented } from './ui'

/**
 * Create or edit a bank account, credit card or cash wallet: which bank and today's balance are all it needs. The
 * last 4 digits are learned from the first alert; the name, card cycle and limit are optional, under More details.
 */
export default function AccountForm({ account, onDone, presetType }: { account?: Account; onDone: () => void; presetType?: AccountType }) {
  const [type, setType] = useState<AccountType>(account?.type ?? presetType ?? 'BANK')
  const [name, setName] = useState(account?.name ?? '')
  const [institution, setInstitution] = useState<Institution>(account?.institution ?? 'OTHER')
  const [last4, setLast4] = useState(account?.last4 ?? '')
  const [opening, setOpening] = useState(account ? String(account.openingBalance) : '')
  const [openingDate, setOpeningDate] = useState(toLocalInput(account?.openingDate))
  const [limit, setLimit] = useState(account?.card?.creditLimit != null ? String(account.card.creditLimit) : '')
  const [statementDay, setStatementDay] = useState(account?.statementDay ? String(account.statementDay) : '')
  const [dueDay, setDueDay] = useState(account?.dueDay ? String(account.dueDay) : '')

  const isCard = type === 'CREDIT_CARD'
  const [more, setMore] = useState(false)
  // A name isn't required: "HDFC card", "Axis savings", "Cash" are used when it's left empty.
  const bankLabel = institution === 'OTHER' ? (isCard ? 'Credit' : 'Bank') : institution === 'AXIS' ? 'Axis' : institution === 'CANARA' ? 'Canara' : institution
  const fallbackName = type === 'CASH' ? 'Cash' : isCard ? `${bankLabel} card` : `${bankLabel} savings`
  const save = useMoneyMutation(
    () =>
      api(account ? `/accounts/${account.id}` : '/accounts', {
        method: account ? 'PUT' : 'POST',
        body: {
          name: name.trim() || fallbackName,
          type,
          institution,
          last4: last4 || null,
          openingBalance: Number(opening || 0),
          openingDate: fromLocalInput(openingDate),
          creditLimit: isCard && limit ? Number(limit) : null,
          statementDay: isCard && statementDay ? Number(statementDay) : null,
          dueDay: isCard && dueDay ? Number(dueDay) : null,
        },
      }),
    onDone,
  )
  const archive = useMoneyMutation(() => api(`/accounts/${account!.id}`, { method: 'PUT', body: { ...account, creditLimit: account!.card?.creditLimit, archived: !account!.archived } }), onDone)

  const BANKS: { value: Institution; label: string }[] = [
    { value: 'HDFC', label: 'HDFC' },
    { value: 'AXIS', label: 'Axis' },
    { value: 'SBI', label: 'SBI' },
    { value: 'CANARA', label: 'Canara' },
    { value: 'OTHER', label: 'Other' },
  ]
  // Filled in, under More details, so the summary on the fold says what's set.
  const extras = [last4 && `•••• ${last4}`, isCard && limit && 'limit', isCard && (statementDay || dueDay) && 'cycle'].filter(Boolean).join(' · ')

  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate(undefined)
      }}
    >
      {!account && (
        <Segmented<AccountType>
          value={type}
          onChange={setType}
          options={[
            { value: 'BANK', label: 'Bank' },
            { value: 'CREDIT_CARD', label: 'Credit card' },
            { value: 'CASH', label: 'Cash' },
          ]}
        />
      )}
      {type !== 'CASH' && (
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Bank">
          {BANKS.map((b) => (
            <Chip key={b.value} active={institution === b.value} onClick={() => setInstitution(b.value)}>
              {b.label}
            </Chip>
          ))}
        </div>
      )}
      <Field label={isCard ? 'What you owe on it today' : type === 'CASH' ? 'Cash in hand today' : 'Balance today'}>
        <Input required inputMode="decimal" value={opening} onChange={(e) => setOpening(e.target.value)} placeholder="0.00" autoFocus={!account} />
      </Field>
      <Field label="Name (optional)">
        <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={fallbackName} />
      </Field>

      {type !== 'CASH' && (
        <div>
          <button type="button" onClick={() => setMore(!more)} aria-expanded={more} className="ink-2 flex w-full items-center justify-between rounded-2xl px-1 py-2 text-[14px] font-medium">
            <span>
              More details
              {extras && !more ? <span className="ml-2 text-[var(--accent)]">{extras}</span> : null}
            </span>
            <CaretDown size={14} weight="bold" className={cx('transition-transform duration-150', more && 'rotate-180')} />
          </button>
          {more && (
            <div className="anim-pop space-y-4 pt-2">
              <Field label="Last 4 digits" hint="Leave empty: the first bank alert fills it in.">
                <Input inputMode="numeric" maxLength={4} pattern="\d{4}" value={last4} onChange={(e) => setLast4(e.target.value.replace(/\D/g, ''))} />
              </Field>
              {isCard && (
                <>
                  <Field label="Credit limit">
                    <Input inputMode="decimal" value={limit} onChange={(e) => setLimit(e.target.value)} />
                  </Field>
                  <div className="flex gap-3">
                    <Field label="Statement day" hint="Day of month">
                      <Input inputMode="numeric" value={statementDay} onChange={(e) => setStatementDay(e.target.value.replace(/\D/g, ''))} placeholder="15" />
                    </Field>
                    <Field label="Payment due day" hint="Day of month">
                      <Input inputMode="numeric" value={dueDay} onChange={(e) => setDueDay(e.target.value.replace(/\D/g, ''))} placeholder="5" />
                    </Field>
                  </div>
                </>
              )}
              <Field label="Balance as of" hint="Payments before this moment don't change the balance.">
                <DateTimePicker value={openingDate} onChange={setOpeningDate} label="Balance as of" />
              </Field>
            </div>
          )}
        </div>
      )}
      <ErrorText error={save.error ?? archive.error} />
      <Button type="submit" className="w-full" disabled={save.isPending}>
        {account ? 'Save changes' : 'Add account'}
      </Button>
      {account && (
        <Button type="button" variant="secondary" className="w-full" onClick={() => archive.mutate(undefined)}>
          {account.archived ? 'Unarchive' : 'Archive (hide, keep history)'}
        </Button>
      )}
    </form>
  )
}
