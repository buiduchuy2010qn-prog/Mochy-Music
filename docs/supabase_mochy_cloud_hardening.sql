-- =============================================================================
-- Mochy Cloud v11.9.0 — Security Hardening, Anti-Spam DB Caps, RLS Lockdown
-- & Zero-Gmail-Leak Account Resolver for Supabase (PostgreSQL)
-- =============================================================================
--
-- HOW TO APPLY (2 steps, 100% idempotent and safe to re-run):
--   Step 1: Deploy Edge Function `mochy-username-login` from
--           `supabase/functions/mochy-username-login/index.ts`
--           (turn OFF "Verify JWT" / deploy with `--no-verify-jwt`).
--   Step 2: Paste and RUN this entire SQL file once in Supabase SQL Editor.
--
-- What this script protects against:
--   1. Strict RLS (`(select auth.uid()) = user_id`) + REVOKE ALL from `anon`
--      on mochy_profiles, mochy_favorites, mochy_history.
--   2. Locks `public.mochy_resolve_login_account` to `service_role` ONLY so
--      unauthenticated callers can NEVER enumerate usernames or read Gmail.
--   3. Adds Postgres fixed-window rate-limiting (`mochy_auth_rate_limits` +
--      `mochy_check_auth_rate_limit`) for the Edge Function (anti-brute-force).
--   4. Prevents DB bloat / Free-tier 500 MB exhaustion:
--      - mochy_profiles: validates username format, case-insensitive uniqueness,
--        avatar WebP <= 34 KB Base64, and 30-second avatar change cooldown.
--      - mochy_favorites: blocks LOCAL_ IDs, caps string lengths, max 3,000 rows/user.
--      - mochy_history: blocks LOCAL_ IDs, caps string lengths, auto-prunes oldest
--        rows above 1,000 tracks/user so real users never break while spammers
--        cannot grow storage beyond ~250 KB per account.
--   5. Adds missing composite indexes for fast ordered queries & RLS checks.
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- 1. Unique case-insensitive @username + avatar cooldown column on mochy_profiles
-- -----------------------------------------------------------------------------
ALTER TABLE public.mochy_profiles
    ADD COLUMN IF NOT EXISTS avatar_updated_at timestamptz;

CREATE UNIQUE INDEX IF NOT EXISTS mochy_profiles_username_lower_uidx
    ON public.mochy_profiles (lower(username));

-- Composite indexes matching MochySupabaseClient ordered queries & per-user caps
CREATE INDEX IF NOT EXISTS mochy_favorites_user_liked_idx
    ON public.mochy_favorites (user_id, liked_at DESC);

CREATE INDEX IF NOT EXISTS mochy_history_user_played_idx
    ON public.mochy_history (user_id, last_played_at DESC);

-- -----------------------------------------------------------------------------
-- 2. Strict Table Privileges & Row Level Security (RLS) Lockdown
-- -----------------------------------------------------------------------------
REVOKE ALL ON TABLE public.mochy_profiles, public.mochy_favorites, public.mochy_history
    FROM PUBLIC, anon;

GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLE public.mochy_profiles, public.mochy_favorites, public.mochy_history
    TO authenticated;

ALTER TABLE public.mochy_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.mochy_favorites ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.mochy_history ENABLE ROW LEVEL SECURITY;

-- Replace any legacy/permissive policies on these 3 tables with strict owner-only
-- policies using `(select auth.uid())` for Postgres initPlan caching.
DO $$
DECLARE
    v_tbl text;
    v_pol record;
BEGIN
    FOREACH v_tbl IN ARRAY ARRAY['mochy_profiles', 'mochy_favorites', 'mochy_history']
    LOOP
        FOR v_pol IN
            SELECT policyname
            FROM pg_policies
            WHERE schemaname = 'public'
              AND tablename = v_tbl
        LOOP
            EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', v_pol.policyname, v_tbl);
        END LOOP;

        EXECUTE format(
            'CREATE POLICY %I ON public.%I FOR SELECT TO authenticated USING ((select auth.uid()) = user_id)',
            v_tbl || '_select_own', v_tbl
        );
        EXECUTE format(
            'CREATE POLICY %I ON public.%I FOR INSERT TO authenticated WITH CHECK ((select auth.uid()) = user_id)',
            v_tbl || '_insert_own', v_tbl
        );
        EXECUTE format(
            'CREATE POLICY %I ON public.%I FOR UPDATE TO authenticated USING ((select auth.uid()) = user_id) WITH CHECK ((select auth.uid()) = user_id)',
            v_tbl || '_update_own', v_tbl
        );
        EXECUTE format(
            'CREATE POLICY %I ON public.%I FOR DELETE TO authenticated USING ((select auth.uid()) = user_id)',
            v_tbl || '_delete_own', v_tbl
        );
    END LOOP;
