-- A learned format word by word (keys and regexes), so formats for the same kind of message can be merged.
alter table sms_template add column parts text;
