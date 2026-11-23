import { CaretDown, CaretLeft, CaretRight, Check, MagnifyingGlass, X, type Icon as PhIcon } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router-dom'
import { money, money0 } from '../format'
import { QUICK, BASE, MOVE } from '../motion'

export const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

// ---- Motion ---------------------------------------------------------------------------------------
// Short and quiet: things fade or slide from where they come from, and everything settles within 250ms.


/** Page root: a single short fade-in when the page opens. */
export function Page({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={BASE} className={className}>
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
    <Tag onClick={onClick} className={cx('surface block w-full rounded-card p-4 text-left', onClick && 'pressable', className)}>
      {children}
    </Tag>
  )
}

export function Section({ title, action, children, className }: { title: string; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cx('mt-7', className)}>
      <div className="mb-2 flex min-h-6 items-center justify-between px-1">
        <h2 className="text-body font-semibold">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  )
}

/** Grouped list inside a card: rows separated by hairlines. */
export function List({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx('list-enter surface overflow-hidden rounded-card [&>*+*]:border-t [&>*+*]:border-line', className)}>{children}</div>
}

/**
 * @param back "Back" on phones (wide screens have the sidebar instead)
 * @param up   for a page opened from inside another one (Settings → Messages): a link back to it, on every screen
 */
export function PageHeader({ title, subtitle, action, back, up }: { title: string; subtitle?: ReactNode; action?: ReactNode; back?: boolean; up?: { to: string; label: string } }) {
  const nav = useNavigate()
  return (
    <header className="mb-5 pt-1">
      {up ? <BackButton label={up.label} onClick={() => nav(up.to)} /> : back && <BackButton onClick={() => nav(-1)} className="lg:hidden" />}
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-heading font-bold leading-tight tracking-[-0.02em]">{title}</h1>
          {subtitle && <p className="ink-2 mt-0.5 text-body">{subtitle}</p>}
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
    secondary: 'bg-surface-2 text-ink',
    ghost: 'text-ink-2 hover:bg-hover hover:text-ink',
    danger: 'bg-[color-mix(in_oklab,var(--negative)_12%,transparent)] text-negative',
    glass: 'glass text-ink',
  }
  return (
    <button
      {...p}
      className={cx(
        'pressable inline-flex items-center justify-center gap-1.5 rounded-full font-semibold disabled:pointer-events-none disabled:opacity-40',
        size === 'sm' ? 'h-9 px-4 text-label' : 'h-12 px-5 text-body',
        v[variant],
        className,
      )}
    />
  )
}

const iconSizes = { xs: ['h-7 w-7', 14], sm: ['h-9 w-9', 17], md: ['h-10 w-10', 19] } as const
const iconButtonStyles = { neutral: 'bg-surface-2 text-ink', raised: 'bg-surface text-ink', primary: 'accent-fill', plain: 'text-ink-2' } as const

/**
 * A round button with only an icon: plain (just the icon, for "remove" crosses), neutral (grey circle) or primary
 * (the confirm in a Yes / No pair) or raised (white, on a tinted box). The label is read out and shown on hover.
 */
export function IconButton({
  icon: Icon,
  label,
  className,
  spinning,
  size = 'md',
  variant = 'neutral',
  ...p
}: ButtonHTMLAttributes<HTMLButtonElement> & { icon: PhIcon; label: string; spinning?: boolean; size?: keyof typeof iconSizes; variant?: keyof typeof iconButtonStyles }) {
  const [box, icon] = iconSizes[size]
  return (
    <button type="button" {...p} aria-label={label} title={label} className={cx('pressable flex shrink-0 items-center justify-center rounded-full disabled:opacity-30', box, iconButtonStyles[variant], className)}>
      <Icon size={icon} weight={size === 'xs' ? 'bold' : 'regular'} className={cx(spinning && 'motion-safe:animate-spin')} />
    </button>
  )
}

/**
 * The round icon at the start of a row or card. Neutral by default; a `tone` when the icon means something
 * (attention, positive …); a `color` for a category, shown as a soft wash of it. Pass children instead of an icon
 * for a short label (a ticker).
 */
