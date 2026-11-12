<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/brand/hisaab-lockup-dark.svg">
  <img src="docs/brand/hisaab-lockup-light.svg" alt="Hisaab" height="72">
</picture>

# Hisaab

*Hisaab* (हिसाब) is Hindi/Urdu for "accounts", as in *hisaab-kitaab*, keeping track of money. The logo is a copper "hole pice", the Indian one-pice coin of the 1940s with a hole through the middle, on midnight blue.

Personal finance tracker that reads bank alert emails (forwarded to a dedicated mailbox), logs
transactions, tracks balances, credit card utilisation and billing cycles, categorises spending,
splits bills with people, and tracks Indian stock / mutual fund investments.

- **Backend:** Java 25, Spring Boot 4.1, PostgreSQL, Flyway (`backend/`)
- **Frontend:** React + TypeScript + Vite + Tailwind, mobile-first (`frontend/`)

## Run locally

Prerequisites: JDK 25, Node 20+, PostgreSQL running locally with a `hisaab` database
(`createdb hisaab`).

**1. Backend** (port 8080):

```sh
cd backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@25   # if JDK 25 isn't your default
APP_PASSWORD=choose-a-password ./mvnw spring-boot:run
```

**2. Frontend dev server** (port 5173, proxies `/api` to 8080):

```sh
cd frontend
npm install   # first time only
npm run dev
```

Open http://localhost:5173 and sign in as `admin` with the password you chose.
On your phone (same Wi-Fi), use the "Network" URL Vite prints, e.g. `http://192.168.x.x:5173`.

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `APP_USERNAME` / `APP_PASSWORD` | `admin` / — | Login. Password is required (plain or `{bcrypt}…`). |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | local `hisaab`, current OS user | PostgreSQL |
| `MAIL_HOST` | — | `imap.gmail.com` or `imap.mail.me.com` |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | — | Mailbox address and **app-specific password** |
| `MAIL_FOLDER` | `INBOX` | Folder to read |
| `MAIL_POLL_INTERVAL` | `PT2M` | How often to check mail |
| `STORAGE_DIR` | `./data` | Where statement PDFs are saved |
| `PRICES_ENABLED` | `true` | Fetch stock prices / AMFI NAVs |

### Mailbox setup

Create a dedicated mailbox, then add a rule in your main inbox that auto-forwards bank alert emails to it.
- **Gmail:** enable 2-Step Verification → create an App Password → `MAIL_HOST=imap.gmail.com`.
- **iCloud:** appleid.apple.com → App-Specific Passwords → `MAIL_HOST=imap.mail.me.com`.

The first sync only bookmarks the mailbox; from then on every new email is processed once (read-only, nothing is deleted).
Older mail can be pulled in from Settings → Import & backups → Import older emails.

## Supported bank emails

| Bank | Formats | Status |
|---|---|---|
| HDFC credit card | "Rs. X has been debited from your HDFC Bank Credit Card ending NNNN towards …" | verified with a real sample |
| Canara Bank account | "An amount of INR X has been DEBITED/CREDITED on dd/mm/yy … account XXXXNNNN …" | verified (debit); credit inferred |
| Axis Bank account | table: Amount Debited/Credited, Account Number, Date & Time, Transaction Info | verified (debit); credit inferred |
| Axis credit card, SBI card | — | not yet: these land in Inbox → Needs review |

Unrecognised emails are kept and can be re-processed once a parser is added.

## Tests

```sh
cd backend && ./mvnw test
```

## Bank SMS

For alerts that only arrive as text messages, generate an API token in **Settings → Mail & SMS** and have the phone POST each bank SMS:

```
curl -X POST https://<host>/api/ingest/sms \
  -H 'Authorization: Bearer hisaab_…' -H 'Content-Type: application/json' \
  -d '{"sender":"AX-HDFCBK","text":"Sent Rs.500.00 From HDFC Bank A/C *2286 To RAHUL On 05/11/26 Ref 531234567890"}'
```

- Body: `text` (required), `sender` (optional, helps recognise the bank), `receivedAt` (optional, ISO-8601 or epoch). A `text/plain` body with `?sender=` also works.
- Response: `status` (PARSED, DUPLICATE, NEEDS_REVIEW, IGNORED, ERROR), a one-line `message`, and the recorded `transaction` / `parsed` fields.
- Security: the token is stored as a SHA-256 hash, works only on this endpoint, and creates no session. Requests are capped at 30/min per client, and 10 bad tokens lock that client out for 15 minutes. Revoke or rotate it in Settings.
- The same SMS sent twice is ignored, and a payment that also arrives by email is recorded once (same account, amount and direction within 20 minutes, or the same day when the alert has no time).
- SMS can also be pasted by hand: **Inbox → Paste SMS**.

