-- Indexes on the columns that link tables. Postgres doesn't create these by itself, so looking up a
-- transaction's repayments, a person's shares or a category's transactions, and deleting a row others point at,
-- scanned the whole table. Only adds indexes; no data changes. Columns that are usually empty get partial indexes.

create index if not exists txn_category_idx on txn (category_id);
create index if not exists txn_raw_email_idx on txn (raw_email_id) where raw_email_id is not null;
create index if not exists txn_twin_raw_email_idx on txn (twin_raw_email_id) where twin_raw_email_id is not null;
create index if not exists txn_linked_txn_idx on txn (linked_txn_id) where linked_txn_id is not null;
create index if not exists txn_tag_tag_idx on txn_tag (tag_id);

create index if not exists split_share_person_idx on split_share (person_id);
create index if not exists settlement_txn_idx on settlement (txn_id) where txn_id is not null;
create index if not exists settlement_person_idx on settlement (person_id);

create index if not exists category_rule_category_idx on category_rule (category_id);
create index if not exists category_vote_category_idx on category_vote (category_id);
create index if not exists reward_rule_category_idx on reward_rule (category_id) where category_id is not null;

create index if not exists alert_account_idx on alert (account_id);
create index if not exists alert_txn_idx on alert (txn_id) where txn_id is not null;
create index if not exists account_hint_account_idx on account_hint (account_id);

create index if not exists raw_email_template_idx on raw_email (template_id) where template_id is not null;
create index if not exists learned_example_raw_email_idx on learned_example (raw_email_id) where raw_email_id is not null;

create index if not exists statement_file_account_idx on statement_file (account_id);
create index if not exists statement_file_raw_email_idx on statement_file (raw_email_id) where raw_email_id is not null;
create index if not exists statement_import_account_idx on statement_import (account_id);
create index if not exists statement_import_file_idx on statement_import (statement_file_id) where statement_file_id is not null;
create index if not exists statement_import_line_match_idx on statement_import_line (match_txn_id) where match_txn_id is not null;
create index if not exists statement_import_line_created_idx on statement_import_line (created_txn_id) where created_txn_id is not null;
