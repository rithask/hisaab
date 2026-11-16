-- Something a friend paid for, part of which was mine (e.g. they paid ₹400 for petrol, my share ₹200). It's kept
-- as a transaction of my share, so it counts in Insights and budgets, but no money left any account of mine: it
-- has no account, and it lowers what that person owes me instead.
alter table txn alter column account_id drop not null;
alter table txn add column paid_by_person_id bigint references person (id);  -- a person who paid for me can't be deleted
alter table txn add column paid_total numeric(14, 2);                        -- what they paid altogether
alter table txn add constraint txn_account_or_payer check ((account_id is null) = (paid_by_person_id is not null));
create index txn_paid_by on txn (paid_by_person_id) where paid_by_person_id is not null;

-- Which way a repayment went: IN, they paid me back; OUT, I paid them back (when I owed them).
alter table settlement add column direction varchar(3) not null default 'IN';
