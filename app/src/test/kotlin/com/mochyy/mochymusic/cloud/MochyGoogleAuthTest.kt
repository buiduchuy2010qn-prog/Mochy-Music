package com.mochyy.mochymusic.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MochyGoogleAuthTest {

    private val pkceAlphabetRegex = Regex("^[A-Za-z0-9_-]{43}$")
    private val validUsernameRegex = Regex("^[a-z0-9._]{3,24}$")

    @Test
    fun pkceChallengeMatchesRfc7636VectorAndVerifierIsUrlSafe() {
        // RFC 7636 Appendix B official test vector
        val rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, MochyGoogleOAuthHelper.computePkceChallenge(rfcVerifier))

        val v1 = MochyGoogleOAuthHelper.generatePkceVerifier()
        val v2 = MochyGoogleOAuthHelper.generatePkceVerifier()
        assertTrue("Verifier must be 43 unpadded base64url chars: $v1", pkceAlphabetRegex.matches(v1))
        assertTrue("Verifier must be 43 unpadded base64url chars: $v2", pkceAlphabetRegex.matches(v2))
        assertNotEquals("Consecutive PKCE verifiers must be distinct", v1, v2)

        val c1 = MochyGoogleOAuthHelper.computePkceChallenge(v1)
        assertTrue("Challenge must be 43 unpadded base64url chars: $c1", pkceAlphabetRegex.matches(c1))
        assertEquals(c1, MochyGoogleOAuthHelper.computePkceChallenge(v1))
    }

    @Test
    fun buildGoogleAuthorizeUrlFormatsRequiredParamsAndFiltersSyntheticHints() {
        val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        val baseUrl = "https://cloud.example.test/"

        val noHintUrl = MochyGoogleOAuthHelper.buildGoogleAuthorizeUrl(baseUrl, challenge)
        assertTrue(noHintUrl.startsWith("https://cloud.example.test/auth/v1/authorize?"))
        assertTrue(noHintUrl.contains("provider=google"))
        assertTrue(noHintUrl.contains("redirect_to=mochy-cloud%3A%2F%2Fauth-callback"))
        assertTrue(noHintUrl.contains("code_challenge=$challenge"))
        assertTrue(noHintUrl.contains("code_challenge_method=s256"))
        assertTrue(noHintUrl.contains("prompt=select_account"))
        assertFalse(noHintUrl.contains("login_hint="))

        // Synthetic @account.mochy.app and invalid emails must never be passed as login_hint
        val syntheticHints = listOf(
            "mochyuser@account.mochy.app",
            "MOCHYUSER@ACCOUNT.MOCHY.APP",
            "   ",
            "not-an-email",
            "@gmail.com",
            "user@localhost",
            "user @gmail.com",
        )
        for (badHint in syntheticHints) {
            val url = MochyGoogleOAuthHelper.buildGoogleAuthorizeUrl(baseUrl, challenge, badHint)
            assertFalse("Unexpected login_hint for '$badHint': $url", url.contains("login_hint="))
        }

        // Valid Gmail hint should be URL-encoded and appended
        val withHintUrl = MochyGoogleOAuthHelper.buildGoogleAuthorizeUrl(
            baseUrl = baseUrl,
            codeChallenge = challenge,
            loginHintEmail = "  Huy.Music+yt@gmail.com  ",
        )
        assertTrue(withHintUrl.contains("&login_hint=Huy.Music%2Byt%40gmail.com"))
    }

    @Test
    fun parseOAuthCallbackParamsHandlesQueryPkceImplicitFragmentAndErrors() {
        val pkce = MochyGoogleOAuthHelper.parseOAuthCallbackParams(
            rawQuery = "code=pkce-auth-code-123&state=ok",
            rawFragment = null,
        )
        assertEquals("pkce-auth-code-123", pkce.code)
        assertNull(pkce.accessToken)
        assertNull(pkce.refreshToken)
        assertNull(pkce.expiresIn)
        assertNull(pkce.error)
        assertNull(pkce.errorDescription)

        val prefixedQuery = MochyGoogleOAuthHelper.parseOAuthCallbackParams(
            rawQuery = "?code=abc-999",
            rawFragment = "",
        )
        assertEquals("abc-999", prefixedQuery.code)

        val implicit = MochyGoogleOAuthHelper.parseOAuthCallbackParams(
            rawQuery = null,
            rawFragment = "#access_token=eyJ.abc.def&refresh_token=ref_123&expires_in=3600&token_type=bearer",
        )
        assertNull(implicit.code)
        assertEquals("eyJ.abc.def", implicit.accessToken)
        assertEquals("ref_123", implicit.refreshToken)
        assertEquals(3600L, implicit.expiresIn)
        assertNull(implicit.error)

        val queryError = MochyGoogleOAuthHelper.parseOAuthCallbackParams(
            rawQuery = "error=server_error&error_description=Unsupported+provider%3A+provider+is+not+enabled",
            rawFragment = null,
        )
        assertEquals("server_error", queryError.error)
        assertEquals("Unsupported provider: provider is not enabled", queryError.errorDescription)
        val mappedProviderErr = MochyGoogleOAuthHelper.mapOAuthErrorToVietnamese(
            queryError.error,
            queryError.errorDescription,
        )
        assertTrue(mappedProviderErr.contains("Supabase"))
        assertTrue(mappedProviderErr.contains("Google"))

        val fragmentError = MochyGoogleOAuthHelper.parseOAuthCallbackParams(
            rawQuery = null,
            rawFragment = "error=access_denied&error_description=User%20denied%20access",
        )
        assertEquals("access_denied", fragmentError.error)
        assertEquals("User denied access", fragmentError.errorDescription)
        val mappedCancelErr = MochyGoogleOAuthHelper.mapOAuthErrorToVietnamese(
            fragmentError.error,
            fragmentError.errorDescription,
        )
        assertTrue(mappedCancelErr.contains("hủy"))

        // Malformed percent encoding should not throw
        val malformed = MochyGoogleOAuthHelper.parseOAuthCallbackParams(
            rawQuery = "code=%ZZraw",
            rawFragment = null,
        )
        assertEquals("%ZZraw", malformed.code)
    }

    @Test
    fun deriveUsernameFromEmailCoversEdgeCasesAndCollisionSuffixes() {
        val uid = "9f8e7d6c-1234-5678-90ab-cdef12345678"

        val standard = MochyGoogleOAuthHelper.deriveUsernameFromEmail("Huy.Nguyen+music@gmail.com", uid)
        assertEquals("huy.nguyenmusic", standard)

        val trimmed = MochyGoogleOAuthHelper.deriveUsernameFromEmail("..__Dev_User__..@GMAIL.COM", uid)
        assertEquals("dev_user", trimmed)

        val shortPrefix = MochyGoogleOAuthHelper.deriveUsernameFromEmail("ab@gmail.com", uid)
        assertEquals("mochy", shortPrefix)

        val symbolsOnly = MochyGoogleOAuthHelper.deriveUsernameFromEmail("___...@gmail.com", uid)
        assertEquals("mochy", symbolsOnly)

        val nullEmail = MochyGoogleOAuthHelper.deriveUsernameFromEmail(null, uid)
        assertEquals("mochy", nullEmail)

        val longEmail = MochyGoogleOAuthHelper.deriveUsernameFromEmail(
            "abcdefghijklmnopqrstuvwxyz012345@gmail.com",
            "deadbeef-0000-0000-0000-000000000000",
            withSuffix = false,
        )
        assertEquals("abcdefghijklmnopqrstuvwx", longEmail)
        assertEquals(24, longEmail.length)

        val suffixedStandard = MochyGoogleOAuthHelper.deriveUsernameFromEmail(
            "huy.nguyen@gmail.com",
            uid,
            withSuffix = true,
        )
        assertEquals("huy.nguyen_9f8e", suffixedStandard)

        val suffixedShort = MochyGoogleOAuthHelper.deriveUsernameFromEmail(
            "ab@gmail.com",
            uid,
            withSuffix = true,
        )
        assertEquals("mochy_9f8e", suffixedShort)

        val suffixedLong = MochyGoogleOAuthHelper.deriveUsernameFromEmail(
            "abcdefghijklmnopqrstuvwxyz012345@gmail.com",
            "deadbeef-0000-0000-0000-000000000000",
            withSuffix = true,
        )
        assertEquals("abcdefghijklmnopqrs_dead", suffixedLong)
        assertEquals(24, suffixedLong.length)

        val suffixedShortUid = MochyGoogleOAuthHelper.deriveUsernameFromEmail(
            "huy_user",
            "12",
            withSuffix = true,
        )
        assertEquals("huy_user_1200", suffixedShortUid)

        val suffixedTrailingDots = MochyGoogleOAuthHelper.deriveUsernameFromEmail(
            "a..................b@gmail.com",
            "cafe1234",
            withSuffix = true,
        )
        assertEquals("a_cafe", suffixedTrailingDots)

        val allDerived = listOf(
            standard,
            trimmed,
            shortPrefix,
            symbolsOnly,
            nullEmail,
            longEmail,
            suffixedStandard,
            suffixedShort,
            suffixedLong,
            suffixedShortUid,
            suffixedTrailingDots,
        )
        for (candidate in allDerived) {
            assertTrue("Derived username '$candidate' must match ^[a-z0-9._]{3,24}$", validUsernameRegex.matches(candidate))
        }
    }

    @Test
    fun buildGoogleLinkAuthorizeUrlFormatsManualLinkingParamsAndHint() {
        val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        val baseUrl = "https://cloud.example.test/"

        val noHintUrl = MochyGoogleOAuthHelper.buildGoogleLinkAuthorizeUrl(baseUrl, challenge)
        assertTrue(noHintUrl.startsWith("https://cloud.example.test/auth/v1/user/identities/authorize?"))
        assertTrue(noHintUrl.contains("provider=google"))
        assertTrue(noHintUrl.contains("redirect_to=mochy-cloud%3A%2F%2Fauth-callback"))
        assertTrue(noHintUrl.contains("code_challenge=$challenge"))
        assertTrue(noHintUrl.contains("code_challenge_method=s256"))
        assertTrue(noHintUrl.contains("skip_http_redirect=true"))
        assertTrue(noHintUrl.contains("prompt=select_account"))
        assertFalse(noHintUrl.contains("login_hint="))

        val syntheticFiltered = MochyGoogleOAuthHelper.buildGoogleLinkAuthorizeUrl(
            baseUrl = baseUrl,
            codeChallenge = challenge,
            loginHintEmail = "duchuy@account.mochy.app",
        )
        assertFalse(syntheticFiltered.contains("login_hint="))

        val withHint = MochyGoogleOAuthHelper.buildGoogleLinkAuthorizeUrl(
            baseUrl = baseUrl,
            codeChallenge = challenge,
            loginHintEmail = "  Huy2010qn@gmail.com ",
        )
        assertTrue(withHint.contains("&login_hint=Huy2010qn%40gmail.com"))
    }

    @Test
    fun mapOAuthErrorToVietnameseCoversLinkingProviderAndCancelErrors() {
        val dupCode = MochyGoogleOAuthHelper.mapOAuthErrorToVietnamese("identity_already_exists", null)
        assertEquals(
            "Tài khoản Google này đã được đăng ký hoặc liên kết với một tài khoản Mochy khác.",
            dupCode,
        )
        val dupDesc = MochyGoogleOAuthHelper.mapOAuthErrorToVietnamese(
            "server_error",
            "Identity is already linked to another user",
        )
        assertEquals(dupCode, dupDesc)

        val parsedDupCallback = MochyGoogleOAuthHelper.parseOAuthCallbackParams(
            rawQuery = "error=server_error&error_code=identity_already_exists&error_description=Identity+is+already+linked",
            rawFragment = null,
        )
        assertEquals("identity_already_exists", parsedDupCallback.errorCode)
        assertEquals(
            dupCode,
            MochyGoogleOAuthHelper.mapOAuthErrorToVietnamese(
                parsedDupCallback.errorCode,
                parsedDupCallback.errorDescription,
            ),
        )

        val manualDisabled = MochyGoogleOAuthHelper.mapOAuthErrorToVietnamese(
            "manual_linking_disabled",
            "Manual linking is disabled",
        )
        assertEquals(
            "Chưa bật tính năng liên kết thủ công trên Supabase (Authentication › Settings › Allow manual linking).",
            manualDisabled,
        )

        val providerDisabled = MochyGoogleOAuthHelper.mapOAuthErrorToVietnamese(
            "validation_failed",
            "Unsupported provider: provider is not enabled",
        )
        assertEquals(
            "Đăng nhập Google chưa được bật trên máy chủ Supabase (Authentication › Providers › Google).",
            providerDisabled,
        )

        val emailExists = MochyGoogleOAuthHelper.mapOAuthErrorToVietnamese("email_exists", "User already registered")
        assertEquals("Tài khoản hoặc tên đăng nhập này đã được đăng ký.", emailExists)

        val userExists = MochyGoogleOAuthHelper.mapOAuthErrorToVietnamese("user_already_exists", null)
        assertEquals("Tài khoản hoặc tên đăng nhập này đã được đăng ký.", userExists)

        val canceled = MochyGoogleOAuthHelper.mapOAuthErrorToVietnamese("access_denied", "User cancelled")
        assertEquals("Bạn đã hủy thao tác đăng nhập/liên kết Google.", canceled)
    }

    @Test
    fun isNewlyRegisteredAccountDistinguishesFreshVsExistingAccounts() {
        assertTrue(
            MochyGoogleOAuthHelper.isNewlyRegisteredAccount(
                createdAtIso = "2026-11-19T07:36:00Z",
                lastSignInAtIso = "2026-11-19T07:36:00Z",
            ),
        )
        assertTrue(
            MochyGoogleOAuthHelper.isNewlyRegisteredAccount(
                createdAtIso = "2026-11-19T07:36:00.125000Z",
                lastSignInAtIso = "2026-11-19T07:36:20.125000+00:00",
            ),
        )
        assertFalse(
            MochyGoogleOAuthHelper.isNewlyRegisteredAccount(
                createdAtIso = "2026-11-19T07:36:00Z",
                lastSignInAtIso = "2026-11-19T07:36:21Z",
            ),
        )
        assertFalse(
            MochyGoogleOAuthHelper.isNewlyRegisteredAccount(
                createdAtIso = "2026-10-01T07:36:00Z",
                lastSignInAtIso = "2026-11-19T07:36:00Z",
            ),
        )
        assertFalse(MochyGoogleOAuthHelper.isNewlyRegisteredAccount(null, "2026-11-19T07:36:00Z"))
        assertFalse(MochyGoogleOAuthHelper.isNewlyRegisteredAccount("2026-11-19T07:36:00Z", null))
        assertFalse(MochyGoogleOAuthHelper.isNewlyRegisteredAccount("not-a-timestamp", "2026-11-19T07:36:00Z"))
    }

    @Test
    fun parseIdentityInfoAccuratelyClassifiesAllUnifiedAccountStates() {
        // 1. Google-only without password
        val googleOnlyJson = """
            {
              "id": "u-google-1",
              "email": "Huy2010qn@gmail.com",
              "created_at": "2026-11-19T07:00:00Z",
              "last_sign_in_at": "2026-11-19T07:00:02Z",
              "app_metadata": { "provider": "google", "providers": ["google"] },
              "user_metadata": { "full_name": "Đức Huy" },
              "identities": [
                { "provider": "google", "identity_data": { "email": "Huy2010qn@gmail.com" } }
              ]
            }
        """.trimIndent()
        val googleOnly = MochyGoogleOAuthHelper.parseIdentityInfo(googleOnlyJson)
        assertTrue(googleOnly.hasGoogleLinked)
        assertEquals("huy2010qn@gmail.com", googleOnly.googleEmail)
        assertFalse(googleOnly.hasPasswordLogin)
        assertNull(googleOnly.username)
        assertEquals("2026-11-19T07:00:00Z", googleOnly.createdAtIso)
        assertEquals("2026-11-19T07:00:02Z", googleOnly.lastSignInAtIso)

        // 2. Google account after setting password
        val googleWithPasswordJson = """
            {
              "id": "u-google-2",
              "email": "huy2010qn@gmail.com",
              "created_at": "2026-11-01T07:00:00Z",
              "last_sign_in_at": "2026-11-19T07:00:00Z",
              "app_metadata": { "provider": "google", "providers": ["google"] },
              "user_metadata": { "username": "huy.music", "has_password": true },
              "identities": [
                { "provider": "google", "identity_data": { "email": "huy2010qn@gmail.com" } }
              ]
            }
        """.trimIndent()
        val googleWithPassword = MochyGoogleOAuthHelper.parseIdentityInfo(googleWithPasswordJson)
        assertTrue(googleWithPassword.hasGoogleLinked)
        assertEquals("huy2010qn@gmail.com", googleWithPassword.googleEmail)
        assertTrue(googleWithPassword.hasPasswordLogin)
        assertEquals("huy.music", googleWithPassword.username)

        // 3. @username-only account (synthetic email, no Google)
        val usernameOnlyJson = """
            {
              "id": "u-user-3",
              "email": "duchuy@account.mochy.app",
              "created_at": "2026-10-15T12:00:00Z",
              "last_sign_in_at": "2026-11-19T07:10:00Z",
              "app_metadata": { "provider": "email", "providers": ["email"] },
              "user_metadata": { "username": "duchuy", "display_name": "Đức Huy" },
              "identities": [
                { "provider": "email", "identity_data": { "email": "duchuy@account.mochy.app" } }
              ]
            }
        """.trimIndent()
        val usernameOnly = MochyGoogleOAuthHelper.parseIdentityInfo(usernameOnlyJson)
        assertFalse(usernameOnly.hasGoogleLinked)
        assertNull(usernameOnly.googleEmail)
        assertTrue(usernameOnly.hasPasswordLogin)
        assertEquals("duchuy", usernameOnly.username)

        // 4. @username account linked with Google (wrapped session object + synthetic primary email)
        val usernameLinkedGoogleJson = """
            {
              "access_token": "jwt.token.here",
              "user": {
                "id": "u-user-4",
                "email": "duchuy@account.mochy.app",
                "created_at": "2026-10-15T12:00:00Z",
                "last_sign_in_at": "2026-11-19T07:20:00Z",
                "app_metadata": { "provider": "email", "providers": ["email", "google"] },
                "user_metadata": { "username": "duchuy", "display_name": "Đức Huy" },
                "identities": [
                  { "provider": "email", "identity_data": { "email": "duchuy@account.mochy.app" } },
                  { "provider": "google", "identity_data": { "email": "Huy.Linked@gmail.com" } }
                ]
              }
            }
        """.trimIndent()
        val linked = MochyGoogleOAuthHelper.parseIdentityInfo(usernameLinkedGoogleJson)
        assertTrue(linked.hasGoogleLinked)
        assertEquals("huy.linked@gmail.com", linked.googleEmail)
        assertTrue(linked.hasPasswordLogin)
        assertEquals("duchuy", linked.username)

        // 5. Blank / malformed JSON fails safely
        val empty = MochyGoogleOAuthHelper.parseIdentityInfo("not-json")
        assertFalse(empty.hasGoogleLinked)
        assertNull(empty.googleEmail)
        assertFalse(empty.hasPasswordLogin)
        assertNull(empty.username)
    }

    @Test
    fun edgeUsernameLoginHelperParsesAllAuthoritativeStatusesAndFallsBackOnGatewayErrors() {
        assertEquals(
            "https://cloud.example.test/functions/v1/mochy-username-login",
            MochyUsernameLoginEdgeHelper.buildEndpointUrl("https://cloud.example.test/"),
        )

        // 1. Authoritative sign-in success with GoTrue session payload
        val okJson = """
            {
              "source": "mochy-edge",
              "status": "ok",
              "session": {
                "access_token": "jwt.access.123",
                "refresh_token": "refresh.456",
                "expires_in": 3600,
                "user": { "id": "u-1", "email": "huy@gmail.com" }
              }
            }
        """.trimIndent()
        val ok = MochyUsernameLoginEdgeHelper.parseSignInResponse(200, okJson)
        assertTrue(ok is EdgeSignInOutcome.Success)
        assertTrue((ok as EdgeSignInOutcome.Success).sessionRawJson.contains("jwt.access.123"))

        // 2. Authoritative error statuses (zero Gmail exposed)
        assertEquals(
            EdgeSignInOutcome.NotFound,
            MochyUsernameLoginEdgeHelper.parseSignInResponse(
                404,
                """{"source":"mochy-edge","status":"not_found"}""",
            ),
        )
        assertEquals(
            EdgeSignInOutcome.GoogleNoPassword,
            MochyUsernameLoginEdgeHelper.parseSignInResponse(
                400,
                """{"source":"mochy-edge","status":"google_no_password"}""",
            ),
        )
        assertEquals(
            EdgeSignInOutcome.InvalidCredentials(hasGoogle = true),
            MochyUsernameLoginEdgeHelper.parseSignInResponse(
                401,
                """{"source":"mochy-edge","status":"invalid_credentials","has_google":true}""",
            ),
        )
        assertEquals(
            EdgeSignInOutcome.InvalidCredentials(hasGoogle = false),
            MochyUsernameLoginEdgeHelper.parseSignInResponse(
                401,
                """{"source":"mochy-edge","status":"invalid_credentials","has_google":false}""",
            ),
        )
        assertEquals(
            EdgeSignInOutcome.RateLimited(retryAfterSec = 180),
            MochyUsernameLoginEdgeHelper.parseSignInResponse(
                429,
                """{"source":"mochy-edge","status":"rate_limited","retry_after":180}""",
            ),
        )

        // 3. Authoritative sign-up availability checks
        assertEquals(
            EdgeCheckSignUpOutcome.Available,
            MochyUsernameLoginEdgeHelper.parseCheckSignUpResponse(
                200,
                """{"source":"mochy-edge","status":"available"}""",
            ),
        )
        assertEquals(
            EdgeCheckSignUpOutcome.Taken(hasGoogle = true),
            MochyUsernameLoginEdgeHelper.parseCheckSignUpResponse(
                200,
                """{"source":"mochy-edge","status":"taken","has_google":true}""",
            ),
        )
        assertEquals(
            EdgeCheckSignUpOutcome.Taken(hasGoogle = false),
            MochyUsernameLoginEdgeHelper.parseCheckSignUpResponse(
                200,
                """{"source":"mochy-edge","status":"taken","has_google":false}""",
            ),
        )
        assertEquals(
            EdgeCheckSignUpOutcome.RateLimited(retryAfterSec = 90),
            MochyUsernameLoginEdgeHelper.parseCheckSignUpResponse(
                429,
                """{"source":"mochy-edge","status":"rate_limited","retry_after":90}""",
            ),
        )

        // 4. Non-edge gateway responses MUST fall back to Unavailable so login is never bricked
        val gateway404 = """{"code":"NOT_FOUND","message":"Requested function was not found"}"""
        assertEquals(EdgeSignInOutcome.Unavailable, MochyUsernameLoginEdgeHelper.parseSignInResponse(404, gateway404))
        assertEquals(EdgeCheckSignUpOutcome.Unavailable, MochyUsernameLoginEdgeHelper.parseCheckSignUpResponse(404, gateway404))

        val gatewayJwt401 = """{"code":401,"message":"Missing authorization header"}"""
        assertEquals(EdgeSignInOutcome.Unavailable, MochyUsernameLoginEdgeHelper.parseSignInResponse(401, gatewayJwt401))
        assertEquals(EdgeCheckSignUpOutcome.Unavailable, MochyUsernameLoginEdgeHelper.parseCheckSignUpResponse(401, gatewayJwt401))

        assertEquals(EdgeSignInOutcome.Unavailable, MochyUsernameLoginEdgeHelper.parseSignInResponse(500, "not-json"))
        assertEquals(EdgeCheckSignUpOutcome.Unavailable, MochyUsernameLoginEdgeHelper.parseCheckSignUpResponse(500, ""))
    }
}
