// PlaylistHubSheet.kt

package com.mochyy.mochymusic.ui.screens.library

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.cloud.MochyOfflineBackupCodec
import com.mochyy.mochymusic.cloud.SinglePlaylistImportResult
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.ui.component.PopupSheetHandle
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.SheetIcon
import com.mochyy.mochymusic.ui.component.SheetLabel
import com.mochyy.mochymusic.ui.component.SheetOptionRow
import com.mochyy.mochymusic.ui.component.SheetPillButton
import com.mochyy.mochymusic.ui.component.SheetTextField
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.ui.screens.search.SearchColors
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
private class PlaylistHubFormState(initialCode: String) {
    var codeInput by mutableStateOf(initialCode)
    var busy by mutableStateOf(false)
}

/**
 * Opens the Library Playlists "+" hub sheet: create a brand-new playlist, open a `.mochypl` file,
 * jump to service imports, or paste/import a `mochypl1:` share code directly in place.
 */
fun PopupSheetState.showPlaylistHubSheet(
    context: Context,
    database: MusicDatabase,
    onCreatePlaylist: () -> Unit,
    onOpenMochyplFile: () -> Unit,
    onImportFromServices: () -> Unit,
    onPlaylistImported: (String) -> Unit,
) {
    val appContext = context.applicationContext
    val state = PlaylistHubFormState(readClipboardPlaylistCode(appContext).orEmpty())
    var sheet: PopupSheetHandle? = null

    fun submitImport(scope: CoroutineScope) {
        val raw = state.codeInput.trim()
        if (raw.isEmpty() || state.busy) return
        state.busy = true
        sheet?.setLocked(true)
        scope.launch {
            try {
                when (
                    val res = withContext(Dispatchers.IO) {
                        MochyOfflineBackupCodec.importSinglePlaylistFromCode(
                            context = appContext,
                            database = database,
                            rawTextOrCode = raw,
                        )
                    }
                ) {
                    is SinglePlaylistImportResult.Success -> {
                        val songsLabel = appContext.resources.getQuantityString(
                            R.plurals.n_song,
                            res.trackCount,
                            res.trackCount,
                        )
                        onPlaylistImported(res.playlistId)
                        sheet?.setLocked(false)
                        sheet?.dismiss()
                        TopToaster.show(
                            text = appContext.getString(
                                R.string.playlist_hub_imported_toast,
                                res.name,
                                songsLabel,
                            ),
                            iconRes = R.drawable.playlist_play,
                        )
                    }
                    is SinglePlaylistImportResult.Failure -> {
                        state.busy = false
                        sheet?.setLocked(false)
                        TopToaster.error(res.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.busy = false
                sheet?.setLocked(false)
                TopToaster.error(
                    e.message ?: appContext.getString(R.string.playlist_hub_import_code_failed),
                )
            }
        }
    }

    sheet = showWithHandle(
        buttonBar = {
            val scope = rememberCoroutineScope()
            val keyboard = LocalSoftwareKeyboardController.current
            SheetPillButton(
                text = stringResource(
                    if (state.busy) R.string.playlist_hub_importing_code
                    else R.string.playlist_hub_import_code_btn,
                ),
                enabled = state.codeInput.isNotBlank() && !state.busy,
                onClick = {
                    keyboard?.hide()
                    submitImport(scope)
                },
            )
        },
    ) {
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            SheetLabel(stringResource(R.string.playlist_hub_title))
            Spacer(Modifier.height(14.dp))

            SheetOptionRow(
                title = stringResource(R.string.playlist_hub_create_new),
                subtitle = stringResource(R.string.playlist_hub_create_new_desc),
                enabled = !state.busy,
                leading = { SheetIcon(R.drawable.playlist_add) },
                onClick = {
                    sheet?.dismiss()
                    onCreatePlaylist()
                },
            )

            Spacer(Modifier.height(8.dp))

            SheetOptionRow(
                title = stringResource(R.string.playlist_hub_open_mochypl),
                subtitle = stringResource(R.string.playlist_hub_open_mochypl_desc),
                enabled = !state.busy,
                leading = { SheetIcon(R.drawable.backup) },
                onClick = {
                    sheet?.dismiss()
                    onOpenMochyplFile()
                },
            )

            Spacer(Modifier.height(8.dp))

            SheetOptionRow(
                title = stringResource(R.string.playlist_hub_import_services),
                subtitle = stringResource(R.string.playlist_hub_import_services_desc),
                subtitleLines = 2,
                enabled = !state.busy,
                leading = { SheetIcon(R.drawable.sync) },
                onClick = {
                    sheet?.dismiss()
                    onImportFromServices()
                },
            )

            Spacer(Modifier.height(18.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SheetLabel(
                    text = stringResource(R.string.playlist_hub_code_label),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.import_component_paste),
                    color = SearchColors.Secondary,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(
                            enabled = !state.busy,
                            role = Role.Button,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = RemoteFocus,
                        ) {
                            val rawClip = readClipboardRawText(appContext)
                            if (rawClip == null) {
                                TopToaster.error(appContext.getString(R.string.playlist_hub_clipboard_empty))
                            } else {
                                state.codeInput = MochyOfflineBackupCodec.extractPlaylistCodeFromText(rawClip) ?: rawClip
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            SheetTextField(
                value = state.codeInput,
                onValueChange = { if (!state.busy) state.codeInput = it },
                placeholder = stringResource(R.string.playlist_hub_code_placeholder),
                leading = { SheetIcon(R.drawable.link) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (state.codeInput.isNotBlank() && !state.busy) {
                            keyboard?.hide()
                            submitImport(scope)
                        }
                    },
                ),
            )
        }
    }
}

private fun readClipboardRawText(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = cm?.primaryClip
    if (clip != null && clip.itemCount > 0) {
        clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
}.getOrNull()

private fun readClipboardPlaylistCode(context: Context): String? =
    readClipboardRawText(context)?.let(MochyOfflineBackupCodec::extractPlaylistCodeFromText)
