package com.mochyy.mochymusic.cloud

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mochyy.mochymusic.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MochyCloudSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
    val username: String,
    val displayName: String,
)

/**
 * Kho bảo mật phiên đăng nhập và cấu hình Mochy Cloud.
 * - Cấu hình cloud được giải mã từ mảng byte XOR lúc build (`BuildConfig.MOCHY_CLOUD_*`), không lưu chuỗi gốc.
 * - Bản release xác minh chữ ký số APK khớp khóa phát hành Mochy trước khi giải mã.
 * - Phiên JWT được mã hóa bằng Android Keystore (`AES/GCM/NoPadding`) trong `noBackupFilesDir`.
 */
object MochyCloudVault {

    private const val KeystoreProvider = "AndroidKeyStore"
    private const val KeyAlias = "mochy_cloud_vault_v1"
    private const val VaultFileName = "mochy_cloud_vault.bin"
    private const val SyncStateFileName = "mochy_cloud_sync_state.json"
    private const val PendingHistoryFileName = "mochy_cloud_pending_history.json"
    private const val PendingPkceFileName = "mochy_cloud_pkce.json"
    private const val GoogleEmailFileName = "mochy_cloud_google_email.txt"
    private const val PendingPkceTtlMs = 15L * 60L * 1000L
    private const val AesGcmMode = "AES/GCM/NoPadding"
    private const val GcmTagBits = 128

    // SHA-256 chữ ký phát hành chính thức của Mochy Music APK
    private val ExpectedSignerDigest = byteArrayOf(
        0x63, 0x6c, 0xc8.toByte(), 0x30, 0xab.toByte(), 0xba.toByte(), 0x0b, 0x5f,
        0x2d, 0xdd.toByte(), 0xad.toByte(), 0x9d.toByte(), 0x5a, 0x90.toByte(), 0xe2.toByte(), 0x13,
        0x24, 0xa7.toByte(), 0xd4.toByte(), 0x8e.toByte(), 0x24, 0x6e, 0x8b.toByte(), 0xc0.toByte(),
        0x74, 0xac.toByte(), 0x72, 0x83.toByte(), 0xd4.toByte(), 0xdb.toByte(), 0x6b, 0xf8.toByte(),
    )

    @Volatile
    private var signatureVerifiedCache: Boolean? = null

    fun isConfigured(context: Context): Boolean {
        if (!verifyApkSignature(context)) return false
        return BuildConfig.MOCHY_CLOUD_M.isNotEmpty() &&
            BuildConfig.MOCHY_CLOUD_U.isNotEmpty() &&
            BuildConfig.MOCHY_CLOUD_K.isNotEmpty()
    }

    internal fun revealEndpoint(context: Context): Pair<String, String>? {
        if (!isConfigured(context)) return null
        val mask = BuildConfig.MOCHY_CLOUD_M
        val url = unmaskString(BuildConfig.MOCHY_CLOUD_U, mask).trim().trimEnd('/')
        val key = unmaskString(BuildConfig.MOCHY_CLOUD_K, mask).trim()
        if (!url.startsWith("https://") || key.isBlank()) return null
        return url to key
    }

    private fun unmaskString(encoded: IntArray, mask: IntArray): String {
        if (encoded.isEmpty() || mask.isEmpty()) return ""
        val out = ByteArray(encoded.size)
        for (i in encoded.indices) {
            val m = mask[i % mask.size] and 0xFF
            val salt = (i * 31 + 17) and 0xFF
            out[i] = ((encoded[i] xor m xor salt) and 0xFF).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    private fun verifyApkSignature(context: Context): Boolean {
        signatureVerifiedCache?.let { return it }
        if (BuildConfig.DEBUG) {
            signatureVerifiedCache = true
            return true
        }
        val ok = runCatching {
            val pm = context.packageManager
            val pkg = context.packageName
            val certBytesList: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo ?: return@runCatching false
                val signers = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                signers?.map { it.toByteArray() }.orEmpty()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray() }.orEmpty()
            }
            val sha = MessageDigest.getInstance("SHA-256")
            certBytesList.any { raw ->
                MessageDigest.isEqual(sha.digest(raw), ExpectedSignerDigest)
            }
        }.getOrDefault(false)
        signatureVerifiedCache = ok
        return ok
    }

    @Synchronized
    fun saveSession(context: Context, session: MochyCloudSession) {
        val json = JSONObject().apply {
            put("accessToken", session.accessToken)
            put("refreshToken", session.refreshToken)
            put("expiresAt", session.expiresAtEpochSec)
            put("userId", session.userId)
            put("username", session.username)
            put("displayName", session.displayName)
        }.toString()
        runCatching {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(AesGcmMode)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
            val payload = ByteArray(1 + iv.size + encrypted.size)
            payload[0] = iv.size.toByte()
            System.arraycopy(iv, 0, payload, 1, iv.size)
            System.arraycopy(encrypted, 0, payload, 1 + iv.size, encrypted.size)
            vaultFile(context).writeBytes(payload)
        }.onFailure {
            vaultFile(context).delete()
        }
    }

