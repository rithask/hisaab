-- Formats (and their lessons) can be learned automatically from readings the app could confirm on its own:
-- the bank's balance figure adds up, or the email and SMS for the same payment agree.
alter table sms_template add column origin varchar(10) not null default 'TAUGHT';   -- TAUGHT | AUTO
alter table learned_example add column origin varchar(10) not null default 'TAUGHT'; -- TAUGHT | AUTO
