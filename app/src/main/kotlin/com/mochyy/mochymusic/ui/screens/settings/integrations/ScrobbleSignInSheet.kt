// last.fm or libre.fm sign-in right inside mochy, the password goes to the service once and only the key it sends back is kept

package com.mochyy.mochymusic.ui.screens.settings.integrations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.scrobble.ScrobbleSite
import com.mochyy.mochymusic.ui.component.SheetLabel
import com.mochyy.mochymusic.ui.component.SheetPillButton
import com.mochyy.mochymusic.ui.component.SheetTextField
import com.mochyy.mochymusic.ui.screens.search.SearchColors
import com.mochyy.mochymusic.viewmodels.IntegrationsViewModel
import com.music.lastfm.AudioscrobblerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

private val ProblemColor = Color(0xFFFF8A80)
private const val LASTFM_CREATE_API_KEY_URL = "https://www.last.fm/api/account/create"

@Composable
fun ScrobbleSignInContent(
    site: ScrobbleSite,
    viewModel: IntegrationsViewModel,
    onSignedIn: (username: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    val savedCredentials by viewModel.customLastFmCredentials.collectAsState()
    val needsCustomKeys = site == ScrobbleSite.LASTFM && !site.api.isConfigured

    var username by rememberSaveable { mutableStateOf("") }
    // plain remember on purpose, a saveable password would be written into the saved screen state
    var password by remember { mutableStateOf("") }
    var customApiKey by rememberSaveable { mutableStateOf("") }
    var customSecret by rememberSaveable { mutableStateOf("") }
    var prefilledCustomKeys by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(savedCredentials, needsCustomKeys) {
        if (
            needsCustomKeys &&
            !prefilledCustomKeys &&
            (savedCredentials.first.isNotBlank() || savedCredentials.second.isNotBlank())
        ) {
            if (customApiKey.isEmpty()) customApiKey = savedCredentials.first
            if (customSecret.isEmpty()) customSecret = savedCredentials.second
            prefilledCustomKeys = true
        }
    }

    val canSubmit =
        username.isNotBlank() &&
            password.isNotEmpty() &&
            (!needsCustomKeys || (customApiKey.isNotBlank() && customSecret.isNotBlank())) &&
            !busy

    fun submit() {
        if (!canSubmit) return
        keyboard?.hide()
        busy = true
        problem = null
        scope.launch {
            try {
                val signedInName = if (needsCustomKeys) {
                    viewModel.signIn(site, username, password, customApiKey, customSecret)
                } else {
                    viewModel.signIn(site, username, password)
                }
                onSignedIn(signedInName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AudioscrobblerException) {
                Timber.tag("Scrobble").w(e, "${site.label} sign-in turned down")
                problem = when {
                    e.isWrongLogin -> context.getString(R.string.scrobble_sign_in_wrong)
                    e.isApiKeyRejected -> context.getString(R.string.scrobble_sign_in_invalid_api_key, site.label)
                    else -> context.getString(R.string.scrobble_sign_in_busy, site.label)
                }
            } catch (e: Exception) {
                problem = context.getString(R.string.scrobble_sign_in_offline, site.label)
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        Text(
            stringResource(R.string.scrobble_sign_in_title, site.label),
            color = SearchColors.Primary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.scrobble_sign_in_body, site.label),
            color = SearchColors.Secondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(22.dp))

        SheetLabel(stringResource(R.string.username))
        Spacer(Modifier.height(8.dp))
        SheetTextField(
            value = username,
            onValueChange = {
                username = it
                problem = null
            },
            placeholder = stringResource(R.string.scrobble_username_hint, site.label),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.semantics { contentType = ContentType.Username },
        )
        Spacer(Modifier.height(18.dp))

        SheetLabel(stringResource(R.string.password))
        Spacer(Modifier.height(8.dp))
        SheetTextField(
            value = password,
            onValueChange = {
                password = it
                problem = null
            },
            placeholder = stringResource(R.string.scrobble_password_hint, site.label),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = if (needsCustomKeys) ImeAction.Next else ImeAction.Done,
            ),
            keyboardActions = if (needsCustomKeys) KeyboardActions.Default else KeyboardActions(onDone = { submit() }),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.semantics { contentType = ContentType.Password },
        )

        if (needsCustomKeys) {
            Spacer(Modifier.height(18.dp))
            SheetLabel(stringResource(R.string.scrobble_api_key_label))
            Spacer(Modifier.height(8.dp))
            SheetTextField(
                value = customApiKey,
                onValueChange = {
                    customApiKey = it
                    prefilledCustomKeys = true
                    problem = null
                },
                placeholder = stringResource(R.string.scrobble_api_key_hint),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
            )
            Spacer(Modifier.height(18.dp))

            SheetLabel(stringResource(R.string.scrobble_shared_secret_label))
            Spacer(Modifier.height(8.dp))
            SheetTextField(
                value = customSecret,
                onValueChange = {
                    customSecret = it
                    prefilledCustomKeys = true
                    problem = null
                },
                placeholder = stringResource(R.string.scrobble_shared_secret_hint),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.scrobble_create_api_key),
                color = SearchColors.Primary.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        runCatching {
                            uriHandler.openUri(site.createApiKeyUrl ?: LASTFM_CREATE_API_KEY_URL)
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }

        problem?.let {
            Text(
                it,
                color = ProblemColor,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
            )
        }
        Spacer(Modifier.height(22.dp))

        SheetPillButton(
            text = stringResource(if (busy) R.string.scrobble_signing_in else R.string.scrobble_sign_in_button),
            onClick = ::submit,
            enabled = canSubmit,
        )
        Text(
            stringResource(R.string.scrobble_sign_up, site.label),
            color = SearchColors.Secondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clickable { runCatching { uriHandler.openUri(site.signUpUrl) } }
                .padding(vertical = 10.dp),
        )
    }
}
