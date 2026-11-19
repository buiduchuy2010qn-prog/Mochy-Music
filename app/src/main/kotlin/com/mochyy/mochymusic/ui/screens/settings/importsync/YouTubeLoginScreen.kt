package com.mochyy.mochymusic.ui.screens.settings.importsync

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.cloud.MochyCloudVault
import com.mochyy.mochymusic.constants.AccountChannelHandleKey
import com.mochyy.mochymusic.constants.AccountEmailKey
import com.mochyy.mochymusic.constants.AccountNameKey
import com.mochyy.mochymusic.constants.DataSyncIdKey
import com.mochyy.mochymusic.constants.InnerTubeCookieKey
import com.mochyy.mochymusic.constants.VisitorDataKey
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.pressBounce
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.ui.utils.stableStatusBarsPadding
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.utils.rememberPreference
import com.music.innertube.YouTube
import com.music.innertube.models.AccountInfo
import com.music.innertube.utils.parseCookieString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder

private const val ACCOUNT_CHOOSER_URL =
    "https://accounts.google.com/AccountChooser?continue=https%3A%2F%2Fmusic.youtube.com&service=youtube"
private const val YOUTUBE_MUSIC_ORIGIN = "https://music.youtube.com"

private fun isValidEmailHint(email: String?): Boolean {
    val trimmed = email?.trim().orEmpty()
    val at = trimmed.indexOf('@')
    return at > 0 &&
        at < trimmed.length - 1 &&
        !trimmed.endsWith("@account.mochy.app", ignoreCase = true)
}

internal fun buildYouTubeLoginUrl(emailHint: String?): String {
    val clean = emailHint?.trim()?.takeIf(::isValidEmailHint)
    return if (clean != null) {
        "$ACCOUNT_CHOOSER_URL&Email=${URLEncoder.encode(clean, "UTF-8")}"
    } else {
        ACCOUNT_CHOOSER_URL
    }
}

/**
 * Clears all stale Google / YouTube cookies and WebStorage inside WebView before loading [targetUrl]
 * so switching Gmail or choosing "Use another Gmail" never snaps back into the previous account session.
 */
private fun clearGoogleWebSessionAndLoad(
    view: WebView?,
    targetUrl: String,
) {
    if (view == null) return
    runCatching {
        val storage = WebStorage.getInstance()
        listOf(
            "https://accounts.google.com",
            "https://music.youtube.com",
            "https://www.youtube.com",
        ).forEach { origin ->
            storage.deleteOrigin(origin)
        }
    }
    var loaded = false
    runCatching {
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            runCatching { cookies.flush() }
            if (!loaded) {
                loaded = true
                view.clearCache(true)
                view.loadUrl(targetUrl)
            }
        }
        cookies.flush()
    }.onFailure {
        if (!loaded) {
            loaded = true
            view.clearCache(true)
            view.loadUrl(targetUrl)
        }
    }
}

private fun mergeCookieHeaders(primary: String?, fallback: String?): String? {
    val p = primary?.trim().orEmpty()
    val f = fallback?.trim().orEmpty()
    if (p.isEmpty() && f.isEmpty()) return null
    if (p.isEmpty()) return f
    if (f.isEmpty() || p == f) return p
    val merged = LinkedHashMap<String, String>()
    fun ingest(raw: String) {
        raw.split(';').forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isNotEmpty()) merged[name] = value
            }
        }
    }
    ingest(f)
    ingest(p)
    return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifBlank { p }
}

/** reads the session ids youtube music keeps in its page config */
private const val READ_CONFIG_JS = """
(function() {
  try {
    var get = function(k) {
      if (window.ytcfg && ytcfg.get) { var v = ytcfg.get(k); if (v) return v; }
      if (window.yt && yt.config_ && yt.config_[k]) return yt.config_[k];
      return null;
    };
    return { visitorData: get('VISITOR_DATA'), dataSyncId: get('DATASYNC_ID') };
  } catch (e) { return null; }
})();
"""