END;
$$;

-- -----------------------------------------------------------------------------
-- 3. Payload Validation, 30s Avatar Cooldown & Per-User Row Caps (Anti-Spam)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.mochy_guard_profiles_biu()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
    NEW.username := lower(btrim(coalesce(NEW.username, '')));
    IF left(NEW.username, 1) = '@' THEN
        NEW.username := substr(NEW.username, 2);
    END IF;

    IF NEW.username !~ '^[a-z0-9._]{3,24}$' THEN
        RAISE EXCEPTION 'Invalid username format' USING ERRCODE = '22023';
    END IF;

    IF NEW.display_name IS NOT NULL THEN
        NEW.display_name := left(btrim(NEW.display_name), 60);
    END IF;

    IF NEW.avatar_webp IS NOT NULL THEN
        IF left(NEW.avatar_webp, 23) <> 'data:image/webp;base64,'
           OR octet_length(NEW.avatar_webp) > 34000 THEN
            RAISE EXCEPTION 'Invalid avatar payload or exceeds size limit' USING ERRCODE = 'P0001';
        END IF;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.avatar_webp IS NOT NULL THEN
            NEW.avatar_updated_at := now();
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.avatar_webp IS NOT NULL AND NEW.avatar_webp IS DISTINCT FROM OLD.avatar_webp THEN
            IF OLD.avatar_updated_at IS NOT NULL
               AND (now() - OLD.avatar_updated_at) < interval '30 seconds' THEN
                RAISE EXCEPTION 'Avatar update cooldown (30s)' USING ERRCODE = 'P0001';
            END IF;
            NEW.avatar_updated_at := now();
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS mochy_guard_profiles_biu ON public.mochy_profiles;
CREATE TRIGGER mochy_guard_profiles_biu
    BEFORE INSERT OR UPDATE ON public.mochy_profiles
    FOR EACH ROW
    EXECUTE FUNCTION public.mochy_guard_profiles_biu();

-- Shared field sanitizer for track tables (mochy_favorites & mochy_history)
CREATE OR REPLACE FUNCTION public.mochy_guard_favorites_biu()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
    NEW.song_id := btrim(coalesce(NEW.song_id, ''));
    IF NEW.song_id = ''
       OR char_length(NEW.song_id) > 128
       OR upper(NEW.song_id) LIKE 'LOCAL\_%' ESCAPE '\' THEN
        RAISE EXCEPTION 'Invalid cloud song_id' USING ERRCODE = '22023';
    END IF;

    NEW.title := left(coalesce(btrim(NEW.title), ''), 300);
    NEW.artists := left(coalesce(btrim(NEW.artists), ''), 300);
    IF NEW.album_name IS NOT NULL THEN
        NEW.album_name := left(btrim(NEW.album_name), 300);
    END IF;

    IF NEW.duration IS NOT NULL AND (NEW.duration < -1 OR NEW.duration > 86400) THEN
        NEW.duration := -1;
    END IF;

    IF NEW.thumbnail_url IS NOT NULL THEN
        NEW.thumbnail_url := btrim(NEW.thumbnail_url);
        IF char_length(NEW.thumbnail_url) > 600
           OR lower(NEW.thumbnail_url) LIKE 'file://%'
           OR lower(NEW.thumbnail_url) LIKE 'content://%'
           OR lower(NEW.thumbnail_url) LIKE 'data:%' THEN
            NEW.thumbnail_url := NULL;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS mochy_guard_favorites_biu ON public.mochy_favorites;
CREATE TRIGGER mochy_guard_favorites_biu
    BEFORE INSERT OR UPDATE ON public.mochy_favorites
    FOR EACH ROW
    EXECUTE FUNCTION public.mochy_guard_favorites_biu();

CREATE OR REPLACE FUNCTION public.mochy_guard_favorites_cap_ai()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_count integer;
BEGIN
    IF v_uid IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT count(*)
      INTO v_count
      FROM public.mochy_favorites
     WHERE user_id = v_uid;

    IF v_count > 3000 THEN
        RAISE EXCEPTION 'favorites_limit: maximum 3000 favorites per account'
            USING ERRCODE = 'P0001';
    END IF;

    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS mochy_guard_favorites_cap_ai ON public.mochy_favorites;
CREATE TRIGGER mochy_guard_favorites_cap_ai
    AFTER INSERT ON public.mochy_favorites
    FOR EACH STATEMENT
    EXECUTE FUNCTION public.mochy_guard_favorites_cap_ai();

