package com.mochyy.mochymusic.core.updater

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mochyy.mochymusic.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val GithubOwner = "buiduchuy2010qn-prog"
const val GithubRepo = "Mochy-Music"

const val GithubProfileUrl = "https://github.com/$GithubOwner"
const val GithubRepoUrl = "https://github.com/$GithubOwner/$GithubRepo"
const val InstagramUrl = "https://www.instagram.com/furfurrfoxx/"
// Threads names follow the Instagram one
const val ThreadsUrl = "https://www.threads.net/@furfurrfoxx"
// dev spaces hides the discord row while this is blank
const val DiscordUrl = ""

const val OtaGithubOwner = "buiduchuy2010qn-prog"
const val OtaGithubRepo = "Mochy-Music"

private const val ReleasesApi =
    "https://api.github.com/repos/$OtaGithubOwner/$OtaGithubRepo/releases?per_page=20"
private const val ReleasesAtomUrl =
    "https://github.com/$OtaGithubOwner/$OtaGithubRepo/releases.atom"

data class GithubRelease(
    val title: String,
    val tag: String,
    val version: String,
    val body: String,
    val htmlUrl: String,
    val apkUrl: String?,
    val apkApiUrl: String? = null,
    val apkName: String?,
    val apkSizeBytes: Long,
    val publishedAt: String,
)

private fun cleanAndroidReleaseBody(raw: String): String {
    if (raw.isBlank()) return raw
    return raw.lines()
        .filterNot { line ->
            val l = line.trim()
            l.contains("Windows", ignoreCase = true) && l.contains(".exe", ignoreCase = true) ||
                l.contains("Linux", ignoreCase = true) && (l.contains(".AppImage", ignoreCase = true) || l.contains(".deb", ignoreCase = true)) ||
                l.contains("Ubuntu", ignoreCase = true) && l.contains(".deb", ignoreCase = true)
        }
        .map { line ->
            line.replace(Regex("""^\s*5\.\s+"""), "• ")
                .replace("(Android • Windows • Linux)", "(Android APK)")
                .replace("trên cả **Android, Windows và Linux**", "trực tiếp trên **Android (OTA)**")
                .replace("trên cả Android, Windows và Linux", "trực tiếp trên Android (OTA)")
        }
        .joinToString("\n")
}

sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: GithubRelease) : UpdateState
    data class Failed(val message: String) : UpdateState
}

data class FullVersion(
    val major: Int = 0,
    val minor: Int = 0,
    val patch: Int = 0,
    val buildNumber: Int = 0
) : Comparable<FullVersion> {
    override fun compareTo(other: FullVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        return buildNumber.compareTo(other.buildNumber)
    }
}

fun parseFullVersion(text: String): FullVersion {
    val semverRegex = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")
    val buildNumRegex = Regex("""(?:build#|\(#|#|\()(\d+)\)?""", RegexOption.IGNORE_CASE)

    val semverMatch = semverRegex.find(text)
    val major = semverMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val minor = semverMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
    val patch = semverMatch?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0

    val buildMatch = buildNumRegex.find(text)
    val buildNumber = buildMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

    return FullVersion(major, minor, patch, buildNumber)
}

private const val CacheTtlMillis = 15L * 60L * 1000L

// older releases in this repo are the app under its old id, so "downgrade" to one would add a second app instead
private val FirstReleaseWithThisId = FullVersion(major = 8, minor = 2, patch = 3)

/** A release with the version it was parsed to, so it can be compared without parsing again. */
private class ParsedRelease(val version: FullVersion, val release: GithubRelease)

private val cacheLock = Any()
private var cachedAt = 0L
private var cachedResult: Result<List<ParsedRelease>>? = null
private var inFlight: kotlinx.coroutines.Deferred<Result<List<ParsedRelease>>>? = null

private val updateScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
)

/** Every published release, asked for once and shared for [CacheTtlMillis] unless [force]d. */
private suspend fun getReleases(force: Boolean): Result<List<ParsedRelease>> {
    val deferred = synchronized(cacheLock) {
        if (!force) {
            val cached = cachedResult
            if (cached != null && System.currentTimeMillis() - cachedAt < CacheTtlMillis) {
                return cached
            }
        }
        inFlight ?: updateScope.async { fetchReleases() }.also { inFlight = it }
    }

    val result = deferred.await()
    synchronized(cacheLock) {
        if (inFlight === deferred) inFlight = null
        if (result.isSuccess) {
            cachedResult = result
            cachedAt = System.currentTimeMillis()
        }
    }
    return result
}

/** The newest release, whatever is installed. */
suspend fun getLatestRelease(force: Boolean = false): Result<GithubRelease?> =
    getReleases(force).map { list -> list.maxByOrNull { it.version }?.release }

