import { ChatText, Envelope } from '@phosphor-icons/react'
import { useState } from 'react'
import { TokenList } from '../../components/ApiTokens'
import { CopyButton } from '../../components/SmsPasteSheet'
import { Badge, Card, Section, Segmented, IconBadge } from '../../components/ui'
import { dateTime } from '../../format'
import { useSettings } from '../../hooks'

export function MailSection() {
  const mail = useSettings().data?.mail
  return (
    <>
      <Section title="Mailbox">
        <Card className="p-5">
          <div className="flex items-center gap-3">
            <IconBadge icon={Envelope} />
            <div className="flex-1">
              <p className="font-semibold">Alerts mailbox</p>
              <p className="ink-2 text-caption">{mail?.lastSync ? `Last checked ${dateTime(mail.lastSync)}` : 'Never checked'}</p>
            </div>
            {mail?.configured ? <Badge tone="positive">connected</Badge> : <Badge tone="attention">not set up</Badge>}
          </div>
          {mail?.lastError && <p className="mt-3 text-label text-negative">{mail.lastError}</p>}
          {!mail?.configured && (
            <p className="ink-2 mt-3 text-label leading-relaxed">
              Start the server with <code className="rounded bg-surface-2 px-1">MAIL_HOST</code>, <code className="rounded bg-surface-2 px-1">MAIL_USERNAME</code> and{' '}
              <code className="rounded bg-surface-2 px-1">MAIL_PASSWORD</code>. Gmail: imap.gmail.com · iCloud: imap.mail.me.com, with an app-specific password.
            </p>
          )}
        </Card>
      </Section>
      <SmsForwarding />
    </>
  )
}

/** Send SMS tokens + setup guide for forwarding bank SMS from the phone. */
function SmsForwarding() {
  const [guide, setGuide] = useState<'ios' | 'android' | 'curl'>('ios')
  const url = `${window.location.origin}/api/v1/sms`

  return (
    <Section title="SMS forwarding">
      <Card className="mb-3 p-4">
        <div className="flex items-center gap-3">
          <IconBadge icon={ChatText} />
          <div className="min-w-0 flex-1">
            <p className="font-semibold">Bank SMS</p>
            <p className="ink-2 text-caption">For alerts that only come by text message. Each phone can have its own token.</p>
          </div>
        </div>
        <div className="mt-3 rounded-card bg-surface-2 p-3">
          <p className="ink-2 text-caption font-medium">Endpoint</p>
          <div className="mt-1 flex items-center gap-2">
            <code className="min-w-0 flex-1 truncate font-mono text-caption">POST {url}</code>
            <CopyButton value={url} />
          </div>
        </div>
      </Card>
      <TokenList jobs={['SEND_SMS']} newJob="SEND_SMS" newLabel="New SMS token" />
      <p className="ink-3 mt-2 px-1 text-caption leading-relaxed">An SMS token can only send SMS; it can't read or change anything else. All your tokens are in Security.</p>

      <Card className="mt-3 p-5">
        <p className="mb-3 font-semibold">Set it up on your phone</p>
        <Segmented3 value={guide} onChange={setGuide} />
        <div className="mt-4 text-label leading-relaxed">
          {guide === 'ios' && (
            <ol className="ink-2 list-decimal space-y-2 pl-5 marker:font-semibold marker:text-ink">
              <li>
                Open <b className="text-ink">Shortcuts</b> → <b className="text-ink">Automation</b> → <b className="text-ink">New Automation</b> → <b className="text-ink">Message</b>.
              </li>
              <li>
                Set <b className="text-ink">Message Contains</b> to <code className="rounded bg-surface-2 px-1">debited</code> (make a second one for <code className="rounded bg-surface-2 px-1">credited</code>, or one per bank sender). Choose <b className="text-ink">Run Immediately</b>.
              </li>
              <li>
                Add <b className="text-ink">Get Contents of URL</b>: URL above, Method <b className="text-ink">POST</b>, Header <code className="rounded bg-surface-2 px-1">Authorization</code> = <code className="break-words rounded bg-surface-2 px-1">Bearer &lt;token&gt;</code>.
              </li>
              <li>
                Request Body <b className="text-ink">JSON</b>: <code className="rounded bg-surface-2 px-1">text</code> = Shortcut Input › Content, <code className="rounded bg-surface-2 px-1">sender</code> = Shortcut Input › Sender.
              </li>
              <li>
                Optional: <b className="text-ink">Get Dictionary Value</b> <code className="rounded bg-surface-2 px-1">message</code> → <b className="text-ink">Show Notification</b>, to see what was recorded.
              </li>
            </ol>
          )}
          {guide === 'android' && (
            <ol className="ink-2 list-decimal space-y-2 pl-5 marker:font-semibold marker:text-ink">
              <li>
                Install an SMS-to-webhook app (e.g. <b className="text-ink">SMS Forwarder</b>, MacroDroid or Tasker).
              </li>
              <li>Add a rule for your bank senders (contains HDFCBK, AXISBK, SBI, CANBNK…).</li>
              <li>
                Webhook: POST to the URL above with header <code className="break-all rounded bg-surface-2 px-1">Authorization: Bearer &lt;token&gt;</code> and JSON body{' '}
                <code className="rounded bg-surface-2 px-1">{'{"text": "%body%", "sender": "%from%"}'}</code>.
              </li>
            </ol>
          )}
          {guide === 'curl' && (
            <pre className="overflow-x-auto rounded-box bg-surface-2 p-3 font-mono text-micro leading-relaxed">{`curl -X POST ${url} \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"sender":"AX-HDFCBK","text":"Sent Rs.500.00 From HDFC Bank A/C *2286 To RAHUL On 05/11/26 Ref 531234567890"}'`}</pre>
          )}
        </div>
        <p className="ink-3 mt-4 text-caption leading-relaxed">
          Payments that also arrive by email are recorded once. You can also paste an SMS by hand from <b>Settings → Messages → Paste SMS</b>. Your phone needs to reach this server (e.g. over Tailscale or your public URL).
        </p>
      </Card>
    </Section>
  )
}

function Segmented3({ value, onChange }: { value: 'ios' | 'android' | 'curl'; onChange: (v: 'ios' | 'android' | 'curl') => void }) {
  return <Segmented value={value} onChange={onChange} options={[{ value: 'ios', label: 'iPhone' }, { value: 'android', label: 'Android' }, { value: 'curl', label: 'Other' }]} />
}