### How the SMS parser learns

No AI is involved; it learns from evidence.

- **Formats.** Open an SMS in the Inbox and choose *Tell me what this is* (or *This was read wrong*). Your four answers (in/out, amount, account, payee) turn the message's wording into a template; the next SMS worded the same way is read exactly. Everything still waiting for review is retried straight away.
- **Accounts.** If the digits in an SMS aren't an account's last four (or there are none), the account you pick is remembered for that bank.
- **Payee names.** Rename a payee on a transaction and every earlier and future alert with the same raw name follows.
- **Self-correction.** When an SMS prints the balance, the arithmetic decides the direction: if the balance only fits the opposite direction, it is flipped and the message is annotated.
- **Senders.** Sender ids of parsed messages are remembered, so a text that doesn't name its bank is still attributed.

Review or remove anything learned under **Settings → Teaching**.

## Statement import

Supported: Canara Bank account (e-Pass Sheet), Axis Bank savings account, Axis Bank credit card, HDFC Bank credit card.

- **Upload** a PDF under *Menu → Import a statement* (or *Statements → Import*), with its password if it has one. Tick *Remember* and that account's statements open without asking next time.
- **By email:** statement emails with a PDF are saved under *Statements* and read automatically with the remembered passwords. If none opens it, the statement shows *Unlock* and Home says it needs a password. Once one is unlocked and imported, other locked statements are retried.
- **Review first.** Nothing is saved until you confirm. Each line is one of:
  - *Already recorded*: matched by reference number, or by amount plus time (or date). The import fills in what the alert lacked: the payee, the real time, the reference, or the exact rupee amount of a foreign spend.
  - *New*: added (you can untick any). Lines before the account's starting-balance date are history only and don't change the balance.
  - *Needs your answer*: same amount but days apart or a different name. Choose *Same one*, *Different, add it* or *Skip*. Answers are remembered if the statement is imported again.
- **Self-checks.** Each parser compares the rows it read with the totals printed on the statement and warns if they don't add up. A balance check compares the statement's closing figure with the app's, and can correct the starting balance if that was the cause.
- **Dates and times.** Times are used when the statement has them: Canara's are inside the narration, HDFC's are next to the date. HDFC's `00:00` means no time. Date-only lines are recorded at noon and marked as such, so a later alert or statement can correct the time.

## Self-hosting with Docker

Everything is configured in `.env`; `compose.yaml` never needs editing.

```sh
cp .env.example .env      # then set APP_PASSWORD, POSTGRES_PASSWORD, APP_BIND, APP_IMAGE and APP_VERSION
docker compose pull && docker compose up -d     # the published image from GitHub Actions
# or build it here instead:  docker compose up -d --build
```

This runs two containers: `app` (the web app and API on port 8080 inside the container) and `db` (PostgreSQL). Everything is stored in a `data` folder next to `compose.yaml`: `data/db` (the database), `data/files` (statement PDFs) and `data/backups` (automatic backups; `BACKUP_PATH` can move them to another disk). Copy or sync that folder to keep everything. Copy `data/db` only while the stack is stopped; at any other time use the backup zips. `data/` is ignored by git and by the Docker build.