/**
 * The newest release from before the installed version (by its x.y.z, so an older build of the
 * same version doesn't count). What "Downgrade to" offers; null when there is none.
 */
suspend fun getPreviousRelease(force: Boolean = false): Result<GithubRelease?> {
    val installed = parseFullVersion(BuildConfig.VERSION_NAME).copy(buildNumber = 0)
    return getReleases(force).map { list ->
        list.filter { it.version.copy(buildNumber = 0) < installed }.maxByOrNull { it.version }?.release
    }
}

private suspend fun fetchReleases(): Result<List<ParsedRelease>> = withContext(Dispatchers.IO) {
    val apiResult = fetchReleasesFromApi()
    if (apiResult.isSuccess && apiResult.getOrNull().orEmpty().isNotEmpty()) {
        return@withContext apiResult
    }
    val atomResult = fetchReleasesFromAtom()
    if (atomResult.isSuccess && atomResult.getOrNull().orEmpty().isNotEmpty()) {
        return@withContext atomResult
    }
    apiResult
}

private fun unescapeXmlAndHtml(input: String): String =
    input
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

private fun atomHtmlToMarkdown(rawContent: String): String {
    val html = unescapeXmlAndHtml(rawContent)
    return html
        .replace(Regex("(?s)<h3[^>]*>(.*?)</h3>"), "### $1")
        .replace(Regex("(?s)<h4[^>]*>(.*?)</h4>"), "#### $1")
        .replace(Regex("(?s)<strong[^>]*>(.*?)</strong>"), "**$1**")
        .replace(Regex("(?s)<b[^>]*>(.*?)</b>"), "**$1**")
        .replace(Regex("(?s)<code[^>]*>(.*?)</code>"), "`$1`")
        .replace(Regex("(?s)<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>"), "[$2]($1)")
        .replace(Regex("(?s)<li[^>]*>(.*?)</li>"), "- $1")
        .replace(Regex("<hr\\s*/?>"), "---")
        .replace(Regex("</?(ul|ol|p|div|span|br)[^>]*>"), "")
        .replace(Regex("<[^>]+>"), "")
        .trim()
}

