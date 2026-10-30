-- People: a list of those who chose to be shown to everybody in the app («Показывать меня всем», off by default),
-- their progress for anyone signed in, and messages between people. As with friends, the tables are reached only
-- through the functions below; every function checks the caller. A listed person's page shows what a friend sees
-- (workouts, sets, runs without GPS tracks, strength estimates, program, goals); never the GPS tracks, notes or the
-- profile document beyond its goals; body weight only when its owner shares it. A block hides the blocker from the
-- blocked and stops messages both ways.

alter table public.profiles add column listed boolean not null default false;

create table public.messages (
  id bigint generated always as identity primary key,
  sender uuid not null references auth.users (id) on delete cascade,
  recipient uuid not null references auth.users (id) on delete cascade,
  body text not null check (char_length(body) between 1 and 1000),
  sent_at timestamptz not null default now(),
  read_at timestamptz,
  check (sender <> recipient)
);
create index messages_pair on public.messages (least(sender, recipient), greatest(sender, recipient), id desc);
create index messages_unread on public.messages (recipient, id) where read_at is null;
create index messages_sender_time on public.messages (sender, sent_at);

create table public.blocks (
  blocker uuid not null references auth.users (id) on delete cascade,
  blocked uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker, blocked),
  check (blocker <> blocked)
);

alter table public.messages enable row level security;
alter table public.blocks enable row level security;
revoke all on table public.messages, public.blocks from anon, authenticated;

-- [other]'s training is visible to [me]: their own, listed, or a friend who shares it — unless [other] blocked [me].
create or replace function public.can_see(me uuid, other uuid)
returns boolean
language sql
stable
set search_path = ''
as $$
  select me is not null and other is not null and (
    other = me or (
      not exists (select 1 from public.blocks b where b.blocker = other and b.blocked = me)
      and exists (
        select 1 from public.profiles p
        where p.user_id = other
          and (p.listed or (p.share_activity and exists (select 1 from public.friends_of(me) f where f.friend = other)))
      )
    )
  );
$$;

-- [me] may write to [other]: listed or a friend, and no block either way.
create or replace function public.can_write(me uuid, other uuid)
returns boolean
language sql
stable
set search_path = ''
as $$
  select me is not null and other is not null and other <> me
    and not exists (select 1 from public.blocks b where (b.blocker = other and b.blocked = me) or (b.blocker = me and b.blocked = other))
    and (
      exists (select 1 from public.profiles p where p.user_id = other and p.listed)
      or exists (select 1 from public.friends_of(me) f where f.friend = other)
    );
$$;

-- The caller's profile, created on first use; [p_name] renames it when given. Now with «listed».
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
    'name', p.display_name, 'code', p.friend_code, 'share_activity', p.share_activity, 'share_body', p.share_body,
    'listed', p.listed
  );
end;
$$;

-- «Показывать меня всем»: on, the caller is in everybody's list and their progress is open to anyone signed in.
create or replace function public.set_listed(p_listed boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform public.my_profile();
  update public.profiles set listed = coalesce(p_listed, false) where user_id = auth.uid();
  return public.my_profile();
end;
$$;

-- Everybody listed but the caller (and those who blocked the caller or whom the caller blocked), most active first.
create or replace function public.people()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(item order by item->>'last_day' desc nulls last, item->>'name'), '[]'::jsonb)
  from (
    select jsonb_build_object(
      'user_id', p.user_id,
      'name', coalesce(nullif(p.display_name, ''), 'Без имени'),
      'friend', exists (select 1 from public.friends_of(auth.uid()) f where f.friend = p.user_id),
      'last_day', (select max(w.day) from public.workouts w where w.user_id = p.user_id and not w.deleted and w.finished_at is not null),
      'month', (
        select count(*) from public.workouts w
        where w.user_id = p.user_id and not w.deleted and w.finished_at is not null and w.day > current_date - 30
      ),
      'aim', (select a.value -> 'aim' from public.app_state a where a.user_id = p.user_id and a.key = 'program' and not a.deleted)
    ) as item
    from public.profiles p
    where auth.uid() is not null and p.listed and p.user_id <> auth.uid()
      and not exists (
        select 1 from public.blocks b
        where (b.blocker = p.user_id and b.blocked = auth.uid()) or (b.blocker = auth.uid() and b.blocked = p.user_id)
      )
  ) t;
$$;

