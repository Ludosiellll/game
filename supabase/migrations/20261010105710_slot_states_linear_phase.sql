-- Novice linear phase per exercise slot (the app adds weight after every successful session while it lasts).
alter table public.slot_states add column linear boolean not null default true;
