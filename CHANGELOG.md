# Changelog

Every notable change, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and versions follow [Semantic Versioning](https://semver.org/). See [docs/versioning.md](docs/versioning.md) for
how to pick the next version and cut a release.

Write entries for the person running the app, not for developers: what they'll notice, and anything they
must do when upgrading (marked **Upgrade:**).

## [Unreleased]

### Changed
- Adding a transaction: the account, category and time each open a **dropdown list** instead of a row of chips.
  Accounts are grouped (cards, then bank and cash) with their last 4 digits and balance; categories can be
  searched. The account picked for you is now the one you use most for that kind of payment (your usual card
  for spending, your usual bank account for money in), not Cash.
- **Sharing a payment is one choice, "Shared"**, instead of three ("Someone else paid", "Split with friends",
  "Lent to someone"). Pick who paid (you or a friend) and who it's split between; equal shares unless you change
  the amounts. The people you share with most come first, your last group is one tap (and you can add or remove
  people after), and someone new can be added right there without leaving the form. The same picker splits a
  payment later from its details, and records what a friend paid from their page ("I paid" there is now
  "Split a payment"). A friend's payment can now be split between more than two of you.

## [0.15.0] - 2026-11-22

### Added
- **Axis Bank credit card** alert emails are now read ("Here's the summary of your Axis Bank Credit Card
  Transaction"): amount, merchant, card, date and time. Refunds and bill payments from the card aren't read yet and
  wait for review.
- **Axis Bank account credits** are now read: "your A/c no. XX… has been credited with INR … on … by …" (the
  account comes first in these, so they used to wait for review). An internal transfer's codes are kept as its
  reference instead of being shown as the payee.
- Settings → What it learned shows **which email senders are trusted**: each bank's own addresses (built in, always
  trusted) and the senders you trusted yourself, with the date and a way to stop trusting one (its later alerts
  wait for review again; what was already recorded stays).

### Fixed
- When something fails on the server, the message now carries a short **reference** (for example "ref 3fa91c")
  that finds the full error in the server log. A request the app couldn't read no longer shows the server's
  internal parser message. Background jobs (mail sync, price updates, statements from email) now log every
  failure instead of dropping some silently.
- A bank alert email whose date can't be read is still recorded, at the time the email arrived, instead of being
  marked as an error. An impossible date (31 Feb) is no longer quietly read as a different day, and 12-hour times
  ("4:44 PM") are read.
- Alert emails from a bank's own address are only read by that bank's parsers, in a fixed order, so one bank's
  email can't be mistaken for another's. Forwarded alerts are still read by all of them.

## [0.14.0] - 2026-11-21

### Changed
- Faster, especially as your history grows. Activity loads a page of transactions with 10 database queries instead
  of 175, Home with 28 instead of 46, and Splits with 4 instead of 13; the database has indexes for looking up a
  transaction's splits and repayments. The app also opens with about 40% less to download: other pages load the
  first time you open them.
- With Budgets or Backups switched off, removing a budget and the backup actions are refused too, like the rest
  of a switched-off feature.
- A tidier, more consistent look: text, corners and colours now come from one small set of sizes and colours, so
  the same kind of thing looks the same on every screen. Buttons dip when pressed while rows just highlight, lists
  ease in when a page opens, and windows open and close the same way on phones and computers.
- **Upgrade:** take a backup first; this release adds database indexes (no data changes), so going back means
  restoring it.

## [0.13.0] - 2026-11-21

### Added
- **Gifts**, a spending category for presents you buy ("Gift" was only for money coming in). Reset puts it back
  with the other built-in categories.
- **Lent to someone** in Add transaction: pick the person, and the payment is recorded as all theirs. It isn't
  counted as your spending, needs no category and shows as "Lent to …". A repayment you link to someone reads
  "Paid back by …" instead of "No category", and To check no longer asks for a category for either.
- Splitting a payment while adding it can be **by amounts or percent**, not only equally. The form shows each
  share as you type and won't save a split that adds up to more than the payment.

### Changed
- On phones, forms and details open as full-screen windows with Back and the title at the top, instead of
  sheets sliding up, and their main button sits at the bottom of the screen. Wider screens keep the centred dialog.

### Fixed
- Picking people for a split no longer shrinks the row or selects the name on a long press; the circle just
  fills in with a tick.
- Statements and the Reset card squeezed their text into a narrow column; their buttons now sit under the text.
  The Reset button reads just "Reset".
- The date and time fields in Add transaction ran off the screen on iPhone; they now share the row. On iPhone
  and iPad, dates use Safari's own date picker.
- **Upgrade:** take a backup first; this release changes the database (it adds the Gifts category), so going
  back means restoring it.

## [0.12.0] - 2026-11-21

### Added
- **Reset** in Settings → Import & backups. Choose what to clear: money data (accounts, transactions, people,
  statements, investments, budgets, saved messages), what the app learned, categories (the built-in ones come
  back), settings, or connected apps (API tokens and the Pocket ID link). Each choice says what it would remove,
  the next step warns that it can't be undone, and you type "reset" to confirm. A backup is taken first, and if it
  fails nothing is reset. Your sign-in, your backups and how far mail reading has got are always kept, so old
  emails aren't imported again (Import older emails still brings them back).

## [0.11.0] - 2026-11-16

### Changed
- Home has one **To check** card instead of a list of links: a balance that's off, a repayment to confirm, a
  payment with no category, a message the app couldn't read, something that looks wrong. Each card asks one
  question with one-tap answers ("Yes / No", "Use the bank's balance / Ignore", the categories you use most);
  "Later" puts it at the back. When nothing needs you it says "All caught up".
- Inbox is now just the history of bank emails and SMS; mismatches and messages to review moved to To check.
- A transaction shows only the amount, payee, account, date and category. A set category is one row with
  "Change"; a guessed one asks "Right?". Splitting, marking a transfer, expecting a refund, linking a repayment,
  notes, tags, fixing what an alert read, the original message and Delete are under **More**. Anything already
  set (a split, a refund you're waiting for, a linked repayment, someone else paid) still shows up front.
- The menu is down to six places: Accounts & cards, Insights, Repeating, Budgets, Statements, Settings.
  **Repeating** is Bills and Subscriptions on one page (the old addresses still work). Tap the net worth number
  on Home for Net worth; rewards are a link under each credit card; Year in review is a row at the top of
  Insights (and a card on Home in December and January); unusual payments are To check cards; Categories &
  rules and Messages (the old Inbox) are in Settings. Pages opened from inside another one (Messages,
  Categories, Rewards, Net worth, a statement import) have a link back to it on every screen size.
- Adding a transaction is amount and where; the app fills in the account, category and time as one line of
  guesses you tap only to change. "Someone else paid", "Split with friends" (equally, you included), a note and
  "Between my accounts" are chips under **Also**.
- Splits: each person is one line ("owes you ₹300", "you owe ₹200", "all square") with a **Settle up** button.
  Settle up says where you stand, picks the money that most likely settled it (their name, or exactly the
  amount due) and offers one button; the amount, another payment, cash, and the direction are there to change.
  Repayments to confirm moved to To check.
- Plainer words throughout: "Between my accounts" instead of transfer, "You owe" instead of outstanding, "No
  category" instead of uncategorised, how a message is "worded" instead of its format, and messages are
  "recorded", "needs you", "couldn't read" or "skipped". Settings → Teaching is now **What it learned**. Notes on
  messages the app couldn't read say what to do instead of naming a parser.
- Home answers "am I okay?": one number, **You have** (cash in your accounts less what you owe on cards), with
  this month's spending and your net worth as small links under it, then To check, then Recent. Upcoming bills,
  This month, budgets and accounts moved off Home (Repeating, Insights, Budgets, Accounts & cards); a card due
  within 3 days is a To check card. "Show investments on Home" now applies to the net worth link.
- Settings → Features has four switches instead of ten: Budgets, Rewards, Automatic backups and the Data API.
  Bills, subscriptions, unusual activity, net worth, year in review and the extra Activity filters only watch
  your own data, so they're always on now. **Upgrade:** if you had turned any of those off, they come back on.
- Adding an account is the bank (one tap) and today's balance. The name is optional ("HDFC card", "Axis
  savings" when left empty). Last 4 digits, credit limit, statement and due days, and "as of" are under **More
  details**. Left empty, the last 4 digits are learned from the bank's first alert, when it's your only account
  of that kind at that bank.
- Sheets grow smoothly when you open extra fields in them: More details on an account, More on a transaction,
  the original message, and changing the amount or payment when settling up.
- Activity is a search box and one **Filter** button. The filter sheet has everything (money in or out,
  account, category or no category, when, amount, sort, and including money moved between your accounts);
  below the search box only what's set shows, each a chip you tap to remove, with Clear all.

### Fixed
- Deleting a transaction could do nothing: the browser's "Delete this transaction?" pop-up can be blocked
  (Safari, the installed app). It now asks in place, with any error shown right there. So does everything else
  that asked with a pop-up: deleting a person or category, removing a split payment or holding, discarding an
  import, "This isn't a transaction", forgetting a format or payee name, revoking an API token, unlinking a
  sign-in account and stopping rewards tracking.

## [0.10.0] - 2026-11-16

### Security
- Password sign-in is limited: after 5 wrong passwords in 15 minutes, that client is locked out for 15 minutes
  (also when linking a sign-in provider account). A right password clears the count. Lockouts are logged.
- Changes (anything but reading) are refused unless they come from the app's own pages. Before, a page on another
  of your subdomains (the same "site" to a browser) could post a form with your session. Refusals are logged.
  Scripts and API tokens aren't affected.
- Signing out only works from the app (a POST), so a link elsewhere can't sign you out.

### Added
- More useful logs, with ids and counts only, never amounts, payees or message text:
  - an audit log (`docker compose logs app | grep audit`): features switched, owner name changed, backups
    downloaded or started, accounts, categories, rules, people, transactions, holdings and repayments deleted,
    balances adjusted, learned formats and payee names forgotten;
  - one line at startup with the version, time zone and how sign-in, mail and features are set up;
  - each email and SMS and what became of it (recorded, needs review, duplicate, matched to the other channel's
    alert); statements read and imported, or why not; balance mismatches; holdings CSV imports;
  - what the app learned: formats taught, merged, refused, learned automatically or paused; categories learned and
    applied to earlier transactions; transfers linked; repayments suggested, confirmed or turned down.

### Fixed
- An unexpected error on a screen now shows a plain message instead of "Internal Server Error", and the server
  log records it with the request.
- Uploading a file that isn't a PDF as a statement said "Something went wrong"; it now says the file isn't a
  readable PDF.
- On Splits, a person's progress bar (and the "settled" total at the top) now counts what they paid for you, so
  squaring off with "Someone else paid" fills the bar instead of leaving it empty.

## [0.9.0] - 2026-11-16

### Added
- **Someone else paid.** When a friend pays for both of you, record your share: in the add sheet pick
  **Paid from → Someone else**, or tap **"Rahul paid"** on their page. Choose half, all of it or an exact share.
  Your share counts in Insights and budgets, no account of yours is touched, and it comes off what they owe you.
  Nothing is learned from it (categories, payee names), so it never affects how bank alerts are sorted.
- Balances with people now go both ways: "Rahul owes you ₹300" or "You owe Rahul ₹150", and you can record paying
  someone back. A person's page shows one history of what each of you paid and paid back, with **I paid** and
  **Rahul paid** buttons. What you owe people is taken off your net worth.
- **Repayments you can link yourself.** Open money that came in and tap **"Repayment from someone?"** (or
  **"Paying someone back?"** on money you sent, when you owe someone): pick the person, with those who owe you
  first, and check the amount. On a person's page, **record a payment** by picking the money it came in, or
  **Cash or another account**. Linked money no longer counts as income (or spending).
- When you link a payment from a sender whose name doesn't match the person (e.g. "MUHAMMED FAZ" for Fazil), that
  name is remembered, so their next payment is matched by name whatever the amount.
- Repayments are looked for again when a balance changes: after a split, a "they paid" entry or a person's UPI name
  is changed, recent money nobody was linked to is checked, so money that arrived before you recorded the split is
  still matched. Money you send is suggested as paying someone back when you owe them. Suggestions also show on the
  person's page, with Yes / No.
- **Upgrade:** take a backup first; this release changes the database, so going back means restoring it.

## [0.8.1] - 2026-11-13

### Changed
- Clearer logs. Every failed Data API request is logged at warn with the reason the app was given (a request
  without a token and a hit rate limit too), and a server failure at error with its stack trace. Creating,
  changing and revoking API tokens is logged. A failed backup is logged at error. Saving or reading a statement
  PDF that fails, and requests refused by a database constraint, are logged with the real cause.

### Fixed
- A Data API request that failed on the server was logged as successful, and its answer now is a problem detail
  like the API's other errors.

## [0.8.0] - 2026-11-13

### Added
- Data API: `updated_since` on `GET /api/v1/transactions` returns transactions created or changed since a time,
  including edits to old ones, so an app can poll for what's new or changed. Transactions now carry `created_at` and
  `updated_at`. **Upgrade:** take a backup first; this release adds a database column, so going back to 0.7.0
  means restoring it.

## [0.7.0] - 2026-11-13

### Added
- API tokens, in Settings → Security. Each token has a name, does one job, and stays within the limits you set:
  - **Send SMS**: what the SMS token did. Each phone can now have its own, and you can revoke one without the other.
  - **Read transactions**: payee, category, amount, account and time, for a day or since a day. Limit it to some
    categories (e.g. only Fuel, for a mileage app), some accounts, and money out only.
  - **Add transactions**: e.g. a cash spend from a Siri shortcut. It shows as "Added by" the token's name.
  - **Read balances**: account balances and card dues, e.g. for a widget.
  - **Read totals**: totals by category, account, month or day, counted like the app's own screens.
- Data API (`/api/v1`), described in docs/api.md. It has a switch in Settings → Features and starts off.

### Changed
- **Upgrade:** SMS forwarding moved to `POST /api/v1/sms`, and the old single SMS token is gone. Create a *Send SMS*
  token in Settings → Mail & SMS, then update your phone's shortcut with the new URL and token. The body is JSON
  (`text`, `sender`, `received_at`); the old `X-Api-Key` header, plain-text bodies and other field names aren't
  accepted any more. Take a backup first: this release adds database tables and columns, so going back to 0.6.0
  means restoring that backup.
- Paste SMS in the Inbox works as before.

## [0.6.0] - 2026-11-12

### Added
- Pick a category while teaching a message, instead of opening the transaction again afterwards.
- Payments whose message has no payee name (an ATM withdrawal, interest, a bank charge) now learn their category
  too. Choose one once, while teaching or on the transaction, and later messages worded the same way get it. A
  payment with a payee is still categorised by its payee.

## [0.5.0] - 2026-11-12

### Changed
- Settings is split into sections (General, Features, Mail & SMS, Teaching, Security, Import & backups), each
  on its own page. On a wide screen the list of sections stays on the left. Sign out is also under Security.
- The refresh button on Invest spins while prices are updating.

## [0.4.0] - 2026-11-11

### Added
- Teaching has a safety net: every message you correct is kept with its right answer, and a newly learned format
  is refused if it would make any of them read differently. You're told when that happens.
- Teach a message by tapping it: tap the amount, tap the first and last word of the payee, pick the account. The
  payee is then read from every later message in that format, and you can show it under your own name ("Swiggy
  Instamart" for "SWIGGY*INSTAMART BLR").
- Bank emails can be taught too, the same way as SMS: open one in the Inbox and choose **Tell me what this is**
  (or **This was read wrong**). Its format is learned and its sender's domain is trusted from then on. Formats you
  teach for emails are used before the built-in bank readers, so a correction always wins.
- **Not a transaction**: for an offer, payment request or anything else that isn't money moving. The message is
  ignored, and later ones worded the same are skipped automatically (waiting ones too). It replaces Ignore in the
  Inbox; a recorded message can be marked too, which removes its transaction.
- A learned format that keeps reading messages wrong stops being used: after two corrections it's paused and its
  messages wait in Needs review. Settings → What it has learned shows how often each format was corrected, and
  paused ones can be resumed.
- The same kind of message in slightly different wording (a new footer, an extra word) is merged into one
  learned format instead of a near-copy, so wording it hasn't seen yet is read too. A merge is kept only if every
  message you taught still reads the same.
- Formats are also learned without you: when an SMS is read by the general rules and the reading is confirmed (the
  balance it prints adds up, or the alert email for the same payment agrees), its wording is learned, so the next
  one is read exactly. These show as "learned automatically" in Settings and are removed the first time one is
  corrected; they can never override something you taught.
- A transaction from an alert that was read wrong can be fixed from Activity: open it and choose **Wrong amount,
  direction or account? Fix it**. It's the same tap-to-mark form, the transaction is corrected in place (its
  category, notes, tags and split stay), and the message's format is learned.

- Categories carry over better:
  - a payee whose name mostly matches one you've filed gets the same category ("KAVERI PROVISIONS" after
    "KAVERI PROVISIONS STORE"); payments to people (UPI P2P) still need the exact name;
  - the same UPI ID gets the same category whatever name the bank prints;
  - each payee keeps a count per category, so the majority wins and one odd purchase doesn't change the rest;
  - when you use two categories about equally for a payee, the amount picks the one you use for amounts like it.
- A category the app picked shows as "Suggested from your earlier choices"; tap it to confirm.

### Fixed
- Correcting a message that had already been recorded no longer replaces its transaction, so a category, notes,
  tags or split you'd added are kept.

### Changed
- When two learned formats fit a message, the one you taught most recently is used.

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
