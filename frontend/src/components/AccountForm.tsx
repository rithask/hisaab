import { useState } from 'react'
import { api, type Account, type AccountType, type Institution } from '../api'
import { fromLocalInput, toLocalInput } from '../format'
import { useMoneyMutation } from '../hooks'
import { DateTimePicker } from './DatePicker'
import { Button, ErrorText, Field, Input, Segmented, Select } from './ui'

/** Create or edit a bank account / credit card / cash wallet, starting from today's figures. */
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
  const save = useMoneyMutation(
    () =>
      api(account ? `/accounts/${account.id}` : '/accounts', {
        method: account ? 'PUT' : 'POST',
        body: {
          name,
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
      <Field label="Name">
        <Input required value={name} onChange={(e) => setName(e.target.value)} placeholder={isCard ? 'HDFC Swiggy' : type === 'CASH' ? 'Wallet' : 'Canara Savings'} />
      </Field>
      {type !== 'CASH' && (
        <div className="flex gap-3">
          <div className="flex-1">
            <Field label="Bank">
              <Select value={institution} onChange={(e) => setInstitution(e.target.value as Institution)}>
                <option value="HDFC">HDFC</option>
                <option value="AXIS">Axis</option>
                <option value="SBI">SBI</option>
                <option value="CANARA">Canara</option>
                <option value="OTHER">Other</option>
              </Select>
            </Field>
          </div>
          <div className="w-28">
            <Field label="Last 4 digits">
              <Input inputMode="numeric" maxLength={4} pattern="\d{4}" value={last4} onChange={(e) => setLast4(e.target.value.replace(/\D/g, ''))} />
            </Field>
          </div>
        </div>
      )}
      {type !== 'CASH' && <p className="ink-3 -mt-2 px-1 text-xs">Alert emails are matched to this account by the last 4 digits.</p>}
      <Field label={isCard ? 'Current outstanding (amount you owe)' : 'Current balance'}>
        <Input required inputMode="decimal" value={opening} onChange={(e) => setOpening(e.target.value)} placeholder="0.00" />
      </Field>
      <Field label="As of" hint="Transactions before this moment are ignored for the balance.">
        <DateTimePicker value={openingDate} onChange={setOpeningDate} label="As of" />
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