CREATE OR REPLACE FUNCTION public.mochy_guard_history_biu()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
    NEW.song_id := btrim(coalesce(NEW.song_id, ''));
    IF NEW.song_id = ''
       OR char_length(NEW.song_id) > 128
       OR upper(NEW.song_id) LIKE 'LOCAL\_%' ESCAPE '\' THEN
        RAISE EXCEPTION 'Invalid cloud song_id' USING ERRCODE = '22023';
    END IF;

    NEW.title := left(coalesce(btrim(NEW.title), ''), 300);
    NEW.artists := left(coalesce(btrim(NEW.artists), ''), 300);
    IF NEW.album_name IS NOT NULL THEN
        NEW.album_name := left(btrim(NEW.album_name), 300);
    END IF;

    IF NEW.duration IS NOT NULL AND (NEW.duration < -1 OR NEW.duration > 86400) THEN
        NEW.duration := -1;
    END IF;

    IF NEW.play_count IS NULL OR NEW.play_count < 1 THEN
        NEW.play_count := 1;
    ELSIF NEW.play_count > 2000000000 THEN
        NEW.play_count := 2000000000;
    END IF;

    IF NEW.total_play_ms IS NULL OR NEW.total_play_ms < 0 THEN
        NEW.total_play_ms := 0;
    ELSIF NEW.total_play_ms > 1000000000000 THEN
        NEW.total_play_ms := 1000000000000;
    END IF;

    IF NEW.thumbnail_url IS NOT NULL THEN
        NEW.thumbnail_url := btrim(NEW.thumbnail_url);
        IF char_length(NEW.thumbnail_url) > 600
           OR lower(NEW.thumbnail_url) LIKE 'file://%'
           OR lower(NEW.thumbnail_url) LIKE 'content://%'
           OR lower(NEW.thumbnail_url) LIKE 'data:%' THEN
            NEW.thumbnail_url := NULL;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS mochy_guard_history_biu ON public.mochy_history;
CREATE TRIGGER mochy_guard_history_biu
    BEFORE INSERT OR UPDATE ON public.mochy_history
    FOR EACH ROW
    EXECUTE FUNCTION public.mochy_guard_history_biu();

CREATE OR REPLACE FUNCTION public.mochy_guard_history_prune_ai()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_count integer;
BEGIN
    IF v_uid IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT count(*)
      INTO v_count
      FROM public.mochy_history
     WHERE user_id = v_uid;

    -- Keep the 1,000 most recent tracks per user (client fetches top 500).
    IF v_count > 1000 THEN
        DELETE FROM public.mochy_history
         WHERE ctid IN (
             SELECT ctid
               FROM public.mochy_history
              WHERE user_id = v_uid
              ORDER BY last_played_at DESC NULLS LAST, song_id DESC
             OFFSET 1000
         );
    END IF;

    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS mochy_guard_history_prune_ai ON public.mochy_history;
CREATE TRIGGER mochy_guard_history_prune_ai
    AFTER INSERT ON public.mochy_history
    FOR EACH STATEMENT
    EXECUTE FUNCTION public.mochy_guard_history_prune_ai();

