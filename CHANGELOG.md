# Changelog

Every notable change, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and versions follow [Semantic Versioning](https://semver.org/). See [docs/versioning.md](docs/versioning.md) for
how to pick the next version and cut a release.

Write entries for the person running the app, not for developers: what they'll notice, and anything they
must do when upgrading (marked **Upgrade:**).

## [Unreleased]

## [0.3.0] - 2026-11-11

### Fixed
- Two payments of the same amount close together (two ₹20 teas) are no longer recorded as one. Each alert email
  is matched with at most one SMS, and the reverse, and alerts with different reference numbers are never treated
  as the same payment.

### Security
- An alert email is recorded only when it comes from one of its bank's own domains (including the new `.bank.in`
  ones) and your mail provider didn't flag it as forged (DMARC/DKIM in its `Authentication-Results` header).
  Anything else waits in Inbox → Needs review with the reason. Bank statements in emails flagged as forged aren't
  saved.
- If your bank sends alerts from a domain the app doesn't know, open one in Needs review and choose
  **Trust <domain>**. The held emails from it are recorded then.

## [0.2.0] - 2026-11-11

### Added
- A calendar for every date in the app (transaction and payment dates, account "as of", refund due dates, points
  balance date, Activity's date range, importing older emails): a sheet on phones, a dropdown on larger screens,
  with a year view for going far back. Importing older emails also has shortcuts for 1, 3, 6 and 12 months ago.
- Importing older emails shows a progress bar (emails looked at, how many were new) and can be stopped.

### Fixed
- Importing older emails runs on the server: leaving Settings, or reloading, no longer resets the button while
  the import is still going.
- Importing older emails (and the regular mail check) fetches messages in batches, so large imports finish much
  faster.

## [0.1.0] - 2026-11-11

First release, for trying the app in production.

### Added
- Transactions from bank alert emails (IMAP: Gmail, iCloud) and from forwarded SMS (`POST /api/ingest/sms` with
  an API token). The SMS parser learns new message formats from your corrections.
- Accounts and credit cards with limits, billing cycles and due dates; balance-mismatch alerts.
- Categories that learn from manual corrections; tags; transfers between your own accounts; manual entries.
- Split payments with people, with repayments you confirm.
- Investments: Indian stocks and mutual funds with live prices.
- Statement PDF import (Canara, Axis bank, Axis MY Zone card, HDFC Swiggy card) with review before import,
  filling details into matching transactions; statements that arrive by email are read automatically.
- Insights by day, week and month.
- Optional features, each with a switch in Settings → Features:
  - Automatic nightly backups (database + statements in one zip), downloadable from Settings.
  - Upcoming bills, learned from payments that repeat.
  - Monthly budgets per category.
  - Net worth over time.
  - Year in review.
  - Advanced search in Activity (date range, amount range, sort).
  - Subscription watch: fixed-price payments that repeat, with alerts when a price goes up, a charge comes
    twice, a new one starts (often a free trial turning paid) or one stops.
  - Unusual activity: double charges, amounts far above your usual at a merchant, large first payments to
    new payees, foreign-currency and late-night spends. Mark any payment "I'm expecting a refund" and you're
    warned if the money hasn't come back by the date.
  - Rewards: describe each card's cashback or points rules (special rates, caps per cycle) and see what each
    billing cycle should earn, with refunds taking rewards back and cashback credits tracked.
- Install it as an app (PWA): home-screen icon, full screen, opens offline with the last-loaded data,
  shortcuts for Add transaction and Activity, and on Android, share an SMS straight into the app.
- Sign-in with a password and/or Pocket ID (or any OpenID Connect provider): standard discovery from the issuer
  URL, authorization code with PKCE, identity by issuer and subject. The provider decides who may sign in; the
  first sign-in asks before linking that account. Password sign-in can be hidden and the sign-in page can go
  straight to Pocket ID.
- The session cookie is marked Secure automatically on HTTPS (forwarded headers are trusted from private and
  Tailscale addresses), so the app works both behind Caddy and over plain `http://` on your network.
- Sign-in logging.
- Your time zone is a setting (`TZ`, default UTC): it decides where days, months and years begin. Times are
  stored in UTC; bank alerts, statements and card billing cycles are always read in India time.
- Docker image published to ghcr.io for every release; `compose.yaml` pulls it by version.
