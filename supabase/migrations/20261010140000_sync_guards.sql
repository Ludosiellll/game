-- Sync guards. The phones already merge carefully; these rules make the server hold the line on its own:
-- per-exercise progress keeps the newer of two changes when phones send them in turn (one was offline), and a deleted
-- workout, set or run never comes back to life (an old phone or an old backup file cannot resurrect it).

create or replace function public.keep_newer_progress()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  -- An older change arriving late does not replace a newer one. The row is then saved unchanged (and gets a new
  -- updated_at), so the phone that sent it downloads the newer one on its next sync.
  if new.changed_at < old.changed_at and not new.deleted and not old.deleted then
    return old;
  end if;
  return new;
end;
$$;

create or replace function public.keep_deleted()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.deleted and not new.deleted then
    return old;
  end if;
  return new;
end;
$$;

-- Before touch_updated_at (triggers of one event fire in name order).
create trigger keep_newer_progress before update on public.slot_states for each row execute function public.keep_newer_progress();
create trigger keep_newer_progress before update on public.exercise_stats for each row execute function public.keep_newer_progress();
create trigger keep_deleted before update on public.workouts for each row execute function public.keep_deleted();
create trigger keep_deleted before update on public.sets for each row execute function public.keep_deleted();
create trigger keep_deleted before update on public.runs for each row execute function public.keep_deleted();

revoke all on function public.keep_newer_progress() from public, anon, authenticated;
revoke all on function public.keep_deleted() from public, anon, authenticated;
