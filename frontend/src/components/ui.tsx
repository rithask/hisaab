import { CaretDown, CaretLeft, X, type Icon as PhIcon } from '@phosphor-icons/react'
import { AnimatePresence, motion, useDragControls } from 'motion/react'
import {
  useEffect,
  useId,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router-dom'
import { money, money0 } from '../format'

export const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

// ---- Motion ---------------------------------------------------------------------------------------
// Short and quiet: things fade or slide from where they come from, and everything settles within 250ms.

export const SPRING = { type: 'spring', duration: 0.25, bounce: 0 } as const
export const SNAP = { type: 'spring', duration: 0.22, bounce: 0 } as const

/** Page root: a single short fade-in when the page opens. */
export function Page({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }} className={className}>
      {children}
    </motion.div>
  )
}

/** Plain block (kept so pages can group content; no animation of its own). */
export function Rise({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={className}>{children}</div>
}

// ---- Surfaces -----------------------------------------------------------------------------------

export function Card({ children, className, onClick }: { children: ReactNode; className?: string; onClick?: () => void }) {
  const Tag = onClick ? 'button' : 'div'
  return (
    <Tag onClick={onClick} className={cx('surface block w-full rounded-[20px] p-4 text-left', onClick && 'pressable', className)}>
      {children}
    </Tag>
  )
}

export function Section({ title, action, children, className }: { title: string; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cx('mt-7', className)}>
      <div className="mb-2 flex min-h-6 items-center justify-between px-1">
        <h2 className="text-[15px] font-semibold">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  )
}

/** Grouped list inside a card: rows separated by hairlines. */
export function List({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx('surface overflow-hidden rounded-[20px] [&>*+*]:border-t [&>*+*]:border-[var(--line)]', className)}>{children}</div>
}

export function PageHeader({ title, subtitle, action, back }: { title: string; subtitle?: ReactNode; action?: ReactNode; back?: boolean }) {
  const nav = useNavigate()
  return (
    <header className="mb-5 pt-1">
      {back && (
        <button onClick={() => nav(-1)} className="link -ml-2 mb-1 flex items-center gap-1 rounded-full px-2 py-1 text-[15px] text-[var(--accent)] lg:hidden">
          <CaretLeft size={16} weight="bold" /> Back
        </button>
      )}
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-[28px] font-bold leading-tight tracking-[-0.02em]">{title}</h1>
          {subtitle && <p className="ink-2 mt-0.5 text-[15px]">{subtitle}</p>}
        </div>
        {action}
      </div>
    </header>
  )
}

// ---- Controls -----------------------------------------------------------------------------------

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'glass'
export function Button({
  variant = 'primary',
  size = 'md',
  className,
  ...p
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: 'sm' | 'md' }) {
  const v: Record<Variant, string> = {
    primary: 'accent-fill',
    secondary: 'bg-[var(--surface-2)] text-[var(--ink)]',
    ghost: 'text-[var(--ink-2)] hover:bg-[var(--hover)] hover:text-[var(--ink)]',
    danger: 'bg-[color-mix(in_oklab,var(--negative)_12%,transparent)] text-[var(--negative)]',
    glass: 'glass text-[var(--ink)]',
  }
  return (
    <button
      {...p}
      className={cx(
        'pressable inline-flex items-center justify-center gap-1.5 rounded-full font-semibold disabled:pointer-events-none disabled:opacity-40',
        size === 'sm' ? 'h-9 px-4 text-[14px]' : 'h-12 px-5 text-[16px]',
        v[variant],
        className,
      )}
    />
  )
}

export function IconButton({ icon: Icon, label, className, ...p }: ButtonHTMLAttributes<HTMLButtonElement> & { icon: PhIcon; label: string }) {
  return (
    <button {...p} aria-label={label} title={label} className={cx('pressable flex h-10 w-10 items-center justify-center rounded-full bg-[var(--surface-2)] text-[var(--ink)]', className)}>
      <Icon size={19} />
    </button>
  )
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block min-w-0 flex-1">
      <span className="ink-2 mb-1.5 block px-1 text-[13px] font-medium">{label}</span>
      {children}
      {hint && <span className="ink-3 mt-1.5 block px-1 text-xs">{hint}</span>}
    </label>
  )
}

const inputCls =
  'w-full rounded-xl border border-transparent bg-[var(--surface-2)] px-4 py-3 text-base text-[var(--ink)] outline-none transition-[border-color,box-shadow] duration-150 hover:border-[var(--line-strong)] placeholder:text-[var(--ink-3)] focus:border-[var(--accent)] focus:shadow-[0_0_0_3px_var(--accent-soft)] disabled:opacity-60'

