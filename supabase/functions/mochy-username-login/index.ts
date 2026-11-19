// =============================================================================
// Mochy Cloud v11.9.0 — Zero-Gmail-Leak Username Login & Sign-Up Check Function
// =============================================================================
//
// Deploy via Supabase Dashboard (Edge Functions -> Deploy a new function -> Via Editor):
//   - Function name: `mochy-username-login`
//   - IMPORTANT: Turn OFF "Enforce JWT Verification" / "Verify JWT" (because this
//     endpoint is called BEFORE the user signs in and receives a JWT).
//
// Or via Supabase CLI:
//   supabase functions deploy mochy-username-login --no-verify-jwt
//
// Security properties:
//   1. Resolves @username -> auth email exclusively server-side using
//      SUPABASE_SERVICE_ROLE_KEY (anon no longer needs access to
//      public.mochy_resolve_login_account, eliminating Gmail enumeration).
//   2. Enforces Postgres-backed fixed-window rate limits per hashed IP and
//      per (IP, @username) via public.mochy_check_auth_rate_limit, with an
//      in-memory isolate fallback if the SQL hardening script has not run yet.
//   3. Never returns login_email / Gmail in error or availability responses.
// =============================================================================

const USERNAME_REGEX = /^[a-z0-9._]{3,24}$/;
const MAX_BODY_BYTES = 4096;
const SIGNIN_IP_MAX_HITS = 12;
const SIGNIN_ACCT_MAX_HITS = 8;
const SIGNUP_CHK_IP_MAX_HITS = 10;
const WINDOW_SECONDS = 300; // 5 minutes

type MemoryBucket = { hits: number; resetAtMs: number };
const memoryLimiter = new Map<string, MemoryBucket>();

function jsonResponse(status: number, payload: Record<string, unknown>, retryAfter?: number): Response {
  const headers: Record<string, string> = {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  };
  if (retryAfter && retryAfter > 0) {
    headers["Retry-After"] = String(Math.ceil(retryAfter));
  }
  return new Response(JSON.stringify({ source: "mochy-edge", ...payload }), { status, headers });
}

function normalizeUsername(raw: unknown): string {
  if (typeof raw !== "string") return "";
  const cleaned = raw.trim().toLowerCase();
  return cleaned.startsWith("@") ? cleaned.slice(1) : cleaned;
}

function extractClientIp(req: Request): string {
  const cfIp = req.headers.get("cf-connecting-ip")?.trim();
  if (cfIp) return cfIp;
  const realIp = req.headers.get("x-real-ip")?.trim();
  if (realIp) return realIp;
  const xff = req.headers.get("x-forwarded-for");
  if (xff) {
    const first = xff.split(",")[0]?.trim();
    if (first) return first;
  }
  return "unknown";
}

async function hashKeyedIp(ip: string, salt: string): Promise<string> {
  const data = new TextEncoder().encode(`${ip}:${salt}`);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("")
    .slice(0, 32);
}

function checkMemoryRateLimit(
  bucket: string,
  maxHits: number,
  windowSeconds: number,
): { allowed: boolean; retryAfter: number } {
  const now = Date.now();
  if (memoryLimiter.size > 2000) {
    for (const [key, entry] of memoryLimiter.entries()) {
      if (entry.resetAtMs <= now) memoryLimiter.delete(key);
    }
  }
  const existing = memoryLimiter.get(bucket);
  if (!existing || existing.resetAtMs <= now) {
    memoryLimiter.set(bucket, { hits: 1, resetAtMs: now + windowSeconds * 1000 });
    return { allowed: true, retryAfter: windowSeconds };
  }
  existing.hits += 1;
  const retryAfter = Math.max(1, Math.ceil((existing.resetAtMs - now) / 1000));
  return { allowed: existing.hits <= maxHits, retryAfter };
}

