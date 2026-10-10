-- Friends: people who exchanged friend codes see each other's workouts and progress and can copy them.
-- The two tables are reached only through the functions below (no Data API access to the tables); every
-- function checks the caller, the friendship and the owner's sharing settings. Friends never see GPS tracks,
-- the profile document (age, height, heart rate), settings or notes; body weight only when its owner shares it.

create table public.profiles (
  user_id uuid primary key references auth.users (id) on delete cascade,
  display_name text not null default '' check (char_length(display_name) <= 40),
  friend_code text not null unique check (friend_code ~ '^[2-9A-HJ-NP-Z]{8}$'),
  share_activity boolean not null default true,
  share_body boolean not null default false,
  created_at timestamptz not null default now()
);

create table public.friendships (
  requester uuid not null references auth.users (id) on delete cascade,
  addressee uuid not null references auth.users (id) on delete cascade,
  accepted boolean not null default false,
  created_at timestamptz not null default now(),
  primary key (requester, addressee),
  check (requester <> addressee)
);
create index friendships_addressee on public.friendships (addressee);

alter table public.profiles enable row level security;
alter table public.friendships enable row level security;
revoke all on table public.profiles, public.friendships from anon, authenticated;

-- 8 characters without look-alikes (no 0/O, 1/I/L), from the random bytes of a v4 UUID.
create or replace function public.new_friend_code()
returns text
language plpgsql
volatile
set search_path = ''
as $$
declare
  alphabet constant text := '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
  bytes bytea := uuid_send(gen_random_uuid());
  idx int;
  code text := '';
begin
  -- Bytes 6 and 8 carry the UUID version and variant bits: skip them.
  foreach idx in array array[0, 1, 2, 3, 4, 5, 7, 9] loop
    code := code || substr(alphabet, 1 + get_byte(bytes, idx) % 31, 1);
  end loop;
  return code;
end;
$$;

create or replace function public.friends_of(me uuid)
returns table (friend uuid)
language sql
stable
set search_path = ''
as $$
  select case when f.requester = me then f.addressee else f.requester end
  from public.friendships f
  where f.accepted and me in (f.requester, f.addressee);
$$;

-- The caller's profile, created on first use; [p_name] renames it when given.
create or replace function public.my_profile(p_name text default null)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := auth.uid();
  p public.profiles;
begin
  if me is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  if not exists (select 1 from public.profiles where user_id = me) then
    loop
      begin
        insert into public.profiles (user_id, display_name, friend_code)
        values (me, coalesce(left(trim(p_name), 40), ''), public.new_friend_code());
        exit;
      exception when unique_violation then
        -- A code drawn twice, or a parallel first call: draw again unless the profile exists by now.
        exit when exists (select 1 from public.profiles where user_id = me);
      end;
    end loop;
  elsif p_name is not null then
    update public.profiles set display_name = left(trim(p_name), 40) where user_id = me;
  end if;
  select * into p from public.profiles where user_id = me;
  return jsonb_build_object(
    'name', p.display_name, 'code', p.friend_code, 'share_activity', p.share_activity, 'share_body', p.share_body
  );
end;
$$;

