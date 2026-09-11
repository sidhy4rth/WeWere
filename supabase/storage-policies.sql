-- Roll — Supabase Storage bucket and access policies.
--
-- Run once in the Supabase dashboard: SQL Editor -> New query -> paste -> Run.
-- Re-running is safe; everything is idempotent.
--
-- Replace roll-3a292 below with your own Firebase project ID if it differs.
--
-- How auth works here, read before changing anything:
--
-- The app never signs in to Supabase. It sends the user's *Firebase* ID token, and
-- Supabase is told to trust Firebase as a third-party issuer (Authentication ->
-- Sign In / Providers -> Third-party auth -> Firebase). Supabase verifies the
-- signature, then exposes the token's claims to these policies via auth.jwt().
--
-- Supabase would normally expect a `role: "authenticated"` claim in that token.
-- Adding one needs a Firebase Cloud Function, which needs the Blaze plan — the very
-- thing this setup avoids. Without it Supabase runs the request as the `anon` role,
-- so every policy below targets `anon` and instead proves the caller is a real
-- Firebase user by checking the issuer and the uid itself. A bare anon-key request
-- (no user token) has neither and matches nothing.
--
-- Membership is NOT enforced here, for the same reason it was not enforceable in
-- Firebase Storage rules: the bucket cannot query Firestore. Object names are
-- unguessable ids and the only place a path is published is the group's Firestore
-- document, which is membership-gated. See README -> Privacy.

-- 1. The bucket. Private: reads need a token or a signed URL. ----------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('roll', 'roll', false, 15 * 1024 * 1024, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do update
  set public = excluded.public,
      file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

-- 2. Who is calling. Returns the Firebase uid, or null for anything else. ------------
create or replace function public.firebase_uid()
returns text
language sql
stable
as $$
  select case
    when auth.jwt() ->> 'iss' = 'https://securetoken.google.com/roll-3a292'
    then auth.jwt() ->> 'sub'
  end;
$$;

-- 3. Where an object is allowed to live. Mirrors StoragePaths in the app. ------------
--    groups/{groupId}/{full|thumbs|covers}/{file}   any signed-in user
--    avatars/{uid}.jpg                              only that user
create or replace function public.roll_path_allowed(object_name text)
returns boolean
language sql
stable
as $$
  select
    (
      (storage.foldername(object_name))[1] = 'groups'
      and array_length(storage.foldername(object_name), 1) = 3
      and (storage.foldername(object_name))[3] in ('full', 'thumbs', 'covers')
    )
    or object_name = 'avatars/' || public.firebase_uid() || '.jpg';
$$;

-- 4. Policies. -------------------------------------------------------------------------
drop policy if exists "roll: signed-in users can read" on storage.objects;
create policy "roll: signed-in users can read"
  on storage.objects for select
  to anon, authenticated
  using (bucket_id = 'roll' and public.firebase_uid() is not null);

drop policy if exists "roll: signed-in users can upload" on storage.objects;
create policy "roll: signed-in users can upload"
  on storage.objects for insert
  to anon, authenticated
  with check (
    bucket_id = 'roll'
    and public.firebase_uid() is not null
    and public.roll_path_allowed(name)
  );

-- Upsert (a retried upload overwriting itself) needs update as well as insert.
drop policy if exists "roll: signed-in users can overwrite" on storage.objects;
create policy "roll: signed-in users can overwrite"
  on storage.objects for update
  to anon, authenticated
  using (bucket_id = 'roll' and public.firebase_uid() is not null)
  with check (
    bucket_id = 'roll'
    and public.firebase_uid() is not null
    and public.roll_path_allowed(name)
  );

drop policy if exists "roll: signed-in users can delete" on storage.objects;
create policy "roll: signed-in users can delete"
  on storage.objects for delete
  to anon, authenticated
  using (bucket_id = 'roll' and public.firebase_uid() is not null);
