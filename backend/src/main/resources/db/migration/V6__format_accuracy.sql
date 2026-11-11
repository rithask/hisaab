-- How often each learned format's reading was corrected; after two it's paused until I resume it.
alter table sms_template add column corrections integer not null default 0;
alter table sms_template add column paused boolean not null default false;
-- The learned format a message was read with, so a correction can be counted against it.
alter table raw_email add column template_id bigint references sms_template (id) on delete set null;
