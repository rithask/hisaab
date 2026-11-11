-- What the mail provider said about who sent an email (its Authentication-Results header):
-- PASS = the From domain is confirmed (DMARC, or DKIM signed by that domain), FAIL = it isn't, NONE = no verdict.
alter table raw_email add column sender_check varchar(10);
-- Why an alert email was held for review instead of recorded: UNKNOWN_SENDER | UNVERIFIED_SENDER
alter table raw_email add column hold varchar(20);

-- Email domains I've said may send alerts for my bank (beyond the ones the app knows).
create table trusted_sender (
    domain     varchar(255) primary key,
    created_at timestamptz not null default now()
);

-- The alert from the other channel (email or SMS) that was matched to this transaction, so one alert
-- never absorbs two payments.
alter table txn add column twin_raw_email_id bigint references raw_email (id) on delete set null;
