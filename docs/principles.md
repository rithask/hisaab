# Design and architecture principles

The rules Hisaab is built on. Read this before changing the app, whether you're a person or a coding
agent (Claude, Codex, …). When a change has to break one of these rules, say so in the change and update this
file in the same change.

**What the app is:** Hisaab (Hindi/Urdu for "accounts", as in *hisaab-kitaab*) is a personal money tracker for **one person**, used mostly on a **phone**, **self-hosted** on a
home server. It reads bank alert emails, forwarded SMS and statement PDFs, and turns them into one accurate
picture of accounts, cards, spending, people who owe money, and investments. Amounts are in Indian rupees; days are
counted in the time zone set with `TZ` (section 3.3).

---

## 1. Product principles

1. **Automatic first, manual always possible.** Money arrives from emails, SMS and statements without the user
   doing anything. Every automatic result can be corrected by hand, and a correction is never overwritten by a
   later automatic pass.
2. **Learn from corrections.** When the user fixes something (a category, a payee name, the account an SMS
   belongs to, a bill that isn't a bill), remember it and apply it next time. Say so in the UI ("Learned: Zomato
   → Food").
3. **Accuracy over completeness.** Better to leave a transaction "Uncategorised", or an SMS in the Inbox for
   review, than to guess wrong. Unsure results go to a place the user checks (Home → *Needs attention*, Inbox).
   Never silently drop data.
4. **Nothing is deleted without asking.** Destructive actions confirm first. Turning a feature off hides it; it
   doesn't delete its data.
5. **Every optional feature has a switch.** Bills, budgets, net worth, year in review, advanced search,
   subscription watch, unusual activity and rewards (and backups) can each be turned off in Settings → Features. A feature that's off is hidden from every screen,
   its endpoints refuse requests, and its background jobs don't run. New optional features follow the same
   pattern (section 3.6).
6. **Private by default.** Personal data stays on the user's server. No analytics or third-party calls apart
   from the price feeds for investments, which can be turned off with `PRICES_ENABLED=false`.

## 2. Design principles (UI)

The tokens and the short form of these rules are at the top of `frontend/src/index.css`.

1. **Minimal.** One question per screen, answered at the top in plain words and one big number. Remove
   anything that doesn't help answer it.
2. **Mobile-first, good on desktop.** Design for a 390px-wide phone first: bottom tab bar, sheets that slide up,
   thumb-sized targets (44px or more). On wide screens the same pages get a sidebar and two columns. Nothing is
   desktop-only.
3. **Colour means something, or it isn't there.** Green = money in or good, red = money out or a problem,
   amber = needs attention soon, blue = tappable. Everything else is three greys of ink (`--ink`, `--ink-2`,
   `--ink-3`). Category colours only mark categories. Never colour alone: pair it with a sign, word or icon
   (`−₹500`, "Over budget").
4. **Familiar patterns.** Lists of rows, chips for filters, segmented controls for views, sheets for forms,
   a switch for on/off. Reuse the components in `components/ui.tsx` (`Card`, `List`, `Section`, `Sheet`, `Chip`,
   `Segmented`, `Toggle`, `Button`, `Field`, `Empty`, `FeatureGate`, …) instead of making new ones.
5. **Motion is short and explains.** At most 250ms, only to show where something came from or went (a sheet
   rising, a row expanding). No decorative animation. Respect `prefers-reduced-motion`.
6. **Feedback on every press.** Clickable things get the `pressable`, `row` or `link` utility: a hover state with
   a mouse, a press state everywhere, and a focus ring **only** for keyboard users (`html[data-keyboard]`).
7. **Plain language.** Write like a helpful person: "No backup in the last 2 days", not "Backup job status:
   STALE". Sentence case. Indian number format (`₹1,50,000`, "₹1.25 lakh"). Say what to do next.
8. **Icons:** Phosphor (`@phosphor-icons/react`), regular weight, 20px in rows. **Font:** Inter. **Numbers:**
   `tabular` so columns line up.
9. **Light and dark.** Every colour is a CSS variable with a dark value. Check both.
10. **Empty, loading and error states are part of the design.** Skeletons while loading (`PageSkeleton`), a
    friendly `Empty` with a next step, and errors shown where they happened (`ErrorText`).

## 3. Architecture principles

### 3.1 Shape

```
phone / browser ──HTTPS──▶ Caddy (VPS) ──Tailscale──▶ app container ──▶ PostgreSQL container
                                                       │  Spring Boot API (/api/**)
                                                       │  + the built React app (static files)
IMAP mailboxes ◀──poll── MailSyncService               │  host folders ./data/{db,files,backups}
phone SMS shortcut ──POST /api/ingest/sms (token)──────┘
```

- **One deployable.** A single Docker image holds the API and the built frontend; Spring serves both. There's
  one database and no other services. Keep it that way unless there's a strong reason: it's run by one person
  on a home server.
- **Boring, current technology.** Java 25 with Spring Boot 4, PostgreSQL, Flyway; React 19 with TypeScript, Vite,
  Tailwind 4 and TanStack Query. Add a dependency only when it removes real work, and prefer what's already in.

### 3.2 Backend layers (`backend/src/main/java/com/hisaab`)

| Package | Holds | Rule |
|---|---|---|
| `web` | REST controllers, DTOs (`Dtos.java`, records), `ApiExceptionHandler` | Thin: parse input, call a service, return a DTO. No business logic, never return entities. |
| `service` | Core logic: transactions, balances, categorisation, splits, transfers, prices, settings | Transaction boundaries (`@Transactional`) live here. |
| `features` | Optional features (backups, bills, budgets, net worth, review) and `FeatureService` | Each checks its switch first. May use `NamedParameterJdbcTemplate` for reporting SQL. |
| `mail`, `mail/parser` | IMAP sync, email processing, bank email parsers, `SmsParser`, SMS learning | One parser per bank and format, behind `BankEmailParser`. |
| `statement` | PDF text extraction and statement parsers, the review-then-import flow | One parser per bank and card, behind `StatementParser`. |
| `domain`, `repo` | JPA entities and Spring Data repositories | Entities only change through services. |
| `config` | Security, OIDC, sign-in logging, the ingest token filter, SPA routing, `AppProperties` | |

- **Reporting reads use SQL; writes use JPA.** Aggregates (budgets, review, net worth) are plain SQL through
  `NamedParameterJdbcTemplate`. Creating and changing data goes through entities and services, so the rules
  (balances, learning, splits) always apply.
- **Errors:** throw `IllegalArgumentException` (bad input) or `IllegalStateException` (not allowed right now,
  e.g. a feature that's off) with a message a user can read; `NoSuchElementException` gives a 404. The handler
  returns `{"error": "..."}` and the frontend shows it as is. So write messages for the user, not for logs.
- **Parsers are pure and tested.** A parser takes text and returns parsed rows, with no database or network.
  Every new bank format comes with a test using **made-up** sample text (`src/test`). Never commit real
  statements, real SMS or real account numbers.

### 3.3 Money, time and identity

- **Money is `BigDecimal`** in Java and `numeric(14,2)` in the database, rounded with `Money.r2` (half up).
  Never `double` for amounts. Amounts are stored **positive** with a `direction` (DEBIT/CREDIT). In JSON they're
  numbers, and the frontend formats them with `money()` / `money0()` from `format.ts`.
- **Time: stored in UTC, counted in your zone, banks read in India time.** Instants are stored as `timestamptz`
  (UTC). Calendar logic (days, months, years, "today", nightly jobs, what the screens show) uses `Money.ZONE`, from
  the `TZ` environment variable (default UTC). The JVM and every database session are set to it at startup, so SQL
  converts with `at time zone current_setting('TimeZone')`, never a hard-coded zone. The browser takes the same
  zone from `/api/health` (`format.ts`: `ymd`, `zonedInstant`, `formatDay`); never call `toLocaleDateString` without it.
  Anything an Indian bank or market defines uses `Money.INDIA` (`Asia/Kolkata`) whatever `TZ` is: times in alerts,
  SMS and statements, card billing and reward cycles, NSE hours, NAV dates.
  The Indian financial year runs from 1 April.
- **Transfers between your own accounts** are not income or spending. Every total excludes them. **Splits:**
  spending totals count only your share.
- **De-duplicate everything that arrives.** The same payment can come by email, by SMS and in a statement; match
  it to one transaction (fill in missing details) instead of creating three.

### 3.4 Data and migrations

- The schema changes only through **Flyway migrations** (`db/migration/V<n>__what.sql`). Never edit one that has
  been released (see `docs/versioning.md`). Prefer additive changes: new tables, nullable columns.
- **Small settings** (feature switches, the owner's name, the OIDC link, backup status) live in the `app_setting`
  key-value table through `SettingsService`, as namespaced keys like `feature.bills` or `backup.lastOk`. Use a
  real table once something has structure or many rows.
- **Files** (statement PDFs) live under `STORAGE_DIR` and are stored as **relative** paths, so moving the folder or
  the container doesn't break them.
- **Back up before destructive work.** Automatic backups produce one zip with `database.sql` and the files; any
  script or migration that rewrites data should tell the user to take one first.

### 3.5 Security

- **Single owner.** One account with role `OWNER`. Sign-in is by app password (bcrypt), OIDC (Pocket ID), or
  both. OIDC follows the standard: issuer discovery, authorization code with PKCE, identity = issuer + `sub`
  (never email). **Who may sign in is the provider's job** (restrict the client there); the app adds no allow-list
  of its own. The first OIDC sign-in must be **explicitly linked** by the owner (confirmed with the app password
  when password sign-in is on); after that only that identity is accepted.
- **Session cookie** (`HISAAB_SESSION`, HttpOnly, SameSite=Lax). **Secure is automatic, never a setting:** it
  follows whether the request was HTTPS, which behind a proxy comes from `X-Forwarded-Proto`, trusted only from
  private and Tailscale addresses (`forward-headers-strategy: native`). Machine clients get **narrow
  tokens**: the SMS token can only call `/api/ingest/sms`, and repeated bad tokens lock out for 15 minutes.
- **Log every sign-in**, success or failure, with the method, IP and browser (`com.hisaab.security`).
  Never log passwords, tokens, statement passwords or full account numbers.
- **Secrets come from the environment** (`.env`), never from code. Statement PDF passwords are typed in the UI and
  stored only if the user ticks *Remember*. `.env.example` lists every setting with a safe placeholder.
- The container runs as an **unprivileged user** (uid 10001); the entrypoint is root only long enough to fix
  folder ownership.

### 3.6 Adding an optional feature (checklist)

1. Add it to `FeatureService.Feature` (on by default unless it's risky).
2. Backend: call `features.require(Feature.X)` (or check `on`) at the top of every endpoint, and skip scheduled
   work when it's off.
3. Frontend: add the key to the `Feature` type in `api.ts` and a row in `FEATURES` in `Settings.tsx`; wrap the
   page in `<FeatureGate on={useFeature('x')} name="…">`; hide its menu, sidebar and Home entries when off; only
   enable its queries when it's on.
4. Add an `Added` line to `CHANGELOG.md`.

### 3.7 Frontend (`frontend/src`)

- **Server state lives in TanStack Query**, nowhere else. Read with `useQuery` and the `api()` helper; write with
  `useMoneyMutation`, which refreshes every query afterwards, because one change (a category) affects many screens
  (Home, Insights, budgets). Local UI state uses `useState`. There's no global store.
- **The URL is state.** Filters, the selected period and the year or financial year go in search params, so Back
  and sharing a link work (`/transactions?categoryId=3&min=500`).
- `api.ts` holds every response type, and they match the backend DTOs field for field. Change both together.
- Pages live in `pages/`, shared pieces in `components/`. Formatting goes through `format.ts`, never ad-hoc
  `toFixed`.
- Charts use Recharts with the colours from `useChartColors`, so light and dark both work.
- **Installable (PWA).** `sw.template.js` becomes `dist/sw.js` at build time (plugin in `vite.config.ts`). It keeps
  the app's files and the last JSON responses so the app opens offline, never keeps sign-in or downloads, and
  `clearSavedData()` (`pwa.ts`) forgets the data on sign-out. Saving needs a connection; there's no offline
  queue, which keeps money data from conflicting. New files in `public/` are picked up automatically.

### 3.8 Operations

- **Data lives in plain folders** next to `compose.yaml` (`./data/db`, `./data/files`, `./data/backups`), not in
  Docker named volumes, so the owner can see it, copy it and back it up with ordinary tools.
- **Configuration is environment variables only**, all listed in `.env.example`, all optional apart from the
  database password. `compose.yaml` shouldn't need editing.
- **Health:** `GET /api/health` returns `{status, version}` and is the container health check.
- **Release by git tag**; CI builds a multi-arch image to ghcr.io. See `docs/versioning.md`.
- Logs go to stdout (Docker collects them). The level is set with `LOG_LEVEL`.

## 4. Working on the code (people and agents)

- **Run it:** see `README.md` → *Run locally*. Backend tests: `cd backend && ./mvnw test`. Frontend check:
  `cd frontend && npm run build` (runs `tsc -b`). Both must pass, because CI runs the same.
- **Check UI changes on a phone-sized screen (≈390px) and on desktop, in light and dark mode.**
- **Test data is made up.** `backend/seed/seed_mock_data.py` fills a local database. Never put real names,
  account numbers, statements, emails, SMS or passwords in code, tests, fixtures, commits or issues.
- **Keep changes small and in the existing style.** Match what's nearby: naming, component use, comment tone.
  Comments explain *why*. Write user-facing text for a non-technical reader.
- **Don't commit or push unless asked**, and don't rewrite history. Record user-visible changes in
  `CHANGELOG.md` under *Unreleased*.
- When a change touches money maths (balances, splits, transfers, budgets, rewards), add or update a test and check
  the totals against the mock data before and after.
