-- Complaints (20261031120000_reports.sql): filed by a signed-in person about another, read back by no one through the API.
\set A '''00000000-0000-0000-0000-0000000000a1'''
\set B '''00000000-0000-0000-0000-0000000000b1'''

create function pg_temp.ok(c boolean, msg text) returns void language plpgsql as $$
begin
  if not coalesce(c, false) then raise exception 'FAILED: %', msg; end if;
end $$;
create function pg_temp.who(u uuid) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claim.sub', u::text, false);
end $$;
create function pg_temp.refused(sql text, state text, msg text) returns void language plpgsql as $$
begin
  execute sql;
  raise exception 'FAILED (not refused): %', msg using errcode = 'P0001';
exception when others then
  if sqlstate <> state then raise; end if;
end $$;
grant execute on all functions in schema pg_temp to authenticated, anon;

insert into auth.users values (:A), (:B);

set role authenticated;
select pg_temp.who(:A);
select public.report_user(:B, '  Пишет гадости  ');
select pg_temp.refused(format('select public.report_user(%L, %L)', :A, 'сам на себя'), '22023', 'no complaint about oneself');
select pg_temp.refused(format('select public.report_user(%L, %L)', :B, '   '), '22023', 'an empty reason is refused');
select pg_temp.refused(format('select public.report_user(%L, %L)', '00000000-0000-0000-0000-0000000000ff', 'кто это'), '22023', 'no complaint about no one');
-- Read back by no one through the API.
select pg_temp.refused('select count(*) from public.reports', '42501', 'reports table closed');
-- Ten a day at most.
select public.report_user(:B, 'ещё ' || g) from generate_series(1, 9) g;
select pg_temp.refused(format('select public.report_user(%L, %L)', :B, 'одиннадцатая'), '54000', 'eleventh complaint of the day refused');
reset role;

select pg_temp.ok((select count(*) from public.reports where reporter = :A and reported = :B) = 10, 'ten complaints kept');
select pg_temp.ok((select reason from public.reports where reporter = :A order by id limit 1) = 'Пишет гадости', 'reason trimmed');

set role anon;
select pg_temp.refused(format('select public.report_user(%L, %L)', :B, 'x'), '42501', 'anon must not report');
reset role;

-- An account deleted takes its complaints with it, both ways.
delete from auth.users where id = :B;
select pg_temp.ok(not exists (select 1 from public.reports where reported = :B), 'complaints gone with the account');
