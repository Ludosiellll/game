# People tab: who is open to all, their progress, mixed copy, messages

Agreed with Denis on 2026-10-30 (his answers: listed only by own choice; messages to anyone listed and to friends;
copy «their gym + my running»; SQL allowed).

## What the person sees

- **«Люди»** — everyone who turned «Показывать меня всем» on (off by default), and friends: name, last workout day,
  workouts in 30 days, what they train for. Friends by code stay as they are.
- **A person's page** — their progress the way «Прогресс» shows one's own: strength index and bests, weekly sessions,
  running fitness (VDOT), weekly kilometres, goals, recent workouts (copy one, as now), body weight only if they share
  it. Never: GPS tracks, notes, the profile document (age, height, heart rate) — only the goals from it.
- **Copy the plan** — «Всё», «Его зал + мой бег», «Мой зал + его бег». A mixed copy is the person's own week with
  their gym days (or running days) from the other program; before «Принять» the week's load is checked and said
  straight (sessions, days without rest, heavy legs the day before a hard run, hard days in a row). «Вернуть свою»
  as now.
- **Messages** — to anyone listed and to friends; a conversation list with unread counts, a chat that refreshes while
  open, a system notification within ~15 min (the sync worker), block anyone. Rate limit against spam.

## Server (project Training, migration `20261030120000_people.sql`)

- `profiles.listed boolean default false`; `messages` (sender, recipient, body ≤ 1000, sent_at, read_at);
  `blocks` (blocker, blocked). RLS on, no table access from the Data API: functions only.
- `can_see(me, other)`: other is me, or listed, or a friend who shares — and other has not blocked me.
  `can_write(me, other)`: listed or friend, no block either way.
- Functions (security definer, `search_path = ''`, signed-in only): `set_listed`, `my_profile` (+ listed),
  `people`, `person_progress` (friend_progress + a year of sets and runs, goals, flags), `person_feed`,
  `send_message`, `conversations`, `messages_with`, `mark_read`, `unread`, `block_user`.
- Tested on a local Postgres 16 with a stub `auth` schema before it is applied.

## App

- `FriendsApi` grows the calls above; the tab gets «Люди · Друзья · Сообщения»; the person page builds its charts
  from the rows with the same chart components as «Прогресс»; the chat screen polls every 5 s while open;
  `SyncWorker` checks `unread` and notifies.
- `CopyPart`: ALL, THEIR_GYM (their gym + own running), THEIR_RUN (own gym + their running); `WeekLoad` (core) says
  what is too much.

## Tests

SQL: who sees what, blocked cannot write, rate limit, read marks (local Postgres). App: API decoding, mixed copy
week, load warnings, the person page with fake data, chat send and read, notification once per new message.
