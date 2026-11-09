package com.mochyy.mochymusic.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import com.mochyy.mochymusic.ui.component.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.CrossmixTestToolsKey
import com.mochyy.mochymusic.constants.ForcePlaybackKey
import com.mochyy.mochymusic.constants.ShowStreamSourceToastKey
import com.mochyy.mochymusic.constants.ForceYtBackdropBlur1500Key
import com.mochyy.mochymusic.constants.HapticFocus
import com.mochyy.mochymusic.constants.HapticFocusKey
import com.mochyy.mochymusic.constants.HapticSensitivity
import com.mochyy.mochymusic.constants.MusicHapticsEnabledKey
import com.mochyy.mochymusic.constants.MusicHapticsSensitivityKey
import com.mochyy.mochymusic.constants.SetupCompletedKey
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.utils.dataStore
import com.mochyy.mochymusic.utils.rememberEnumPreference
import com.mochyy.mochymusic.utils.rememberPreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentalSettingsScreen(navController: NavController) {
    val (musicHapticsEnabled, onMusicHapticsEnabledChange) = rememberPreference(MusicHapticsEnabledKey, defaultValue = false)
    val (musicHapticsSensitivity, onMusicHapticsSensitivityChange) = rememberEnumPreference(MusicHapticsSensitivityKey, defaultValue = HapticSensitivity.MEDIUM)
    val (hapticFocus, onHapticFocusChange) = rememberEnumPreference(HapticFocusKey, defaultValue = HapticFocus.VIBE)
    val (showBigDiscStyles, onShowBigDiscStylesChange) = rememberPreference(
        com.mochyy.mochymusic.constants.ShowBigDiscStylesKey,
        defaultValue = false,
    )
    val (forceRecap, onForceRecapChange) = rememberPreference(
        com.mochyy.mochymusic.constants.ForceMonthlyRecapKey,
        defaultValue = false,
    )
    val (forceYtBackdropBlur1500, onForceYtBackdropBlur1500Change) = rememberPreference(
        ForceYtBackdropBlur1500Key,
        defaultValue = false,
    )

    val (crossmixTestTools, onCrossmixTestToolsChange) = rememberPreference(
        CrossmixTestToolsKey,
        defaultValue = false,
    )
    val crossmixTools = crossmixTestItems(isVisible = crossmixTestTools)
    val (sourceBanner, onSourceBannerChange) = rememberPreference(ShowStreamSourceToastKey, defaultValue = false)
    val (forcePlayback, onForcePlaybackChange) = rememberPreference(ForcePlaybackKey, defaultValue = false)

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    SubSettingsScaffold(
        title = "Thử nghiệm",
        onBack = { navController.navigateUp() },
    ) {
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = buildList {
                    add(
                        SettingsItem(
                            title = { Text("Nhập & đồng bộ") },
                            highlightKey = "Import & sync",
                            descriptionText = "Đưa danh sách phát từ dịch vụ khác về, đồng bộ với YouTube, xuất bản sao lưu",
                            icon = painterResource(R.drawable.sync),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            onClick = { navController.navigate(com.mochyy.mochymusic.ui.screens.settings.importsync.ImportSyncRoute) }
                        )
                    )
                    add(
                        SettingsItem(
                            title = { Text("Công cụ thử nghiệm Crossmix") },
                            highlightKey = "Crossmix test tools",
                            descriptionText = "Trạng thái trực tiếp và nút tua tới điểm trộn bài",
                            detail = "Hiển thị trạng thái trực tiếp và nút tua nhanh tới ngay trước lần trộn bài tiếp theo",
                            icon = painterResource(R.drawable.graphic_eq),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            onClick = { onCrossmixTestToolsChange(!crossmixTestTools) },
                            trailingContent = {
                                AppSwitch(
                                    checked = crossmixTestTools,
                                    onCheckedChange = onCrossmixTestToolsChange,
                                )
                            }
                        )
                    )
                    crossmixTools.forEach { add(it.copy(isSubOption = true)) }
                    add(
                        SettingsItem(
                            title = { Text("Hiển thị nguồn phát bài hát") },
                            highlightKey = "Show where songs play from",
                            descriptionText = "Biểu ngữ hiển thị nguồn phát và chất lượng của mỗi bài hát",
                            detail = "Biểu ngữ ở phía trên hiển thị nguồn của bài hát: Apple Music, Monochrome hoặc YouTube cùng chất lượng âm thanh",
                            icon = painterResource(R.drawable.info),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            onClick = { onSourceBannerChange(!sourceBanner) },
                            trailingContent = {
                                AppSwitch(checked = sourceBanner, onCheckedChange = onSourceBannerChange)
                            }
                        )
                    )
                    add(
                        SettingsItem(
                            title = { Text("Buộc phát nhạc") },
                            highlightKey = "Force playback",
                            descriptionText = "Nếu một nguồn phát bị lỗi, tự động thử nguồn tiếp theo",
                            detail = "Không bao giờ dừng lại: nếu một nguồn không phát được, nguồn tiếp theo sẽ tự động thay thế; nếu không nguồn nào phát được, bài hát sẽ được bỏ qua",
                            icon = painterResource(R.drawable.play),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            onClick = { onForcePlaybackChange(!forcePlayback) },
                            trailingContent = {
                                AppSwitch(checked = forcePlayback, onCheckedChange = onForcePlaybackChange)
                            }
                        )
                    )
                    add(
                        SettingsItem(
                            title = { Text("Buộc làm mờ ảnh YouTube 1500 px") },
                            highlightKey = "Force 1500 px YouTube blur",
                            descriptionText = "Ghi đè mức mặc định 120 px; có thể làm tăng tải GPU",
                            icon = painterResource(R.drawable.tune),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            onClick = { onForceYtBackdropBlur1500Change(!forceYtBackdropBlur1500) },
                            trailingContent = {
                                AppSwitch(
                                    checked = forceYtBackdropBlur1500,
                                    onCheckedChange = onForceYtBackdropBlur1500Change,
                                )
                            }
                        )
                    )
                    add(
                        SettingsItem(
                            title = { Text("Buộc hiển thị tổng kết tháng") },
                            highlightKey = "Force show monthly recap",
                            descriptionText = "Mở khóa sớm bản tổng kết tháng",
                            detail = "Mở khóa ngay bản tổng kết tháng phía sau thẻ Mochy mà không cần đợi",
                            icon = painterResource(R.drawable.history),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            onClick = { onForceRecapChange(!forceRecap) },
                            trailingContent = {
                                AppSwitch(
                                    checked = forceRecap,
                                    onCheckedChange = onForceRecapChange,
                                )
                            }
                        )
                    )
                    add(
                        SettingsItem(
                            title = { Text("Kiểu bìa đĩa nhạc cỡ lớn") },
                            highlightKey = "Big disc cover styles",
                            descriptionText = "Unfinished — oversized discs that bleed off screen",
                            icon = painterResource(R.drawable.album),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            onClick = { onShowBigDiscStylesChange(!showBigDiscStyles) },
                            trailingContent = {
                                AppSwitch(
                                    checked = showBigDiscStyles,
                                    onCheckedChange = onShowBigDiscStylesChange,
                                )
                            }
                        )
                    )
                    add(
                        SettingsItem(
                            title = { Text("Rung phản hồi theo nhạc") },
                            highlightKey = "Music haptics",
                            descriptionText = "Vibrate to the beat — uses battery",
                            icon = painterResource(R.drawable.music_note),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            onClick = { onMusicHapticsEnabledChange(!musicHapticsEnabled) },
                            trailingContent = {
                                AppSwitch(checked = musicHapticsEnabled, onCheckedChange = onMusicHapticsEnabledChange)
                            }
                        )
                    )
                    add(
                        SettingsItem(
                            title = { Text("Độ nhạy rung") },
                            description = {
                                Text(
                                    when (musicHapticsSensitivity) {
                                        HapticSensitivity.LOW -> "Thấp"
                                        HapticSensitivity.MEDIUM -> "Trung bình"
                                        HapticSensitivity.HIGH -> "Cao"
                                    }
                                )
                            },
                            icon = painterResource(R.drawable.sliders),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            enabled = musicHapticsEnabled,
                            onClick = {
                                onMusicHapticsSensitivityChange(
                                    when (musicHapticsSensitivity) {
                                        HapticSensitivity.LOW -> HapticSensitivity.MEDIUM
                                        HapticSensitivity.MEDIUM -> HapticSensitivity.HIGH
                                        HapticSensitivity.HIGH -> HapticSensitivity.LOW
                                    }
                                )
                            }
                        )
                    )
                    add(
                        SettingsItem(
                            title = { Text("Trọng tâm nhịp rung") },
                            description = {
                                Text(
                                    when (hapticFocus) {
                                        HapticFocus.BALANCE -> "Cân bằng"
                                        HapticFocus.VOCAL -> "Tập trung giọng hát"
                                        HapticFocus.VIBE -> "Tập trung giai điệu"
                                        HapticFocus.BASS -> "Tập trung âm trầm"
                                    }
                                )
                            },
                            icon = painterResource(R.drawable.equalizer),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            enabled = musicHapticsEnabled,
                            onClick = {
                                onHapticFocusChange(
                                    when (hapticFocus) {
                                        HapticFocus.BALANCE -> HapticFocus.VOCAL
                                        HapticFocus.VOCAL -> HapticFocus.VIBE
                                        HapticFocus.VIBE -> HapticFocus.BASS
                                        HapticFocus.BASS -> HapticFocus.BALANCE
                                    }
                                )
                            }
                        )
                    )
                    add(
                        SettingsItem(
                            title = { Text("Cipher") },
                            highlightKey = "Cipher",
                            descriptionText = "Trạng thái mã trình phát, bộ đếm ngược và làm mới",
                            icon = painterResource(R.drawable.key),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            onClick = { navController.navigate("cipher_settings") }
                        )
                    )
                    add(
                        SettingsItem(
                            title = { Text("Chẩn đoán phát nhạc") },
                            highlightKey = "Playback diagnostics",
                            descriptionText = "Xem máy khách nào đã phục vụ từng bài hát và trạng thái PoToken",
                            icon = painterResource(R.drawable.info),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            onClick = { navController.navigate("playback_diagnostics") }
                        )
                    )
                    add(
                        SettingsItem(
                            title = { Text("Chạy lại thiết lập ban đầu") },
                            highlightKey = "Repeat initial setup",
                            descriptionText = "Mở lại trình hướng dẫn thiết lập",
                            icon = painterResource(R.drawable.restore),
                            iconShape = androidx.compose.foundation.shape.CircleShape,
                            onClick = {
                                coroutineScope.launch {
                                    context.dataStore.updateData { prefs ->
                                        prefs.toMutablePreferences().apply {
                                            set(SetupCompletedKey, false)
                                        }
                                    }
                                }
                            }
                        )
                    )
                }
            )
    }
}
