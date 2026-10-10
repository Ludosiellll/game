-- Forma: cloud copy of the training log. The phone keeps the main copy and works offline;
-- these tables mirror it per user. Every row belongs to the signed-in user (RLS), and the
-- Data API sees the tables only through the explicit grants at the bottom (no anon access).

create or replace function public.touch_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := clock_timestamp();
  return new;
end;
$$;

-- Finished workouts (lifting and running).
create table public.workouts (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id uuid not null,
  day date not null,
  kind text not null,
  template_id text,
  title text not null,
  started_at timestamptz not null,
  finished_at timestamptz,
  rpe smallint,
  duration_sec integer,
  load double precision,
  deload boolean not null default false,
  plan jsonb,
  summary jsonb,
  note text,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, id)
);

-- Logged sets of a lifting workout.
create table public.sets (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id uuid not null,
  workout_id uuid not null,
  exercise_id text not null,
  slot_key text not null,
  position smallint not null,
  weight double precision not null,
  reps integer not null,
  rir smallint,
  target_weight double precision,
  target_reps integer,
  done_at timestamptz not null,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, id),
  foreign key (user_id, workout_id) references public.workouts (user_id, id) on delete cascade
);

-- Run details; track is a compact array of [time_ms, lat, lon, accuracy_m].
create table public.runs (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  workout_id uuid not null,
  type text not null,
  plan jsonb not null,
  completed double precision not null,
  moving_sec integer not null,
  distance_m double precision,
  avg_pace double precision,
  work_distance_m double precision,
  work_pace double precision,
  vdot_after double precision,
  track jsonb,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, workout_id),
  foreign key (user_id, workout_id) references public.workouts (user_id, id) on delete cascade
);

-- Progression state per exercise slot (weight, reps, stalls...).
create table public.slot_states (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  slot_key text not null,
  exercise_id text not null,
  weight double precision,
  reps integer not null,
  stalls integer not null,
  misses integer not null,
  sessions integer not null,
  changed_at timestamptz not null,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, slot_key)
);

-- Estimated one-rep max per exercise.
create table public.exercise_stats (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  exercise_id text not null,
  e1rm double precision not null,
  best double precision not null,
  changed_at timestamptz not null,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, exercise_id)
);

create table public.body_weights (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  day date not null,
  kg double precision not null,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, day)
);

-- Profile, generated program, running state, settings: one JSON document per key.
create table public.app_state (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  key text not null,
  value jsonb not null,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, key)
);

-- Incremental download: "what changed for me since X".
create index workouts_user_updated on public.workouts (user_id, updated_at);
create index sets_user_updated on public.sets (user_id, updated_at);
create index sets_user_workout on public.sets (user_id, workout_id);
create index runs_user_updated on public.runs (user_id, updated_at);
create index slot_states_user_updated on public.slot_states (user_id, updated_at);
create index exercise_stats_user_updated on public.exercise_stats (user_id, updated_at);
create index body_weights_user_updated on public.body_weights (user_id, updated_at);
create index app_state_user_updated on public.app_state (user_id, updated_at);

-- Server time decides "changed since": set on every insert and update.
create trigger touch_updated_at before insert or update on public.workouts for each row execute function public.touch_updated_at();
create trigger touch_updated_at before insert or update on public.sets for each row execute function public.touch_updated_at();
create trigger touch_updated_at before insert or update on public.runs for each row execute function public.touch_updated_at();
create trigger touch_updated_at before insert or update on public.slot_states for each row execute function public.touch_updated_at();
create trigger touch_updated_at before insert or update on public.exercise_stats for each row execute function public.touch_updated_at();
create trigger touch_updated_at before insert or update on public.body_weights for each row execute function public.touch_updated_at();
create trigger touch_updated_at before insert or update on public.app_state for each row execute function public.touch_updated_at();

-- Each user sees and changes only their own rows.
alter table public.workouts enable row level security;
alter table public.sets enable row level security;
alter table public.runs enable row level security;
alter table public.slot_states enable row level security;
alter table public.exercise_stats enable row level security;
alter table public.body_weights enable row level security;
alter table public.app_state enable row level security;

create policy "Own rows" on public.workouts for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "Own rows" on public.sets for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "Own rows" on public.runs for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "Own rows" on public.slot_states for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "Own rows" on public.exercise_stats for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "Own rows" on public.body_weights for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "Own rows" on public.app_state for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

-- Explicit Data API access: signed-in users only, and only plain reads and writes.
revoke all on table public.workouts, public.sets, public.runs, public.slot_states,
  public.exercise_stats, public.body_weights, public.app_state from anon, authenticated;
grant select, insert, update, delete on table public.workouts, public.sets, public.runs,
  public.slot_states, public.exercise_stats, public.body_weights, public.app_state to authenticated;
revoke all on function public.touch_updated_at() from anon, authenticated;
