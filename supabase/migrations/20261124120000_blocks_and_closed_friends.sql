-- A block now hides the blocker from a friend they blocked, as the people migration promised («A block hides the blocker
-- from the blocked»): the friend functions of the friends migration never looked at blocks, so a blocked friend kept the
-- blocker's workouts in «Что делают друзья», their last training day in «Друзья», and could ask to be friends again (or
-- accept an old request of theirs at once). A block also withdraws the friend requests between the two, and a blocked
-- person's messages no longer count as unread for the one who blocked them (no badge, no notification).
-- And a friend who shares nothing and is not listed opens with their name and «Написать» (friends may write to each other
-- whatever they share), not with an error: person_progress gives what friend_progress used to give such a friend, and
-- person_feed an empty list. None of their training is shown.
-- Every other line of these functions is as in 20261010124606_friends.sql and 20261030120000_people.sql.

-- Friends and requests of the caller: state is friend, incoming or outgoing. No last training day of a friend who
-- blocked the caller.
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
    'last_day', case when o.state = 'friend' and coalesce(p.share_activity, false)
      and not exists (select 1 from public.blocks b where b.blocker = o.other and b.blocked = auth.uid()) then (
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
-- [p_user] narrows to one friend; [p_before] pages back. Never of a friend who blocked the caller.
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
      and not exists (select 1 from public.blocks b where b.blocker = fo.friend and b.blocked = auth.uid())
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
-- copy, body weight when shared. A friend who blocked the caller shares nothing with them.
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
  if not coalesce(p.share_activity, false)
    or exists (select 1 from public.blocks b where b.blocker = p_user and b.blocked = me) then
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

-- Sends a request by friend code; accepts at once if that person already asked.
-- Returns: requested, accepted, already, not_found or self. A block either way answers as a code nobody has: no
-- request is sent and none is accepted, and the blocked one does not learn of the block from the answer.
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
  if exists (select 1 from public.blocks b where (b.blocker = other and b.blocked = me) or (b.blocker = me and b.blocked = other)) then
    return 'not_found';
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

-- Accepts or declines a request sent to the caller. Not accepted while a block stands either way.
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
    update public.friendships set accepted = true
    where requester = p_user and addressee = auth.uid()
      and not exists (
        select 1 from public.blocks b
        where (b.blocker = p_user and b.blocked = auth.uid()) or (b.blocker = auth.uid() and b.blocked = p_user)
      );
  else
    delete from public.friendships where requester = p_user and addressee = auth.uid() and not accepted;
  end if;
end;
$$;

-- One person's progress as far as the caller may see it: what friend_progress gives, a year of sets and runs for the
-- charts, the goals, and whether they are a friend, listed and open to messages. A friend who shares nothing and is not
-- listed: only their name, that they are a friend and whether one may write to them.
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
    if exists (select 1 from public.friends_of(me) f where f.friend = p_user)
      and not exists (select 1 from public.blocks b where b.blocker = p_user and b.blocked = me) then
      select * into p from public.profiles where user_id = p_user;
      return jsonb_build_object(
        'name', coalesce(nullif(p.display_name, ''), 'Без имени'),
        'shared', false,
        'listed', coalesce(p.listed, false),
        'friend', true,
        'can_write', public.can_write(me, p_user)
      );
    end if;
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

-- One visible person's finished workouts, newest first, in the shape of friend_feed. None (and no error) for a friend
-- who shares nothing and is not listed: their page opens with their name.
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
    if exists (select 1 from public.friends_of(me) f where f.friend = p_user)
      and not exists (select 1 from public.blocks b where b.blocker = p_user and b.blocked = me) then
      return '[]'::jsonb;
    end if;
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

-- The caller's conversations, latest first: the other person, the last message, unread count, whether one can write.
-- Nothing unread from someone the caller blocked.
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
    'unread', (
      select count(*) from public.messages u
      where u.recipient = auth.uid() and u.sender = l.other and u.read_at is null
        and not exists (select 1 from public.blocks b where b.blocker = auth.uid() and b.blocked = l.other)
    ),
    'can_write', public.can_write(auth.uid(), l.other),
    'blocked', exists (select 1 from public.blocks b where b.blocker = auth.uid() and b.blocked = l.other)
  ) order by l.id desc), '[]'::jsonb)
  from last l
  left join public.profiles p on p.user_id = l.other;
$$;

-- Unread messages to the caller: how many, and from whom (for the badge and the notification). Not from someone the
-- caller blocked: what they wrote before the block is neither counted nor told.
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
      and not exists (select 1 from public.blocks b where b.blocker = auth.uid() and b.blocked = m.sender)
    group by m.sender, p.display_name
  ) t;
$$;

-- Blocks or unblocks [p_user]: a block hides the caller from them and stops messages both ways, and withdraws the
-- friend requests between the two (a friendship stays until either removes it).
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
    delete from public.friendships
    where not accepted and ((requester = auth.uid() and addressee = p_user) or (requester = p_user and addressee = auth.uid()));
  else
    delete from public.blocks where blocker = auth.uid() and blocked = p_user;
  end if;
end;
$$;

-- The requests left between people blocked before this migration go as a new block's would.
delete from public.friendships f
where not f.accepted and exists (
  select 1 from public.blocks b
  where (b.blocker = f.requester and b.blocked = f.addressee) or (b.blocker = f.addressee and b.blocked = f.requester)
);