export function IconBadge({ icon: Icon, size = 'md', tone = 'neutral', color, children, className }: { icon?: PhIcon; size?: 'sm' | 'md' | 'lg'; tone?: Tone; color?: string; children?: ReactNode; className?: string }) {
  const box = size === 'sm' ? 'h-9 w-9' : size === 'lg' ? 'h-11 w-11' : 'h-10 w-10'
  const look = color ? undefined : tone === 'neutral' ? 'bg-surface-2 text-ink' : toneCls[tone]
  return (
    <span className={cx('flex shrink-0 items-center justify-center rounded-full', box, look, className)} style={color ? { background: `color-mix(in oklab, ${color} 14%, transparent)`, color } : undefined}>
      {Icon ? <Icon size={size === 'sm' ? 18 : size === 'lg' ? 22 : 20} /> : children}
    </span>
  )
}

/** A soft box that explains what applies to something ("Money you lent", "Moved between my accounts"), with an optional action. */
export function InfoBox({ icon: Icon, tone = 'neutral', title, children, action, className }: { icon?: PhIcon; tone?: Tone; title?: ReactNode; children?: ReactNode; action?: ReactNode; className?: string }) {
  return (
    <div className={cx('flex items-start gap-3 rounded-box p-4 text-label', tone === 'neutral' ? 'bg-surface-2' : toneCls[tone], className)}>
      {Icon && <Icon size={18} className="mt-0.5 shrink-0" />}
      <div className="min-w-0 flex-1">
        {title && <p className="font-medium">{title}</p>}
        {children && <div className={cx(tone === 'neutral' && 'text-ink-2', !!title && 'mt-0.5')}>{children}</div>}
        {action && <div className="mt-2">{action}</div>}
      </div>
    </div>
  )
}

/** A blue text action: "Change amounts", "See all", "It wasn't". */
export function TextButton({ className, ...p }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button type="button" {...p} className={cx('link font-semibold text-accent', className)} />
}

/** "‹ Back" at the top of a page or a full-screen window. */
export function BackButton({ label = 'Back', onClick, className }: { label?: string; onClick: () => void; className?: string }) {
  return (
    <button type="button" onClick={onClick} className={cx('link -ml-2 mb-1 flex items-center gap-1 rounded-full px-2 py-1 text-body text-accent', className)}>
      <CaretLeft size={16} weight="bold" /> {label}
    </button>
  )
}

/** The small grey label over a group of fields or chips ("Paid", "Also", "Your share"). */
export function FieldLabel({ children, className }: { children: ReactNode; className?: string }) {
  return <p className={cx('mb-2 px-1 text-small font-medium text-ink-2', className)}>{children}</p>
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block min-w-0 flex-1">
      <span className="ink-2 mb-1.5 block px-1 text-small font-medium">{label}</span>
      {children}
      {hint && <span className="ink-3 mt-1.5 block px-1 text-caption">{hint}</span>}
    </label>
  )
}

const inputCls =
  'w-full rounded-field border border-transparent bg-surface-2 px-4 py-3 text-input text-ink outline-none transition-[border-color,box-shadow] duration-quick hover:border-line-strong placeholder:text-ink-3 focus:border-accent focus:shadow-[0_0_0_3px_var(--accent-soft)] disabled:opacity-60'

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
    <div className={cx('relative grid rounded-full bg-surface-2 p-1', className)} style={{ gridTemplateColumns: `repeat(${options.length}, minmax(0,1fr))` }}>
      {options.map((o) => (
        <button
          type="button"
          key={o.value}
          onClick={() => onChange(o.value)}
          className={cx('relative h-9 rounded-full text-small font-semibold transition-[color,opacity] duration-quick active:opacity-60', o.value === value ? 'text-ink' : 'text-ink-2 hover:text-ink')}
        >
          {o.value === value && <motion.span layoutId={`seg-${id}`} transition={MOVE} className="absolute inset-0 rounded-full bg-surface shadow-[0_1px_3px_rgba(0,0,0,0.1)] dark:bg-white/12" />}
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
          'pressable h-9 appearance-none rounded-full pl-3.5 pr-8 text-small font-semibold outline-none',
          active ? 'bg-ink text-surface' : 'bg-surface text-ink ring-1 ring-line-strong',
          className,
        )}
      />
      <CaretDown size={14} className={cx('pointer-events-none absolute right-3 top-1/2 -translate-y-1/2', active ? 'text-bg' : 'text-ink-3')} />
    </span>
  )
}

