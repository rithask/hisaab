export class ApiError extends Error {
  status: number
  code?: string
  constructor(status: number, message: string, code?: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

type Opts = { method?: string; body?: unknown; form?: FormData }

export async function api<T = unknown>(path: string, opts: Opts = {}): Promise<T> {
  const init: RequestInit = { method: opts.method ?? (opts.body || opts.form ? 'POST' : 'GET'), credentials: 'same-origin' }
  if (opts.form) init.body = opts.form
  else if (opts.body !== undefined) {
    init.body = JSON.stringify(opts.body)
    init.headers = { 'Content-Type': 'application/json' }
  }
  let res: Response
  try {
    res = await fetch('/api' + path, init)
  } catch {
    // No connection (or the server is down). Reads may still come from the copy the app saved for offline use.
    throw new ApiError(0, navigator.onLine ? "Can't reach the server. Try again in a moment." : "You're offline. Try again when you're connected.")
  }
  if (res.status === 401 && path !== '/auth/login') window.dispatchEvent(new Event('hisaab:unauthorized'))
  const text = await res.text()
  const data = text ? safeJson(text) : null
  if (!res.ok) {
    const e = data as { error?: string; code?: string } | null
    throw new ApiError(res.status, e?.error ?? `Request failed (${res.status})`, e?.code)
  }
  return data as T
}

function safeJson(t: string): unknown {
  try {
    return JSON.parse(t)
  } catch {
    return t
  }
}

export const qs = (params: Record<string, string | number | boolean | undefined | null>) => {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== '') p.set(k, String(v))
  const s = p.toString()
  return s ? '?' + s : ''
}

// ---- types (mirror the backend DTOs) ---------------------------------------------------------------

export type AccountType = 'BANK' | 'CREDIT_CARD' | 'CASH'
export type Institution = 'HDFC' | 'AXIS' | 'SBI' | 'CANARA' | 'OTHER'
export type Direction = 'DEBIT' | 'CREDIT'

export interface CardInfo {
  creditLimit?: number
  outstanding: number
  available?: number
  utilizationPct?: number
  lastStatement?: string
  nextStatement?: string
  dueDate?: string
  daysToDue: number
  spentThisCycle?: number
  paidSinceStatement?: number
}

export interface Account {
  id: number
  name: string
  type: AccountType
  institution: Institution
  last4?: string
  openingBalance: number
  openingDate: string
  archived: boolean
  balance: number
  statementDay?: number
  dueDay?: number
  card?: CardInfo
}

export interface AccountRef {
  id: number
  name: string
  type: AccountType
  institution: Institution
  last4?: string
}

export interface Category {
  id: number
  name: string
  kind: 'EXPENSE' | 'INCOME'
  color: string
}

export interface Txn {
  id: number
  account: AccountRef
  direction: Direction
  amount: number
  originalAmount?: number
  originalCurrency?: string
  amountEstimated: boolean
  occurredAt: string
  counterparty?: string
  description?: string
  referenceNo?: string
  reportedBalance?: number
  category?: Category
  tags: string[]
  notes?: string
  source: 'EMAIL' | 'SMS' | 'STATEMENT' | 'MANUAL' | 'ADJUSTMENT'
  rawEmailId?: number
  transfer: boolean
  linkedTxnId?: number
  linkedAccount?: AccountRef
  sharedAmount?: number
  /** the app chose the category from what it learned; I haven't confirmed it */
  categorySuggested?: boolean
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
  hasMore: boolean
}

export interface Share {
  id: number
  personId: number
  personName: string
  method: 'EQUAL' | 'EXACT' | 'PERCENT'
  input?: number
  amount: number
}

export interface Split {
  total: number
  othersOwe: number
  myShare: number
  shares: Share[]
}

export interface Person {
  id: number
  name: string
  upiName?: string
  phone?: string
  notes?: string
  owed: number
  paid: number
  outstanding: number
}

export interface Settlement {
  id: number
  personId: number
  personName: string
  amount: number
  settledAt: string
  status: 'SUGGESTED' | 'CONFIRMED' | 'REJECTED'
  note?: string
  txnId?: number
  txnCounterparty?: string
}

export interface Holding {
  id: number
  assetType: 'STOCK' | 'MF'
  symbol: string
  exchange?: string
  name: string
  quantity: number
  avgPrice: number
  lastPrice?: number
  prevClose?: number
  priceAsOf?: string
  invested: number
  currentValue?: number
  pnl?: number
  pnlPct?: number
  dayChange?: number
}

export interface Portfolio {
  invested: number
  currentValue: number
  pnl: number
  pnlPct: number
  dayChange: number
  holdings: Holding[]
}

export interface CategoryTotal {
  categoryId?: number
  name: string
  color: string
  total: number
  count: number
}

export interface Report {
  from: string
  to: string
  income: number
  expense: number
  expenseByCategory: CategoryTotal[]
  incomeByCategory: CategoryTotal[]
  topMerchants: { name: string; total: number; count: number }[]
  topSources: { name: string; total: number; count: number }[]
  daily: { day: string; expense: number; income: number }[]
}

export interface Dashboard {
  totals: {
    cash: number
    cardOutstanding: number
    cardLimit: number
    investments: number
    invested: number
    receivable: number
    netWorth: number
  }
  accounts: Account[]
  month: Report
  trend: { month: string; expense: number; income: number }[]
  counts: { alerts: number; emailsToReview: number; suggestedSettlements: number; statementsToReview: number; statementsLocked: number }
}

export interface Alert {
  id: number
  type: string
  message: string
  account?: AccountRef
  txnId?: number
  expected?: number
  actual?: number
  createdAt: string
}

export type EmailStatus = 'PARSED' | 'IGNORED' | 'NEEDS_REVIEW' | 'STATEMENT' | 'DUPLICATE' | 'ERROR'

export interface Email {
  id: number
  channel?: 'EMAIL' | 'SMS'
  fromAddress?: string
  subject?: string
  receivedAt: string
  status: EmailStatus
  parser?: string
  note?: string
  bodyText?: string
  /** why an alert was held instead of recorded */
  hold?: 'UNKNOWN_SENDER' | 'UNVERIFIED_SENDER'
  senderDomain?: string
}

export interface Statement {
  id: number
  account?: AccountRef
  fileName: string
  sizeBytes: number
  periodLabel?: string
  receivedAt: string
  importStatus?: 'REVIEW' | 'IMPORTED' | 'CANCELLED' | 'NEEDS_PASSWORD' | 'UNSUPPORTED' | 'ERROR'
  importNote?: string
  fromEmail: boolean
}

export interface Rule {
  id: number
  matchType: 'MERCHANT' | 'CONTAINS'
  pattern: string
  category: Category
  source: 'SYSTEM' | 'MANUAL' | 'LEARNED'
  priority: number
  hits: number
}

export interface Settings {
  ownerName?: string
  mail: { configured: boolean; lastSync?: string; lastError?: string }
  showInvestmentsOnHome: boolean
  features: Record<Feature, boolean>
}

export type Feature = 'backups' | 'bills' | 'budgets' | 'networth' | 'review' | 'search' | 'subscriptions' | 'unusual' | 'rewards'

export interface Bill {
  key: string
  name: string
  accountId?: number
  account?: string
  category?: string
  categoryColor?: string
  amount: number
  amountVaries: boolean
  due: string
  daysLeft: number
  overdue: boolean
  every: string
  timesSeen: number
  lastPaid?: string
  card: boolean
}

export interface Budget {
  categoryId: number
  name: string
  color: string
  limit: number
  spent: number
  remaining: number
  pct: number
  projected: number
  status: 'OK' | 'WARN' | 'OVER'
}

export interface NetWorthPoint {
  day: string
  netWorth: number
  cash: number
  cardDues: number
  investments: number
  receivable: number
  estimated: boolean
}

export interface YearReview {
  year: number
  years: number[]
  income: number
  expense: number
  saved: number
  savingsRate?: number
  transactions: number
  perDay: number
  months: { month: string; income: number; expense: number }[]
  bestMonth?: string
  costliestMonth?: string
  categories: CategoryTotal[]
  merchants: { name: string; total: number; count: number }[]
  mostVisited?: string
  mostVisitedCount: number
  biggest?: { payee?: string; category?: string; amount: number; day: string }
  busiestWeekday?: string
  noSpendDays: number
  lastYearExpense: number
  lastYearIncome: number
  networthStart?: number
  networthEnd?: number
  partial: boolean
}

/** An older-mail import running on the server (see GET /mail/import). */
export interface MailImport {
  state: 'IDLE' | 'CONNECTING' | 'RUNNING' | 'DONE' | 'CANCELLED' | 'FAILED'
  since?: string
  total?: number
  done: number
  added: number
  startedAt?: string
  finishedAt?: string
  error?: string
}

export interface BackupStatus {
  enabled: boolean
  dir: string
  keep: number
  lastOk?: string
  lastError?: string
  lastErrorAt?: string
  stale: boolean
  backups: { file: string; sizeBytes: number; createdAt: string }[]
}

export interface MfScheme {
  code: string
  name: string
  nav?: number
  navDate?: string
}

export interface SmsResult {
  status: EmailStatus
  message: string
  messageId: number
  transaction?: { id: number; direction: Direction; amount: number; currency: string; account: string; counterparty?: string; category?: string; occurredAt: string }
  parsed?: { bank: string; accountType: string; direction: Direction; amount: number; currency: string; last4?: string; date?: string; time?: string; counterparty?: string; reference?: string; balance?: number }
}

export interface TokenStatus {
  enabled: boolean
  hint?: string
  createdAt?: string
  lastUsedAt?: string
}

export interface SmsPreview {
  direction?: Direction
  amount?: number
  accountId?: number
  counterparty?: string
  /** the message with whitespace collapsed; the spans below are [start, end] in it */
  flat: string
  amountAt?: number[]
  payeeAt?: number[]
}

export interface TeachResult {
  message: Email
  txnId?: number
  learnedFormat: boolean
  learnedAccount: boolean
  alsoFixed: number
  /** no format was learned because it would change how this many earlier lessons are read */
  conflicts: number
  /** the format that had read it was paused: it has now been corrected too often */
  pausedFormat: boolean
  /** the format was folded into one it already knew for this kind of message */
  mergedFormat: boolean
}

export interface Learned {
  formats: { id: number; skeleton: string; sample?: string; institution?: string; direction?: Direction; hits: number; lastUsedAt?: string; channel: 'SMS' | 'EMAIL'; kind: 'TXN' | 'SKIP'; corrections: number; paused: boolean; auto: boolean }[]
  payees: { id: number; rawKey: string; displayName: string; hits: number }[]
  senders: number
}

export type LineKind = 'MATCH' | 'POSSIBLE' | 'NEW'
export type LineDecision = 'MERGE' | 'ADD' | 'SKIP'

export interface ImportLine {
  id: number
  date: string
  time?: string
  description?: string
  counterparty?: string
  reference?: string
  direction: Direction
  amount: number
  balance?: number
  uncertain?: string
  kind?: LineKind
  match?: { id: number; direction: Direction; amount: number; occurredAt: string; timeEstimated: boolean; counterparty?: string; referenceNo?: string; source: string }
  matchReason?: string
  fills: { field: string; label: string; from?: string; to: string }[]
  decision?: LineDecision
  beforeStart: boolean
  balanceEffect?: number
}

export interface StatementImport {
  id: number
  status: 'REVIEW' | 'IMPORTED' | 'CANCELLED'
  label: string
  fileName?: string
  institution: Institution
  accountType: AccountType
  last4?: string
  from?: string
  to?: string
  opening?: number
  closing?: number
  creditLimit?: number
  account?: AccountRef
  accountStart?: string
  checks: { label: string; ok: boolean; detail: string }[]
  balance?: { at: string; statement: number; appNow: number }
  lines: ImportLine[]
  notInStatement: NonNullable<ImportLine['match']>[]
}

export interface ImportResult {
  added: number
  updated: number
  skipped: number
  startingBalance?: number
}

// ---- Subscription watch -------------------------------------------------------------------------------------
export interface PriceChange {
  from: number
  to: number
  on: string
}
export interface Subscription {
  key: string
  name: string
  accountId?: number
  account?: string
  category?: string
  categoryColor?: string
  amount: number
  every: string
  monthly: number
  yearly: number
  since: string
  lastPaid: string
  nextDue: string
  timesPaid: number
  status: 'ACTIVE' | 'NEW' | 'STOPPED'
  priceChange?: PriceChange
  paidThisYear: number
}
/** See SubscriptionService.Alert for which fields each kind uses. */
export interface SubscriptionAlert {
  key: string
  kind: 'PRICE_UP' | 'PRICE_DOWN' | 'DOUBLE' | 'NEW' | 'STOPPED'
  subscriptionKey: string
  name: string
  amount: number
  previous?: number
  perYear?: number
  every: string
  day: string
  otherDay?: string
  account?: string
  otherAccount?: string
  txnIds: number[]
}
export interface Subscriptions {
  items: Subscription[]
  alerts: SubscriptionAlert[]
  monthly: number
  yearly: number
  hidden: { key: string; name: string }[]
}

// ---- Unusual activity ---------------------------------------------------------------------------------------
export type UnusualKind = 'DOUBLE_CHARGE' | 'BIG_FOR_MERCHANT' | 'NEW_PAYEE_BIG' | 'FOREIGN' | 'LATE_NIGHT'
export interface UnusualReason {
  kind: UnusualKind
  otherTxnId?: number
  minutesApart?: number
  typical?: number
  timesBefore?: number
  currency?: string
  originalAmount?: number
}
export interface UnusualItem {
  key: string
  txnId: number
  name?: string
  accountId?: number
  account?: string
  amount: number
  at: string
  reasons: UnusualReason[]
}
export interface RefundWatch {
  txnId: number
  name?: string
  account?: string
  amount: number
  paidAt: string
  expectedBy: string
  status: 'WAITING' | 'LATE' | 'ARRIVED'
  refundTxnId?: number
  refunded?: number
  refundedAt?: string
}
export interface Unusual {
  items: UnusualItem[]
  refunds: RefundWatch[]
}

// ---- Card rewards -------------------------------------------------------------------------------------------
export interface RewardRule {
  id?: number
  label: string
  merchantMatch?: string | null
  categoryId?: number | null
  category?: string
  rate: number
  cycleCap?: number | null
}
export interface RewardProgram {
  kind: 'CASHBACK' | 'POINTS'
  /** % cashback, or points per ₹100 */
  baseRate: number
  cycleCap?: number | null
  pointValue?: number | null
  pointsBalance?: number | null
  pointsAsOf?: string | null
  rules: RewardRule[]
}
export interface RewardLine {
  label: string
  rate: number
  spent: number
  earned: number
  cap?: number
  capped: boolean
}
export interface RewardCycle {
  from: string
  to: string
  current: boolean
  spent: number
  /** ₹ for cashback, points for points cards */
  earned: number
  value?: number
  received: number
  capped: boolean
  lines: RewardLine[]
}
export interface CardRewards {
  accountId: number
  account: string
  last4?: string
  statementCycles: boolean
  program?: RewardProgram
  cycles: RewardCycle[]
  yearEarned?: number
  yearValue?: number
  yearReceived?: number
  points?: { balance: number; asOf: string; earnedSince: number; estimate: number; value?: number }
}
