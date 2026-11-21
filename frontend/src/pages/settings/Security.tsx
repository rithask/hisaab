import { useQuery } from '@tanstack/react-query'
import { IdentificationCard, SignOut } from '@phosphor-icons/react'
import { api } from '../../api'
import { TokenList } from '../../components/ApiTokens'
import { Button, Card, Confirm, Section, IconBadge } from '../../components/ui'
import { dateTime } from '../../format'
import { useMoneyMutation, useSignOut } from '../../hooks'

export function SecuritySection() {
  const signOut = useSignOut()
  return (
    <>
      <PocketIdCard />
      <Section title="API tokens">
        <p className="ink-2 mb-2 px-1 text-small">Apps and shortcuts that can use Hisaab. Each token does one job, within the limits you set.</p>
        <TokenList />
        <p className="ink-3 mt-2 px-1 text-caption leading-relaxed">Tokens are stored hashed and shown once. Repeated wrong tokens are locked out for 15 minutes, and every use is logged.</p>
      </Section>
      <Section title="This device">
        <button className="pressable surface flex h-12 w-full items-center justify-center gap-2 rounded-card text-body font-semibold text-negative" onClick={signOut}>
          <SignOut size={17} /> Sign out
        </button>
      </Section>
    </>
  )
}

/** Which Pocket ID (OIDC) account is linked, with a way to unlink it. Hidden when OIDC isn't set up. */
function PocketIdCard() {
  const opts = useQuery({ queryKey: ['auth-options'], queryFn: () => api<{ oidc?: { name: string } }>('/auth/options') })
  const link = useQuery({ queryKey: ['oidc-link'], queryFn: () => api<{ linked: boolean; email?: string; linkedAt?: string }>('/oidc-link'), enabled: !!opts.data?.oidc })
  const unlink = useMoneyMutation(() => api('/oidc-link', { method: 'DELETE' }))
  const name = opts.data?.oidc?.name
  if (!name) return null
  return (
    <Section title={name}>
      <Card className="flex items-center gap-3 p-4">
        <IconBadge icon={IdentificationCard} />
        <div className="min-w-0 flex-1">
          <p className="font-medium">{link.data?.linked ? 'Linked' : 'Not linked yet'}</p>
          <p className="ink-2 truncate text-small">
            {link.data?.linked ? `${link.data.email ?? 'account'}${link.data.linkedAt ? ` · since ${dateTime(link.data.linkedAt)}` : ''}` : `You'll be asked to link it the first time you sign in with ${name}.`}
          </p>
        </div>
        {link.data?.linked && (
          <Confirm className="mt-3" ask={`Unlink this ${name} account? Signing in with ${name} will ask to link again.`} yes="Unlink" busy={unlink.isPending} onYes={() => unlink.mutate(undefined)}>
            {(ask) => (
              <Button variant="secondary" size="sm" onClick={ask}>
                Unlink
              </Button>
            )}
          </Confirm>
        )}
      </Card>
    </Section>
  )
}