private fun fetchReleasesFromAtom(): Result<List<ParsedRelease>> = runCatching {
    val connection = (URL(ReleasesAtomUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 12_000
        setRequestProperty("Accept", "application/atom+xml,application/xml,text/xml,*/*")
        setRequestProperty("User-Agent", "mochy-android")
    }
    val xml = connection.inputStream.bufferedReader().use { it.readText() }
    val entryRegex = Regex("(?s)<entry>(.*?)</entry>")
    val tagRegex = Regex("releases/tag/([^\"&<>\\s]+)")
    val titleRegex = Regex("(?s)<title[^>]*>(.*?)</title>")
    val updatedRegex = Regex("(?s)<updated[^>]*>(.*?)</updated>")
    val contentRegex = Regex("(?s)<content[^>]*>(.*?)</content>")
    val apkNameRegex = Regex("(Mochy-Music-[^\"<>\\s*`]*?\\.apk)", RegexOption.IGNORE_CASE)
    val seenSemvers = mutableSetOf<Triple<Int, Int, Int>>()

    buildList {
        for (match in entryRegex.findAll(xml)) {
            val entry = match.groupValues[1]
            val tag = tagRegex.find(entry)?.groupValues?.get(1).orEmpty()
            if (tag.isBlank()) continue
            val rawTitle = unescapeXmlAndHtml(titleRegex.find(entry)?.groupValues?.get(1).orEmpty()).ifBlank { tag }
            val title = rawTitle.replace("(Android • Windows • Linux)", "(Android APK)")
            val publishedAt = updatedRegex.find(entry)?.groupValues?.get(1).orEmpty().trim()
            val rawContent = contentRegex.find(entry)?.groupValues?.get(1).orEmpty()
            val markdownBody = cleanAndroidReleaseBody(atomHtmlToMarkdown(rawContent))

            var apkName = apkNameRegex.find(markdownBody)?.groupValues?.get(1)
                ?: apkNameRegex.find(rawContent)?.groupValues?.get(1)

            if (apkName.isNullOrBlank()) {
                // Fallback to expanded_assets fragment on github.com (also not rate-limited by api.github.com)
                val assetsUrl = "https://github.com/$OtaGithubOwner/$OtaGithubRepo/releases/expanded_assets/$tag"
                val assetsHtml = runCatching {
                    val c = (URL(assetsUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000
                        readTimeout = 8_000
                        setRequestProperty("User-Agent", "mochy-android")
                    }
                    c.inputStream.bufferedReader().use { it.readText() }
                }.getOrDefault("")
                apkName = Regex("releases/download/[^/]+/([^\"<>\\s]+?\\.apk)", RegexOption.IGNORE_CASE)
                    .find(assetsHtml)?.groupValues?.get(1)
            }

            if (apkName.isNullOrBlank() || apkName.contains("debug", ignoreCase = true)) continue
            val apkUrl = "https://github.com/$OtaGithubOwner/$OtaGithubRepo/releases/download/$tag/$apkName"

            val tagVer = parseFullVersion("$rawTitle $tag")
            val apkVer = parseFullVersion(apkName)
            val fullVer = if (
                apkVer.major > 0 &&
                (apkVer.major != tagVer.major || apkVer.minor != tagVer.minor || apkVer.patch != tagVer.patch)
            ) {
                apkVer
            } else {
                tagVer
            }
            if (fullVer.major == 0 && fullVer.minor == 0 && fullVer.patch == 0) continue
            if (fullVer.copy(buildNumber = 0) < FirstReleaseWithThisId) continue

            val semverKey = Triple(fullVer.major, fullVer.minor, fullVer.patch)
            if (!seenSemvers.add(semverKey)) continue

            val displayVersion = if (fullVer.buildNumber > 0) {
                "${fullVer.major}.${fullVer.minor}.${fullVer.patch} (${fullVer.buildNumber})"
            } else {
                "${fullVer.major}.${fullVer.minor}.${fullVer.patch}"
            }

            add(
                ParsedRelease(
                    version = fullVer,
                    release = GithubRelease(
                        title = title,
                        tag = tag,
                        version = displayVersion,
                        body = markdownBody,
                        htmlUrl = "https://github.com/$OtaGithubOwner/$OtaGithubRepo/releases/tag/$tag",
                        apkUrl = apkUrl,
                        apkApiUrl = null,
                        apkName = apkName,
                        apkSizeBytes = 0L,
                        publishedAt = publishedAt,
                    ),
                ),
            )
        }
    }
}

private suspend fun fetchReleasesFromApi(): Result<List<ParsedRelease>> = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL(ReleasesApi).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "mochy-android")
        }
        val json = connection.inputStream.bufferedReader().use { it.readText() }
        val releases = JSONArray(json)
        val appArch = runCatching { BuildConfig.ARCHITECTURE }.getOrDefault("universal")
        val seenSemvers = mutableSetOf<Triple<Int, Int, Int>>()

        buildList {
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue

                val tag = release.optString("tag_name").orEmpty()
                val rawTitle = release.optString("name").orEmpty().ifBlank { tag }
                val title = rawTitle.replace("(Android • Windows • Linux)", "(Android APK)")
                val tagVer = parseFullVersion("$rawTitle $tag")

                if (tagVer.major == 0 && tagVer.minor == 0 && tagVer.patch == 0) continue

                val assets = release.optJSONArray("assets")
                var selectedUrl: String? = null
                var selectedApiUrl: String? = null
                var selectedName: String? = null
                var selectedSize = 0L

                var universalUrl: String? = null
                var universalApiUrl: String? = null
                var universalName: String? = null
                var universalSize = 0L

                var fallbackUrl: String? = null
                var fallbackApiUrl: String? = null
                var fallbackName: String? = null
                var fallbackSize = 0L

                if (assets != null) {
                    for (a in 0 until assets.length()) {
                        val asset = assets.getJSONObject(a)
                        val name = asset.optString("name").orEmpty()
                        if (!name.endsWith(".apk", ignoreCase = true) || name.contains("debug", ignoreCase = true)) continue
                        val url = asset.optString("browser_download_url").orEmpty()
                        val apiUrl = asset.optString("url").orEmpty().takeIf { it.isNotBlank() }
                        val size = asset.optLong("size", 0L)

                        if (appArch.isNotBlank() && appArch != "universal" && name.contains(appArch, ignoreCase = true)) {
                            selectedUrl = url
                            selectedApiUrl = apiUrl
                            selectedName = name
                            selectedSize = size
                        } else if (name.contains("universal", ignoreCase = true)) {
                            universalUrl = url
                            universalApiUrl = apiUrl
                            universalName = name
                            universalSize = size
                        } else if (fallbackUrl == null) {
                            fallbackUrl = url
                            fallbackApiUrl = apiUrl
                            fallbackName = name
                            fallbackSize = size
                        }
                    }
                }

                val apkUrl = selectedUrl ?: universalUrl ?: fallbackUrl
                val apkApiUrl = when {
                    selectedUrl != null -> selectedApiUrl
                    universalUrl != null -> universalApiUrl
                    else -> fallbackApiUrl
                }
                val apkName = selectedName ?: universalName ?: fallbackName
                val apkSize = when {
                    selectedUrl != null -> selectedSize
                    universalUrl != null -> universalSize
                    else -> fallbackSize
                }

                // Skip PC-only releases that do not contain an Android APK asset
                if (apkUrl == null || apkName == null) continue

                // If the APK filename encodes its own version (e.g. Mochy-Music-v9.4.0-vi-arm64.apk),
                // prefer the APK's actual version over a PC-only release tag.
                val apkVer = parseFullVersion(apkName)
                val fullVer = if (
                    apkVer.major > 0 &&
                    (apkVer.major != tagVer.major || apkVer.minor != tagVer.minor || apkVer.patch != tagVer.patch)
                ) {
                    apkVer
                } else {
                    tagVer
                }

                if (fullVer.copy(buildNumber = 0) < FirstReleaseWithThisId) continue

                val semverKey = Triple(fullVer.major, fullVer.minor, fullVer.patch)
                if (!seenSemvers.add(semverKey)) continue

                val displayVersion = if (fullVer.buildNumber > 0) {
                    "${fullVer.major}.${fullVer.minor}.${fullVer.patch} (${fullVer.buildNumber})"
                } else {
                    "${fullVer.major}.${fullVer.minor}.${fullVer.patch}"
                }

                add(
                    ParsedRelease(
                        version = fullVer,
                        release = GithubRelease(
                            title = title,
                            tag = tag,
                            version = displayVersion,
                            body = cleanAndroidReleaseBody(release.optString("body").orEmpty()),
                            htmlUrl = release.optString("html_url").orEmpty().ifBlank { "$GithubRepoUrl/releases" },
                            apkUrl = apkUrl,
                            apkApiUrl = apkApiUrl,
                            apkName = apkName,
                            apkSizeBytes = apkSize,
                            publishedAt = release.optString("published_at").orEmpty(),
                        ),
                    ),
                )
            }
        }
    }
}

