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
    // marked as a prerelease on github, which is what makes it a nightly
    val isNightly: Boolean = false,
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

// a release is x.y.z, a nightly adds a fourth number counting up from the release it was built on (8.3.2.1, 8.3.2.2, then 8.3.3)
data class FullVersion(
    val major: Int = 0,
    val minor: Int = 0,
    val patch: Int = 0,
    val buildNumber: Int = 0,
    val nightly: Int = 0,
) : Comparable<FullVersion> {
    override fun compareTo(other: FullVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (nightly != other.nightly) return nightly.compareTo(other.nightly)
        return buildNumber.compareTo(other.buildNumber)
    }

    // what people read: 8.3.2, 8.3.2.4 for a nightly
    val name: String get() = if (nightly > 0) "$major.$minor.$patch.$nightly" else "$major.$minor.$patch"
}

private val VersionRegex = Regex("""(\d+)\.(\d+)(?:\.(\d+))?(?:\.(\d+))?""")
private val BuildNumberRegex = Regex("""(?:build#|-b|\(#|#|\()(\d+)\)?""", RegexOption.IGNORE_CASE)

fun parseFullVersion(text: String): FullVersion {
    val version = VersionRegex.find(text)?.groupValues
    val buildNumber = BuildNumberRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return FullVersion(
        major = version?.get(1)?.toIntOrNull() ?: 0,
        minor = version?.get(2)?.toIntOrNull() ?: 0,
        patch = version?.getOrNull(3)?.toIntOrNull() ?: 0,
        buildNumber = buildNumber,
        nightly = version?.getOrNull(4)?.toIntOrNull() ?: 0,
    )
}

internal fun deviceSupportsArm64(): Boolean =
    android.os.Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

/**
 * Selects the compatible release APK asset for this phone's CPU ABI:
 * - 32-bit OS devices (e.g., Samsung Galaxy J6 running armeabi-v7a): never pick arm64-only APKs; choose universal/armeabi.
 * - 64-bit devices: match the installed flavor architecture (arm64 stays lightweight arm64; universal keeps universal) with safe fallback.
 */
internal fun <T> selectPreferredApkAsset(
    items: List<T>,
    nameOf: (T) -> String,
): T? {
    val nonDebug = items.filter { item ->
        val n = nameOf(item)
        n.endsWith(".apk", ignoreCase = true) && !n.contains("debug", ignoreCase = true)
    }
    if (nonDebug.isEmpty()) return null

    val appArch = runCatching { BuildConfig.ARCHITECTURE }.getOrDefault("universal")
    val supportsArm64 = deviceSupportsArm64()

    val arm64 = nonDebug.firstOrNull { nameOf(it).contains("arm64", ignoreCase = true) }
    val universal = nonDebug.firstOrNull { nameOf(it).contains("universal", ignoreCase = true) }
    val armeabi = nonDebug.firstOrNull { nameOf(it).contains("armeabi", ignoreCase = true) }
    val other = nonDebug.firstOrNull {
        val n = nameOf(it)
        !n.contains("arm64", ignoreCase = true) && !n.contains("x86", ignoreCase = true)
    }

    return when {
        !supportsArm64 -> universal ?: armeabi ?: other
        appArch.equals("universal", ignoreCase = true) -> universal ?: arm64 ?: other
        else -> arm64 ?: universal ?: other
    }
}

// automatic background checks reuse the last answer for 15 minutes; tapping "Kiểm tra cập nhật" passes force = true
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

private fun diskCacheFile(): File? = runCatching {
    val dir = System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() } ?: return@runCatching null
    File(dir, "mochy_releases_cache_v4.json")
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
                isNightly = o.optBoolean("isNightly", false),
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
                    .put("isNightly", r.isNightly)
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

// release only sees releases; nightly sees the prereleases and the releases, and takes the newest of all
private fun ParsedRelease.inChannel(channel: UpdateChannel) = channel == UpdateChannel.Nightly || !release.isNightly

/** The newest release on [channel], whatever is installed. */
suspend fun getLatestRelease(
    channel: UpdateChannel = UpdateChannel.Release,
    force: Boolean = false,
): Result<GithubRelease?> =
    getReleases(force).map { list -> list.filter { it.inChannel(channel) }.maxByOrNull { it.version }?.release }

/**
 * What "Downgrade to" offers, null when there's none: on release, the newest release before the
 * installed x.y.z (an older build of the same version doesn't count); on nightly, the nightly before
 * this one. A nightly never steps down to a release, that can break the library.
 */
