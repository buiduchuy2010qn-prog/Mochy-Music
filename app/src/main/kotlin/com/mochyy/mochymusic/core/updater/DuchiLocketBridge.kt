// DuchiLocketBridge.kt

package com.mochyy.mochymusic.core.updater

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.playback.PlayerConnection
import com.mochyy.mochymusic.playback.queues.ListQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

const val DuchiLocketPackage = "com.huylocket.app"
const val DuchiLocketManifestUrl =
    "https://github.com/buiduchuy2010qn-prog/buiduchuy2010qn-prog/releases/download/locket-ota-latest/ota-manifest.json"
const val DuchiLocketRepoUrl = "https://github.com/buiduchuy2010qn-prog/duchi-locketgold"

private const val ManifestCacheTtlMs = 10L * 60L * 1000L
private const val ConnectTimeoutMs = 12_000
private const val ReadTimeoutMs = 30_000
private const val MaxRedirects = 6
private const val ApkCacheFileName = "duchi_locket_update.apk"
private const val LocketApkMaxAgeMs = 24L * 60L * 60L * 1000L
private const val LocketTempApkMaxAgeMs = 60L * 60L * 1000L

data class DuchiLocketManifest(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val signerSha256: String,
    val publishedAt: String,
    val notes: String,
)

data class InstalledLocketInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
)

sealed interface DuchiLocketOtaState {
    data object Checking : DuchiLocketOtaState

    data class Ready(
        val installed: InstalledLocketInfo?,
        val manifest: DuchiLocketManifest,
        val updateAvailable: Boolean,
    ) : DuchiLocketOtaState

    data class Offline(
        val installed: InstalledLocketInfo?,
        val errorMessage: String,
    ) : DuchiLocketOtaState
}

@Volatile
private var cachedManifest: DuchiLocketManifest? = null

@Volatile
private var cachedManifestAtMs: Long = 0L

fun getInstalledLocketInfo(context: Context): InstalledLocketInfo? = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(DuchiLocketPackage, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(DuchiLocketPackage, 0)
    }
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    InstalledLocketInfo(
        packageName = info.packageName,
        versionName = info.versionName.orEmpty().ifBlank { "1.0.0" },
        versionCode = code,
    )
}.getOrNull()

fun loadLocketAppIcon(context: Context): ImageBitmap? = runCatching {
    context.packageManager.getApplicationIcon(DuchiLocketPackage).toBitmap().asImageBitmap()
}.getOrNull()

fun openDuchiLocketApp(context: Context): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(DuchiLocketPackage) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

/**
 * Copies song link/metadata to clipboard, ensures Mochy actively plays this song so
 * Duchi Locket's LocketMediaSessionPlugin picks it up at #1, and switches to Duchi Locket.
 */
fun shareSongToDuchiLocket(
    context: Context,
    song: MediaMetadata,
    playerConnection: PlayerConnection?,
): Boolean {
    val artists = song.artists.joinToString(", ") { it.name }.ifBlank { "Mochy Music" }
    val songLink = if (
        song.localUri != null ||
        song.id.startsWith("LOCAL_") ||
        song.id.startsWith("LA")
    ) {
        null
    } else {
        "https://music.youtube.com/watch?v=${song.id}"
    }
    val clipText = if (songLink != null) "${song.title} — $artists\n$songLink" else "${song.title} — $artists"
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Mochy Music · Duchi Locket", clipText))
    }

    if (playerConnection != null) {
        val activatePlayback = Runnable {
            runCatching {
                if (playerConnection.mediaMetadata.value?.id == song.id) {
                    playerConnection.player.playWhenReady = true
                    if (!playerConnection.isPlaying.value) {
                        playerConnection.player.play()
                    }
                } else {
                    playerConnection.playQueue(
                        ListQueue(
                            title = song.title,
                            items = listOf(song.toMediaItem()),
                        )
                    )
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            activatePlayback.run()
        } else {
            Handler(Looper.getMainLooper()).post(activatePlayback)
        }
    }

    val opened = openDuchiLocketApp(context)
    val toastMsg = if (opened) {
        "Đang phát trên Mochy & đã chép bài — chọn khung Nhạc trong Duchi Locket!"
    } else {
        "Chưa cài Duchi Locket — vào Cài đặt › Cập nhật Mochy để cài đặt."
    }
    Toast.makeText(context, toastMsg, Toast.LENGTH_LONG).show()
    return opened
}

suspend fun checkDuchiLocketOta(
    context: Context,
    force: Boolean = false,
): DuchiLocketOtaState = withContext(Dispatchers.IO) {
    val installed = getInstalledLocketInfo(context)
    val now = System.currentTimeMillis()
    val cached = if (!force && now - cachedManifestAtMs < ManifestCacheTtlMs) cachedManifest else null
    val manifest = cached ?: fetchDuchiLocketManifest().getOrElse { err ->
        cleanupOldLocketApk(context, installed, null)
        return@withContext DuchiLocketOtaState.Offline(
            installed = installed,
            errorMessage = err.message ?: "Không kiểm tra được kênh OTA Duchi Locket.",
        )
    }

    cachedManifest = manifest
    cachedManifestAtMs = System.currentTimeMillis()
    cleanupOldLocketApk(context, installed, manifest)

    val updateAvailable = installed != null && isLocketUpdateAvailable(installed, manifest)
    DuchiLocketOtaState.Ready(
        installed = installed,
        manifest = manifest,
        updateAvailable = updateAvailable,
    )
}

/**
 * Deletes cached `duchi_locket_update.apk` when Duchi Locket is already at or above the manifest
 * versionCode or when the cached APK is older than 24 hours.
 */
fun cleanupOldLocketApk(
    context: Context,
    installed: InstalledLocketInfo? = null,
    manifest: DuchiLocketManifest? = null,
) {
    runCatching {
        val now = System.currentTimeMillis()
        val tempFile = File(context.cacheDir, "$ApkCacheFileName.tmp")
        if (tempFile.exists()) {
            val modified = tempFile.lastModified()
            if (modified <= 0L || now - modified >= LocketTempApkMaxAgeMs) {
                tempFile.delete()
            }
        }
        val apkFile = File(context.cacheDir, ApkCacheFileName)
        if (!apkFile.exists()) return
        val currentInstalled = installed ?: getInstalledLocketInfo(context)
        val currentManifest = manifest ?: cachedManifest
        val alreadyInstalled = currentInstalled != null && currentManifest != null && (
            (currentManifest.versionCode > 0L && currentInstalled.versionCode >= currentManifest.versionCode) ||
                !isLocketUpdateAvailable(currentInstalled, currentManifest)
            )
        val modified = apkFile.lastModified()
        val expired = modified <= 0L || now - modified >= LocketApkMaxAgeMs
        if (alreadyInstalled || expired) {
            apkFile.delete()
        }
    }
}

suspend fun downloadDuchiLocketApk(
    context: Context,
    manifest: DuchiLocketManifest,
    onProgress: (Float) -> Unit,
): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
        if (!isAllowedGithubHttpsUrl(manifest.downloadUrl)) {
            error("Liên kết tải APK Duchi Locket không hợp lệ.")
        }
        val expectedSha = manifest.sha256.trim().lowercase()
        if (expectedSha.length != 64 || expectedSha.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            error("Manifest thiếu chữ ký SHA-256 hợp lệ.")
        }

        val targetFile = File(context.cacheDir, ApkCacheFileName)
        val tempFile = File(context.cacheDir, "$ApkCacheFileName.tmp")
        tempFile.delete()

        val conn = openHttpsGithubConnection(
            rawUrl = manifest.downloadUrl,
            accept = "application/vnd.android.package-archive, application/octet-stream",
        )
        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                error("Máy chủ trả mã HTTP $status.")
            }
            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: manifest.sizeBytes.coerceAtLeast(1L)
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            val buffer = ByteArray(16 * 1024)

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        onProgress((downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f))
                    }
                    output.flush()
                }
            }

            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                tempFile.delete()
                error("Kiểm tra toàn vẹn SHA-256 APK thất bại.")
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            onProgress(1f)
            targetFile
        } finally {
            conn.disconnect()
        }
    }
}