    @Synchronized
    fun loadSession(context: Context): MochyCloudSession? {
        val file = vaultFile(context)
        if (!file.exists()) return null
        return runCatching {
            val raw = file.readBytes()
            if (raw.size < 16) return null
            val ivLen = raw[0].toInt() and 0xFF
            if (ivLen !in 12..16 || raw.size <= 1 + ivLen) return null
            val iv = raw.copyOfRange(1, 1 + ivLen)
            val cipherBytes = raw.copyOfRange(1 + ivLen, raw.size)
            val key = getExistingKey() ?: return null
            val cipher = Cipher.getInstance(AesGcmMode)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GcmTagBits, iv))
            val plain = cipher.doFinal(cipherBytes)
            val json = JSONObject(String(plain, Charsets.UTF_8))
            MochyCloudSession(
                accessToken = json.optString("accessToken"),
                refreshToken = json.optString("refreshToken"),
                expiresAtEpochSec = json.optLong("expiresAt", 0L),
                userId = json.optString("userId"),
                username = json.optString("username"),
                displayName = json.optString("displayName"),
            ).takeIf { it.accessToken.isNotBlank() && it.userId.isNotBlank() }
        }.getOrElse {
            file.delete()
            null
        }
    }

    @Synchronized
    fun clearSession(context: Context) {
        vaultFile(context).delete()
        syncStateFile(context).delete()
        pendingHistoryFile(context).delete()
        pendingPkceFile(context).delete()
        googleEmailFile(context).delete()
    }

    @Synchronized
    fun savePendingPkceVerifier(context: Context, verifier: String) {
        val file = pendingPkceFile(context)
        val clean = verifier.trim()
        if (clean.isEmpty()) {
            file.delete()
            return
        }
        runCatching {
            val json = JSONObject().apply {
                put("verifier", clean)
                put("createdAtMs", System.currentTimeMillis())
            }
            file.writeText(json.toString(), Charsets.UTF_8)
        }.onFailure {
            file.delete()
        }
    }

    @Synchronized
    fun consumePendingPkceVerifier(context: Context): String? {
        val file = pendingPkceFile(context)
        if (!file.exists()) return null
        return try {
            runCatching {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val verifier = json.optString("verifier").trim()
                val createdAtMs = json.optLong("createdAtMs", 0L)
                val ageMs = System.currentTimeMillis() - createdAtMs
                if (verifier.isBlank() || createdAtMs <= 0L || ageMs > PendingPkceTtlMs) {
                    null
                } else {
                    verifier
                }
            }.getOrNull()
        } finally {
            file.delete()
        }
    }

    @Synchronized
    fun saveGoogleEmail(context: Context, email: String?) {
        val file = googleEmailFile(context)
        val clean = email?.trim()?.lowercase()?.takeIf {
            MochyGoogleOAuthHelper.isRealUserEmail(it)
        }
        if (clean == null) {
            file.delete()
            return
        }
        runCatching {
            file.writeText(clean, Charsets.UTF_8)
        }.onFailure {
            file.delete()
        }
    }

    @Synchronized
    fun loadGoogleEmail(context: Context): String? {
        val file = googleEmailFile(context)
        if (!file.exists()) return null
        return runCatching {
            file.readText(Charsets.UTF_8)
                .trim()
                .lowercase()
                .takeIf { MochyGoogleOAuthHelper.isRealUserEmail(it) }
        }.getOrNull()
    }

    @Synchronized
    fun loadSyncedFavoriteIds(context: Context, userId: String): Set<String>? =
        loadSyncedIdSet(context, userId, "favoriteIds")

    @Synchronized
    fun saveSyncedFavoriteIds(context: Context, userId: String, ids: Set<String>) {
        saveSyncedIdSet(context, userId, "favoriteIds", ids)
    }

    @Synchronized
    fun loadSyncedPlaylistIds(context: Context, userId: String): Set<String>? =
        loadSyncedIdSet(context, userId, "playlistIds")

    @Synchronized
    fun saveSyncedPlaylistIds(context: Context, userId: String, ids: Set<String>) {
        saveSyncedIdSet(context, userId, "playlistIds", ids)
    }

    @Synchronized
    fun loadSyncedPlaylistsDigest(context: Context, userId: String): String? =
        loadSyncedString(context, userId, "playlistsDigest")

    @Synchronized
    fun saveSyncedPlaylistsDigest(context: Context, userId: String, digest: String?) {
        saveSyncedString(context, userId, "playlistsDigest", digest)
    }

    @Synchronized
    fun loadSyncedAlbumIds(context: Context, userId: String): Set<String>? =
        loadSyncedIdSet(context, userId, "albumIds")

    @Synchronized
    fun saveSyncedAlbumIds(context: Context, userId: String, ids: Set<String>) {
        saveSyncedIdSet(context, userId, "albumIds", ids)
    }

    @Synchronized
    fun loadSyncedArtistIds(context: Context, userId: String): Set<String>? =
        loadSyncedIdSet(context, userId, "artistIds")

    @Synchronized
    fun saveSyncedArtistIds(context: Context, userId: String, ids: Set<String>) {
        saveSyncedIdSet(context, userId, "artistIds", ids)
    }

    @Synchronized
    fun loadSyncedEqDigest(context: Context, userId: String): String? =
        loadSyncedString(context, userId, "eqDigest")

    @Synchronized
    fun saveSyncedEqDigest(context: Context, userId: String, digest: String?) {
        saveSyncedString(context, userId, "eqDigest", digest)
    }

    private fun readSyncStateJson(context: Context, userId: String): JSONObject? {
        val file = syncStateFile(context)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            json.takeIf { it.optString("userId") == userId }
        }.getOrNull()
    }

    private fun writeSyncStateJson(context: Context, userId: String, mutate: (JSONObject) -> Unit) {
        runCatching {
            val file = syncStateFile(context)
            val json = readSyncStateJson(context, userId) ?: JSONObject().put("userId", userId)
            mutate(json)
            file.writeText(json.toString(), Charsets.UTF_8)
        }
    }

    private fun loadSyncedString(context: Context, userId: String, key: String): String? =
        readSyncStateJson(context, userId)
            ?.optString(key)
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun saveSyncedString(context: Context, userId: String, key: String, value: String?) {
        writeSyncStateJson(context, userId) { json ->
            if (value.isNullOrBlank()) {
                json.remove(key)
            } else {
                json.put(key, value)
            }
        }
    }

    private fun loadSyncedIdSet(context: Context, userId: String, key: String): Set<String>? {
        val json = readSyncStateJson(context, userId) ?: return null
        val arr = json.optJSONArray(key) ?: return null
        return buildSet(arr.length()) {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun saveSyncedIdSet(context: Context, userId: String, key: String, ids: Set<String>) {
        writeSyncStateJson(context, userId) { json ->
            json.put(key, JSONArray(ids.toList()))
        }
    }

    @Synchronized
    fun loadPendingHistory(context: Context, userId: String): List<CloudHistoryRecord> {
        val file = pendingHistoryFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optString("userId") != userId) return@runCatching emptyList()
            val arr = json.optJSONArray("items") ?: return@runCatching emptyList()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val songId = o.optString("songId")
                    if (songId.isBlank()) continue
                    add(
                        CloudHistoryRecord(
                            songId = songId,
                            title = o.optString("title"),
                            artists = o.optString("artists"),
                            albumName = o.optString("albumName").takeIf { it.isNotBlank() && it != "null" },
                            duration = o.optInt("duration", -1),
                            thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
                            playCount = o.optLong("playCount", 1L).coerceAtLeast(1L),
                            totalPlayMs = o.optLong("totalPlayMs", 0L).coerceAtLeast(0L),
                            lastPlayedAt = o.optString("lastPlayedAt"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun savePendingHistory(context: Context, userId: String, items: List<CloudHistoryRecord>) {
        val file = pendingHistoryFile(context)
        if (items.isEmpty()) {
            file.delete()
            return
        }
        runCatching {
            val arr = JSONArray()
            for (item in items) {
                arr.put(
                    JSONObject().apply {
                        put("songId", item.songId)
                        put("title", item.title)
                        put("artists", item.artists)
                        if (!item.albumName.isNullOrBlank()) put("albumName", item.albumName)
                        put("duration", item.duration)
                        if (!item.thumbnailUrl.isNullOrBlank()) put("thumbnailUrl", item.thumbnailUrl)
                        put("playCount", item.playCount)
                        put("totalPlayMs", item.totalPlayMs)
                        put("lastPlayedAt", item.lastPlayedAt)
                    },
                )
            }
            val root = JSONObject().apply {
                put("userId", userId)
                put("items", arr)
            }
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun vaultFile(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, VaultFileName)

    private fun syncStateFile(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, SyncStateFileName)

    private fun pendingHistoryFile(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, PendingHistoryFileName)

    private fun pendingPkceFile(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, PendingPkceFileName)

    private fun googleEmailFile(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, GoogleEmailFileName)

    private fun getExistingKey(): SecretKey? {
        val ks = KeyStore.getInstance(KeystoreProvider).apply { load(null) }
        return (ks.getEntry(KeyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreProvider)
        val spec = KeyGenParameterSpec.Builder(
            KeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
