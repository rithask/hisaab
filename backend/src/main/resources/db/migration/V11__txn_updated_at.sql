-- When each transaction last changed, for apps that ask for what changed since their last visit
-- (GET /api/v1/transactions?updated_since=). Set by the database, so every change counts, including ones made in
-- bulk: any change to the row, and adding or removing a tag.
alter table txn add column updated_at timestamptz;
update txn set updated_at = created_at;
alter table txn alter column updated_at set not null;
alter table txn alter column updated_at set default now();

create function txn_touch() returns trigger language plpgsql as $$
begin
    new.updated_at := now();
    return new;
end $$;

create trigger txn_touch before update on txn for each row execute function txn_touch();

create function txn_tag_touch() returns trigger language plpgsql as $$
begin
    update txn set updated_at = now() where id = coalesce(new.txn_id, old.txn_id);
    return null;
end $$;

create trigger txn_tag_touch after insert or delete on txn_tag for each row execute function txn_tag_touch();

create index txn_updated_at on txn (updated_at, id);
