import { api, type Feature } from '../../api'
import { Card, Toggle } from '../../components/ui'
import { useMoneyMutation, useSettings } from '../../hooks'

const FEATURES: { key: Feature; name: string; hint: string; off?: boolean }[] = [
  { key: 'backups', name: 'Automatic backups', hint: 'Nightly copy of everything, kept on the server' },
  { key: 'budgets', name: 'Budgets', hint: 'Monthly limits per category' },
  { key: 'rewards', name: 'Rewards', hint: 'Cashback and points on your credit cards' },
  { key: 'data_api', name: 'Data API', hint: 'Let other apps read or add transactions with a token (Security → API tokens)', off: true },
]

/** Switch optional features off: they disappear from the app and their background jobs stop. */
export function FeaturesCard() {
  const features = useSettings().data?.features
  const set = useMoneyMutation((change: Partial<Record<Feature, boolean>>) => api('/settings', { method: 'PUT', body: { features: change } }))
  return (
    <>
      <Card className="divide-y divide-line p-0">
        {FEATURES.map((f) => (
          <div key={f.key} className="px-4 py-2.5">
            <Toggle
              checked={features?.[f.key] ?? !f.off}
              onChange={(v) => set.mutate({ [f.key]: v })}
              label={
                <span>
                  <span className="block text-body">{f.name}</span>
                  <span className="ink-2 block text-caption">{f.hint}</span>
                </span>
              }
            />
          </div>
        ))}
      </Card>
      <p className="ink-3 mt-2 px-1 text-caption">Turning a feature off hides it everywhere and stops any background work for it. Nothing is deleted.</p>
    </>
  )
}
