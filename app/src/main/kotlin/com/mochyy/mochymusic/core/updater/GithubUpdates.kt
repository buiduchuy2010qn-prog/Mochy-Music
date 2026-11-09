package com.mochyy.mochymusic.core.updater

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mochyy.mochymusic.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

// automatic background checks reuse the last answer for 6 hours; tapping "Kiểm tra cập nhật" passes force = true
private const val CacheTtlMillis = 6L * 60L * 60L * 1000L

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

private fun diskCacheFile(): File? = runCatching {
    val dir = System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() } ?: return@runCatching null
    File(dir, "mochy_releases_cache_v2.json")
}.getOrNull()

private fun readDiskCache(): Pair<Long, List<ParsedRelease>>? = runCatching {
    val file = diskCacheFile() ?: return@runCatching null
    if (!file.exists()) return@runCatching null
    val root = JSONObject(file.readText())
    val savedAt = root.optLong("savedAt", 0L)
    if (savedAt <= 0L || System.currentTimeMillis() - savedAt >= CacheTtlMillis) return@runCatching null
    val arr = root.optJSONArray("items") ?: return@runCatching null
    val list = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val rel = GithubRelease(
                title = o.optString("title"),
                tag = o.optString("tag"),
                version = o.optString("version"),
                body = o.optString("body"),
                htmlUrl = o.optString("htmlUrl"),
                apkUrl = o.optString("apkUrl").takeIf { it.isNotBlank() },
                apkApiUrl = o.optString("apkApiUrl").takeIf { it.isNotBlank() },
                apkName = o.optString("apkName").takeIf { it.isNotBlank() },
                apkSizeBytes = o.optLong("apkSizeBytes", 0L),
                publishedAt = o.optString("publishedAt"),
            )
            add(ParsedRelease(parseFullVersion(rel.version), rel))
        }
    }
    if (list.isEmpty()) null else (savedAt to list)
}.getOrNull()

private fun writeDiskCache(savedAt: Long, list: List<ParsedRelease>) {
    runCatching {
        val file = diskCacheFile() ?: return
        val arr = JSONArray()
        for (item in list) {
            val r = item.release
            arr.put(
                JSONObject()
                    .put("title", r.title)
                    .put("tag", r.tag)
                    .put("version", r.version)
                    .put("body", r.body)
                    .put("htmlUrl", r.htmlUrl)
                    .put("apkUrl", r.apkUrl.orEmpty())
                    .put("apkApiUrl", r.apkApiUrl.orEmpty())
                    .put("apkName", r.apkName.orEmpty())
                    .put("apkSizeBytes", r.apkSizeBytes)
                    .put("publishedAt", r.publishedAt)
            )
        }
        val root = JSONObject().put("savedAt", savedAt).put("items", arr)
        file.writeText(root.toString())
    }
}

