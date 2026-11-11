-- How often I've put each payee (or UPI ID) in each category, with the amounts, so the majority decides and the
-- amount can tell two of my usual categories for the same payee apart.
create table category_vote (
    id          bigserial primary key,
    key_type    varchar(10)    not null,             -- MERCHANT | VPA
    key         varchar(200)   not null,
    category_id bigint         not null references category (id) on delete cascade,
    votes       integer        not null default 0,
    amount_sum  numeric(16, 2) not null default 0,
    amount_n    integer        not null default 0,
    last_at     timestamptz    not null default now(),
    unique (key_type, key, category_id)
);

-- Payees learned so far start with one vote each.
insert into category_vote (key_type, key, category_id, votes)
select 'MERCHANT', pattern, category_id, 1 from category_rule where match_type = 'MERCHANT' and source = 'LEARNED';

-- A category the app chose (rather than one I set) is shown as suggested.
alter table txn add column category_suggested boolean not null default false;
