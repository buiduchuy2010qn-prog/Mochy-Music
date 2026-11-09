package com.mochyy.mochymusic.ui.screens.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.SettingsItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


@Composable
fun crossmixTestItems(isVisible: Boolean = true): List<SettingsItem> {
    val playerConnection = LocalPlayerConnection.current
    val statusFlow: StateFlow<String> = remember(playerConnection) {
        playerConnection?.crossmixStatus ?: MutableStateFlow("")
    }
    val status by statusFlow.collectAsState()
    return listOf(
        SettingsItem(
            title = { Text("Trạng thái Crossmix") },
            descriptionText = status.ifBlank { "Chưa có dữ liệu. Hãy phát một bài hát có bài tiếp theo trong hàng đợi" },
            icon = painterResource(R.drawable.info),
            iconShape = androidx.compose.foundation.shape.CircleShape,
            isVisible = isVisible,
        ),
        SettingsItem(
            title = { Text("Thử trên bài hát này") },
            highlightKey = "Test on this song",
            descriptionText = "Trộn chuyển sang bài hát tiếp theo",
            detail = "Lên lịch trộn sang bài tiếp theo rồi tua tới vài giây trước điểm chuyển",
            icon = painterResource(R.drawable.fast_forward),
            iconShape = androidx.compose.foundation.shape.CircleShape,
            enabled = playerConnection != null,
            isVisible = isVisible,
            onClick = { playerConnection?.testCrossmix() },
        ),
    )
}