create or replace function public.set_sharing(p_activity boolean, p_body boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform public.my_profile();
  update public.profiles set share_activity = p_activity, share_body = p_body where user_id = auth.uid();
  return public.my_profile();
end;
$$;

-- Sends a request by friend code; accepts at once if that person already asked.
-- Returns: requested, accepted, already, not_found or self.
create or replace function public.add_friend(p_code text)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := auth.uid();
  other uuid;
begin
  if me is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  perform public.my_profile();
  select user_id into other from public.profiles where friend_code = upper(regexp_replace(coalesce(p_code, ''), '[^0-9A-Za-z]', '', 'g'));
  if other is null then
    return 'not_found';
  end if;
  if other = me then
    return 'self';
  end if;
  if exists (select 1 from public.friendships where accepted and me in (requester, addressee) and other in (requester, addressee)) then
    return 'already';
  end if;
  if exists (select 1 from public.friendships where requester = other and addressee = me) then
    update public.friendships set accepted = true where requester = other and addressee = me;
    return 'accepted';
  end if;
  insert into public.friendships (requester, addressee) values (me, other) on conflict do nothing;
  return 'requested';
end;
$$;

-- Accepts or declines a request sent to the caller.
create or replace function public.answer_friend(p_user uuid, p_accept boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  if p_accept then
    update public.friendships set accepted = true where requester = p_user and addressee = auth.uid();
  else
    delete from public.friendships where requester = p_user and addressee = auth.uid() and not accepted;
  end if;
end;
$$;

-- Ends a friendship or withdraws a request, from either side.
create or replace function public.remove_friend(p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  delete from public.friendships
  where (requester = auth.uid() and addressee = p_user) or (requester = p_user and addressee = auth.uid());
end;
$$;

-- Friends and requests of the caller: state is friend, incoming or outgoing.
create or replace function public.friends()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object(
    'user_id', o.other,
    'name', coalesce(nullif(p.display_name, ''), 'Без имени'),
    'state', o.state,
    'since', o.created_at,
    'last_day', case when o.state = 'friend' and coalesce(p.share_activity, false) then (
      select max(w.day) from public.workouts w where w.user_id = o.other and not w.deleted and w.finished_at is not null
    ) end
  ) order by o.state, p.display_name), '[]'::jsonb)
  from (
    select
      case when f.requester = auth.uid() then f.addressee else f.requester end as other,
      case when f.accepted then 'friend' when f.requester = auth.uid() then 'outgoing' else 'incoming' end as state,
      f.created_at
    from public.friendships f
    where auth.uid() in (f.requester, f.addressee)
  ) o
  left join public.profiles p on p.user_id = o.other;
$$;

-- Finished workouts of friends who share, newest first: plans, summaries, sets, run distance and pace.
-- [p_user] narrows to one friend; [p_before] pages back.
create or replace function public.friend_feed(p_before timestamptz default null, p_limit integer default 30, p_user uuid default null)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  with visible as (
    select fo.friend, coalesce(nullif(p.display_name, ''), 'Без имени') as name
    from public.friends_of(auth.uid()) fo
    join public.profiles p on p.user_id = fo.friend
    where p.share_activity and (p_user is null or fo.friend = p_user)
  ), items as (
    select w.user_id, w.id, w.day, w.kind, w.title, w.started_at, w.duration_sec, w.rpe, w.deload, w.plan, w.summary, v.name
    from public.workouts w
    join visible v on v.friend = w.user_id
    where not w.deleted and w.finished_at is not null and (p_before is null or w.started_at < p_before)
    order by w.started_at desc
    limit least(greatest(coalesce(p_limit, 30), 1), 100)
  )
  select coalesce(jsonb_agg(jsonb_build_object(
    'user_id', i.user_id, 'name', i.name, 'workout_id', i.id, 'day', i.day, 'kind', i.kind, 'title', i.title,
    'started_at', i.started_at, 'duration_sec', i.duration_sec, 'rpe', i.rpe, 'deload', i.deload,
    'plan', case when i.kind = 'LIFT' then i.plan end,
    'summary', i.summary,
    'run', (
      select jsonb_build_object('type', r.type, 'distance_m', r.distance_m, 'moving_sec', r.moving_sec, 'avg_pace', r.avg_pace, 'vdot', r.vdot_after)
      from public.runs r where r.user_id = i.user_id and r.workout_id = i.id and not r.deleted
    ),
    'sets', (
      select coalesce(jsonb_agg(jsonb_build_object('exercise_id', s.exercise_id, 'position', s.position, 'weight', s.weight, 'reps', s.reps)
        order by s.position, s.done_at), '[]'::jsonb)
      from public.sets s where s.user_id = i.user_id and s.workout_id = i.id and not s.deleted
    )
  ) order by i.started_at desc), '[]'::jsonb)
  from items i;
$$;

-- One friend's progress: strength estimates, running fitness, weekly sessions, program and own exercises to
-- copy, body weight when shared.
create or replace function public.friend_progress(p_user uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  me uuid := auth.uid();
  p public.profiles;
begin
  if me is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  if not exists (select 1 from public.friends_of(me) where friend = p_user) then
    raise exception 'not friends' using errcode = '42501';
  end if;
  select * into p from public.profiles where user_id = p_user;
  if not coalesce(p.share_activity, false) then
    return jsonb_build_object('name', coalesce(nullif(p.display_name, ''), 'Без имени'), 'shared', false);
  end if;
  return jsonb_build_object(
    'name', coalesce(nullif(p.display_name, ''), 'Без имени'),
    'shared', true,
    'stats', (
      select coalesce(jsonb_agg(jsonb_build_object('exercise_id', s.exercise_id, 'e1rm', s.e1rm, 'best', s.best, 'changed_at', s.changed_at)), '[]'::jsonb)
      from public.exercise_stats s where s.user_id = p_user and not s.deleted
    ),
    'vdot', (
      select r.vdot_after from public.runs r
      join public.workouts w on w.user_id = r.user_id and w.id = r.workout_id
      where r.user_id = p_user and not r.deleted and not w.deleted and r.vdot_after is not null
      order by w.started_at desc limit 1
    ),
    'weeks', (
      select coalesce(jsonb_agg(jsonb_build_object('week', t.wk, 'lifts', t.lifts, 'runs', t.runs) order by t.wk), '[]'::jsonb)
      from (
        select date_trunc('week', w.day)::date as wk,
          count(*) filter (where w.kind = 'LIFT') as lifts,
          count(*) filter (where w.kind = 'RUN') as runs
        from public.workouts w
        where w.user_id = p_user and not w.deleted and w.finished_at is not null and w.day > current_date - 56
        group by 1
      ) t
    ),
    'program', (select a.value from public.app_state a where a.user_id = p_user and a.key = 'program' and not a.deleted),
    'custom', (select a.value from public.app_state a where a.user_id = p_user and a.key = 'custom_exercises' and not a.deleted),
    'body', case when p.share_body then (
      select coalesce(jsonb_agg(jsonb_build_object('day', b.day, 'kg', b.kg) order by b.day), '[]'::jsonb)
      from public.body_weights b where b.user_id = p_user and not b.deleted and b.day > current_date - 180
    ) end
  );
end;
$$;

-- Only signed-in users call these, and only through the functions meant for the app.
revoke all on function public.new_friend_code() from public, anon, authenticated;
revoke all on function public.friends_of(uuid) from public, anon, authenticated;
revoke all on function public.my_profile(text), public.set_sharing(boolean, boolean), public.add_friend(text),
  public.answer_friend(uuid, boolean), public.remove_friend(uuid), public.friends(),
  public.friend_feed(timestamptz, integer, uuid), public.friend_progress(uuid) from public, anon;
grant execute on function public.my_profile(text), public.set_sharing(boolean, boolean), public.add_friend(text),
  public.answer_friend(uuid, boolean), public.remove_friend(uuid), public.friends(),
  public.friend_feed(timestamptz, integer, uuid), public.friend_progress(uuid) to authenticated;
