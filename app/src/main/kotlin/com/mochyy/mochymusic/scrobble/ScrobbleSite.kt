// ScrobbleSite.kt

package com.mochyy.mochymusic.scrobble

import androidx.datastore.preferences.core.Preferences
import com.mochyy.mochymusic.BuildConfig
import com.mochyy.mochymusic.constants.CustomLastFmApiKey
import com.mochyy.mochymusic.constants.CustomLastFmSecretKey
import com.mochyy.mochymusic.constants.EnableLastFMScrobblingKey
import com.mochyy.mochymusic.constants.EnableLibreFmScrobblingKey
import com.mochyy.mochymusic.constants.LastFMSessionKey
import com.mochyy.mochymusic.constants.LastFMUsernameKey
import com.mochyy.mochymusic.constants.LibreFmSessionKey
import com.mochyy.mochymusic.constants.LibreFmUsernameKey
import com.mochyy.mochymusic.utils.AppProxy
import com.music.lastfm.Audioscrobbler
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

private val scrobbleHttpClient: HttpClient by lazy {
    HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        expectSuccess = false
        engine {
            preconfigured = AppProxy.sharedClient
        }
    }
}

/**
 * The services Mochy scrobbles to. Both speak Last.fm's API, so one client serves both. Their
 * API keys come from local.properties (or CI secrets) at build time and never sit in the source.
 */
enum class ScrobbleSite(
    val label: String,
    val sessionKey: Preferences.Key<String>,
    val usernameKey: Preferences.Key<String>,
    /** Scrobbling on or paused, with the account kept either way. */
    val enabledKey: Preferences.Key<Boolean>,
    private val profilePage: String,
    /** where someone without an account makes one */
    val signUpUrl: String,
    val api: Audioscrobbler,
    /** where someone can create a personal API key when the build doesn't embed one */
    val createApiKeyUrl: String? = null,
) {
    LASTFM(
        label = "Last.fm",
        sessionKey = LastFMSessionKey,
        usernameKey = LastFMUsernameKey,
        enabledKey = EnableLastFMScrobblingKey,
        profilePage = "https://www.last.fm/user/",
        signUpUrl = "https://www.last.fm/join",
        api = Audioscrobbler(
            apiRoot = "https://ws.audioscrobbler.com/2.0/",
            apiKey = BuildConfig.LASTFM_API_KEY,
            secret = BuildConfig.LASTFM_SECRET,
            httpClientProvider = { scrobbleHttpClient },
        ),
        createApiKeyUrl = "https://www.last.fm/api/account/create",
    ),
    LIBREFM(
        label = "Libre.fm",
        sessionKey = LibreFmSessionKey,
        usernameKey = LibreFmUsernameKey,
        enabledKey = EnableLibreFmScrobblingKey,
        profilePage = "https://libre.fm/user/",
        signUpUrl = "https://libre.fm/register.php",
        api = Audioscrobbler(
            apiRoot = "https://libre.fm/2.0/",
            apiKey = BuildConfig.LIBREFM_API_KEY,
            secret = BuildConfig.LIBREFM_SECRET,
            signsInWithToken = true,
            httpClientProvider = { scrobbleHttpClient },
        ),
    );

    fun profileUrl(username: String) = profilePage + username

    /** Resolves the client to use: prefers [api] if configured, else user-provided Last.fm keys in [prefs]. */
    fun resolveApi(prefs: Preferences): Audioscrobbler {
        if (api.isConfigured || this != LASTFM) return api
        return api.withCredentials(
            overrideApiKey = prefs[CustomLastFmApiKey].orEmpty(),
            overrideSecret = prefs[CustomLastFmSecretKey].orEmpty(),
        )
    }
}