**HTTPS and cookies.** Nothing to set: the app trusts `X-Forwarded-Proto` from proxies on private or Tailscale addresses (Tomcat's standard list), so behind Caddy it knows requests are HTTPS, marks its session cookie `Secure` and builds `https://` sign-in callback URLs. Over plain `http://` on your network it uses a normal cookie, so signing in works there too. If your proxy sits on another address, set `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` (a regex of trusted IPs).

**Reaching it from Caddy on the VPS over Tailscale.** Set `APP_BIND` in `.env` to this server's Tailscale IP (`tailscale ip -4`) and, on the VPS:

```
money.example.com {
    reverse_proxy 100.x.y.z:8080
}
```

**Moving existing data in** (from a local PostgreSQL and `backend/data`), before the app's first start:

```sh
docker compose up -d db
pg_dump --no-owner --no-acl hisaab | docker compose exec -T db psql -U hisaab -d hisaab
mkdir -p data/files && cp -R backend/data/. data/files/
docker compose up -d --build     # the app fixes the folder's ownership when it starts
```

(Use the `POSTGRES_USER`/`POSTGRES_DB` from your `.env` if you changed them.)

**Upgrading:** take a backup (Settings → Import & backups → *Back up now*), set the new `APP_VERSION` in `.env`, then `docker compose pull && docker compose up -d`. Database migrations run automatically on start. Versions, image tags and rollback are explained in [docs/versioning.md](docs/versioning.md); what changed in each version is in [CHANGELOG.md](CHANGELOG.md).

**Backups** run every night at 3:30 (`BACKUP_CRON`) while *Automatic backups* is on in Settings → Features. Each one is a single zip with the whole database (`database.sql`) and the saved statements, plus a `RESTORE.txt` with the steps to restore it. The newest `BACKUP_KEEP` (14) are kept. Settings shows the last backup, lets you back up now and download one, and Home warns when backups fail or are more than two days old. Set `BACKUP_PATH` to a host folder (e.g. on a NAS) and copy it somewhere else too: a backup on the same disk doesn't protect against that disk failing.

**Logs:** `docker compose logs -f app`. **Health:** `curl http://<APP_BIND>:<APP_PORT>/api/health`.

## Install it on your phone

Open the app's address in the phone's browser and sign in.
- **iPhone / iPad (Safari):** tap Share, then *Add to Home Screen*.
- **Android (Chrome):** Settings → *General* → *Install* (or the browser menu → *Install app*).

The installed app opens full screen, starts instantly, and when there's no connection it shows what it last loaded (with an "Offline" note; changes need a connection). Long-press the icon for *Add transaction* and *Activity*. On Android it also appears in the share menu: share a bank SMS to Money and it opens in *Paste SMS*. Data saved for offline use is cleared when you sign out. Installing needs HTTPS (the Caddy setup above provides it).

## Releases and the Docker image

Pushing a version tag (`v1.2.0`) makes GitHub Actions test the app, build the image for amd64 and arm64, publish it as `ghcr.io/<owner>/hisaab:1.2.0` (plus `1.2`, `1` and `latest`), and create a GitHub Release from `CHANGELOG.md`. Every commit on `main` publishes `:edge`. Cut a release with `./scripts/release.sh 1.2.0`. The full guide, including how to choose the version number, is [docs/versioning.md](docs/versioning.md).

## Contributing (people and coding agents)

[docs/principles.md](docs/principles.md) describes the design and architecture principles to follow. `AGENTS.md` and `CLAUDE.md` point agents to it.

## Sign in with Pocket ID (OpenID Connect)

Standard OpenID Connect: three values, and the provider decides who may sign in.

1. In Pocket ID, add an OIDC client (e.g. "Hisaab") with callback URL `https://<this app's address>/login/oauth2/code/oidc`, and restrict it to your user or group (*Allowed user groups*). That's what decides who can sign in.
2. In `.env`, set `OIDC_ISSUER_URI` (your Pocket ID address, e.g. `https://id.example.com`; everything else is discovered from its `/.well-known/openid-configuration`), `OIDC_CLIENT_ID` and `OIDC_CLIENT_SECRET`.
3. `docker compose up -d`.

The app uses the authorization-code flow with PKCE, asks for the `openid profile email` scopes, and identifies you by the provider's issuer and subject (`sub`), the identifier OpenID Connect guarantees never changes. Email is only shown, never trusted as identity.

The sign-in page goes straight to Pocket ID (`OIDC_AUTO_REDIRECT=true`). It doesn't after you sign out or when a sign-in fails, and `https://<app>/?password` shows the password form anyway. Set `PASSWORD_LOGIN=false` to hide password sign-in. That only takes effect while OIDC is configured, so you can't lock yourself out, and `APP_PASSWORD` can then be left empty. The SMS forwarding token is unaffected.

**Linking.** The first time you sign in with Pocket ID, the app asks whether to link that Pocket ID account to your Hisaab account, and asks for the app password to confirm. After that, only the linked Pocket ID account (same issuer and subject) can sign in; any other account is turned away until you unlink in Settings → Security. With `PASSWORD_LOGIN=false` there's no password to check, so the first account Pocket ID lets through is the one that gets linked: restrict the client in Pocket ID before turning password sign-in off.

Pocket ID is looked up the first time someone signs in, so the app starts even if Pocket ID is down. Sign-ins, failures and sign-outs are logged under `com.hisaab.security`.

## Mock data (local development)

Start from an empty database and load about 3½ months of made-up data (5 accounts, ~180 transactions, splits with three people, stocks and a mutual fund):

```sh
pg_dump hisaab > backups/hisaab-$(date +%F).sql   # keep a copy first
# stop the backend, then:
dropdb hisaab && createdb hisaab
# start the backend (migrations recreate the tables), then:
python3 backend/seed/seed_mock_data.py            # optional args: base-url username password
```

The script refuses to run if accounts already exist, so it never mixes mock data into real data. To restore a backup: stop the backend, run `dropdb hisaab && createdb hisaab && psql hisaab < backups/<file>.sql`, then start it again.
