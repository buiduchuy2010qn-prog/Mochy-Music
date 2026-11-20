package com.mochyy.mochymusic.ui.screens.audiolab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.eq.AudioLabProfileSnapshot
import com.mochyy.mochymusic.eq.DeviceAudioProfile
import com.mochyy.mochymusic.eq.DeviceAudioProfileEngine
import com.mochyy.mochymusic.playback.audio.AudioOutputs
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.viewmodels.AudioLabViewModel

/**
 * Per-device Audio Lab card in the Audio Lab hub: shows the active output device,
 * auto-switch toggle, and all saved headphone / DAC / speaker profiles.
 */
@Composable
internal fun DeviceProfilesCard(
    viewModel: AudioLabViewModel,
    modifier: Modifier = Modifier,
) {
    val enabled by viewModel.deviceProfilesEnabled.collectAsState()
    val profiles by viewModel.deviceProfiles.collectAsState()
    val activeDevice by viewModel.activeDevice.collectAsState()

    val sortedProfiles = remember(profiles, activeDevice?.key) {
        profiles.values.sortedWith(
            compareByDescending<DeviceAudioProfile> { it.key == activeDevice?.key }
                .thenByDescending { it.updatedAtMs },
        )
    }

    LabCard(modifier = modifier, padding = 14.dp) {
        LabToggleRow(
            title = stringResource(R.string.smart_device_profiles_title),
            subtitle = stringResource(R.string.smart_device_profiles_desc),
            checked = enabled,
            onCheckedChange = viewModel::setDeviceProfilesEnabled,
            leadingDot = true,
        )

        LabCollapsible(visible = enabled) {
            Spacer(Modifier.height(10.dp))

            activeDevice?.let { current ->
                val activeName = localizedDeviceName(current.kind, current.displayName)
                val isSaved = profiles.containsKey(current.key)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LabColors.Row)
                        .border(1.dp, Color(0xFF8BE07A).copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF8BE07A).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = iconForDeviceKind(current.kind),
                            contentDescription = null,
                            tint = Color(0xFF8BE07A),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.smart_device_active_label).uppercase(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = Color(0xFF8BE07A),
                            maxLines = 1,
                        )
                        Text(
                            text = activeName,
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (isSaved) {
                        Text(
                            text = stringResource(R.string.smart_device_saved_badge),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF8BE07A),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF8BE07A).copy(alpha = 0.14f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.smart_device_save_now),
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.14f))
                                .clickable { viewModel.saveCurrentToActiveDevice() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            if (sortedProfiles.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LabSectionLabel(
                    text = stringResource(R.string.smart_device_saved_count, sortedProfiles.size),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sortedProfiles.forEach { profile ->
                        val kind = DeviceAudioProfileEngine.kindOf(profile.kind)
                        val isCurrent = profile.key == activeDevice?.key
                        val name = localizedDeviceName(kind, profile.displayName)
                        val summary = profileEffectsSummary(profile.snapshot)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isCurrent) LabColors.RowSelected else LabColors.Stage)
                                .clickable { viewModel.applyDeviceProfile(profile.key) }
                                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = iconForDeviceKind(kind),
                                contentDescription = null,
                                tint = if (isCurrent) Color.White else LabColors.Secondary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = name,
                                    fontFamily = InterFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = summary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = LabColors.Secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { viewModel.deleteDeviceProfile(profile.key) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = LabColors.Secondary,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.smart_device_profiles_footnote),
                fontFamily = InterFontFamily,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = LabColors.Secondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
internal fun localizedDeviceName(kind: AudioOutputs.Kind, storedName: String): String {
    if (storedName.isNotBlank() && kind != AudioOutputs.Kind.PHONE && kind != AudioOutputs.Kind.WIRED) {
        return storedName
    }
    return when (kind) {
        AudioOutputs.Kind.PHONE -> stringResource(R.string.smart_device_kind_phone)
        AudioOutputs.Kind.WIRED -> stringResource(R.string.smart_device_kind_wired)
        AudioOutputs.Kind.USB -> stringResource(R.string.smart_device_kind_usb)
        AudioOutputs.Kind.BLUETOOTH -> stringResource(R.string.smart_device_kind_bluetooth)
        AudioOutputs.Kind.HDMI -> stringResource(R.string.smart_device_kind_hdmi)
    }
}

private fun iconForDeviceKind(kind: AudioOutputs.Kind): ImageVector = when (kind) {
    AudioOutputs.Kind.PHONE -> Icons.Rounded.PhoneAndroid
    AudioOutputs.Kind.WIRED -> Icons.Rounded.Headphones
    AudioOutputs.Kind.USB -> Icons.Rounded.Usb
    AudioOutputs.Kind.BLUETOOTH -> Icons.Rounded.BluetoothAudio
    AudioOutputs.Kind.HDMI -> Icons.Rounded.Tv
}

@Composable
private fun profileEffectsSummary(snapshot: AudioLabProfileSnapshot): String {
    val tags = buildList {
        if (snapshot.eq.enabled) add("EQ")
        if (snapshot.eq.enabled && snapshot.eq.correctionEnabled && snapshot.eq.correction != null) {
            add("AutoEQ")
        }
        if (snapshot.spatial.enabled || snapshot.spatial.surround) add("Spatial")
        if (snapshot.eightD.enabled) add("8D")
        if (snapshot.loudness.enabled) add("Loudness")
        if (snapshot.reverb.enabled) add("Reverb")
    }
    return if (tags.isEmpty()) stringResource(R.string.smart_device_profile_flat) else tags.joinToString(" · ")
}