-- One person's progress as far as the caller may see it: what friend_progress gives, a year of sets and runs for the
-- charts, the goals, and whether they are a friend, listed and open to messages.
create or replace function public.person_progress(p_user uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  me uuid := auth.uid();
  p public.profiles;
  doc jsonb;
begin
  if me is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  if not public.can_see(me, p_user) then
    raise exception 'not visible' using errcode = '42501';
  end if;
  select * into p from public.profiles where user_id = p_user;
  select a.value into doc from public.app_state a where a.user_id = p_user and a.key = 'profile' and not a.deleted;
  return jsonb_build_object(
    'name', coalesce(nullif(p.display_name, ''), 'Без имени'),
    'shared', true,
    'listed', coalesce(p.listed, false),
    'friend', exists (select 1 from public.friends_of(me) f where f.friend = p_user),
    'can_write', public.can_write(me, p_user),
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
        where w.user_id = p_user and not w.deleted and w.finished_at is not null and w.day > current_date - 365
        group by 1
      ) t
    ),
    'sets', (
      select coalesce(jsonb_agg(jsonb_build_object('day', w.day, 'exercise_id', s.exercise_id, 'weight', s.weight, 'reps', s.reps)
        order by w.day, s.position, s.done_at), '[]'::jsonb)
      from public.sets s
      join public.workouts w on w.user_id = s.user_id and w.id = s.workout_id
      where s.user_id = p_user and not s.deleted and not w.deleted and w.finished_at is not null and w.day > current_date - 365
    ),
    'runs', (
      select coalesce(jsonb_agg(jsonb_build_object(
        'day', w.day, 'type', r.type, 'distance_m', r.distance_m, 'moving_sec', r.moving_sec, 'avg_pace', r.avg_pace,
        'work_pace', r.work_pace, 'vdot', r.vdot_after
      ) order by w.day), '[]'::jsonb)
      from public.runs r
      join public.workouts w on w.user_id = r.user_id and w.id = r.workout_id
      where r.user_id = p_user and not r.deleted and not w.deleted and w.finished_at is not null and w.day > current_date - 365
    ),
    'run_goal', doc -> 'runGoal',
    'lift_goal', doc -> 'liftGoal',
    'program', (select a.value from public.app_state a where a.user_id = p_user and a.key = 'program' and not a.deleted),
    'custom', (select a.value from public.app_state a where a.user_id = p_user and a.key = 'custom_exercises' and not a.deleted),
    'body', case when p.share_body then (
      select coalesce(jsonb_agg(jsonb_build_object('day', b.day, 'kg', b.kg) order by b.day), '[]'::jsonb)
      from public.body_weights b where b.user_id = p_user and not b.deleted and b.day > current_date - 365
    ) end
  );
end;
$$;

-- One visible person's finished workouts, newest first, in the shape of friend_feed.
create or replace function public.person_feed(p_user uuid, p_before timestamptz default null, p_limit integer default 30)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  me uuid := auth.uid();
  who text;
