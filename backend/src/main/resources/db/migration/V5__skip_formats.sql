-- "Not a transaction": a learned format can mean "skip messages worded like this".
alter table sms_template add column kind varchar(10) not null default 'TXN';  -- TXN | SKIP
alter table sms_template alter column institution drop not null;
alter table sms_template alter column account_type drop not null;
alter table sms_template alter column direction drop not null;
