-- What the coach's DeepSeek key spent, by person: who asked, when, the tokens and the units charged. Numbers only, no
-- text. Written and read by the coach function with the service key (Denis, 5 November: «раздел остаток токенов … сколько
-- потрачено, на что»); nobody reads it straight: RLS on, no policies, no grants.
create table if not exists public.coach_usage (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  at timestamptz not null default now(),
  provider text not null,
  prompt_tokens integer not null default 0,
  completion_tokens integer not null default 0,
  units numeric not null default 0
);
create index if not exists coach_usage_at on public.coach_usage (at desc);
create index if not exists coach_usage_user on public.coach_usage (user_id);
alter table public.coach_usage enable row level security;
revoke all on table public.coach_usage from anon, authenticated;
