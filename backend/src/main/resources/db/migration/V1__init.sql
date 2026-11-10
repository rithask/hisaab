-- Hisaab database schema, version 1.
-- Every later change goes in a new file (V2__…, V3__…); never edit this one after a release. See docs/versioning.md.

-- ============================================================================================================
-- Core: accounts, transactions, categories, people, investments, settings
-- ============================================================================================================

-- Accounts: bank accounts, credit cards, cash wallets
create table account (
    id               bigserial primary key,
    name             varchar(100)  not null,
    type             varchar(20)   not null,           -- BANK | CREDIT_CARD | CASH
    institution      varchar(30)   not null default 'OTHER',
    last4            varchar(4),
    -- BANK/CASH: balance on opening_date. CREDIT_CARD: outstanding on opening_date.
    opening_balance  numeric(14, 2) not null default 0,
    opening_date     timestamptz   not null,
    credit_limit     numeric(14, 2),
    statement_day    int,
    due_day          int,
    archived         boolean       not null default false,
    created_at       timestamptz   not null default now()
);
create index account_last4_idx on account (last4);

create table category (
    id     bigserial primary key,
    name   varchar(60) not null,
    kind   varchar(10) not null,                       -- EXPENSE | INCOME
    color  varchar(9)  not null default '#64748b',
    system boolean     not null default false,
    unique (name, kind)
);

create table tag (
    id   bigserial primary key,
    name varchar(40) not null unique
);

create table raw_email (
    id           bigserial primary key,
    message_id   varchar(500) not null unique,
    from_address varchar(300),
    subject      varchar(1000),
    received_at  timestamptz,
    body_text    text,
    status       varchar(20)  not null,                -- PARSED | IGNORED | NEEDS_REVIEW | STATEMENT | DUPLICATE | ERROR
    parser       varchar(50),
    note         text,
    created_at   timestamptz  not null default now(),
    channel      varchar(10)  not null default 'EMAIL',  -- EMAIL | SMS
    sender       varchar(100)                           -- SMS sender id
);
create index raw_email_status_idx on raw_email (status);

create table txn (
    id                bigserial primary key,
    account_id        bigint        not null references account (id) on delete cascade,
    direction         varchar(6)    not null,          -- DEBIT | CREDIT
    amount            numeric(14, 2) not null,         -- always INR
    original_amount   numeric(14, 2),
    original_currency varchar(3),
    amount_estimated  boolean       not null default false,
    occurred_at       timestamptz   not null,
    counterparty      varchar(200),
    merchant_key      varchar(200),
    description       varchar(500),
    reference_no      varchar(100),
    reported_balance  numeric(14, 2),
    category_id       bigint references category (id) on delete set null,
    notes             text,
    source            varchar(10)   not null,          -- EMAIL | MANUAL | ADJUSTMENT
    raw_email_id      bigint references raw_email (id) on delete set null,
    transfer          boolean       not null default false,
    linked_txn_id     bigint references txn (id) on delete set null,
    created_at        timestamptz   not null default now(),
    -- the time of day is a guess (the alert or statement gave only a date); a statement with the real time corrects it
    time_estimated    boolean       not null default false
);
create index txn_account_time_idx on txn (account_id, occurred_at);
create index txn_time_idx on txn (occurred_at);
create index txn_ref_idx on txn (reference_no);
create index txn_merchant_idx on txn (merchant_key);

create table txn_tag (
    txn_id bigint not null references txn (id) on delete cascade,
    tag_id bigint not null references tag (id) on delete cascade,
    primary key (txn_id, tag_id)
);

-- Auto-categorisation rules. MERCHANT = exact merchant_key match (learned from corrections),
-- CONTAINS = substring match on counterparty/description.
create table category_rule (
    id          bigserial primary key,
    match_type  varchar(10)  not null,
    pattern     varchar(200) not null,
    category_id bigint       not null references category (id) on delete cascade,
    source      varchar(10)  not null,                 -- SYSTEM | MANUAL | LEARNED
    priority    int          not null default 0,
    hits        int          not null default 0,
    unique (match_type, pattern)
);