private fun isLocketUpdateAvailable(
    installed: InstalledLocketInfo,
    manifest: DuchiLocketManifest,
): Boolean {
    if (manifest.versionCode > 0L && installed.versionCode > 0L) {
        if (manifest.versionCode != installed.versionCode) {
            return manifest.versionCode > installed.versionCode
        }
    }
    val remoteSemver = parseFullVersion(manifest.versionName).copy(buildNumber = 0)
    val localSemver = parseFullVersion(installed.versionName).copy(buildNumber = 0)
    return remoteSemver > localSemver
}

private fun fetchDuchiLocketManifest(): Result<DuchiLocketManifest> = runCatching {
    val conn = openHttpsGithubConnection(
        rawUrl = DuchiLocketManifestUrl,
        accept = "application/json",
    )
    try {
        val status = conn.responseCode
        if (status !in 200..299) {
            error("Không tải được manifest OTA (HTTP $status).")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val pkg = json.optString("packageName").trim()
        if (pkg != DuchiLocketPackage) {
            error("Package trong manifest không khớp Duchi Locket.")
        }
        val downloadUrl = json.optString("downloadUrl").trim()
        if (!isAllowedGithubHttpsUrl(downloadUrl)) {
            error("URL tải Duchi Locket không thuộc GitHub HTTPS.")
        }
        DuchiLocketManifest(
            packageName = pkg,
            versionName = json.optString("versionName").trim().ifBlank { "0.0.0" },
            versionCode = json.optLong("versionCode", 0L),
            fileName = json.optString("fileName").trim().ifBlank { "Locket-update.apk" },
            downloadUrl = downloadUrl,
            sizeBytes = json.optLong("sizeBytes", 0L),
            sha256 = json.optString("sha256").trim(),
            signerSha256 = json.optString("signerSha256").trim(),
            publishedAt = json.optString("publishedAt").trim(),
            notes = json.optString("notes").trim(),
        )
    } finally {
        conn.disconnect()
    }
}

private fun openHttpsGithubConnection(rawUrl: String, accept: String): HttpURLConnection {
    var currentUrl = rawUrl
    repeat(MaxRedirects) {
        if (!isAllowedGithubHttpsUrl(currentUrl)) {
            error("Chặn chuyển hướng không an toàn ngoài GitHub HTTPS.")
        }
        val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = ConnectTimeoutMs
            readTimeout = ReadTimeoutMs
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "MochyMusic-Android")
        }
        val code = conn.responseCode
        if (code in listOf(
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308,
            )
        ) {
            val location = conn.getHeaderField("Location").orEmpty().trim()
            conn.disconnect()
            if (location.isBlank()) error("Chuyển hướng trống từ máy chủ GitHub.")
            currentUrl = URI(currentUrl).resolve(location).toString()
        } else {
            return conn
        }
    }
    error("Quá nhiều lần chuyển hướng khi tải Duchi Locket.")
}

private fun isAllowedGithubHttpsUrl(rawUrl: String): Boolean {
    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return host == "github.com" ||
        host.endsWith(".github.com") ||
        host == "githubusercontent.com" ||
        host.endsWith(".githubusercontent.com")
}
