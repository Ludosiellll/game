-- Who sees whom and who may write to whom (20261030120000_people.sql), as each person through the API roles.
-- A — the caller (Denis); B — his friend who shares; C — a stranger, not listed; D — listed for everybody.
\set A '''00000000-0000-0000-0000-00000000000a'''
\set B '''00000000-0000-0000-0000-00000000000b'''
\set C '''00000000-0000-0000-0000-00000000000c'''
\set D '''00000000-0000-0000-0000-00000000000d'''

create function pg_temp.ok(c boolean, msg text) returns void language plpgsql as $$
begin
  if not coalesce(c, false) then raise exception 'FAILED: %', msg; end if;
end $$;
create function pg_temp.who(u uuid) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claim.sub', u::text, false);
end $$;
-- [sql] fails for the caller with [state] (42501 — not allowed).
create function pg_temp.refused(sql text, state text, msg text) returns void language plpgsql as $$
begin
  execute sql;
  raise exception 'FAILED (not refused): %', msg using errcode = 'P0001';
exception when others then
  if sqlstate <> state then raise; end if;
end $$;
grant execute on all functions in schema pg_temp to authenticated, anon;

insert into auth.users values (:A), (:B), (:C), (:D);

-- Their training, as the phones would have synced it.
insert into public.workouts (user_id, id, day, kind, title, started_at, finished_at, rpe, duration_sec)
values
  (:B, '10000000-0000-0000-0000-000000000001', current_date - 3, 'LIFT', 'Всё тело · А', now() - interval '3 days', now() - interval '3 days', 7, 3000),
  (:D, '20000000-0000-0000-0000-000000000001', current_date - 2, 'LIFT', 'Верх · А', now() - interval '2 days', now() - interval '2 days', 8, 3300),
  (:D, '20000000-0000-0000-0000-000000000002', current_date - 1, 'RUN', 'Лёгкий бег', now() - interval '1 day', now() - interval '1 day', 4, 2400),
  (:D, '20000000-0000-0000-0000-000000000003', current_date - 400, 'RUN', 'Старый бег', now() - interval '400 days', now() - interval '400 days', 4, 2400),
  (:C, '30000000-0000-0000-0000-000000000001', current_date - 1, 'LIFT', 'Секрет', now() - interval '1 day', now() - interval '1 day', 7, 3000);