begin
  if me is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  if not public.can_see(me, p_user) then
    raise exception 'not visible' using errcode = '42501';
  end if;
  select coalesce(nullif(p.display_name, ''), 'Без имени') into who from public.profiles p where p.user_id = p_user;
  return (
    with items as (
      select w.user_id, w.id, w.day, w.kind, w.title, w.started_at, w.duration_sec, w.rpe, w.deload, w.plan, w.summary
      from public.workouts w
      where w.user_id = p_user and not w.deleted and w.finished_at is not null and (p_before is null or w.started_at < p_before)
      order by w.started_at desc
      limit least(greatest(coalesce(p_limit, 30), 1), 100)
    )
    select coalesce(jsonb_agg(jsonb_build_object(
      'user_id', i.user_id, 'name', coalesce(who, 'Без имени'), 'workout_id', i.id, 'day', i.day, 'kind', i.kind, 'title', i.title,
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
    from items i
  );
end;
$$;

-- A message to someone the caller may write to: trimmed, 1–1000 characters, at most 20 a minute and 300 a day.
create or replace function public.send_message(p_to uuid, p_body text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := auth.uid();
  text_ text := trim(coalesce(p_body, ''));
  m public.messages;
begin
  if me is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  if char_length(text_) = 0 or char_length(text_) > 1000 then
    raise exception 'bad message' using errcode = '22023';
  end if;
  if not public.can_write(me, p_to) then
    raise exception 'cannot write' using errcode = '42501';
  end if;
  if (select count(*) from public.messages where sender = me and sent_at > now() - interval '1 minute') >= 20
    or (select count(*) from public.messages where sender = me and sent_at > now() - interval '1 day') >= 300 then
    raise exception 'too many messages' using errcode = '54000';
  end if;
  insert into public.messages (sender, recipient, body) values (me, p_to, text_) returning * into m;
  return jsonb_build_object('id', m.id, 'from_me', true, 'body', m.body, 'sent_at', m.sent_at, 'read_at', m.read_at);
end;
$$;

-- The caller's conversations, latest first: the other person, the last message, unread count, whether one can write.
create or replace function public.conversations()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  with mine as (
    select case when m.sender = auth.uid() then m.recipient else m.sender end as other, m.id, m.body, m.sent_at, m.sender
    from public.messages m
    where auth.uid() is not null and auth.uid() in (m.sender, m.recipient)
  ), last as (
    select distinct on (other) other, id, body, sent_at, sender from mine order by other, id desc
  )
  select coalesce(jsonb_agg(jsonb_build_object(
    'user_id', l.other,
    'name', coalesce(nullif(p.display_name, ''), 'Без имени'),
    'last', left(l.body, 200),
    'last_at', l.sent_at,
    'from_me', l.sender = auth.uid(),
    'unread', (select count(*) from public.messages u where u.recipient = auth.uid() and u.sender = l.other and u.read_at is null),
    'can_write', public.can_write(auth.uid(), l.other),
    'blocked', exists (select 1 from public.blocks b where b.blocker = auth.uid() and b.blocked = l.other)
  ) order by l.id desc), '[]'::jsonb)
  from last l
  left join public.profiles p on p.user_id = l.other;
$$;

-- Messages between the caller and [p_user], newest first; [p_before] (a message id) pages back.
create or replace function public.messages_with(p_user uuid, p_before bigint default null, p_limit integer default 50)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(jsonb_agg(jsonb_build_object(
    'id', t.id, 'from_me', t.sender = auth.uid(), 'body', t.body, 'sent_at', t.sent_at, 'read_at', t.read_at
  ) order by t.id desc), '[]'::jsonb)
  from (
    select m.id, m.sender, m.body, m.sent_at, m.read_at
    from public.messages m
    where auth.uid() is not null
      and ((m.sender = auth.uid() and m.recipient = p_user) or (m.sender = p_user and m.recipient = auth.uid()))
      and (p_before is null or m.id < p_before)
    order by m.id desc
    limit least(greatest(coalesce(p_limit, 50), 1), 200)
  ) t;
$$;

-- The messages from [p_user] to the caller marked read; how many were unread.
create or replace function public.mark_read(p_user uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  n integer;
begin
  if auth.uid() is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  update public.messages set read_at = now() where recipient = auth.uid() and sender = p_user and read_at is null;
  get diagnostics n = row_count;
  return n;
end;
$$;

-- Unread messages to the caller: how many, and from whom (for the badge and the notification).
create or replace function public.unread()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'count', coalesce(sum(t.n), 0),
    'from', coalesce(jsonb_agg(jsonb_build_object('user_id', t.sender, 'name', t.name, 'count', t.n, 'last_id', t.last_id, 'last', t.last)
      order by t.last_id desc), '[]'::jsonb)
  )
  from (
    select m.sender, coalesce(nullif(p.display_name, ''), 'Без имени') as name, count(*) as n, max(m.id) as last_id,
      (select left(x.body, 120) from public.messages x where x.id = max(m.id)) as last
    from public.messages m
    left join public.profiles p on p.user_id = m.sender
    where auth.uid() is not null and m.recipient = auth.uid() and m.read_at is null
    group by m.sender, p.display_name
  ) t;
$$;

-- Blocks or unblocks [p_user]: a block hides the caller from them and stops messages both ways.
create or replace function public.block_user(p_user uuid, p_block boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  if p_user is null or p_user = auth.uid() then
    return;
  end if;
  if p_block then
    insert into public.blocks (blocker, blocked) values (auth.uid(), p_user) on conflict do nothing;
  else
    delete from public.blocks where blocker = auth.uid() and blocked = p_user;
  end if;
end;
$$;

revoke all on function public.can_see(uuid, uuid), public.can_write(uuid, uuid) from public, anon, authenticated;
revoke all on function public.my_profile(text), public.set_listed(boolean), public.people(), public.person_progress(uuid),
  public.person_feed(uuid, timestamptz, integer), public.send_message(uuid, text), public.conversations(),
  public.messages_with(uuid, bigint, integer), public.mark_read(uuid), public.unread(), public.block_user(uuid, boolean)
  from public, anon;
grant execute on function public.my_profile(text), public.set_listed(boolean), public.people(), public.person_progress(uuid),
  public.person_feed(uuid, timestamptz, integer), public.send_message(uuid, text), public.conversations(),
  public.messages_with(uuid, bigint, integer), public.mark_read(uuid), public.unread(), public.block_user(uuid, boolean)
  to authenticated;