-- -----------------------------------------------------------------------------
-- 4. Server-Side Rate Limit Store (service_role ONLY — used by Edge Function)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.mochy_auth_rate_limits (
    bucket text PRIMARY KEY,
    hits integer NOT NULL DEFAULT 1,
    window_started_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS mochy_auth_rate_limits_window_idx
    ON public.mochy_auth_rate_limits (window_started_at);

ALTER TABLE public.mochy_auth_rate_limits ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.mochy_auth_rate_limits FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.mochy_check_auth_rate_limit(
    p_bucket text,
    p_max_hits integer DEFAULT 12,
    p_window_seconds integer DEFAULT 300
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_bucket text := left(btrim(coalesce(p_bucket, '')), 120);
    v_max integer := greatest(1, least(coalesce(p_max_hits, 12), 1000));
    v_window integer := greatest(10, least(coalesce(p_window_seconds, 300), 86400));
    v_now timestamptz := now();
    v_hits integer;
    v_started timestamptz;
    v_retry integer;
BEGIN
    IF v_bucket = '' THEN
        RETURN jsonb_build_object('allowed', false, 'hits', v_max + 1, 'retry_after', v_window);
    END IF;

    INSERT INTO public.mochy_auth_rate_limits (bucket, hits, window_started_at)
    VALUES (v_bucket, 1, v_now)
    ON CONFLICT (bucket) DO UPDATE
    SET hits = CASE
            WHEN public.mochy_auth_rate_limits.window_started_at + make_interval(secs => v_window) <= v_now
                THEN 1
            ELSE public.mochy_auth_rate_limits.hits + 1
        END,
        window_started_at = CASE
            WHEN public.mochy_auth_rate_limits.window_started_at + make_interval(secs => v_window) <= v_now
                THEN v_now
            ELSE public.mochy_auth_rate_limits.window_started_at
        END
    RETURNING hits, window_started_at INTO v_hits, v_started;

    -- Opportunistic cleanup of stale buckets (~5% of calls)
    IF random() < 0.05 THEN
        DELETE FROM public.mochy_auth_rate_limits
         WHERE window_started_at < v_now - interval '2 hours';
    END IF;

    v_retry := greatest(
        1,
        ceil(extract(epoch FROM ((v_started + make_interval(secs => v_window)) - v_now)))::integer
    );

    RETURN jsonb_build_object(
        'allowed', v_hits <= v_max,
        'hits', v_hits,
        'retry_after', v_retry
    );
END;
$$;

REVOKE ALL ON FUNCTION public.mochy_check_auth_rate_limit(text, integer, integer)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.mochy_check_auth_rate_limit(text, integer, integer)
    TO service_role;

-- -----------------------------------------------------------------------------
-- 5. Lock Down mochy_resolve_login_account to service_role ONLY (Zero Gmail Leak)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.mochy_resolve_login_account(p_username text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_clean text;
    v_user_id uuid;
    v_email text;
    v_encrypted_password text;
    v_meta jsonb;
    v_has_google boolean := false;
    v_has_password boolean := false;
BEGIN
    v_clean := lower(btrim(coalesce(p_username, '')));
    IF left(v_clean, 1) = '@' THEN
        v_clean := substr(v_clean, 2);
    END IF;

    IF v_clean = '' OR v_clean !~ '^[a-z0-9._]{3,24}$' THEN
        RETURN jsonb_build_object(
            'exists', false,
            'login_email', NULL,
            'has_google', false,
            'has_password', false
        );
    END IF;

    SELECT p.user_id
      INTO v_user_id
      FROM public.mochy_profiles AS p
     WHERE lower(p.username) = v_clean
     LIMIT 1;

    IF v_user_id IS NULL THEN
        SELECT u.id
          INTO v_user_id
          FROM auth.users AS u
         WHERE lower(u.email) = v_clean || '@account.mochy.app'
           AND u.deleted_at IS NULL
         LIMIT 1;
    END IF;

    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object(
            'exists', false,
            'login_email', NULL,
            'has_google', false,
            'has_password', false
        );
    END IF;

    SELECT u.email, u.encrypted_password, coalesce(u.raw_user_meta_data, '{}'::jsonb)
      INTO v_email, v_encrypted_password, v_meta
      FROM auth.users AS u
     WHERE u.id = v_user_id
       AND u.deleted_at IS NULL
     LIMIT 1;

    IF v_email IS NULL THEN
        RETURN jsonb_build_object(
            'exists', false,
            'login_email', NULL,
            'has_google', false,
            'has_password', false
        );
    END IF;

    SELECT coalesce(bool_or(i.provider = 'google'), false)
      INTO v_has_google
      FROM auth.identities AS i
     WHERE i.user_id = v_user_id;

    v_has_password := (
        coalesce(btrim(v_encrypted_password), '') <> ''
        OR lower(coalesce(v_meta ->> 'has_password', '')) = 'true'
    );

    RETURN jsonb_build_object(
        'exists', true,
        'login_email', v_email,
        'has_google', v_has_google,
        'has_password', v_has_password
    );
END;
$$;

-- CRITICAL: Revoke from anon & authenticated so Gmail can NEVER be queried directly!
-- Only the `mochy-username-login` Edge Function (using service_role) can call this.
REVOKE ALL ON FUNCTION public.mochy_resolve_login_account(text)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.mochy_resolve_login_account(text)
    TO service_role;

COMMIT;

-- -----------------------------------------------------------------------------
-- 6. Instant Post-Run Audit Report (shown right in Supabase SQL Editor output)
-- -----------------------------------------------------------------------------
SELECT
    t.tablename AS table_name,
    t.rowsecurity AS rls_enabled,
    (SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = t.tablename) AS rls_policy_count,
    has_table_privilege('anon', format('public.%I', t.tablename), 'SELECT') AS anon_can_select,
    has_table_privilege('authenticated', format('public.%I', t.tablename), 'SELECT') AS auth_can_select,
    (SELECT count(*) FROM pg_trigger g WHERE g.tgrelid = format('public.%I', t.tablename)::regclass AND NOT g.tgisinternal) AS guard_triggers,
    has_function_privilege('anon', 'public.mochy_resolve_login_account(text)', 'EXECUTE') AS anon_can_probe_gmail
FROM pg_tables t
WHERE t.schemaname = 'public'
  AND t.tablename IN ('mochy_profiles', 'mochy_favorites', 'mochy_history', 'mochy_auth_rate_limits')
ORDER BY t.tablename;
