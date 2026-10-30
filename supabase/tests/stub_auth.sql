-- The parts of a Supabase project the migrations lean on, for a plain local Postgres: the API roles, auth.users and
-- auth.uid() (read from request.jwt.claim.sub, as PostgREST sets it), and the platform's own rls_auto_enable().
create role anon nologin;
create role authenticated nologin;
create schema auth;
create table auth.users (id uuid primary key);
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
grant usage on schema auth to anon, authenticated;
grant execute on function auth.uid() to anon, authenticated;
grant usage on schema public to anon, authenticated;
create function public.rls_auto_enable() returns void language sql as $$ select $$;
