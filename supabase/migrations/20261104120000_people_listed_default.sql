-- «Люди»: everybody is in the list by default (Denis, 4 November: «чтобы всех автоматически (по умолчанию) было видно»).
-- New profiles start listed, and the ones there are now are listed too. The app says so once on «Люди», and
-- «Показывать меня всем» turns it off; routes, age, height, pulse and notes stay hidden as before.
alter table public.profiles alter column listed set default true;
update public.profiles set listed = true where not listed;
