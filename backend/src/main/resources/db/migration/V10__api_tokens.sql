-- Named API tokens, each with one job (send SMS, read transactions, add transactions, read balances, read totals)
-- and the limits I chose for it. Only a SHA-256 hash of each token is kept.
create table api_token (
    id            bigserial primary key,
    name          varchar(60)  not null,
    job           varchar(20)  not null,             -- SEND_SMS | READ_TRANSACTIONS | ADD_TRANSACTIONS | READ_BALANCES | READ_TOTALS
    token_hash    varchar(64)  not null unique,
    hint          varchar(8)   not null,             -- last characters, to tell tokens apart
    -- Limits; null means all. Comma-separated ids; categories may include "none" for uncategorised. An empty
    -- string means nothing is left (e.g. every chosen category was deleted), which allows nothing.
    category_ids  text,
    account_ids   text,
    money_in      boolean      not null default false, -- also money coming in (otherwise money out only)
    include_notes boolean      not null default true,
    created_at    timestamptz  not null default now(),
    last_used_at  timestamptz,
    revoked_at    timestamptz                         -- kept, so "Added by" still has a name
);

-- The single SMS token from before is gone: SMS now goes to /api/v1/sms with a Send SMS token.
delete from app_setting where key in ('ingest.tokenHash', 'ingest.tokenHint', 'ingest.tokenCreated', 'ingest.tokenLastUsed');

-- Transactions an app added: which token added it, and the request's Idempotency-Key with a fingerprint of the
-- request, so a retry isn't added twice and a reused key with a different request is refused.
alter table txn add column api_token_id bigint references api_token (id) on delete set null;
alter table txn add column request_key varchar(100);
alter table txn add column request_hash varchar(64);
create unique index txn_request_key on txn (api_token_id, request_key) where request_key is not null;