export function Input(p: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...p} className={cx(inputCls, p.className)} />
}

export function Select(p: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...p} className={cx(inputCls, 'appearance-auto pr-3', p.className)} />
}

export function Textarea(p: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...p} className={cx(inputCls, 'resize-none', p.className)} />
}

/** Segmented control. The selection pill is a shared-layout element, so it glides between options. */
export function Segmented<T extends string>({ value, options, onChange, className }: { value: T; options: { value: T; label: ReactNode }[]; onChange: (v: T) => void; className?: string }) {
  const id = useId()
  return (
    <div className={cx('relative grid rounded-full bg-[var(--surface-2)] p-1', className)} style={{ gridTemplateColumns: `repeat(${options.length}, minmax(0,1fr))` }}>
      {options.map((o) => (
        <button
          type="button"
          key={o.value}
          onClick={() => onChange(o.value)}
          className={cx('relative h-9 rounded-full text-[13px] font-semibold transition-[color,opacity] duration-150 active:opacity-60', o.value === value ? 'text-[var(--ink)]' : 'text-[var(--ink-2)] hover:text-[var(--ink)]')}
        >
          {o.value === value && <motion.span layoutId={`seg-${id}`} transition={SPRING} className="absolute inset-0 rounded-full bg-[var(--surface)] shadow-[0_1px_3px_rgba(0,0,0,0.1)] dark:bg-white/12" />}
          <span className="relative">{o.label}</span>
        </button>
      ))}
    </div>
  )
}

/** Compact pill-shaped dropdown for filter rows. */
export function PillSelect({ active, className, ...p }: SelectHTMLAttributes<HTMLSelectElement> & { active?: boolean }) {
  return (
    <span className="relative inline-flex shrink-0">
      <select
        {...p}
        className={cx(
          'pressable h-9 appearance-none rounded-full pl-3.5 pr-8 text-[13px] font-semibold outline-none',
          active ? 'bg-[var(--ink)] text-[var(--surface)]' : 'bg-[var(--surface)] text-[var(--ink)] ring-1 ring-[var(--line-strong)]',
          className,
        )}
      />
      <CaretDown size={14} className={cx('pointer-events-none absolute right-3 top-1/2 -translate-y-1/2', active ? 'text-[var(--bg)]' : 'text-[var(--ink-3)]')} />
    </span>
  )
}

export function Chip({ active, children, onClick }: { active?: boolean; children: ReactNode; onClick?: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cx(
        'pressable inline-flex h-9 shrink-0 items-center gap-1.5 rounded-full px-3.5 text-[13px] font-semibold transition-colors duration-150',
        active ? 'bg-[var(--ink)] text-[var(--surface)]' : 'bg-[var(--surface)] text-[var(--ink)] ring-1 ring-[var(--line-strong)]',
      )}
    >
      {children}
    </button>
  )
}

export function Toggle({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode }) {
  return (
    <button type="button" role="switch" aria-checked={checked} onClick={() => onChange(!checked)} className="group flex w-full items-center justify-between gap-3 py-1 text-left text-[15px]">
      <span>{label}</span>
      <span className={cx('flex h-7 w-12 shrink-0 rounded-full p-0.5 transition-[background-color,filter] duration-200 group-hover:[filter:var(--hover-filter)] group-active:[filter:var(--press-filter)]', checked ? 'justify-end bg-[var(--positive)]' : 'justify-start bg-[var(--line-strong)]')}>
        <motion.span layout transition={SPRING} className="h-6 w-6 rounded-full bg-white shadow" />
      </span>
    </button>
  )
}

// ---- Sheet ----------------------------------------------------------------------------------------

/**
 * Bottom sheet on phones, centred dialog on wider screens. Springs up in 250ms; on a phone it can be
 * dragged down by its header to dismiss.
 */