export function Chip({ active, children, onClick }: { active?: boolean; children: ReactNode; onClick?: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cx(
        'pressable inline-flex h-9 shrink-0 items-center gap-1.5 rounded-full px-3.5 text-small font-semibold transition-colors duration-quick',
        active ? 'bg-ink text-surface' : 'bg-surface text-ink ring-1 ring-line-strong',
      )}
    >
      {children}
    </button>
  )
}

export function Toggle({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode }) {
  return (
    <button type="button" role="switch" aria-checked={checked} onClick={() => onChange(!checked)} className="group flex w-full items-center justify-between gap-3 py-1 text-left text-body">
      <span>{label}</span>
      <span className={cx('flex h-7 w-12 shrink-0 rounded-full p-0.5 transition-[background-color,filter] duration-base group-hover:[filter:var(--hover-filter)] group-active:[filter:var(--press-filter)]', checked ? 'justify-end bg-positive' : 'justify-start bg-line-strong')}>
        <motion.span layout transition={MOVE} className="h-6 w-6 rounded-full bg-white shadow" />
      </span>
    </button>
  )
}

// ---- Sheet ----------------------------------------------------------------------------------------

const openSheets: object[] = []

/**
 * A form or detail on top of the page. On phones it's a full-screen window that opens like a page (a short fade
 * and rise) with the page header: Back, then the title. On wider screens it's a centred dialog over the page.
 * Escape closes the top one either way.
 */
export function Sheet({ open, onClose, title, children }: { open: boolean; onClose: () => void; title: string; children: ReactNode }) {
  const wide = typeof window !== 'undefined' && window.matchMedia('(min-width: 640px)').matches

  const closeRef = useRef(onClose)
  closeRef.current = onClose
  useEffect(() => {
    if (!open) return
    const me = {}
    openSheets.push(me)
    // Escape closes only the top sheet (a date picker can open over another sheet)
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && openSheets[openSheets.length - 1] === me && closeRef.current()
    document.addEventListener('keydown', onKey)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      openSheets.splice(openSheets.indexOf(me), 1)
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = prev
    }
  }, [open])

  return createPortal(
    <AnimatePresence>
      {open &&
        (wide ? (
          <div className="fixed inset-0 z-[60] flex items-center justify-center p-6" role="dialog" aria-modal="true" aria-label={title}>
            <motion.div className="absolute inset-0 bg-black/40" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, transition: QUICK }} transition={BASE} onClick={onClose} />
            <motion.div
              className="relative flex max-h-[92dvh] w-full max-w-lg flex-col overflow-y-auto overflow-x-hidden overscroll-contain rounded-dialog bg-surface px-5 pb-6 shadow-[var(--shadow-float)]"
              initial={{ opacity: 0, y: 16, scale: 0.98 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 8, scale: 0.98, transition: QUICK }}
              transition={BASE}
            >
              <div className="sticky top-0 z-20 -mx-5 mb-3 rounded-t-dialog bg-surface px-5 pb-2 pt-4">
                <div className="flex items-center justify-between">
                  <h3 className="text-subheading font-semibold">{title}</h3>
                  <button onClick={onClose} className="pressable flex h-8 w-8 items-center justify-center rounded-full bg-surface-2 text-ink-2 hover:text-ink" aria-label="Close">
                    <X size={15} weight="bold" />
                  </button>
                </div>
              </div>
              <div className="flex flex-1 flex-col">{children}</div>
            </motion.div>
          </div>
        ) : (
          <motion.div
            role="dialog"
            aria-modal="true"
            aria-label={title}
            className="fixed inset-0 z-[60] flex flex-col overflow-y-auto overflow-x-hidden overscroll-contain bg-surface px-5 pb-safe"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 8, transition: QUICK }}
            transition={BASE}
          >
            {/* the page header, kept in view while the window scrolls */}
            <header className="sticky top-0 z-20 -mx-5 mb-4 bg-surface px-5 pb-2 pt-[max(env(safe-area-inset-top),0.75rem)]">
              <BackButton onClick={onClose} />
              <h1 className="text-heading font-bold leading-tight tracking-[-0.02em]">{title}</h1>
            </header>
            {/* fills the rest of the screen, so a form can keep its button at the bottom (mt-auto) */}
            <div className="flex flex-1 flex-col">{children}</div>
          </motion.div>
        ))}
    </AnimatePresence>,
    document.body,
  )
}

