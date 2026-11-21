/**
 * The app's motion, in one place (docs/principles.md §2.1). The CSS side is duration-quick / duration-base and
 * ease-standard in index.css; these are the same values for motion components.
 */

/** Starts fast, settles gently: for everything that appears, fades or folds. */
export const EASE = [0.22, 1, 0.36, 1] as const

/** 120ms: press feedback, colour changes, things leaving. */
export const QUICK = { duration: 0.12, ease: EASE }

/** 180ms: things appearing (windows, folds, notices). */
export const BASE = { duration: 0.18, ease: EASE }

/** 250ms spring without bounce: things moving from one place to another (a switch's knob, the selected tab). */
export const MOVE = { type: 'spring', duration: 0.25, bounce: 0 } as const