export function Sheet({ open, onClose, title, children }: { open: boolean; onClose: () => void; title: string; children: ReactNode }) {
  const drag = useDragControls()
  const wide = typeof window !== 'undefined' && window.matchMedia('(min-width: 640px)').matches

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = prev
    }
  }, [open, onClose])

  return createPortal(
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-[60] flex items-end justify-center sm:items-center sm:p-6" role="dialog" aria-modal="true" aria-label={title}>
          <motion.div
            className="absolute inset-0 bg-black/40"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.18 }}
            onClick={onClose}
          />
          <motion.div
            className="relative max-h-[92dvh] w-full overflow-y-auto overscroll-contain rounded-t-[24px] bg-[var(--surface)] px-5 pb-safe shadow-[var(--shadow-float)] sm:max-w-lg sm:rounded-[24px] sm:pb-6"
            initial={wide ? { opacity: 0, y: 16, scale: 0.98 } : { y: '100%' }}
            animate={wide ? { opacity: 1, y: 0, scale: 1 } : { y: 0 }}
            exit={wide ? { opacity: 0, y: 8, scale: 0.98, transition: { duration: 0.15 } } : { y: '100%', transition: { duration: 0.2, ease: [0.4, 0, 1, 1] } }}
            transition={SPRING}
            drag={wide ? false : 'y'}
            dragControls={drag}
            dragListener={false}
            dragConstraints={{ top: 0, bottom: 0 }}
            dragElastic={{ top: 0, bottom: 0.7 }}
            onDragEnd={(_, info) => (info.offset.y > 110 || info.velocity.y > 600) && onClose()}
          >
            <div onPointerDown={(e) => !wide && drag.start(e)} className="sticky top-0 z-20 -mx-5 mb-3 touch-none rounded-t-[24px] bg-[var(--surface)] px-5 pb-2 pt-3 sm:touch-auto">
              <div className="mx-auto mb-3 h-1 w-9 rounded-full bg-[var(--line-strong)] sm:hidden" />
              <div className="flex items-center justify-between">
                <h3 className="text-[19px] font-semibold">{title}</h3>
                <button onClick={onClose} onPointerDown={(e) => e.stopPropagation()} className="pressable flex h-8 w-8 items-center justify-center rounded-full bg-[var(--surface-2)] text-[var(--ink-2)] hover:text-[var(--ink)]" aria-label="Close">
                  <X size={15} weight="bold" />
                </button>
              </div>
            </div>
            {children}
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  )
}

// ---- Data display ---------------------------------------------------------------------------------

type Tone = 'neutral' | 'green' | 'red' | 'amber' | 'blue' | 'violet'
const toneCls: Record<Tone, string> = {
  neutral: 'bg-[var(--surface-2)] text-[var(--ink-2)]',
  green: 'bg-[color-mix(in_oklab,var(--positive)_14%,transparent)] text-[var(--positive)]',
  red: 'bg-[color-mix(in_oklab,var(--negative)_13%,transparent)] text-[var(--negative)]',
  amber: 'bg-amber-500/14 text-amber-700 dark:text-amber-300',
  blue: 'bg-sky-500/12 text-sky-700 dark:text-sky-300',
  violet: 'bg-violet-500/12 text-violet-700 dark:text-violet-300',
}

export function Badge({ children, tone = 'neutral', className }: { children: ReactNode; tone?: Tone; className?: string }) {
  return <span className={cx('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold', toneCls[tone], className)}>{children}</span>
}

/** Change vs a previous value. `goodWhenUp` flips the colour meaning (spending up = bad). */
export function Delta({ now, before, goodWhenUp = true }: { now: number; before: number; goodWhenUp?: boolean }) {
  if (!before) return null
  const pct = Math.round(((now - before) / Math.abs(before)) * 100)
  if (!isFinite(pct)) return null
  const up = pct > 0
  const good = pct === 0 ? true : up === goodWhenUp
  return (
    <Badge tone={pct === 0 ? 'neutral' : good ? 'green' : 'red'}>
      {up ? '↑' : pct < 0 ? '↓' : '→'} {Math.abs(pct)}%
    </Badge>
  )
}