// ---- Dropdown ---------------------------------------------------------------------------------------

export type DropdownOption<T> = { value: T; label: string; detail?: string; icon?: ReactNode; group?: string }

/**
 * A pill that opens a list of choices right under it (or above, when there's no room below): one tap to pick,
 * a tick on the current one. Long lists get a search box. Escape, a tap outside or picking closes it; arrow keys
 * and Enter work with a keyboard. The list is drawn over everything, so a scrolling sheet can't clip it.
 */
export function Dropdown<T extends string | number>({
  value,
  options,
  onChange,
  label,
  children,
  searchFrom = 9,
}: {
  value: T | undefined
  options: DropdownOption<T>[]
  onChange: (v: T) => void
  /** What the list is for, for screen readers ("Account"). */
  label: string
  /** What the pill shows. */
  children: ReactNode
  /** Show a search box from this many options. */
  searchFrom?: number
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const [place, setPlace] = useState<{ left: number; top?: number; bottom?: number; width: number; maxHeight: number } | null>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLDivElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const id = useId()

  const q = query.trim().toLowerCase()
  const shown = q ? options.filter((o) => o.label.toLowerCase().includes(q) || o.detail?.toLowerCase().includes(q)) : options
  const searchable = options.length >= searchFrom

  const close = (focusBack = true) => {
    setOpen(false)
    setQuery('')
    if (focusBack) trigger.current?.focus({ preventScroll: true })
  }
  const pick = (o: DropdownOption<T>) => {
    onChange(o.value)
    close()
  }

  // Where to draw it: under the pill if there's room, otherwise above; never past the screen's 16px gutters.
  const measure = () => {
    const r = trigger.current?.getBoundingClientRect()
    if (!r) return
    const vw = window.innerWidth
    const vh = window.innerHeight
    const width = Math.min(Math.max(r.width, 260), 340, vw - 32)
    const left = Math.min(Math.max(16, r.left), vw - 16 - width)
    const below = vh - r.bottom - 16
    const above = r.top - 16
    const up = below < 260 && above > below
    const maxHeight = Math.min(up ? above : below, 420) - 8
    setPlace(up ? { left, bottom: vh - r.top + 8, width, maxHeight } : { left, top: r.bottom + 8, width, maxHeight })
  }
  useLayoutEffect(() => {
    if (!open) return
    measure()
    setActive(Math.max(0, options.findIndex((o) => o.value === value)))
    window.addEventListener('resize', measure)
    window.addEventListener('scroll', measure, true)
    return () => {
      window.removeEventListener('resize', measure)
      window.removeEventListener('scroll', measure, true)
    }
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  const drawn = open && place != null
  useEffect(() => {
    if (!drawn) return
    // On a phone the keyboard would cover the list, so the search box is only focused with a mouse or keyboard.
    const fine = window.matchMedia('(pointer: fine)').matches
    ;(searchable && fine ? panel.current?.querySelector('input') : list.current)?.focus({ preventScroll: true })
    const outside = (e: PointerEvent) => {
      const t = e.target as Node
      if (!panel.current?.contains(t) && !trigger.current?.contains(t)) close(false)
    }
    // Escape closes only the list, not the sheet underneath (which listens on document).
    const key = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      close()
    }
    document.addEventListener('pointerdown', outside, true)
    document.addEventListener('keydown', key, true)
    return () => {
      document.removeEventListener('pointerdown', outside, true)
      document.removeEventListener('keydown', key, true)
    }
  }, [drawn]) // eslint-disable-line react-hooks/exhaustive-deps

  // Keep the highlighted row in view while moving with the arrow keys.
  useEffect(() => {
    list.current?.querySelector(`[data-i="${active}"]`)?.scrollIntoView({ block: 'nearest' })
  }, [active])

  const onKeyDown = (e: ReactKeyboardEvent) => {
    if (e.key === 'ArrowDown') setActive((i) => Math.min(i + 1, shown.length - 1))
    else if (e.key === 'ArrowUp') setActive((i) => Math.max(i - 1, 0))
    else if (e.key === 'Home') setActive(0)
    else if (e.key === 'End') setActive(shown.length - 1)
    else if (e.key === 'Enter' && shown[active]) pick(shown[active])
    else if (e.key === 'Tab') close(false)
    else return
    e.preventDefault()
  }

  return (
    <>
      <button
        ref={trigger}
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? `${id}-list` : undefined}
        onClick={() => (open ? close() : setOpen(true))}
        onKeyDown={(e) => {
          if (!open && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
            e.preventDefault()
            setOpen(true)
          }
        }}
        className={cx(
          'pressable flex h-11 max-w-full items-center gap-2 rounded-full px-4 text-label font-medium transition-colors duration-quick',
          open ? 'bg-ink text-bg' : 'bg-surface-2 text-ink',
        )}
      >
        <span className="flex min-w-0 items-center gap-2 truncate">{children}</span>
        <CaretDown size={12} weight="bold" className={cx('shrink-0 transition-transform duration-quick', open ? 'rotate-180' : 'opacity-50')} />
      </button>
      {createPortal(
        <AnimatePresence>
          {open && place && (
            <motion.div
              ref={panel}
              className="fixed z-[70] flex flex-col overflow-hidden rounded-card border border-line bg-surface shadow-[var(--shadow-float)]"
              style={{ left: place.left, top: place.top, bottom: place.bottom, width: place.width, maxHeight: place.maxHeight, transformOrigin: place.top != null ? 'top left' : 'bottom left' }}
              initial={{ opacity: 0, scale: 0.97, y: place.top != null ? -4 : 4 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.97, transition: QUICK }}
              transition={BASE}
              onKeyDown={onKeyDown}
            >
              {searchable && (
                <label className="flex shrink-0 items-center gap-2 border-b border-line px-3">
                  <MagnifyingGlass size={16} className="ink-3 shrink-0" />
                  <input
                    value={query}
                    onChange={(e) => (setQuery(e.target.value), setActive(0))}
                    placeholder="Search"
                    aria-label={`Search ${label.toLowerCase()}`}
                    aria-controls={`${id}-list`}
                    aria-activedescendant={shown[active] ? `${id}-${active}` : undefined}
                    className="h-11 min-w-0 flex-1 bg-transparent text-input outline-none placeholder:text-ink-3"
                  />
                </label>
              )}
              <div
                ref={list}
                id={`${id}-list`}
                role="listbox"
                aria-label={label}
                tabIndex={-1}
                aria-activedescendant={shown[active] ? `${id}-${active}` : undefined}
                className="overflow-y-auto overscroll-contain py-1.5 outline-none"
              >
                {shown.length === 0 && <p className="ink-2 px-4 py-3 text-label">Nothing matches “{query}”</p>}
                {shown.map((o, i) => {
                  const on = o.value === value
                  return (
                    <div key={String(o.value)}>
                      {o.group && o.group !== shown[i - 1]?.group && (
                        <p className={cx('ink-3 px-4 pb-1 text-caption font-medium', i > 0 ? 'pt-3' : 'pt-1.5')}>{o.group}</p>
                      )}
                      <div
                        id={`${id}-${i}`}
                        data-i={i}
                        role="option"
                        aria-selected={on}
                        onClick={() => pick(o)}
                        onPointerMove={() => setActive(i)}
                        className={cx('row flex min-h-11 cursor-pointer items-center gap-3 px-4 py-2', i === active && 'bg-[var(--hover)]')}
                      >
                        {o.icon && <span className="flex w-5 shrink-0 justify-center">{o.icon}</span>}
                        <span className="min-w-0 flex-1">
                          <span className={cx('block truncate text-body', on && 'font-semibold')}>{o.label}</span>
                          {o.detail && <span className="ink-2 block truncate text-small">{o.detail}</span>}
                        </span>
                        {on && <Check size={16} weight="bold" className="shrink-0 text-accent" />}
                      </div>
                    </div>
                  )
                })}
              </div>
            </motion.div>
          )}
        </AnimatePresence>,
        document.body,
      )}
    </>
  )
}

// ---- Data display ---------------------------------------------------------------------------------

/** What a colour means (principles §2.3): good or money in, a problem or money out, needs attention soon, tappable. */
export type Tone = 'neutral' | 'positive' | 'negative' | 'attention' | 'accent'
export const toneCls: Record<Tone, string> = {
  neutral: 'tint-neutral',
  positive: 'tint-positive',
  negative: 'tint-negative',
  attention: 'tint-attention',
  accent: 'tint-accent',
}

export function Badge({ children, tone = 'neutral', className }: { children: ReactNode; tone?: Tone; className?: string }) {
  return <span className={cx('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-micro font-semibold', toneCls[tone], className)}>{children}</span>
}

/** Change vs a previous value. `goodWhenUp` flips the colour meaning (spending up = bad). */
export function Delta({ now, before, goodWhenUp = true }: { now: number; before: number; goodWhenUp?: boolean }) {
  if (!before) return null
  const pct = Math.round(((now - before) / Math.abs(before)) * 100)
  if (!isFinite(pct)) return null
  const up = pct > 0
  const good = pct === 0 ? true : up === goodWhenUp
  return (
    <Badge tone={pct === 0 ? 'neutral' : good ? 'positive' : 'negative'}>
      {up ? '↑' : pct < 0 ? '↓' : '→'} {Math.abs(pct)}%
    </Badge>
  )
}

export function Empty({ icon: Icon, title, children, action }: { icon?: PhIcon; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="surface flex flex-col items-center rounded-card px-6 py-10 text-center">
      {Icon && (
        <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-box bg-accent-soft text-accent">
          <Icon size={26} />
        </div>
      )}
      <p className="text-title font-semibold">{title}</p>
      {children && <div className="ink-2 mt-1 max-w-xs text-label">{children}</div>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

/** Static placeholder blocks while loading (no looping animation). */
export function Skeleton({ className }: { className?: string }) {
  return <div className={cx('rounded-box bg-surface-2', className)} />
}

export function PageSkeleton() {
  return (
    <div className="space-y-4 pt-4">
      <Skeleton className="h-8 w-40" />
      <Skeleton className="h-40 w-full rounded-card" />
      <Skeleton className="h-24 w-full rounded-card" />
      <Skeleton className="h-64 w-full rounded-card" />
    </div>
  )
}

/** Thin horizontal meter that fills from zero. Pass `status` to colour by utilisation thresholds instead of the accent. */
export function Meter({ pct, color, status, className }: { pct: number; color?: string; status?: boolean; className?: string }) {
  const v = Math.max(0, Math.min(100, pct))
  const c = status ? statusColor(v) : (color ?? 'var(--ink)')
  return (
    <div className={cx('h-1.5 w-full overflow-hidden rounded-full bg-surface-2', className)}>
      <motion.div className="h-full rounded-full" initial={{ width: 0 }} animate={{ width: `${v}%` }} transition={MOVE} style={{ background: c }} />
    </div>
  )
}

export const statusColor = (pct: number) => (pct >= 75 ? 'var(--negative)' : pct >= 40 ? 'var(--attention)' : 'var(--positive)')

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
          transition={MOVE}
          style={{ stroke: color ?? statusColor(v) }}
        />
      </svg>
      <div className="absolute inset-0 flex items-center justify-center text-center">{children}</div>
    </div>
  )
}

export function Amount({ value, direction, className, muted }: { value: number; direction?: 'DEBIT' | 'CREDIT'; className?: string; muted?: boolean }) {
  const color = muted ? 'text-ink-3' : direction === 'CREDIT' ? 'text-positive' : ''
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
    <div className="flex shrink-0 items-center justify-center rounded-full bg-surface-2 font-semibold text-ink-2" style={{ width: size, height: size, fontSize: size * 0.38 }}>
      {initials}
    </div>
  )
}

export function ErrorText({ error }: { error: unknown }) {
  if (!error) return null
  return <p className="anim-pop rounded-box bg-[color-mix(in_oklab,var(--negative)_12%,transparent)] px-4 py-3 text-label text-negative">{(error as Error).message}</p>
}

export function Notice({ children, tone = 'positive' }: { children: ReactNode; tone?: Tone }) {
  return <p className={cx('anim-pop rounded-box px-4 py-3 text-label', toneCls[tone])}>{children}</p>
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
        <Empty title={`${name} is turned off`} action={<Button variant="secondary" onClick={() => nav('/settings/features')}>Open Settings</Button>}>
          Switch it on under Settings → Features.
        </Empty>
      </Page>
    )
  return <>{children}</>
}

