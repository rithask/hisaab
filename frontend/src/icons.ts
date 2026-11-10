import { AirplaneTilt, ArrowCounterClockwise, ArrowDownLeft, ArrowUpRight, ArrowsLeftRight, Basket, Briefcase, Car, CircleDashed, FilmSlate, ForkKnife, GasPump, Gift, GraduationCap, Heartbeat, House, Percent, Receipt, Repeat, SealPercent, ShoppingBag, Tag, TrendUp, type Icon as PhIcon } from '@phosphor-icons/react'
import type { Category, Txn } from './api'

const byKeyword: [RegExp, PhIcon][] = [
  [/food|dining|restaurant|cafe/i, ForkKnife],
  [/grocer/i, Basket],
  [/shop/i, ShoppingBag],
  [/transport|cab|taxi|auto/i, Car],
  [/fuel|petrol/i, GasPump],
  [/bill|utilit|electric|phone|internet/i, Receipt],
  [/rent|home|house/i, House],
  [/entertain|movie/i, FilmSlate],
  [/health|medic|pharma/i, Heartbeat],
  [/travel|flight|trip/i, AirplaneTilt],
  [/educat|course|school/i, GraduationCap],
  [/subscri/i, Repeat],
  [/invest/i, TrendUp],
  [/fee|charge/i, SealPercent],
  [/salary|wage|payroll/i, Briefcase],
  [/interest/i, Percent],
  [/refund|cashback/i, ArrowCounterClockwise],
  [/gift/i, Gift],
  [/other/i, CircleDashed],
]

export function categoryIcon(c?: Pick<Category, 'name'> | null): PhIcon {
  if (!c) return Tag
  return byKeyword.find(([re]) => re.test(c.name))?.[1] ?? Tag
}

export function txnIcon(t: Txn): PhIcon {
  if (t.transfer) return ArrowsLeftRight
  if (t.category) return categoryIcon(t.category)
  return t.direction === 'CREDIT' ? ArrowDownLeft : ArrowUpRight
}

/** Translucent tint of a hex colour, for icon tiles. */
export function tint(hex: string, alpha = 0.14) {
  const h = hex.replace('#', '')
  const n = parseInt(h.length === 3 ? h.replace(/./g, (x) => x + x) : h, 16)
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`
}