export function Empty({ icon: Icon, title, children, action }: { icon?: PhIcon; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="surface flex flex-col items-center rounded-[20px] px-6 py-10 text-center">
      {Icon && (
        <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-[var(--accent-soft)] text-[var(--accent)]">
          <Icon size={26} />
        </div>
      )}
      <p className="text-[17px] font-semibold">{title}</p>
      {children && <div className="ink-2 mt-1 max-w-xs text-sm">{children}</div>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

/** Static placeholder blocks while loading (no looping animation). */
export function Skeleton({ className }: { className?: string }) {
  return <div className={cx('rounded-2xl bg-[var(--surface-2)]', className)} />
}

export function PageSkeleton() {
  return (
    <div className="space-y-4 pt-4">
      <Skeleton className="h-8 w-40" />
      <Skeleton className="h-40 w-full rounded-[20px]" />
      <Skeleton className="h-24 w-full rounded-[20px]" />
      <Skeleton className="h-64 w-full rounded-[20px]" />
    </div>
  )
}

/** Thin horizontal meter that fills from zero. Pass `status` to colour by utilisation thresholds instead of the accent. */
export function Meter({ pct, color, status, className }: { pct: number; color?: string; status?: boolean; className?: string }) {
  const v = Math.max(0, Math.min(100, pct))
  const c = status ? statusColor(v) : (color ?? 'var(--ink)')
  return (
    <div className={cx('h-1.5 w-full overflow-hidden rounded-full bg-[var(--surface-2)]', className)}>
      <motion.div className="h-full rounded-full" initial={{ width: 0 }} animate={{ width: `${v}%` }} transition={SNAP} style={{ background: c }} />
    </div>
  )
}

export const statusColor = (pct: number) => (pct >= 75 ? '#ef4444' : pct >= 40 ? '#f59e0b' : '#22c55e')

/** Circular progress ring with a centred label. */
export function Ring({ pct, size = 56, stroke = 6, color, children, track = 'var(--line-strong)' }: { pct: number; size?: number; stroke?: number; color?: string; children?: ReactNode; track?: string }) {
  const r = (size - stroke) / 2
  const c = 2 * Math.PI * r
  const v = Math.max(0, Math.min(100, pct))
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" style={{ stroke: track }} strokeWidth={stroke} />
        <motion.circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={c}
          initial={{ strokeDashoffset: c }}
          animate={{ strokeDashoffset: c * (1 - v / 100) }}
          transition={SNAP}
          style={{ stroke: color ?? statusColor(v) }}
        />
      </svg>
      <div className="absolute inset-0 flex items-center justify-center text-center">{children}</div>
    </div>
  )
}

export function Amount({ value, direction, className, muted }: { value: number; direction?: 'DEBIT' | 'CREDIT'; className?: string; muted?: boolean }) {
  const color = muted ? 'text-[var(--ink-3)]' : direction === 'CREDIT' ? 'text-[var(--positive)]' : ''
  return (
    <span className={cx('tabular font-semibold tracking-tight', color, className)}>
      {direction === 'CREDIT' ? '+' : direction === 'DEBIT' ? '−' : ''}
      {money(value).replace('-', '')}
    </span>
  )
}

/** Headline currency figure: big, tight, with quiet paise. Pass `paise={false}` for whole rupees. */
export function BigMoney({ value, className, paise = true }: { value: number; className?: string; paise?: boolean }) {
  const abs = Math.abs(value)
  const s = paise ? money(abs) : money0(abs)
  const [whole, frac] = s.split('.')
  return (
    <span className={cx('display', className)}>
      {value < 0 && '−'}
      {whole}
      {paise && frac && <span className="ink-3">.{frac}</span>}
    </span>
  )
}

/** Initials on a neutral circle. */
export function Avatar({ name, size = 40 }: { name: string; size?: number }) {
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join('')
  return (
    <div className="flex shrink-0 items-center justify-center rounded-full bg-[var(--surface-2)] font-semibold text-[var(--ink-2)]" style={{ width: size, height: size, fontSize: size * 0.38 }}>
      {initials}
    </div>
  )
}

export function ErrorText({ error }: { error: unknown }) {
  if (!error) return null
  return <p className="anim-pop rounded-2xl bg-[color-mix(in_oklab,var(--negative)_12%,transparent)] px-4 py-3 text-sm text-[var(--negative)]">{(error as Error).message}</p>
}

export function Notice({ children, tone = 'green' }: { children: ReactNode; tone?: Tone }) {
  return <p className={cx('anim-pop rounded-2xl px-4 py-3 text-sm', toneCls[tone])}>{children}</p>
}

/**
 * Wraps a page that belongs to an optional feature: a placeholder while settings load, a short "turned off"
 * note (with a way to switch it back on) when it's off, otherwise the page.
 */
export function FeatureGate({ on, name, children }: { on: boolean | undefined; name: string; children: ReactNode }) {
  const nav = useNavigate()
  if (on === undefined) return <PageSkeleton />
  if (!on)
    return (
      <Page>
        <PageHeader back title={name} />
        <Empty title={`${name} is turned off`} action={<Button variant="secondary" onClick={() => nav('/settings')}>Open Settings</Button>}>
          Switch it on under Settings → Features.
        </Empty>
      </Page>
    )
  return <>{children}</>
}