/** Every published release, asked for once and shared for [CacheTtlMillis] (including across cold starts) unless [force]d. */
private suspend fun getReleases(force: Boolean): Result<List<ParsedRelease>> {
    val deferred = synchronized(cacheLock) {
        if (!force) {
            val cached = cachedResult
            if (cached != null && System.currentTimeMillis() - cachedAt < CacheTtlMillis) {
                return cached
            }
            val disk = readDiskCache()
            if (disk != null) {
                val res = Result.success(disk.second)
                cachedAt = disk.first
                cachedResult = res
                return res
            }
        }
        inFlight ?: updateScope.async { fetchReleases() }.also { inFlight = it }
    }

    val result = deferred.await()
    synchronized(cacheLock) {
        if (inFlight === deferred) inFlight = null
        if (result.isSuccess) {
            val now = System.currentTimeMillis()
            cachedResult = result
            cachedAt = now
            writeDiskCache(now, result.getOrThrow())
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
    // api.github.com allows only 60 unauthenticated calls an hour per IP (shared by every device on
    // the same Wi-Fi). When it refuses, the public releases.atom feed on github.com answers instead.
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

private fun unescapeXmlAndHtml(input: String): String {
    // the atom feed escapes the release's html once more, so this runs twice
    var out = input
    repeat(2) {
        out = out
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
    return out
}

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

private fun httpGetText(url: String, accept: String? = null, timeoutMs: Int = 12_000): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        setRequestProperty("User-Agent", "mochy-android")
        if (accept != null) setRequestProperty("Accept", accept)
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) error("HTTP $code")
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

/**
 * The exact size of a release file, asked for with a one-byte ranged request on its download link
 * (github.com and its asset host, not the rate-limited API). 0 when it can't be told.
 */
private fun remoteFileSize(url: String): Long = runCatching {
    var current = url
    for (hop in 0 until 6) {
        val connection = (URL(current).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "mochy-android")
            setRequestProperty("Range", "bytes=0-0")
        }
        try {
            val code = connection.responseCode
            when {
                code in 300..399 -> {
                    val location = connection.getHeaderField("Location") ?: return@runCatching 0L
                    current = URL(URL(current), location).toString()
                }
                code == 206 -> {
                    // "bytes 0-0/34067828"
                    val total = connection.getHeaderField("Content-Range")
                        ?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    return@runCatching total ?: 0L
                }
                code in 200..299 -> return@runCatching connection.contentLengthLong.coerceAtLeast(0L)
                else -> return@runCatching 0L
            }
        } finally {
            connection.disconnect()
        }
    }
    0L
}.getOrDefault(0L)

/** The APK's file name in a release's expanded asset list on github.com, or null. */
private fun apkNameFromExpandedAssets(tag: String): String? = runCatching {
    val html = httpGetText(
        "https://github.com/$OtaGithubOwner/$OtaGithubRepo/releases/expanded_assets/$tag",
        timeoutMs = 8_000,
    )
    Regex("releases/download/[^/\"]+/([^\"<>\\s/]+?\\.apk)\"", RegexOption.IGNORE_CASE)
        .findAll(html)
        .map { it.groupValues[1] }
        .firstOrNull { !it.contains("debug", ignoreCase = true) }
}.getOrNull()

private class AtomEntry(
    val tag: String,
    val title: String,
    val body: String,
    val publishedAt: String,
    val apkName: String?,
    val version: FullVersion,
)

private fun fetchReleasesFromAtom(): Result<List<ParsedRelease>> = runCatching {
    val xml = httpGetText(ReleasesAtomUrl, accept = "application/atom+xml,application/xml,text/xml,*/*")
    val entryRegex = Regex("(?s)<entry>(.*?)</entry>")
    val tagRegex = Regex("releases/tag/([^\"&<>\\s]+)")
    val titleRegex = Regex("(?s)<title[^>]*>(.*?)</title>")
    val updatedRegex = Regex("(?s)<updated[^>]*>(.*?)</updated>")
    val contentRegex = Regex("(?s)<content[^>]*>(.*?)</content>")
    val apkNameRegex = Regex("(Mochy-Music-[^\"<>\\s*`]*?\\.apk)", RegexOption.IGNORE_CASE)

    // read every entry first, without touching the network again
    val entries = entryRegex.findAll(xml).mapNotNull { match ->
        val entry = match.groupValues[1]
        val tag = tagRegex.find(entry)?.groupValues?.get(1).orEmpty()
        if (tag.isBlank()) return@mapNotNull null
        val rawTitle = unescapeXmlAndHtml(titleRegex.find(entry)?.groupValues?.get(1).orEmpty()).ifBlank { tag }
        val rawContent = contentRegex.find(entry)?.groupValues?.get(1).orEmpty()
        // the apk is named in the notes the release workflow writes; read it before the notes are trimmed for android
        val fullBody = atomHtmlToMarkdown(rawContent)
        val apkName = apkNameRegex.find(fullBody)?.groupValues?.get(1)
            ?.takeIf { !it.contains("debug", ignoreCase = true) }
        val tagVer = parseFullVersion("$rawTitle $tag")
        val apkVer = apkName?.let(::parseFullVersion)
        val version = if (
            apkVer != null && apkVer.major > 0 &&
            (apkVer.major != tagVer.major || apkVer.minor != tagVer.minor || apkVer.patch != tagVer.patch)
        ) {
            apkVer
        } else {
            tagVer
        }
        AtomEntry(
            tag = tag,
            title = rawTitle.replace("(Android • Windows • Linux)", "(Android APK)"),
            body = cleanAndroidReleaseBody(fullBody),
            publishedAt = updatedRegex.find(entry)?.groupValues?.get(1).orEmpty().trim(),
            apkName = apkName,
            version = version,
        )
    }
        .filter { !(it.version.major == 0 && it.version.minor == 0 && it.version.patch == 0) }
        .filter { it.version.copy(buildNumber = 0) >= FirstReleaseWithThisId }
        .sortedByDescending { it.version }
        .toList()

    val seenSemvers = mutableSetOf<Triple<Int, Int, Int>>()
    buildList {
        entries.forEachIndexed { index, entry ->
            // only the newest release is worth extra requests: its apk name if the notes lack it,
            // and its exact size for the sheet and the download check. Older ones get theirs on download.
            val newest = index == 0
            val apkName = entry.apkName ?: if (newest) apkNameFromExpandedAssets(entry.tag) else null
            if (apkName.isNullOrBlank()) return@forEachIndexed

            // the apk inside may be older than the release (a PC-only release carries the last apk forward)
            val fileVer = parseFullVersion(apkName)
            val fullVer = if (
                fileVer.major > 0 &&
                (fileVer.major != entry.version.major || fileVer.minor != entry.version.minor || fileVer.patch != entry.version.patch)
            ) {
                fileVer
            } else {
                entry.version
            }
            val semverKey = Triple(fullVer.major, fullVer.minor, fullVer.patch)
            if (!seenSemvers.add(semverKey)) return@forEachIndexed

            val apkUrl = "https://github.com/$OtaGithubOwner/$OtaGithubRepo/releases/download/${entry.tag}/$apkName"
            val size = if (newest) remoteFileSize(apkUrl) else 0L
            val displayVersion = if (fullVer.buildNumber > 0) {
                "${fullVer.major}.${fullVer.minor}.${fullVer.patch} (${fullVer.buildNumber})"
            } else {
                "${fullVer.major}.${fullVer.minor}.${fullVer.patch}"
            }

            add(
                ParsedRelease(
                    version = fullVer,
                    release = GithubRelease(
                        title = entry.title,
                        tag = entry.tag,
                        version = displayVersion,
                        body = entry.body,
                        htmlUrl = "https://github.com/$OtaGithubOwner/$OtaGithubRepo/releases/tag/${entry.tag}",
                        apkUrl = apkUrl,
                        apkApiUrl = null,
                        apkName = apkName,
                        apkSizeBytes = size,
                        publishedAt = entry.publishedAt,
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
                if (release.optBoolean("prerelease", false)) continue

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
        // written here first and only moved to target once complete, so a cut-off download
        // never looks finished to isDownloaded
        val part = File(target.path + ".part")

        val candidates = buildList {
            add(primaryUrl to false)
            release.apkApiUrl?.takeIf { it.isNotBlank() }?.let { add(it to true) }
        }

        var lastError: Throwable? = null
        for ((candidateUrl, isApiEndpoint) in candidates) {
            try {
                if (part.exists()) part.delete()
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

                // the server's own length is exact; the release's size can be unknown (0) when it came from the atom feed
                val total = connection.contentLengthLong.takeIf { it > 0 }
                    ?: release.apkSizeBytes.takeIf { it > 0 }
                    ?: -1L

                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
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
                val got = part.length()
                if (got < 5_000_000L) {
                    part.delete()
                    error("Tệp tải về không hợp lệ ($got bytes)")
                }
                if (total > 0 && got != total) {
                    part.delete()
                    error("Tệp tải về bị thiếu ($got/$total bytes), hãy thử lại")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                onProgress(1f)
                return@runCatching target
            } catch (t: Throwable) {
                lastError = t
                if (part.exists()) part.delete()
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
    bytes <= 0 -> "không rõ dung lượng"
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000f)
    else -> String.format("%.0f KB", bytes / 1000f)
}
