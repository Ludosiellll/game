-- The social fixes of the audit (20261124120000_blocks_and_closed_friends.sql), as each person through the API roles.
-- E — a friend F blocks; F — E's friend who shares, then blocks E; G — someone F asked to be friends, then blocked;
-- H — E's friend who shares nothing and is not listed.
\set E '''00000000-0000-0000-0000-0000000000e1'''
\set F '''00000000-0000-0000-0000-0000000000f1'''
\set G '''00000000-0000-0000-0000-0000000000c2'''
\set H '''00000000-0000-0000-0000-0000000000d2'''

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

insert into auth.users values (:E), (:F), (:G), (:H);
insert into public.workouts (user_id, id, day, kind, title, started_at, finished_at, rpe, duration_sec)
values
  (:F, '50000000-0000-0000-0000-000000000001', current_date - 1, 'LIFT', 'Верх · А', now() - interval '1 day', now() - interval '1 day', 7, 3000),
  (:H, '60000000-0000-0000-0000-000000000001', current_date - 1, 'LIFT', 'Низ · А', now() - interval '1 day', now() - interval '1 day', 7, 3000);
insert into public.sets (user_id, id, workout_id, exercise_id, slot_key, position, weight, reps, done_at)
values (:H, '61000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 'squat', 'k', 0, 120, 5, now());

-- Nobody is listed: what a friend sees is what is tested. H shares nothing.
set role authenticated;
select pg_temp.who(:E); select public.my_profile('Ева'); select public.set_listed(false);
select pg_temp.who(:F); select public.my_profile('Фёдор'); select public.set_listed(false);
select pg_temp.who(:G); select public.my_profile('Гоша'); select public.set_listed(false);
select pg_temp.who(:H); select public.my_profile('Хана'); select public.set_listed(false); select public.set_sharing(false, false);
select pg_temp.who(:F);
select public.my_profile() ->> 'code' as f_code \gset
reset role;
insert into public.friendships (requester, addressee, accepted) values (:E, :F, true), (:E, :H, true), (:F, :G, false);
set role authenticated;

-- Before the block E sees F's training like any friend's, and writes to F.
select pg_temp.who(:E);
select pg_temp.ok(exists (select 1 from jsonb_array_elements(public.friend_feed()) i where i ->> 'user_id' = :F), 'E sees F''s workout before the block');
select pg_temp.ok((select f ->> 'last_day' from jsonb_array_elements(public.friends()) f where f ->> 'user_id' = :F) is not null, 'and F''s last training day');
select pg_temp.ok(public.send_message(:F::uuid, 'Привет') is not null, 'E writes to F');

-- F blocks E (from the chat, or with a complaint), and G, whom F asked to be friends.
select pg_temp.who(:F);
select pg_temp.ok((public.unread() ->> 'count')::int = 1, 'F has E''s message unread');
select public.block_user(:E::uuid, true);
select public.block_user(:G::uuid, true);
-- What E wrote before the block is neither counted nor told to F.
select pg_temp.ok((public.unread() ->> 'count')::int = 0 and jsonb_array_length(public.unread() -> 'from') = 0, 'no unread from someone F blocked');
select pg_temp.ok((select (c ->> 'unread')::int from jsonb_array_elements(public.conversations()) c where c ->> 'user_id' = :E) = 0, 'nor in the conversation');

-- E no longer sees F's training: not in the feed, not asked for by name, no last day, nothing through the old call.
select pg_temp.who(:E);
select pg_temp.ok(not exists (select 1 from jsonb_array_elements(public.friend_feed()) i where i ->> 'user_id' = :F), 'no workouts of the one who blocked in the feed');
select pg_temp.ok(jsonb_array_length(public.friend_feed(null, 30, :F::uuid)) = 0, 'nor asked for by name');
select pg_temp.ok((select f ->> 'last_day' from jsonb_array_elements(public.friends()) f where f ->> 'user_id' = :F) is null, 'no last training day');
select pg_temp.ok(public.friend_progress(:F::uuid) ->> 'shared' = 'false' and public.friend_progress(:F::uuid) -> 'stats' is null, 'friend_progress shares nothing');
select pg_temp.refused(format('select public.person_progress(%L)', :F), '42501', 'the page of the one who blocked stays closed');
select pg_temp.refused(format('select public.person_feed(%L)', :F), '42501', 'and their workouts');
-- Nor does F's code take a request from E.
select pg_temp.ok(public.add_friend(:'f_code') = 'not_found', 'the code of the one who blocked answers as nobody''s');

-- G: F's request went with the block. One left from before the block (as on the server before this migration) is not
-- accepted, neither by G's own request nor by «Принять».
select pg_temp.who(:G);
select pg_temp.ok(not exists (select 1 from jsonb_array_elements(public.friends()) f where f ->> 'user_id' = :F), 'the block withdrew F''s request');
reset role;
insert into public.friendships (requester, addressee, accepted) values (:F, :G, false) on conflict do nothing;
set role authenticated;
select pg_temp.who(:G);
select pg_temp.ok(public.add_friend(:'f_code') <> 'accepted', 'no friendship by G''s request while blocked');
select public.answer_friend(:F::uuid, true);
reset role;
select pg_temp.ok(not exists (select 1 from public.friendships where accepted and :F in (requester, addressee) and :G in (requester, addressee)), 'no friendship while blocked');
set role authenticated;

-- Unblocked: E sees F's training again.
select pg_temp.who(:F); select public.block_user(:E::uuid, false);
select pg_temp.who(:E);
select pg_temp.ok(exists (select 1 from jsonb_array_elements(public.friend_feed()) i where i ->> 'user_id' = :F), 'unblocked, F''s workout is back');

-- H shares nothing and is not listed: E, her friend, opens her page with her name and «Написать», and none of her
-- training (it was an error, and the page had no «Написать»).
select pg_temp.who(:E);
select pg_temp.ok(public.person_progress(:H::uuid) ->> 'name' = 'Хана' and (public.person_progress(:H::uuid) ->> 'shared')::boolean = false, 'her name, not shared');
select pg_temp.ok((public.person_progress(:H::uuid) ->> 'friend')::boolean and (public.person_progress(:H::uuid) ->> 'can_write')::boolean, 'a friend one may write to');
select pg_temp.ok(public.person_progress(:H::uuid) -> 'sets' is null and public.person_progress(:H::uuid) -> 'stats' is null
  and public.person_progress(:H::uuid) -> 'program' is null and public.person_progress(:H::uuid) -> 'runs' is null, 'none of her training');
select pg_temp.ok(jsonb_array_length(public.person_feed(:H::uuid)) = 0, 'no workouts on her page');
select pg_temp.ok(public.send_message(:H::uuid, 'Как дела?') is not null, 'E writes to H');
-- Anyone else finds her closed as before.
select pg_temp.who(:G);
select pg_temp.refused(format('select public.person_progress(%L)', :H), '42501', 'G must not open H');
select pg_temp.refused(format('select public.person_feed(%L)', :H), '42501', 'nor read H''s workouts');
reset role;

-- Made anew, the functions keep their grants: closed to anonymous callers.
set role anon;
select pg_temp.refused('select public.friend_feed()', '42501', 'anon must not read the feed');
select pg_temp.refused('select public.unread()', '42501', 'anon must not count unread');
select pg_temp.refused(format('select public.person_progress(%L)', :H), '42501', 'anon must not open a page');
select pg_temp.refused(format('select public.block_user(%L, true)', :H), '42501', 'anon must not block');
reset role;