create table alert (
    id         bigserial primary key,
    type       varchar(30)  not null,                  -- BALANCE_MISMATCH
    account_id bigint references account (id) on delete cascade,
    txn_id     bigint references txn (id) on delete cascade,
    message    text         not null,
    expected   numeric(14, 2),
    actual     numeric(14, 2),
    dismissed  boolean      not null default false,
    created_at timestamptz  not null default now()
);

create table statement_file (
    id           bigserial primary key,
    account_id   bigint references account (id) on delete set null,
    raw_email_id bigint references raw_email (id) on delete set null,
    file_name    varchar(300) not null,
    stored_path  varchar(600) not null,
    size_bytes   bigint       not null,
    period_label varchar(20),
    received_at  timestamptz  not null,
    -- how reading a statement that arrived by email went: REVIEW | IMPORTED | CANCELLED | NEEDS_PASSWORD | UNSUPPORTED | ERROR
    import_status varchar(20),
    import_note  text
);

create table person (
    id       bigserial primary key,
    name     varchar(100) not null unique,
    upi_name varchar(200),                             -- name as it appears in UPI credits, for matching
    phone    varchar(20),
    notes    text
);

-- What each person owes for a transaction I paid for
create table split_share (
    id        bigserial primary key,
    txn_id    bigint        not null references txn (id) on delete cascade,
    person_id bigint        not null references person (id) on delete cascade,
    method    varchar(10)   not null,                  -- EQUAL | EXACT | PERCENT
    input     numeric(14, 3),                          -- the raw value entered (amount or percent)
    amount    numeric(14, 2) not null,
    unique (txn_id, person_id)
);

-- Money a person paid me back
create table settlement (
    id         bigserial primary key,
    person_id  bigint        not null references person (id) on delete cascade,
    txn_id     bigint references txn (id) on delete set null,
    amount     numeric(14, 2) not null,
    settled_at timestamptz   not null,
    status     varchar(10)   not null,                 -- SUGGESTED | CONFIRMED | REJECTED
    note       varchar(300)
);

create table holding (
    id          bigserial primary key,
    asset_type  varchar(5)     not null,               -- STOCK | MF
    symbol      varchar(50)    not null,               -- NSE/BSE symbol, or AMFI scheme code
    exchange    varchar(5),                            -- NSE | BSE (stocks only)
    name        varchar(300),
    quantity    numeric(18, 4) not null,
    avg_price   numeric(14, 4) not null,
    last_price  numeric(14, 4),
    prev_close  numeric(14, 4),
    price_as_of timestamptz,
    unique (asset_type, symbol)
);

create table mf_scheme (
    code     varchar(20) primary key,
    name     varchar(300) not null,
    nav      numeric(14, 4),
    nav_date date
);
create index mf_scheme_name_idx on mf_scheme (lower(name));

create table app_setting (
    key   varchar(100) primary key,
    value text
);

-- ============================================================================================================
-- SMS: what the parser has learned
-- ============================================================================================================

-- A message format taught by a correction: the SMS text with its variable parts replaced by slots.
create table sms_template (
    id           bigserial primary key,
    skeleton     text        not null unique,     -- readable form, e.g. "sent rs.<AMT> from hdfc bank a/c *<L4> to <PAYEE> on # ref <REF>"
    pattern      text        not null,            -- regex with named groups amt, payee, l4, ref, bal
    institution  varchar(20) not null,
    account_type varchar(20) not null,
    direction    varchar(6)  not null,
    card_payment boolean     not null default false,
    sample       text,
    hits         integer     not null default 0,
    created_at   timestamptz not null default now(),
    last_used_at timestamptz
);

-- "SWIGGY*INSTAMART BLR" -> "Swiggy Instamart": learned when a payee is renamed.
create table payee_alias (
    id           bigserial primary key,
    raw_key      varchar(200) not null unique,
    display_name varchar(200) not null,
    hits         integer      not null default 0,
    created_at   timestamptz  not null default now()
);

-- Which account a bank's SMS refers to when its number is missing or differs (e.g. a debit card number).
create table account_hint (
    id          bigserial primary key,
    institution varchar(20) not null,
    last4       varchar(4)  not null default '',
    account_id  bigint      not null references account (id) on delete cascade,
    created_at  timestamptz not null default now(),
    unique (institution, last4)
);

