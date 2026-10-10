-- rls_auto_enable() is the event trigger behind "Enable automatic RLS"; it only needs to run on DDL,
-- not through the Data API. Remove API execution as the security advisor recommends.
revoke execute on function public.rls_auto_enable() from public, anon, authenticated;
