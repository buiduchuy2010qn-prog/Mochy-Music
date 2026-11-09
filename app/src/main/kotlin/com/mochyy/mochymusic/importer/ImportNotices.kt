// ImportNotices.kt

package com.mochyy.mochymusic.importer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mochyy.mochymusic.R

/** how a finished import or re-sync tells the user, wherever they are in the app by then */
internal object ImportNotices {

    fun importFinished(context: Context, matched: Int, total: Int) {
        val channelId = "music_import"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channelId, "Nhập nhạc", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.mochy_notification)
            .setContentTitle("Đã nhập nhạc xong")
            .setContentText("Đã khớp $matched / $total bài hát")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching { notificationManager.notify(4242, notification) }
    }

    /** what a finished re-sync changed, said the way a person would */
    fun resyncFinished(context: Context, done: ImportProgress) {
        val resync = done.resync ?: return
        toast(context, if (resync.playlists == 1) oneResynced(done, resync) else manyResynced(done, resync))
    }

    private fun oneResynced(done: ImportProgress, resync: ResyncSummary): String {
        if (resync.failed.isNotEmpty()) return "Không thể đồng bộ lại \"${resync.playlistName}\". ${done.error.orEmpty()}".trim()
        val missing = done.unmatched.size
        val result = when {
            resync.added > 0 && missing > 0 ->
                "Đã thêm ${newSongs(resync.added)} vào \"${resync.playlistName}\". Còn $missing bài không tìm thấy trên YouTube Music"
            resync.added > 0 -> "Đã thêm ${newSongs(resync.added)} vào \"${resync.playlistName}\""
            missing > 0 -> "Không tìm thấy ${newSongs(missing)} trên YouTube Music"
            else -> "\"${resync.playlistName}\" đã được cập nhật mới nhất"
        }
        return listOfNotNull(result, resync.note).joinToString(". ")
    }

    private fun manyResynced(done: ImportProgress, resync: ResyncSummary): String {
        val read = resync.playlists - resync.failed.size
        val result = when {
            resync.added > 0 -> "Đã thêm ${newSongs(resync.added)} vào ${playlists(resync.playlistsChanged)}"
            done.unmatched.isNotEmpty() -> "Không tìm thấy ${newSongs(done.unmatched.size)} trên YouTube Music"
            read > 0 -> if (read == 1) "Danh sách phát đã nhập của bạn đã ở bản mới nhất" else "Cả $read danh sách phát đã nhập đều đã ở bản mới nhất"
            else -> null
        }
        val failed = resync.failed.takeIf { it.isNotEmpty() }?.let { "Không thể đồng bộ lại ${it.joinToString(", ")}" }
        return listOfNotNull(result, failed).joinToString(". ")
    }

    /** shown over whatever the user is on now: re-sync carries on after its menu has closed */
    fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    private fun newSongs(count: Int) = "$count bài hát mới"

    private fun playlists(count: Int) = "$count danh sách phát"
}