-- Sender ids seen on successfully parsed SMS, so a text that doesn't name its bank can still be attributed.
create table sms_sender (
    sender_key  varchar(40) primary key,
    institution varchar(20) not null,
    hits        integer     not null default 0
);

-- ============================================================================================================
-- Statement import (review before import)
-- ============================================================================================================

-- A statement being reviewed before its transactions are imported.
create table statement_import (
    id                 bigserial primary key,
    account_id         bigint references account (id) on delete cascade,
    statement_file_id  bigint references statement_file (id) on delete set null,
    parser             varchar(40)  not null,
    label              varchar(120) not null,
    institution        varchar(20)  not null,
    account_type       varchar(20)  not null,
    last4              varchar(4),
    period_from        date,
    period_to          date,
    opening_balance    numeric(14, 2),
    closing_balance    numeric(14, 2),
    credit_limit       numeric(14, 2),
    checks             text,
    file_name          varchar(300),
    stored_path        varchar(600),
    remember_password  varchar(200),
    status             varchar(12)  not null,   -- REVIEW | IMPORTED | CANCELLED
    created_at         timestamptz  not null default now(),
    completed_at       timestamptz
);

create table statement_import_line (
    id              bigserial primary key,
    import_id       bigint        not null references statement_import (id) on delete cascade,
    line_no         integer       not null,
    txn_date        date          not null,
    txn_time        time,
    description     text,
    counterparty    varchar(200),
    reference_no    varchar(100),
    direction       varchar(6)    not null,
    amount          numeric(14, 2) not null,
    balance         numeric(14, 2),
    card_payment    boolean       not null default false,
    uncertain       text,
    kind            varchar(10),               -- MATCH | POSSIBLE | NEW
    match_txn_id    bigint references txn (id) on delete set null,
    match_reason    text,
    decision        varchar(6),                -- MERGE | ADD | SKIP
    created_txn_id  bigint references txn (id) on delete set null
);
create index statement_import_line_import_idx on statement_import_line (import_id, line_no);

-- ============================================================================================================
-- Budgets, bills, net worth
-- ============================================================================================================

-- Monthly spending limit per category.
create table budget (
    id            bigserial primary key,
    category_id   bigint         not null unique references category (id) on delete cascade,
    monthly_limit numeric(14, 2) not null check (monthly_limit > 0),
    created_at    timestamptz    not null default now()
);

-- Payees I said aren't bills, so the recurring-payment detector leaves them out.
create table bill_ignore (
    merchant_key varchar(200) primary key,
    created_at   timestamptz not null default now()
);

-- One row per day: what everything was worth. Rows rebuilt from transactions are marked estimated.
create table networth_snapshot (
    day           date primary key,
    cash          numeric(14, 2) not null,
    card_dues     numeric(14, 2) not null,
    investments   numeric(14, 2) not null,
    receivable    numeric(14, 2) not null,
    net_worth     numeric(14, 2) not null,
    estimated     boolean        not null default false
);

-- ============================================================================================================
-- Subscription watch, unusual activity, card rewards
-- ============================================================================================================

-- Alerts I've looked at and said are fine ("sub:price:<merchant>:<day>", "unusual:<type>:<txn id>", …).
create table watch_dismiss (
    key          varchar(300) primary key,
    dismissed_at timestamptz not null default now()
);

-- Recurring payments I said aren't subscriptions (they can still be bills).
create table subscription_ignore (
    merchant_key varchar(200) primary key
);

-- A payment I'm expecting money back for. "Unusual activity" warns when it hasn't arrived by expected_by.
create table refund_watch (
    txn_id      bigint primary key references txn (id) on delete cascade,
    expected_by date        not null,
    created_at  timestamptz not null default now()
);

-- How a card earns rewards. rate = % cashback, or points per ₹100 spent (the same formula either way).
-- Caps are per billing cycle, in ₹ (cashback) or points.
create table reward_program (
    account_id     bigint primary key references account (id) on delete cascade,
    kind           varchar(10)   not null,             -- CASHBACK | POINTS
    base_rate      numeric(8, 4) not null default 0,
    cycle_cap      numeric(14, 2),
    point_value    numeric(8, 4),                       -- ₹ per point (POINTS only)
    points_balance numeric(14, 2),                      -- last known balance from the bank, and when
    points_as_of   date,
    updated_at     timestamptz   not null default now()
);