suspend fun getPreviousRelease(
    channel: UpdateChannel = UpdateChannel.Release,
    force: Boolean = false,
): Result<GithubRelease?> {
    val installed = parseFullVersion(BuildConfig.VERSION_NAME).copy(buildNumber = 0)
    return getReleases(force).map { list ->
        when {
            channel == UpdateChannel.Release && installedIsNightly -> null
            channel == UpdateChannel.Release -> list.filter { !it.release.isNightly && it.version.copy(buildNumber = 0) < installed }
            !installedIsNightly -> null
            else -> list.filter { it.release.isNightly && it.version.copy(buildNumber = 0) < installed }
        }?.maxByOrNull { it.version }?.release
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

/** Compatible APK file name in a release's expanded asset list on github.com, or null. */
private fun apkNameFromExpandedAssets(tag: String): String? = runCatching {
    val html = httpGetText(
        "https://github.com/$OtaGithubOwner/$OtaGithubRepo/releases/expanded_assets/$tag",
        timeoutMs = 8_000,
    )
    val names = Regex("releases/download/[^/\"]+/([^\"<>\\s/]+?\\.apk)\"", RegexOption.IGNORE_CASE)
        .findAll(html)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
    selectPreferredApkAsset(names) { it }
}.getOrNull()

private class AtomEntry(
    val tag: String,
    val title: String,
    val body: String,
    val publishedAt: String,
    val apkNames: List<String>,
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

    val entries = entryRegex.findAll(xml).mapNotNull { match ->
        val entry = match.groupValues[1]
        val tag = tagRegex.find(entry)?.groupValues?.get(1).orEmpty()
        if (tag.isBlank()) return@mapNotNull null
        val rawTitle = unescapeXmlAndHtml(titleRegex.find(entry)?.groupValues?.get(1).orEmpty()).ifBlank { tag }
        val rawContent = contentRegex.find(entry)?.groupValues?.get(1).orEmpty()
        val fullBody = atomHtmlToMarkdown(rawContent)
        val apkNames = apkNameRegex.findAll(fullBody)
            .map { it.groupValues[1] }
            .filter { !it.contains("debug", ignoreCase = true) }
            .distinct()
            .toList()
        val chosenApkName = selectPreferredApkAsset(apkNames) { it }
        val tagVer = parseFullVersion("$rawTitle $tag")
        val apkVer = (chosenApkName ?: apkNames.firstOrNull())?.let(::parseFullVersion)
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
            apkNames = apkNames,
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
            val newest = index == 0
            val preferredFromNotes = selectPreferredApkAsset(entry.apkNames) { it }
            val apkName = if (newest) {
                preferredFromNotes ?: apkNameFromExpandedAssets(entry.tag)
            } else {
                preferredFromNotes
            }
            if (apkName.isNullOrBlank()) return@forEachIndexed

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
                "${fullVer.name} (${fullVer.buildNumber})"
            } else {
                fullVer.name
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
                        isNightly = false,
                    ),
                ),
            )
        }
    }
}

private data class ApiApkAsset(
    val name: String,
    val url: String,
    val apiUrl: String?,
    val size: Long,
)

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
        val seenSemvers = mutableSetOf<Triple<Int, Int, Int>>()

        buildList {
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue
                val isNightly = release.optBoolean("prerelease", false)

                val tag = release.optString("tag_name").orEmpty()
                val rawTitle = release.optString("name").orEmpty().ifBlank { tag }
                val title = rawTitle.replace("(Android • Windows • Linux)", "(Android APK)")
                val tagVer = parseFullVersion("$rawTitle $tag")

                if (tagVer.major == 0 && tagVer.minor == 0 && tagVer.patch == 0) continue

                val assetsJson = release.optJSONArray("assets")
                val apkAssets = buildList {
                    if (assetsJson != null) {
                        for (a in 0 until assetsJson.length()) {
                            val asset = assetsJson.getJSONObject(a)
                            val name = asset.optString("name").orEmpty()
                            val url = asset.optString("browser_download_url").orEmpty()
                            val apiUrl = asset.optString("url").orEmpty().takeIf { it.isNotBlank() }
                            val size = asset.optLong("size", 0L)
                            add(ApiApkAsset(name = name, url = url, apiUrl = apiUrl, size = size))
                        }
                    }
                }

                val picked = selectPreferredApkAsset(apkAssets) { it.name } ?: continue

                val apkVer = parseFullVersion(picked.name)
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
                    "${fullVer.name} (${fullVer.buildNumber})"
                } else {
                    fullVer.name
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
                            apkUrl = picked.url,
                            apkApiUrl = picked.apiUrl,
                            apkName = picked.name,
                            apkSizeBytes = picked.size,
                            publishedAt = release.optString("published_at").orEmpty(),
                            isNightly = isNightly,
                        ),
                    ),
                )
            }
        }
    }
}

fun isNewerThanInstalled(candidateVersionText: String): Boolean {
    val candidate = parseFullVersion(candidateVersionText)
    val installed = parseFullVersion(BuildConfig.VERSION_NAME)
    val candidateSemver = candidate.copy(buildNumber = 0)
    val installedSemver = installed.copy(buildNumber = 0)
    return when {
        candidateSemver != installedSemver -> candidateSemver > installedSemver
        candidate.buildNumber > 0 && installed.buildNumber > 0 -> candidate.buildNumber > installed.buildNumber
        else -> false
    }
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
    // many tv boxes have no page for one app, so it falls back to the whole list, then security, then settings
    val pages = listOf(
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + context.packageName)),
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    pages.firstOrNull { page ->
        runCatching { context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
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