async function checkRateLimit(
  supabaseUrl: string,
  serviceRoleKey: string,
  bucket: string,
  maxHits: number,
  windowSeconds: number,
): Promise<{ allowed: boolean; retryAfter: number }> {
  try {
    const resp = await fetch(`${supabaseUrl}/rest/v1/rpc/mochy_check_auth_rate_limit`, {
      method: "POST",
      headers: {
        apikey: serviceRoleKey,
        Authorization: `Bearer ${serviceRoleKey}`,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify({
        p_bucket: bucket,
        p_max_hits: maxHits,
        p_window_seconds: windowSeconds,
      }),
    });
    if (resp.ok) {
      const data = await resp.json();
      if (data && typeof data.allowed === "boolean") {
        return {
          allowed: data.allowed,
          retryAfter: typeof data.retry_after === "number" ? data.retry_after : windowSeconds,
        };
      }
    }
  } catch {
    // Fall back to isolate memory limiter if SQL hardening has not run yet.
  }
  return checkMemoryRateLimit(bucket, maxHits, windowSeconds);
}

interface ResolvedAccount {
  exists: boolean;
  login_email: string | null;
  has_google: boolean;
  has_password: boolean;
}

async function resolveAccountServerSide(
  supabaseUrl: string,
  serviceRoleKey: string,
  username: string,
): Promise<ResolvedAccount | null> {
  const resp = await fetch(`${supabaseUrl}/rest/v1/rpc/mochy_resolve_login_account`, {
    method: "POST",
    headers: {
      apikey: serviceRoleKey,
      Authorization: `Bearer ${serviceRoleKey}`,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify({ p_username: username }),
  });
  if (!resp.ok) return null;
  const data = await resp.json();
  if (!data || typeof data.exists !== "boolean") return null;
  return {
    exists: Boolean(data.exists),
    login_email: typeof data.login_email === "string" && data.login_email.trim() ? data.login_email.trim() : null,
    has_google: Boolean(data.has_google),
    has_password: Boolean(data.has_password),
  };
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") {
    return jsonResponse(405, { status: "method_not_allowed" });
  }

  const supabaseUrl = (Deno.env.get("SUPABASE_URL") ?? "").trim().replace(/\/+$/, "");
  const serviceRoleKey = (Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "").trim();
  const supabaseAnonKey = (Deno.env.get("SUPABASE_ANON_KEY") ?? "").trim();
  const expectedPublishableKey = (Deno.env.get("MOCHY_PUBLISHABLE_KEY") ?? "").trim();

  if (!supabaseUrl || !serviceRoleKey) {
    return jsonResponse(500, { status: "server_misconfigured" });
  }

  const reqApiKey = (req.headers.get("apikey") ?? "").trim();
  if (reqApiKey.length < 16 || reqApiKey.length > 512) {
    return jsonResponse(401, { status: "unauthorized_client" });
  }
  if (expectedPublishableKey && reqApiKey !== expectedPublishableKey && reqApiKey !== supabaseAnonKey) {
    return jsonResponse(401, { status: "unauthorized_client" });
  }

  const contentLength = Number(req.headers.get("content-length") ?? "0");
  if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
    return jsonResponse(413, { status: "payload_too_large" });
  }

  let body: Record<string, unknown>;
  try {
    const raw = await req.text();
    if (raw.length > MAX_BODY_BYTES) {
      return jsonResponse(413, { status: "payload_too_large" });
    }
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return jsonResponse(400, { status: "invalid_request" });
    }
    body = parsed as Record<string, unknown>;
  } catch {
    return jsonResponse(400, { status: "invalid_request" });
  }

  const action = typeof body.action === "string" ? body.action.trim().toLowerCase() : "";
  const username = normalizeUsername(body.username);
  if (!USERNAME_REGEX.test(username)) {
    return jsonResponse(400, { status: "invalid_username" });
  }

  const rawIp = extractClientIp(req);
  const ipHash = await hashKeyedIp(rawIp, serviceRoleKey.slice(0, 32));
  const ipMultiplier = rawIp === "unknown" ? 3 : 1;

  if (action === "check_signup") {
    const ipLimit = await checkRateLimit(
      supabaseUrl,
      serviceRoleKey,
      `signup_chk:ip:${ipHash}`,
      SIGNUP_CHK_IP_MAX_HITS * ipMultiplier,
      WINDOW_SECONDS,
    );
    if (!ipLimit.allowed) {
      return jsonResponse(429, { status: "rate_limited", retry_after: ipLimit.retryAfter }, ipLimit.retryAfter);
    }

    const resolved = await resolveAccountServerSide(supabaseUrl, serviceRoleKey, username);
    if (!resolved) {
      return jsonResponse(503, { status: "resolver_unavailable" });
    }
    if (!resolved.exists) {
      return jsonResponse(200, { status: "available" });
    }
    // Never return login_email here — only whether taken and whether Google is linked.
    return jsonResponse(200, { status: "taken", has_google: resolved.has_google });
  }

  if (action === "signin") {
    const password = typeof body.password === "string" ? body.password : "";
    if (password.length === 0 || password.length > 256) {
      return jsonResponse(400, { status: "invalid_request" });
    }

    const ipLimit = await checkRateLimit(
      supabaseUrl,
      serviceRoleKey,
      `signin:ip:${ipHash}`,
      SIGNIN_IP_MAX_HITS * ipMultiplier,
      WINDOW_SECONDS,
    );
    if (!ipLimit.allowed) {
      return jsonResponse(429, { status: "rate_limited", retry_after: ipLimit.retryAfter }, ipLimit.retryAfter);
    }

    const acctLimit = await checkRateLimit(
      supabaseUrl,
      serviceRoleKey,
      `signin:acct:${ipHash}:${username}`,
      SIGNIN_ACCT_MAX_HITS,
      WINDOW_SECONDS,
    );
    if (!acctLimit.allowed) {
      return jsonResponse(429, { status: "rate_limited", retry_after: acctLimit.retryAfter }, acctLimit.retryAfter);
    }

    const resolved = await resolveAccountServerSide(supabaseUrl, serviceRoleKey, username);
    if (!resolved) {
      return jsonResponse(503, { status: "resolver_unavailable" });
    }
    if (!resolved.exists) {
      return jsonResponse(404, { status: "not_found" });
    }
    if (resolved.has_google && !resolved.has_password) {
      return jsonResponse(400, { status: "google_no_password" });
    }

    const targetEmail = resolved.login_email ?? `${username}@account.mochy.app`;
    const authApiKey = reqApiKey || supabaseAnonKey;

    let authResp: Response;
    try {
      authResp = await fetch(`${supabaseUrl}/auth/v1/token?grant_type=password`, {
        method: "POST",
        headers: {
          apikey: authApiKey,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify({ email: targetEmail, password }),
      });
    } catch {
      return jsonResponse(502, { status: "upstream_error" });
    }

    const rawAuth = await authResp.text();
    let authJson: Record<string, unknown> | null = null;
    try {
      authJson = JSON.parse(rawAuth) as Record<string, unknown>;
    } catch {
      authJson = null;
    }

    if (authResp.ok && authJson && typeof authJson.access_token === "string") {
      return jsonResponse(200, { status: "ok", session: authJson });
    }
    if (authResp.status === 429) {
      return jsonResponse(429, { status: "rate_limited", retry_after: 60 }, 60);
    }
    if (authResp.status === 400 || authResp.status === 401) {
      return jsonResponse(401, {
        status: "invalid_credentials",
        has_google: resolved.has_google,
      });
    }
    return jsonResponse(502, { status: "upstream_error" });
  }

  return jsonResponse(400, { status: "invalid_action" });
});