-- Special rates, checked in order; the first match wins. Matches a merchant (text in the payee or description)
-- and/or a category. rate 0 marks an exclusion (fuel, rent, wallet loads…).
create table reward_rule (
    id             bigserial primary key,
    account_id     bigint        not null references reward_program (account_id) on delete cascade,
    position       int           not null default 0,
    label          varchar(100)  not null,
    merchant_match varchar(200),
    category_id    bigint references category (id) on delete cascade,
    rate           numeric(8, 4) not null,
    cycle_cap      numeric(14, 2)
);
create index reward_rule_account_idx on reward_rule (account_id, position);

-- ============================================================================================================
-- Built-in categories and categorisation rules
-- ============================================================================================================

-- Seed categories
insert into category (name, kind, color, system) values
    ('Food & Dining', 'EXPENSE', '#f97316', false),
    ('Groceries', 'EXPENSE', '#84cc16', false),
    ('Shopping', 'EXPENSE', '#ec4899', false),
    ('Transport', 'EXPENSE', '#0ea5e9', false),
    ('Fuel', 'EXPENSE', '#eab308', false),
    ('Bills & Utilities', 'EXPENSE', '#8b5cf6', false),
    ('Rent', 'EXPENSE', '#6366f1', false),
    ('Entertainment', 'EXPENSE', '#f43f5e', false),
    ('Health', 'EXPENSE', '#14b8a6', false),
    ('Travel', 'EXPENSE', '#06b6d4', false),
    ('Education', 'EXPENSE', '#a855f7', false),
    ('Subscriptions', 'EXPENSE', '#d946ef', false),
    ('Investments', 'EXPENSE', '#22c55e', false),
    ('Fees & Charges', 'EXPENSE', '#ef4444', false),
    ('Other Expense', 'EXPENSE', '#64748b', false),
    ('Salary', 'INCOME', '#22c55e', false),
    ('Interest', 'INCOME', '#10b981', false),
    ('Refund & Cashback', 'INCOME', '#0ea5e9', false),
    ('Gift', 'INCOME', '#f59e0b', false),
    ('Other Income', 'INCOME', '#64748b', false);

insert into category_rule (match_type, pattern, category_id, source, priority)
select 'CONTAINS', p.pattern, c.id, 'SYSTEM', 0
from (values
    ('swiggy instamart', 'Groceries'),
    ('instamart', 'Groceries'),
    ('blinkit', 'Groceries'),
    ('zepto', 'Groceries'),
    ('bigbasket', 'Groceries'),
    ('swiggy', 'Food & Dining'),
    ('zomato', 'Food & Dining'),
    ('eternal', 'Food & Dining'),
    ('amazon', 'Shopping'),
    ('flipkart', 'Shopping'),
    ('myntra', 'Shopping'),
    ('uber', 'Transport'),
    ('ola', 'Transport'),
    ('rapido', 'Transport'),
    ('irctc', 'Travel'),
    ('makemytrip', 'Travel'),
    ('indigo', 'Travel'),
    ('netflix', 'Subscriptions'),
    ('spotify', 'Subscriptions'),
    ('youtube', 'Subscriptions'),
    ('hotstar', 'Subscriptions'),
    ('airtel', 'Bills & Utilities'),
    ('jio', 'Bills & Utilities'),
    ('electricity', 'Bills & Utilities'),
    ('kseb', 'Bills & Utilities'),
    ('zerodha', 'Investments'),
    ('indian clearing corp', 'Investments'),
    ('petrol', 'Fuel'),
    ('fuel', 'Fuel'),
    ('pharmacy', 'Health'),
    ('apollo', 'Health'),
    ('salary', 'Salary'),
    ('interest', 'Interest'),
    ('cashback', 'Refund & Cashback'),
    ('refund', 'Refund & Cashback')
) as p(pattern, category)
join category c on c.name = p.category;

-- longer, more specific patterns should win
update category_rule set priority = length(pattern) where source = 'SYSTEM';
