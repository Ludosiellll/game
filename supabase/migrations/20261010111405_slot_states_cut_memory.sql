-- Weight before the last cut (reduce/reset) and the reps that led to it: a cut that brings no more reps means the
-- weight is not what limits, and the app stops cutting.
alter table public.slot_states add column cut_from double precision, add column cut_reps integer;
