-- Presents I buy for others. "Gift" (money in) already exists; this is its spending side.
insert into category (name, kind, color, system)
values ('Gifts', 'EXPENSE', '#f59e0b', false)
on conflict (name, kind) do nothing;