insert into public.sets (user_id, id, workout_id, exercise_id, slot_key, position, weight, reps, done_at)
values
  (:B, '11000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'squat', 'k', 0, 100, 5, now()),
  (:D, '21000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'bench', 'k', 0, 80, 6, now()),
  (:D, '21000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', 'bench', 'k', 0, 82.5, 4, now());
insert into public.runs (user_id, workout_id, type, plan, completed, moving_sec, distance_m, avg_pace, vdot_after, track)
values
  (:D, '20000000-0000-0000-0000-000000000002', 'EASY', '{}', 1, 2390, 7200, 331.9, 44.5, '{"points":"secret"}'),
  (:D, '20000000-0000-0000-0000-000000000003', 'EASY', '{}', 1, 2390, 7000, 340, 43, null);
insert into public.app_state (user_id, key, value)
values
  (:D, 'program', '{"aim":{"lift":"MUSCLE","run":"ENDURANCE","emphasis":"BALANCED"},"templates":[]}'),
  (:D, 'profile', '{"runGoal":{"meters":1500,"seconds":330},"liftGoal":null,"birthYear":1990,"heightCm":181,"restingHr":52}'),
  (:D, 'settings', '{"notes":"secret"}');
insert into public.body_weights (user_id, day, kg) values (:D, current_date - 1, 81.5);

set role authenticated;

-- Everyone gets a profile; D opens up; A and B become friends.
select pg_temp.who(:A); select public.my_profile('Денис');
select pg_temp.who(:B); select public.my_profile('Егор');
select pg_temp.who(:C); select public.my_profile('Чужой');
select pg_temp.who(:D); select public.my_profile('Открытый');
select pg_temp.ok((public.my_profile() ->> 'listed')::boolean = false, 'listed is off by default');
select pg_temp.ok((public.set_listed(true) ->> 'listed')::boolean, 'D is listed');
reset role;
insert into public.friendships (requester, addressee, accepted) values (:A, :B, true);
set role authenticated;

-- The list: D only (B is a friend, not listed; C is not listed; never oneself).
select pg_temp.who(:A);
select pg_temp.ok(jsonb_array_length(public.people()) = 1 and public.people() -> 0 ->> 'user_id' = :D, 'A sees D alone in the list');
select pg_temp.ok(public.people() -> 0 ->> 'name' = 'Открытый' and (public.people() -> 0 ->> 'month')::int = 2, 'name and workouts in 30 days');
select pg_temp.ok(public.people() -> 0 -> 'aim' ->> 'lift' = 'MUSCLE', 'what D trains for');

-- Pages: D (listed) and B (a friend who shares) are open to A; C is not.
select pg_temp.ok(public.person_progress(:D::uuid) ->> 'name' = 'Открытый', 'A opens D');
select pg_temp.ok(public.person_progress(:B::uuid) ->> 'name' = 'Егор', 'A opens his friend B');
select pg_temp.refused(format('select public.person_progress(%L)', :C), '42501', 'A must not open C');
select pg_temp.refused(format('select public.person_feed(%L)', :C), '42501', 'A must not read C''s workouts');
-- A friend who stopped sharing is closed again (not listed either).
select pg_temp.who(:B); select public.set_sharing(false, false);
select pg_temp.who(:A);
select pg_temp.refused(format('select public.person_progress(%L)', :B), '42501', 'a friend who does not share');
select pg_temp.who(:B); select public.set_sharing(true, false);
select pg_temp.who(:A);

-- What D's page holds: a year of sets and runs, goals, no track, no profile document beyond goals, no weight.
select pg_temp.ok(jsonb_array_length(public.person_progress(:D::uuid) -> 'sets') = 2, 'D''s sets');
select pg_temp.ok(jsonb_array_length(public.person_progress(:D::uuid) -> 'runs') = 1, 'runs of the last year only');
select pg_temp.ok(public.person_progress(:D::uuid) -> 'run_goal' ->> 'meters' = '1500', 'the run goal');
select pg_temp.ok(position('secret' in public.person_progress(:D::uuid)::text) = 0, 'no track, no settings');
select pg_temp.ok(position('1990' in public.person_progress(:D::uuid)::text) = 0 and position('restingHr' in public.person_progress(:D::uuid)::text) = 0, 'no age, no pulse');
select pg_temp.ok(public.person_progress(:D::uuid) -> 'body' = 'null'::jsonb, 'no body weight unless shared');
select pg_temp.ok(jsonb_array_length(public.person_feed(:D::uuid)) = 3, 'D''s workouts in the feed');
select pg_temp.ok(position('secret' in public.person_feed(:D::uuid)::text) = 0, 'the feed has no track either');

-- C, a stranger, sees D (listed) but not B (a friend of A's only) — until B opens up.
select pg_temp.who(:C);
select pg_temp.ok(public.person_progress(:D::uuid) ->> 'name' = 'Открытый', 'C opens D');
select pg_temp.refused(format('select public.person_progress(%L)', :B), '42501', 'C must not open B');
select pg_temp.who(:B); select public.set_listed(true);
select pg_temp.who(:C);
select pg_temp.ok(public.person_progress(:B::uuid) ->> 'name' = 'Егор', 'C opens B once listed');
select pg_temp.who(:B); select public.set_listed(false);

-- Messages: to a friend and to a listed person; never to a stranger who is neither.
select pg_temp.who(:A);
select pg_temp.ok(public.send_message(:B::uuid, '  Привет  ') ->> 'body' = 'Привет', 'A writes to his friend, trimmed');
select pg_temp.refused(format('select public.send_message(%L, %L)', :C, 'привет'), '42501', 'A must not write to C');
select pg_temp.refused(format('select public.send_message(%L, %L)', :B, '   '), '22023', 'an empty message');
select pg_temp.refused(format('select public.send_message(%L, %L)', :B, repeat('а', 1001)), '22023', 'a too long message');
select pg_temp.who(:C);
select pg_temp.ok(public.send_message(:D::uuid, 'Как тренируешься?') is not null, 'C writes to listed D');

-- B reads: one unread from A, marked read once.
select pg_temp.who(:B);
select pg_temp.ok((public.unread() ->> 'count')::int = 1 and public.unread() -> 'from' -> 0 ->> 'name' = 'Денис', 'B has one unread from A');
select pg_temp.ok((public.conversations() -> 0 ->> 'unread')::int = 1 and (public.conversations() -> 0 ->> 'can_write')::boolean, 'B''s conversation with A');
select pg_temp.ok(public.mark_read(:A::uuid) = 1 and public.mark_read(:A::uuid) = 0, 'read once');
select pg_temp.ok((public.unread() ->> 'count')::int = 0, 'nothing unread');
select pg_temp.ok(public.send_message(:A::uuid, 'Здорово') is not null, 'B answers');
select pg_temp.ok(jsonb_array_length(public.messages_with(:A::uuid)) = 2 and (public.messages_with(:A::uuid) -> 0 ->> 'from_me')::boolean, 'the chat, newest first');
select pg_temp.ok(jsonb_array_length(public.messages_with(:A::uuid, (public.messages_with(:A::uuid) -> 0 ->> 'id')::bigint)) = 1, 'paging back');
-- Nobody else reads a chat that is not theirs.
select pg_temp.who(:C);
select pg_temp.ok(jsonb_array_length(public.messages_with(:A::uuid)) = 0, 'C sees nothing of A and B');

-- A block: D blocks C — C no longer finds D, opens D or writes to D, and D cannot write to C either.
select pg_temp.who(:D); select public.block_user(:C::uuid, true);
select pg_temp.who(:C);
select pg_temp.ok(jsonb_array_length(public.people()) = 0, 'C no longer sees D in the list');
select pg_temp.refused(format('select public.person_progress(%L)', :D), '42501', 'C must not open D');
select pg_temp.refused(format('select public.send_message(%L, %L)', :D, 'эй'), '42501', 'C must not write to D');
select pg_temp.who(:D);
select pg_temp.ok((public.conversations() -> 0 ->> 'blocked')::boolean and not (public.conversations() -> 0 ->> 'can_write')::boolean, 'D sees the block');
select public.block_user(:C::uuid, false);
select pg_temp.who(:C);
select pg_temp.ok(jsonb_array_length(public.people()) = 1, 'unblocked');

-- Too many at once: 20 a minute.
select pg_temp.who(:C);
do $$ begin for i in 1..19 loop perform public.send_message('00000000-0000-0000-0000-00000000000d', 'сообщение ' || i); end loop; end $$;
select pg_temp.refused(format('select public.send_message(%L, %L)', :D, 'ещё'), '54000', 'the 21st message in a minute');

-- The tables themselves are closed to the API roles, and the lists to anonymous callers.
select pg_temp.refused('select count(*) from public.messages', '42501', 'messages table closed');
select pg_temp.refused('select count(*) from public.blocks', '42501', 'blocks table closed');
select pg_temp.refused(format('select public.can_see(%L, %L)', :C, :D), '42501', 'can_see is internal');
reset role;
set role anon;
select pg_temp.refused('select public.people()', '42501', 'anon must not list people');
select pg_temp.refused(format('select public.send_message(%L, %L)', :D, 'x'), '42501', 'anon must not write');
reset role;

-- An account deleted takes its messages and blocks with it.
delete from auth.users where id = :C;
select pg_temp.ok(not exists (select 1 from public.messages where sender = :C or recipient = :C), 'messages gone with the account');
select pg_temp.ok(not exists (select 1 from public.blocks where blocker = :C or blocked = :C), 'blocks gone with the account');
