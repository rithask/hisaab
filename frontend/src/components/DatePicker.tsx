import { CalendarBlank, CaretLeft, CaretRight } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { formatDay, ymd } from '../format'
import { cx, Input, Sheet } from './ui'

/** yyyy-mm-dd arithmetic on calendar dates (no time zone involved). */
const key = (y: number, m: number, d: number) => new Date(Date.UTC(y, m, d)).toISOString().slice(0, 10)
const parts = (s: string) => s.split('-').map(Number) as [number, number, number]
const addDays = (s: string, n: number) => {
  const [y, m, d] = parts(s)
  return key(y, m - 1, d + n)
}
const WEEKDAYS = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su']

/**
 * A date field that opens a month calendar: today is marked, dates outside min/max are disabled, and tapping the
 * month title switches to a year grid for jumping back a long way. Values are yyyy-mm-dd in the app's time zone.
 * On phones the calendar is a bottom sheet (nothing hides it, thumbs reach it); on wider screens it drops down
 * from the field, or up when there isn't room below. Both are rendered on top of the page, so it works inside a
 * Sheet or a <Field> label too. Keyboard: arrows move a day (or a week), Enter picks, Escape closes.
 */
export function DatePicker({
  value,
  onChange,
  min,
  max,
  disabled,
  placeholder = 'Pick a date',
  label,
  short,
  clearable,
}: {
  value: string
  onChange: (v: string) => void
  min?: string
  max?: string
  disabled?: boolean
  placeholder?: string
  /** accessible name for the field when there's no visible <Field> label */
  label?: string
  /** "11 Nov 2026" instead of "Wed, 11 November 2026", for narrow fields */
  short?: boolean
  /** offer "Clear" (for optional dates such as filters) */
  clearable?: boolean
}) {
  const today = ymd()
  const [open, setOpen] = useState(false)
  const [phone, setPhone] = useState(false)
  const [pos, setPos] = useState<{ left: number; top?: number; bottom?: number }>({ left: 0 })
  const [years, setYears] = useState(false)
  const [focus, setFocus] = useState(value || (max && max < today ? max : today))
  const [vy, vm] = parts(focus)                                      // the month on screen
  const root = useRef<HTMLDivElement>(null)
  const pop = useRef<HTMLDivElement>(null)
  const grid = useRef<HTMLDivElement>(null)
  const id = useId()

  const allowed = (d: string) => (!min || d >= min) && (!max || d <= max)
  const show = () => {
    setFocus(value || (max && max < today ? max : today))
    setYears(false)
    setPhone(!window.matchMedia('(min-width: 640px)').matches)
    const box = root.current?.getBoundingClientRect()
    if (box) {
      const below = window.innerHeight - box.bottom
      const up = below < 400 && box.top > below
      const left = Math.max(8, Math.min(box.left, window.innerWidth - 320 - 8))
      setPos(up ? { left, bottom: window.innerHeight - box.top + 8 } : { left, top: box.bottom + 8 })
    }
    setOpen(true)
  }
  const pick = (d: string) => {
    if (!allowed(d)) return
    onChange(d)
    setOpen(false)
  }

  // the dropdown closes on a tap outside, a scroll or a resize (the phone sheet has its own backdrop)
  useEffect(() => {
    if (!open || phone) return
    const away = (e: PointerEvent) => !root.current?.contains(e.target as Node) && !pop.current?.contains(e.target as Node) && setOpen(false)
    const close = (e: Event) => !pop.current?.contains(e.target as Node) && setOpen(false)
    document.addEventListener('pointerdown', away)
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    return () => {
      document.removeEventListener('pointerdown', away)
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
    }
  }, [open, phone])
  // keep keyboard focus on the focused day
  useEffect(() => {
    if (open && !years) grid.current?.querySelector<HTMLButtonElement>(`[data-day="${focus}"]`)?.focus()
  }, [open, years, focus])

  const onKey = (e: KeyboardEvent) => {
    const step = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }[e.key]
    if (step) {
      e.preventDefault()
      setFocus((f) => addDays(f, step))
    } else if (e.key === 'Escape') {
      e.preventDefault()
      e.stopPropagation()
      setOpen(false)
    }
  }

  // the month grid, Monday first, padded with the neighbouring months' days
  const first = key(vy, vm - 1, 1)
  const lead = (new Date(first + 'T00:00:00Z').getUTCDay() + 6) % 7
  const cells = Array.from({ length: 42 }, (_, i) => addDays(first, i - lead))
  const lastRowNeeded = cells.slice(35).some((d) => parts(d)[1] === vm)
  const days = lastRowNeeded ? cells : cells.slice(0, 35)
  const thisYear = parts(today)[0]
  const yearList = Array.from({ length: 12 }, (_, i) => thisYear - 11 + i)
  const shift = (n: number) => setFocus(key(vy, vm - 1 + n, Math.min(parts(focus)[2], 28)))

  const calendar = (
    <>
      <div className="mb-2 flex items-center justify-between">
        <button type="button" aria-label="Previous month" disabled={years} onClick={() => shift(-1)} className="pressable ink-2 flex h-9 w-9 items-center justify-center rounded-full disabled:opacity-0">
          <CaretLeft size={18} />
        </button>
        <button type="button" onClick={() => setYears((y) => !y)} aria-label={years ? 'Back to days' : 'Choose a year'} className="pressable rounded-full px-3 py-1.5 text-[15px] font-semibold">
          {years ? 'Choose a year' : formatDay(first, { month: 'long', year: 'numeric' })}
        </button>
        <button type="button" aria-label="Next month" disabled={years || (!!max && key(vy, vm, 1) > max)} onClick={() => shift(1)} className="pressable ink-2 flex h-9 w-9 items-center justify-center rounded-full disabled:opacity-30">
          <CaretRight size={18} />
        </button>
      </div>

      {years ? (
        <div className="grid grid-cols-3 gap-1.5">
          {yearList.map((y) => {
            const ok = (!max || `${y}-01-01` <= max) && (!min || `${y}-12-31` >= min)
            return (
              <button
                key={y}
                type="button"
                disabled={!ok}
                onClick={() => (setFocus(key(y, vm - 1, Math.min(parts(focus)[2], 28))), setYears(false))}
                className={cx('pressable h-11 rounded-xl text-[15px] font-medium disabled:opacity-30', y === vy ? 'bg-[var(--ink)] text-[var(--surface)]' : 'hover:bg-[var(--surface-2)]')}
              >
                {y}
              </button>
            )
          })}
        </div>
      ) : (
        <div ref={grid} role="grid">
          <div className="ink-3 mb-1 grid grid-cols-7 text-center text-[11px] font-semibold">
            {WEEKDAYS.map((w) => (
              <span key={w}>{w}</span>
            ))}
          </div>
          <div className="grid grid-cols-7 gap-y-0.5">
            {days.map((d) => {
              const inMonth = parts(d)[1] === vm
              const selected = d === value
              const ok = allowed(d)
              return (
                <button
                  key={d}
                  type="button"
                  data-day={d}
                  tabIndex={d === focus ? 0 : -1}
                  disabled={!ok}
                  aria-pressed={selected}
                  aria-label={formatDay(d, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }) + (d === today ? ', today' : '')}
                  onClick={() => pick(d)}
                  onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), pick(focus))}
                  className={cx(
                    'tabular mx-auto flex h-10 w-10 items-center justify-center rounded-full text-[14px] transition-colors duration-150 disabled:opacity-25',
                    selected ? 'bg-[var(--ink)] font-semibold text-[var(--surface)]' : ok && 'hover:bg-[var(--surface-2)]',
                    !selected && d === today && 'font-semibold text-[var(--accent)] ring-1 ring-[var(--accent)]',
                    !inMonth && !selected && 'ink-3',
                  )}
                >
                  {parts(d)[2]}
                </button>
              )
            })}
          </div>
        </div>
      )}
      {clearable && value && (
        <div className="mt-2 flex justify-end border-t border-[var(--line)] pt-2">
          <button type="button" onClick={() => (onChange(''), setOpen(false))} className="pressable ink-2 rounded-full px-3 py-1.5 text-[13px] font-semibold">
            Clear
          </button>
        </div>
      )}
    </>
  )

  return (
    <div ref={root} className="relative">
      <button
        type="button"
        disabled={disabled}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={id}
        aria-label={label ? `${label}: ${value ? formatDay(value, { day: 'numeric', month: 'long', year: 'numeric' }) : placeholder}` : undefined}
        onClick={() => (open ? setOpen(false) : show())}
        className="flex w-full items-center gap-2.5 rounded-xl border border-transparent bg-[var(--surface-2)] px-4 py-3 text-left text-base outline-none transition-[border-color,box-shadow] duration-150 hover:border-[var(--line-strong)] focus-visible:border-[var(--accent)] focus-visible:shadow-[0_0_0_3px_var(--accent-soft)] disabled:opacity-60"
      >
        <CalendarBlank size={18} className="ink-2 shrink-0" />
        <span className={cx('min-w-0 flex-1 truncate', !value && 'ink-3')}>
          {value ? formatDay(value, short ? { day: 'numeric', month: 'short', year: 'numeric' } : { weekday: 'short', day: 'numeric', month: 'long', year: 'numeric' }) : placeholder}
        </span>
      </button>

      {phone ? (
        <Sheet open={open} onClose={() => setOpen(false)} title="Choose a date">
          <div id={id} onKeyDown={onKey} className="pb-2">
            {calendar}
          </div>
        </Sheet>
      ) : (
        createPortal(
          <AnimatePresence>
            {open && (
            <motion.div
              ref={pop}
              id={id}
              role="dialog"
              aria-label="Choose a date"
              initial={{ opacity: 0, y: pos.bottom != null ? 4 : -4 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: pos.bottom != null ? 4 : -4 }}
              transition={{ duration: 0.15 }}
              onKeyDown={onKey}
              style={pos}
              className="surface fixed z-[70] w-[20rem] rounded-2xl p-3 shadow-[var(--shadow-float)] ring-1 ring-[var(--line)]"
            >
              {calendar}
            </motion.div>
            )}
          </AnimatePresence>,
          document.body,
        )
      )}
    </div>
  )
}

/**
 * A date and a time ("yyyy-MM-ddTHH:mm", like <input type="datetime-local">): the calendar for the day and the
 * system time picker beside it. A `max` moment (e.g. now) limits both.
 */
export function DateTimePicker({ value, onChange, max, label = 'Date' }: { value: string; onChange: (v: string) => void; max?: string; label?: string }) {
  const [day, hm] = [value.slice(0, 10), value.slice(11, 16)]
  const set = (d: string, t: string) => {
    if (!d || !t) return
    const v = `${d}T${t}`
    onChange(max && v > max ? max : v)
  }
  return (
    <div className="flex gap-2">
      <div className="min-w-0 flex-1">
        <DatePicker value={day} onChange={(d) => set(d, hm)} max={max?.slice(0, 10)} label={label} short />
      </div>
      <div className="w-[7.5rem] shrink-0">
        <Input type="time" value={hm} onChange={(e) => set(day, e.target.value)} aria-label="Time" />
      </div>
    </div>
  )
}
