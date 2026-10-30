-- Complaints about a person («Пожаловаться» in a chat or on a person's page): what was wrong, said in a few words,
-- read by the app's owner in the dashboard. Only the caller files them, never reads them back; ten a day at most.

create table public.reports (
  id bigint generated always as identity primary key,
  reporter uuid not null references auth.users (id) on delete cascade,
  reported uuid not null references auth.users (id) on delete cascade,
  reason text not null check (char_length(reason) between 1 and 500),
  created_at timestamptz not null default now(),
  check (reporter <> reported)
);

create index reports_reported on public.reports (reported, created_at);

alter table public.reports enable row level security;
revoke all on table public.reports from anon, authenticated;

create or replace function public.report_user(p_user uuid, p_reason text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := auth.uid();
  why text := trim(coalesce(p_reason, ''));
begin
  if me is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  if p_user is null or p_user = me or not exists (select 1 from auth.users where id = p_user) then
    raise exception 'no such person' using errcode = '22023';
  end if;
  if char_length(why) = 0 or char_length(why) > 500 then
    raise exception 'bad reason' using errcode = '22023';
  end if;
  if (select count(*) from public.reports where reporter = me and created_at > now() - interval '1 day') >= 10 then
    raise exception 'too many reports' using errcode = '54000';
  end if;
  insert into public.reports (reporter, reported, reason) values (me, p_user, why);
end;
$$;

revoke all on function public.report_user(uuid, text) from public, anon;
grant execute on function public.report_user(uuid, text) to authenticated;
