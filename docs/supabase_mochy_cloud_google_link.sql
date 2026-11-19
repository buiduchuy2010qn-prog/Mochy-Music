-- =============================================================================
-- Mochy Cloud v11.8.0 — Unified Account Linking (@username <-> Google)
-- & Cross-Device Login Resolver for Supabase (PostgreSQL)
-- =============================================================================
--
-- Prerequisites in Supabase Dashboard:
--   1. Authentication -> Providers -> Google: Enabled (Android / Web OAuth client).
--   2. Authentication -> URL Configuration -> Redirect URLs:
--      Add `mochy-cloud://auth-callback`.
--   3. Authentication -> Settings -> Allow manual linking:
--      Enable "Allow manual linking" so existing @username accounts can link
--      Google onto the exact same auth.users.id without creating a duplicate account.
--
-- This script is idempotent and safe to re-run in the Supabase SQL Editor.
-- =============================================================================

-- 1. Enforce case-insensitive uniqueness on public.mochy_profiles.username.
--    Note: If legacy case-colliding rows exist (e.g., 'Huy' and 'huy'), inspect
--    and deduplicate them before running:
--      SELECT lower(username), count(*)
--      FROM public.mochy_profiles
--      GROUP BY lower(username)
--      HAVING count(*) > 1;
CREATE UNIQUE INDEX IF NOT EXISTS mochy_profiles_username_lower_uidx
    ON public.mochy_profiles (lower(username));

-- 2. Cross-device @username resolver RPC:
--    Maps any @username (including Google-registered and renamed usernames) to
--    the underlying Supabase Auth login email and capability flags so Mochy Music
--    can authenticate @username + password across devices and show exact Vietnamese
--    guidance when an account does not exist or has not yet set a password.
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

    -- Reject empty or invalid @username patterns early (3-24 chars: a-z, 0-9, ., _).
    IF v_clean = '' OR v_clean !~ '^[a-z0-9._]{3,24}$' THEN
        RETURN jsonb_build_object(
            'exists', false,
            'login_email', NULL,
            'has_google', false,
            'has_password', false
        );
    END IF;

    -- Primary lookup: public.mochy_profiles (covers Google-created & renamed @usernames).
    SELECT p.user_id
      INTO v_user_id
      FROM public.mochy_profiles AS p
     WHERE lower(p.username) = v_clean
     LIMIT 1;

    -- Fallback lookup: auth.users by synthetic email if a profile row has not been created yet.
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

-- 3. Lock down execution privileges explicitly to Supabase API roles only.
REVOKE ALL ON FUNCTION public.mochy_resolve_login_account(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mochy_resolve_login_account(text) TO anon, authenticated;
