# Hisaab API

Other apps and shortcuts can use Hisaab with an **API token**. Each token does one job, within limits you choose.
Create them in **Settings → Security → API tokens**. The token is shown once; only a hash is stored.

| Job | Endpoints | Limits you can set |
|---|---|---|
| Send SMS | `POST /api/v1/sms` | — |
| Read transactions | `GET /api/v1/transactions`, `GET /api/v1/transactions/{id}`, `GET /api/v1/categories`, `GET /api/v1/accounts` | categories, accounts, money out only or in and out, include notes and tags |
| Add transactions | `POST /api/v1/transactions`, `GET /api/v1/categories`, `GET /api/v1/accounts` | accounts it may add to, categories it may use, money out only or in and out |
| Read balances | `GET /api/v1/balances`, `GET /api/v1/accounts` | accounts |
| Read totals | `GET /api/v1/summary`, `GET /api/v1/categories`, `GET /api/v1/accounts` | categories, accounts, money out only or in and out |

Everything except Send SMS also needs **Settings → Features → Data API** switched on (it's off by default).
Send SMS always works, as SMS forwarding is part of the app itself.

Nothing in the API can edit or delete a transaction, or change settings.

## Conventions

- **Authentication:** `Authorization: Bearer <token>` ([RFC 6750](https://www.rfc-editor.org/rfc/rfc6750)).
- **Errors:** problem details, `Content-Type: application/problem+json`
  ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)). There's no `type` member, which means `about:blank`.
- **Times:** [RFC 3339](https://www.rfc-editor.org/rfc/rfc3339), with the offset of the server's time zone (`TZ`),
  e.g. `2026-11-12T13:44:00+05:30`.
- **Days** (`date`, `since`, `from`, `to`, `month`) are calendar days in that same time zone.
- **Money:** `{"value": "2500.00", "currency": "INR"}`. The value is a decimal string, so it isn't rounded by
  floating point; the currency is an [ISO 4217](https://www.iso.org/iso-4217-currency-codes.html) code.
- **Ids** are strings. Names are `snake_case`. Every key is always present; empty ones are `null`.
- **Paging:** `has_more` and `next_cursor` in the body, and the next page in a `Link: <…>; rel="next"` header
  ([RFC 8288](https://www.rfc-editor.org/rfc/rfc8288)).
- **Limits:** 60 requests a minute per client. 10 wrong tokens in 15 minutes lock the client out for 15 minutes.
  Both answer `429` with `Retry-After` ([RFC 6585](https://www.rfc-editor.org/rfc/rfc6585)).
- Transfers between your own accounts are never included.

## Send SMS

### `POST /api/v1/sms`

```sh
curl -X POST https://money.example.com/api/v1/sms \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sender": "AX-HDFCBK", "text": "Sent Rs.500.00 From HDFC Bank A/C *2286 To RAHUL On 05/11/26 Ref 531234567890"}'
```

| Field | |
|---|---|
| `text` | required: the SMS, up to 2000 characters |
| `sender` | the sender id, if known; it helps recognise the bank |
| `received_at` | RFC 3339; left out, now |

```json
{
  "status": "parsed",
  "message": "₹500.00 spent · RAHUL · HDFC Savings",
  "transaction": { "id": "813", "direction": "debit", "…": "same shape as below" }
}
```

`status` is `parsed`, `duplicate` (this SMS was received before), `needs_review` (it's waiting in the Inbox),
`ignored` (not a transaction) or `error`. `message` is one line, e.g. for a phone notification. `transaction` is
what was recorded, or `null`. The same SMS sent twice is counted once, and a payment that also arrives by email is
recorded once.

## Read transactions

### `GET /api/v1/transactions`

| Parameter | |
|---|---|
| `date` | `2026-11-12`: transactions on that day |
| `since` | `2026-11-01`: from that day until now |
| `updated_since` | `2026-11-13T08:30:00Z` (RFC 3339): created or changed at or after that time |
| `category` | optional: a category id or name (`fuel`), or `uncategorised` |
| `account` | optional: an account id |
| `limit` | 1–200, default 50 |
| `cursor` | `next_cursor` from the previous page (send the same other parameters) |

Send `date` or `since`, not both. With neither (and no `updated_since`) you get the last 30 days. Oldest first.

**Polling for changes.** With `updated_since`, the list holds transactions created or changed at or after that
time, in the order they changed, with no 30-day window, so an edit to an old transaction comes through too. It
combines with `date`, `since`, `category` and `account`. To poll, send the newest `updated_at` you've seen as
the next `updated_since`. The time is inclusive and `updated_at` is to the second, so you can get back
transactions you already have: match them by `id`.

A change is: anything about the transaction (amount, payee, category, notes, account, time…), or adding or removing
a tag. Renaming its category or account doesn't count. A transaction that's deleted, or that leaves the token's
limits (e.g. moved out of Fuel), just stops appearing; the API doesn't say it's gone.

```sh
curl -H "Authorization: Bearer $TOKEN" "https://money.example.com/api/v1/transactions?since=2026-11-01&category=fuel"
```

```json
{
  "data": [
    {
      "id": "812",
      "direction": "debit",
      "occurred_at": "2026-11-12T13:44:00+05:30",
      "amount": { "value": "2500.00", "currency": "INR" },
      "original_amount": null,
      "payee": "Indian Oil, MG Road",
      "category": { "id": "5", "name": "Fuel" },
      "account": { "id": "3", "name": "HDFC card", "type": "credit_card", "last4": "4321" },
      "notes": "Full tank",
      "tags": ["car"],
      "created_at": "2026-11-12T13:45:10+05:30",
      "updated_at": "2026-11-12T18:02:41+05:30"
    }
  ],
  "has_more": false,
  "next_cursor": null
}
```

- `direction`: `debit` (money out) or `credit` (money in).
- `amount`: the whole payment, as on the bank statement, even if you split it with someone.
- `original_amount`: what was charged in a foreign currency, when it was; otherwise `null`.
- `account.type`: `bank`, `credit_card` or `cash`.
- `notes` and `tags` are `null` and `[]` when the token was made without them.
- `created_at`: when Hisaab recorded it; `updated_at`: when it last changed.

### `GET /api/v1/transactions/{id}`

One transaction, in the same shape. A transaction the token can't read gets the same `404` as one that doesn't exist.

## Add transactions

### `POST /api/v1/transactions`

```sh
curl -X POST https://money.example.com/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 4f1c2a9e-7b1d-4c55-9a43-2f0a1e6b8c11" \
  -d '{"amount": "60.00", "account": "7", "payee": "Tea stall", "category": "Food & Dining"}'
```

| Field | |
|---|---|
| `amount` | required: positive, at most two decimals; a string or a number |
| `account` | required: an account id |
| `direction` | `debit` (default) or `credit` |
| `payee` | up to 200 characters |
| `category` | a category id or name; left out, Hisaab picks one from the payee as usual |
| `occurred_at` | RFC 3339; left out, now; not in the future |
| `notes` | up to 2000 characters |

The answer is `201 Created` with a `Location` header and the transaction, in the same shape as above. In the app it
shows as "Added by" and the token's name.

**`Idempotency-Key`** (optional). This header is an IETF draft,
[draft-ietf-httpapi-idempotency-key-header](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/),
not yet an RFC. Send a new unique value (e.g. a UUID) with each new transaction:

- the same key and the same request again (a retry): you get the transaction the first one created, not a second one;
- the same key with a different request: `422`;
- two requests with the same key at the same moment: one of them gets `409`; try it again.

## Read balances

### `GET /api/v1/balances`

```json
{
  "data": [
    {
      "account": { "id": "1", "name": "Axis savings", "type": "bank", "last4": "2286" },
      "balance": { "value": "50000.00", "currency": "INR" },
      "card": null
    },
    {
      "account": { "id": "3", "name": "HDFC card", "type": "credit_card", "last4": "4321" },
      "balance": { "value": "8400.00", "currency": "INR" },
      "card": {
        "limit": { "value": "100000.00", "currency": "INR" },
        "available": { "value": "91600.00", "currency": "INR" },
        "due_date": "2026-12-08",
        "days_to_due": 25
      }
    }
  ],
  "as_of": "2026-11-13T17:05:00+05:30"
}
```

For a card, `balance` is what's owed on it. `card` is `null` for other accounts; its fields are `null` when the
card's limit or due day isn't set. These are the same figures as the Accounts screen.

## Read totals

### `GET /api/v1/summary`

| Parameter | |
|---|---|
| `month` | `2026-11`; or `from` and `to` (days, both included; `to` defaults to today); neither: this month |
| `group_by` | `category` (default), `account`, `month` or `day` |
| `direction` | `debit` (default) or `credit` |
| `category`, `account` | optional, as for transactions |

```json
{
  "from": "2026-11-01",
  "to": "2026-11-30",
  "direction": "debit",
  "group_by": "category",
  "total": { "value": "4650.00", "currency": "INR" },
  "count": 5,
  "groups": [
    { "key": "5", "name": "Fuel", "total": { "value": "3900.00", "currency": "INR" }, "count": 3 },
    { "key": null, "name": "Uncategorised", "total": { "value": "300.00", "currency": "INR" }, "count": 1 }
  ]
}
```

Totals are counted like the app's own screens: your share of split payments, no transfers. So they match Insights.
`key` is the category or account id, the month (`2026-11`) or the day (`2026-11-12`); `null` for uncategorised.

## Lists for menus

`GET /api/v1/categories` → `{"data": [{"id": "5", "name": "Fuel", "kind": "expense"}]}`
(`kind` is `expense` or `income`).

`GET /api/v1/accounts` → `{"data": [{"id": "3", "name": "HDFC card", "type": "credit_card", "last4": "4321"}]}`.

Both list only what the token may see.

## Errors

```json
{ "title": "Forbidden", "status": 403, "detail": "This token can only read transactions.", "instance": "/api/v1/summary" }
```

| Status | When |
|---|---|
| 400 | A parameter or the body isn't valid |
| 401 | No token, or a wrong one (with `WWW-Authenticate`) |
| 403 | The token's job doesn't include this endpoint (with `WWW-Authenticate: Bearer error="insufficient_scope"`), a category, account or direction outside its limits, or the Data API is off |
| 404 | No such endpoint, or a transaction that doesn't exist or the token can't read |
| 415 | The body isn't JSON (`Content-Type: application/json`) |
| 409 | Two requests with the same Idempotency-Key at once |
| 422 | An Idempotency-Key reused with a different request |
| 429 | Too many requests, or locked out after wrong tokens (with `Retry-After`) |

## Example: log a cash spend from a Siri shortcut

1. Make an **Add transactions** token limited to your Cash account.
2. In Shortcuts: **Ask for Input** (Number, "Amount") → **Ask for Input** (Text, "Paid to") →
   **Get Contents of URL**: `https://money.example.com/api/v1/transactions`, Method **POST**, Headers
   `Authorization` = `Bearer <token>`, Request Body **JSON** with `amount` = the number, `account` = your Cash
   account's id (from `GET /api/v1/accounts`), `payee` = the text.
