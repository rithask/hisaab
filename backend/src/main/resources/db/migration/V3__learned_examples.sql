-- Every message I've taught, with the right answer. Before a learned format is added or changed, all of them
-- are read again, and the change is refused if any that was read right would now be read wrong.
create table learned_example (
    id           bigserial primary key,
    raw_email_id bigint references raw_email (id) on delete cascade,
    channel      varchar(10)    not null,            -- SMS | EMAIL
    text         text           not null,
    kind         varchar(10)    not null,            -- TXN
    direction    varchar(6),
    amount       numeric(14, 2),
    institution  varchar(20),
    account_type varchar(20),
    payee        varchar(200),                       -- as written in the message; null = not checked
    created_at   timestamptz    not null default now()
);

-- Messages taught before this existed become the first examples.
insert into learned_example (raw_email_id, channel, text, kind, direction, amount, institution, account_type)
select r.id, 'SMS', r.body_text, 'TXN', t.direction, t.amount, a.institution,
       case when a.type = 'CREDIT_CARD' then 'CREDIT_CARD' else 'BANK' end
from raw_email r
join txn t on t.raw_email_id = r.id
join account a on a.id = t.account_id
where r.parser = 'sms-taught' and r.body_text is not null;