/** One row in a list of things you could do (a transaction's More list): icon, label, chevron. */
export function MoreRow({ icon: Icon, onClick, children, tone }: { icon: PhIcon; onClick: () => void; children: ReactNode; tone?: 'danger' }) {
  return (
    <button type="button" onClick={onClick} className={cx('row flex w-full items-center gap-3 px-4 py-3.5 text-left text-body', tone === 'danger' && 'text-negative')}>
      <Icon size={19} className={tone === 'danger' ? undefined : 'ink-2'} />
      <span className="min-w-0 flex-1">{children}</span>
      {tone !== 'danger' && <CaretRight size={15} className="ink-3" />}
    </button>
  )
}

/**
 * Asks before something that can't be undone, in place: the trigger is swapped for the question with Cancel and
 * the action. Not a browser pop-up (confirm()), which Safari and the installed app can block without a word.
 *
 * @param children renders the trigger; call the function it's given to ask
 */
export function Confirm({ ask, yes, onYes, busy, className, children }: { ask: ReactNode; yes: string; onYes: () => void; busy?: boolean; className?: string; children: (ask: () => void) => ReactNode }) {
  const [asking, setAsking] = useState(false)
  if (!asking) return <>{children(() => setAsking(true))}</>
  return (
    <div className={cx('anim-pop flex w-full flex-wrap items-center gap-2', className)}>
      <span className="min-w-0 flex-1 text-label">{ask}</span>
      <Button type="button" size="sm" variant="secondary" onClick={() => setAsking(false)}>
        Cancel
      </Button>
      <Button type="button" size="sm" variant="danger" disabled={busy} onClick={onYes}>
        {yes}
      </Button>
    </div>
  )
}

/**
 * Extra fields that open in place (More details, More): the height animates, so a sheet grows smoothly instead of
 * jumping. Closed, nothing is rendered.
 */
export function Fold({ open, children }: { open: boolean; children: ReactNode }) {
  return (
    <AnimatePresence initial={false}>
      {open && (
        // Clipped only while it moves, so focus rings inside aren't cut off once it's open.
        <motion.div
          initial={{ height: 0, opacity: 0, overflow: 'hidden' }}
          animate={{ height: 'auto', opacity: 1, transitionEnd: { overflow: 'visible' } }}
          exit={{ height: 0, opacity: 0, overflow: 'hidden' }}
          transition={BASE}
        >
          {children}
        </motion.div>
      )}
    </AnimatePresence>
  )
}