@Composable
private fun rememberDeviceGoogleAccountPicker(
    onPicked: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pickedEmail = result.data
                ?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                ?.trim()
                ?.takeIf(::isValidEmailHint)
            if (pickedEmail != null) {
                currentOnPicked(pickedEmail)
            }
        }
    }
    return remember(context, launcher) {
        {
            try {
                val intent = AccountManager.newChooseAccountIntent(
                    null,
                    null,
                    arrayOf("com.google"),
                    null,
                    null,
                    null,
                    null,
                )
                launcher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    context,
                    context.getString(R.string.yt_login_device_picker_unavailable),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

@Composable
private fun DeviceGmailPickerChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .pressBounce(interaction, pressedScale = 0.95f)
            .clip(CircleShape)
            .background(MenuRowSurface)
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = RemoteFocus,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_google_logo),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = stringResource(R.string.yt_login_pick_device_gmail),
            color = Color.White,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UseAnotherGmailChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .pressBounce(interaction, pressedScale = 0.95f)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = RemoteFocus,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            text = stringResource(R.string.yt_login_use_another_gmail),
            color = Color.White.copy(alpha = 0.9f),
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** the full-screen sign-in page used from the sync settings */
@Composable
fun YouTubeLoginScreen(navController: NavController) {
    val context = LocalContext.current
    var pageLoading by remember { mutableStateOf(true) }
    var pickedEmail by remember { mutableStateOf<String?>(null) }
    var pickedEmailVersion by remember { mutableIntStateOf(0) }
    var switchRequestVersion by remember { mutableIntStateOf(0) }

    val launchDeviceAccountPicker = rememberDeviceGoogleAccountPicker { email ->
        pickedEmail = email
        pickedEmailVersion++
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .stableStatusBarsPadding()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        ) {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.yt_login_back),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.yt_login_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.yt_login_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
        ) {
            DeviceGmailPickerChip(onClick = launchDeviceAccountPicker)
            UseAnotherGmailChip(
                onClick = {
                    pickedEmail = null
                    switchRequestVersion++
                },
            )
        }
        Box(modifier = Modifier.height(2.dp).fillMaxWidth()) {
            if (pageLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        YouTubeLoginWebContent(
            onSignedIn = { info ->
                Toast
                    .makeText(
                        context,
                        info?.name?.let { context.getString(R.string.yt_login_signed_in_as, it) }
                            ?: context.getString(R.string.yt_login_signed_in_general),
                        Toast.LENGTH_SHORT,
                    ).show()
                navController.navigateUp()
            },
            onExit = { navController.navigateUp() },
            onLoadingChange = { pageLoading = it },
            showDeviceAccountPicker = false,
            externalEmailHint = pickedEmail,
            externalEmailRequestVersion = pickedEmailVersion,
            externalSwitchRequestVersion = switchRequestVersion,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** google sign-in in a webview without chrome, once signed in on music.youtube.com it saves the session where the app reads it */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginWebContent(
    onSignedIn: (AccountInfo?) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onUrlChange: (String) -> Unit = {},
    onLoadingChange: (Boolean) -> Unit = {},
    showDeviceAccountPicker: Boolean = true,
    freshAccountSwitch: Boolean = false,
    externalEmailHint: String? = null,
    externalEmailRequestVersion: Int = 0,
    externalSwitchRequestVersion: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnSignedIn by rememberUpdatedState(onSignedIn)
    val currentOnUrlChange by rememberUpdatedState(onUrlChange)
    val currentOnLoadingChange by rememberUpdatedState(onLoadingChange)
    val savedAccountEmail by rememberPreference(AccountEmailKey, "")
    val initialEmailHint = remember(context, freshAccountSwitch) {
        if (freshAccountSwitch) {
            null
        } else {
            MochyCloudVault.loadGoogleEmail(context)?.trim()?.takeIf(::isValidEmailHint)
                ?: savedAccountEmail.trim().takeIf(::isValidEmailHint)
        }
    }
    var activeEmailHint by remember(initialEmailHint, freshAccountSwitch) {
        mutableStateOf(
            if (freshAccountSwitch) {
                null
            } else {
                externalEmailHint?.trim()?.takeIf(::isValidEmailHint) ?: initialEmailHint
            },
        )
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var saving by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    val launchDeviceAccountPicker = rememberDeviceGoogleAccountPicker { email ->
        activeEmailHint = email
        clearGoogleWebSessionAndLoad(webView, buildYouTubeLoginUrl(email))
    }

    LaunchedEffect(externalEmailRequestVersion) {
        if (externalEmailRequestVersion > 0) {
            val clean = externalEmailHint?.trim()?.takeIf(::isValidEmailHint) ?: return@LaunchedEffect
            activeEmailHint = clean
            clearGoogleWebSessionAndLoad(webView, buildYouTubeLoginUrl(clean))
        }
    }

    LaunchedEffect(externalSwitchRequestVersion) {
        if (externalSwitchRequestVersion > 0) {
            activeEmailHint = null
            clearGoogleWebSessionAndLoad(webView, buildYouTubeLoginUrl(null))
        }
    }

    fun finishLogin(
        view: WebView,
        url: String,
    ) {
        if (saving || finished) return
        val cookieManager = CookieManager.getInstance()
        runCatching { cookieManager.flush() }
        val cookie = mergeCookieHeaders(
            primary = cookieManager.getCookie(url),
            fallback = cookieManager.getCookie(YOUTUBE_MUSIC_ORIGIN),
        ) ?: return
        if ("SAPISID" !in runCatching { parseCookieString(cookie) }.getOrDefault(emptyMap())) return
        saving = true
        view.evaluateJavascript(READ_CONFIG_JS) { raw ->
            val config = runCatching { JSONObject(raw) }.getOrNull()
            val visitorData = config?.optString("visitorData")?.takeIf { it.isNotBlank() && it != "null" }
            val dataSyncId = config?.optString("dataSyncId")?.takeIf { it.isNotBlank() && it != "null" }
            scope.launch(Dispatchers.IO) {
                try {
                    // account details need the new session, the app would set these from prefs anyway
                    YouTube.cookie = cookie
                    visitorData?.let { YouTube.visitorData = it }
                    YouTube.dataSyncId =
                        dataSyncId?.let {
                            it.takeIf { !it.contains("||") }
                                ?: it.takeIf { it.endsWith("||") }?.substringBefore("||")
                                ?: it.substringAfter("||")
                        }
                    val info = YouTube.accountInfo().getOrNull()
                    context.dataStore.edit { settings ->
                        settings[InnerTubeCookieKey] = cookie
                        visitorData?.let { settings[VisitorDataKey] = it }
                        dataSyncId?.let { settings[DataSyncIdKey] = it }
                        settings[AccountNameKey] = info?.name.orEmpty()
                        settings[AccountEmailKey] = info?.email.orEmpty()
                        settings[AccountChannelHandleKey] = info?.channelHandle.orEmpty()
                    }
                    runCatching { CookieManager.getInstance().flush() }
                    withContext(Dispatchers.Main) {
                        finished = true
                        currentOnSignedIn(info)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Saving YouTube login failed")
                    withContext(Dispatchers.Main) {
                        saving = false
                        Toast.makeText(context, context.getString(R.string.yt_login_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack() && !saving) view.goBack() else onExit()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }

    Column(modifier = modifier) {
        if (showDeviceAccountPicker) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C1C1C))
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
            ) {
                Text(
                    text = activeEmailHint?.let {
                        stringResource(R.string.yt_login_prefilled_email, it)
                    } ?: stringResource(R.string.yt_login_device_picker_prompt),
                    color = Color.White.copy(alpha = 0.72f),
                    fontFamily = InterFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DeviceGmailPickerChip(onClick = launchDeviceAccountPicker)
                    UseAnotherGmailChip(
                        onClick = {
                            activeEmailHint = null
                            clearGoogleWebSessionAndLoad(webView, buildYouTubeLoginUrl(null))
                        },
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // see CaptureWebContent: wrap_content makes chromium lay pages out at zero height
                        layoutParams =
                            android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        // google refuses sign-in from pages that announce themselves as an embedded webview
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        disableForcedDarkening()
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    currentOnLoadingChange(true)
                                    url?.let(currentOnUrlChange)
                                }

                                override fun onPageFinished(
                                    view: WebView,
                                    url: String?,
                                ) {
                                    currentOnLoadingChange(false)
                                    url?.let(currentOnUrlChange)
                                    if (url != null && (
                                            url.startsWith("https://accounts.google.com") ||
                                                url.startsWith(YOUTUBE_MUSIC_ORIGIN)
                                        )
                                    ) {
                                        runCatching { CookieManager.getInstance().flush() }
                                    }
                                    if (url != null && url.startsWith(YOUTUBE_MUSIC_ORIGIN)) {
                                        finishLogin(view, url)
                                    }
                                }
                            }
                        if (freshAccountSwitch) {
                            clearGoogleWebSessionAndLoad(this, buildYouTubeLoginUrl(null))
                        } else {
                            loadUrl(buildYouTubeLoginUrl(activeEmailHint))
                        }
                        webView = this
                    }
                },
            )
            if (saving) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f)),
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
