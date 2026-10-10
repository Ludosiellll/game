-- Successful sessions in a row with nothing to spare on the "+" set (the weight is being consolidated).
alter table public.slot_states add column steady integer not null default 0;