fun isNewerThanInstalled(candidateVersionText: String): Boolean {
    val candidate = parseFullVersion(candidateVersionText).copy(buildNumber = 0)
    val installed = parseFullVersion(BuildConfig.VERSION_NAME).copy(buildNumber = 0)
    return candidate > installed
}

suspend fun downloadApk(
    context: Context,
    release: GithubRelease,
    onProgress: (Float) -> Unit,
): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
        val primaryUrl = release.apkUrl ?: error("Bản phát hành này chưa có tệp APK")
        val target = apkFileFor(context, release)

        if (isDownloaded(context, release)) {
            onProgress(1f)
            return@runCatching target
        }
        if (target.exists()) target.delete()

        val candidates = buildList {
            add(primaryUrl to false)
            release.apkApiUrl?.takeIf { it.isNotBlank() }?.let { add(it to true) }
        }

        var lastError: Throwable? = null
        for ((candidateUrl, isApiEndpoint) in candidates) {
            try {
                if (target.exists()) target.delete()
                var currentUrl = candidateUrl
                var redirects = 0
                var connection: HttpURLConnection
                while (true) {
                    connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 45_000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "mochy-android")
                        if (isApiEndpoint && redirects == 0) {
                            setRequestProperty("Accept", "application/octet-stream")
                        }
                    }
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (location.isNullOrBlank() || ++redirects > 6) {
                            error("Quá nhiều lần chuyển hướng khi tải tệp APK (HTTP $code)")
                        }
                        currentUrl = URL(URL(currentUrl), location).toString()
                        continue
                    }
                    if (code !in 200..299) {
                        connection.disconnect()
                        error("Máy chủ trả về mã lỗi HTTP $code")
                    }
                    break
                }

                val total = release.apkSizeBytes.takeIf { it > 0 }
                    ?: connection.contentLengthLong.takeIf { it > 0 }
                    ?: -1L

                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (target.length() < 5_000_000L) {
                    val badLen = target.length()
                    target.delete()
                    error("Tệp tải về không hợp lệ ($badLen bytes)")
                }
                onProgress(1f)
                return@runCatching target
            } catch (t: Throwable) {
                lastError = t
                if (target.exists()) target.delete()
            }
        }
        throw (lastError ?: IllegalStateException("Không thể tải tệp APK"))
    }
}

fun apkFileFor(context: Context, release: GithubRelease): File =
    File(context.cacheDir, release.apkName ?: "mochy-update.apk")

fun isDownloaded(context: Context, release: GithubRelease): Boolean {
    val f = apkFileFor(context, release)
    if (!f.exists() || f.length() < 5_000_000L) return false
    return release.apkSizeBytes <= 0L || f.length() == release.apkSizeBytes
}

fun canInstallPackages(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

fun requestInstallPermission(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + context.packageName),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

fun installApk(context: Context, apk: File): Boolean {
    if (!canInstallPackages(context)) {
        requestInstallPermission(context)
        return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { context.startActivity(intent); true }.getOrDefault(false)
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "~34,1 MB"
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000f)
    else -> String.format("%.0f KB", bytes / 1000f)
}
