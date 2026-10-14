-- Deleting one's own account from the app ("Удалить аккаунт и данные в облаке" on the cloud screen).
-- Every table of the app references auth.users with "on delete cascade" (workouts, sets, runs, slot_states,
-- exercise_stats, body_weights, app_state, profiles, friendships): removing the caller's auth user removes all
-- of their rows, friendships and friend profile with it. Only the caller can delete themselves.

create or replace function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := auth.uid();
begin
  if me is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  delete from auth.users where id = me;
end;
$$;

revoke all on function public.delete_my_account() from public, anon, authenticated;
grant execute on function public.delete_my_account() to authenticated;
