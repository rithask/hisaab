-- Learned formats can be for bank emails as well as SMS.
alter table sms_template add column channel varchar(10) not null default 'SMS';
