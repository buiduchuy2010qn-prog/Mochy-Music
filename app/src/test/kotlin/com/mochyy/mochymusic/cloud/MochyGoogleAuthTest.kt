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
}
