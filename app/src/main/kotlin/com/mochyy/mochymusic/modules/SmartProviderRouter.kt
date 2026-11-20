package com.mochyy.mochymusic.modules

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import com.mochyy.mochymusic.module.TrackQuery
import com.mochyy.mochymusic.module.applemusic.AppleMusicModule
import com.mochyy.mochymusic.module.jiosaavn.JioSaavnModule
import com.mochyy.mochymusic.module.monochrome.MonochromeModule
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.module.zingmp3.ZingMp3Module
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

enum class NetworkTier {
    WIFI_UNMETERED,
    CELLULAR_METERED,
    UNKNOWN,
}

data class SmartRoutingPolicy(
    val enabled: Boolean = true,
    val networkAware: Boolean = true,
    val vietnamesePriority: Boolean = true,
    val rememberBestPerSong: Boolean = true,
)

@Serializable
data class RememberedProvider(
    val songId: String,
    val providerId: String,
    val networkTier: String = NetworkTier.UNKNOWN.name,
    val updatedAtMs: Long = 0L,
)

/**
 * High-precision detector for Vietnamese tracks (title / artist / album):
 * 1. Uniquely Vietnamese Quốc Ngữ diacritics (excluding shared French/Spanish/German accents like é, è, ê, à, â, ô, ü).
 * 2. Curated Vietnamese artist names (including ASCII stage names like HIEUTHUHAI, tlinh, Wren Evans, Low G, MCK).
 * 3. Explicit Vietnamese music tags (V-Pop, Nhạc trẻ, Bolero, Vinahouse).
 */
object VietnameseTrackDetector {

    /**
     * Characters that exist uniquely in Vietnamese orthography and do not appear in
     * English, French, Spanish, German, Italian, Portuguese, Japanese, Korean, or Chinese.
     */
    private val UniquelyVietnameseChars: Set<Char> = (
        "đĐăĂơƠưƯ" +
            "ắằẳẵặẮẰẲẴẶ" +
            "ấầẩẫậẤẦẨẪẬ" +
            "ếềểễệẾỀỂỄỆ" +
            "ốồổỗộỐỒỔỖỘ" +
            "ớờởỡợỚỜỞỠỢ" +
            "ứừửữựỨỪỬỮỰ" +
            "ảạẢẠ" +
            "ẻẽẹẺẼẸ" +
            "ỉĩịỈĨỊ" +
            "ỏọỎỌ" +
            "ủũụỦŨỤ" +
            "ỷỹỵỶỸỴ"
        ).toSet()

    /**
     * Normalized lowercase Vietnamese artist names (ASCII / stage names that carry no Vietnamese diacritics).
     */
    private val KnownVietnameseArtists: Set<String> = setOf(
        "son tung m-tp", "son tung mtp", "sơn tùng m-tp", "sơn tùng mtp",
        "den vau", "đen vâu", "den",
        "hoang thuy linh", "hoàng thùy linh",
        "hieuthuhai", "hieu thu hai",
        "wren evans",
        "tlinh",
        "mck", "rpt mck", "nghiem vu hoang long",
        "low g",
        "obito",
        "grey d",
        "amee",
        "soobin", "soobin hoang son",
        "binz",
        "justatee",
        "karik",
        "rhymastic",
        "suboi",
        "andree right hand", "b ray",
        "vu.", "vũ.",
        "my tam", "mỹ tâm",
        "ha anh tuan", "hà anh tuấn",
        "bich phuong", "bích phương",
        "phuong ly", "phương ly",
        "erik", "duc phuc", "đức phúc", "hoa minzy", "hòa minzy",
        "jack - j97", "jack j97", "k-icm",
        "noo phuoc thinh", "noo phước thịnh",
        "dong nhi", "đông nhi",
        "phan manh quynh", "phan mạnh quỳnh",
        "tang duy tan", "tăng duy tân",
        "truc nhan", "trúc nhân",
        "van mai huong", "văn mai hương",
        "trung quan", "trung quân", "trung quan idol",
        "bui anh tuan", "bùi anh tuấn",
        "vu cat tuong", "vũ cát tường",
        "tien tien", "tiên tiên",
        "chillies",
        "da lab", "dalab",
        "ngot", "ngọt",
        "ca hoi hoang", "cá hồi hoang",
        "hoang dung", "hoàng dũng",
        "buitruonglinh", "bùi trường linh",
        "rhyder",
        "quang hung masterd", "quang hùng masterd",
        "duong domic", "dương domic",
        "captain boy", "phap kieu", "pháp kiều",
        "hurrykng", "negav", "jsol", "wean", "wean le",
        "wxrdie", "24k.right", "double2t",
        "quan a.p", "quân a.p",
        "lyly", "hien ho", "hiền hồ", "anh tu", "anh tú",
        "lou hoang", "lou hoàng", "only c", "onlyc", "masew",
        "dat g", "đạt g", "thinh suy", "thịnh suy", "t.r.i",
        "min", "mono", "kay tran", "kay trần", "isaac",
        "toc tien", "tóc tiên", "chi pu", "bao anh", "bảo anh",
        "huong tram", "hương tràm", "thuy chi", "thùy chi",
        "le quyen", "lệ quyên", "dam vinh hung", "đàm vĩnh hưng",
        "bang kieu", "bằng kiều", "my linh", "mỹ linh",
        "quang dung", "quang dũng", "trinh cong son", "trịnh công sơn",
        "ngo kien huy", "ngô kiến huy", "truong thao nhi", "trương thảo nhi",
        "vu phung tien", "vũ phụng tiên", "juky san", "orange",
        "phuong my chi", "phương mỹ chi", "van mai huong",
        "dinh dung", "đình dũng", "quang dang tran", "phan duy anh",
        "nal", "k-icm", "khac viet", "khắc việt", "trinh thang binh", "trịnh thăng bình",
        "le bao binh", "lê bảo bình", "chi dan", "chi dân",
        "mr. siro", "mr siro", "ung hoang phuc", "ưng hoàng phúc",
        "lam truong", "lam trường", "dan truong", "đan trường",
        "cam ly", "cẩm ly", "nhu quynh", "như quỳnh", "quang le", "quang lê",
    )

    private val VietnameseTagRegex = Regex(
        "\\b(v-?pop|nhac\\s+tre|nhạc\\s+trẻ|vinahouse|bolero|nhac\\s+viet|nhạc\\s+việt|vpop)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val whitespaceRegex = Regex("\\s+")

    fun hasUniqueVietnameseDiacritics(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.any { it in UniquelyVietnameseChars }
    }

    fun isKnownVietnameseArtist(artistName: String?): Boolean {
        val norm = artistName.orEmpty().trim().replace(whitespaceRegex, " ").lowercase()
        if (norm.isEmpty()) return false
        if (norm in KnownVietnameseArtists) return true
        // Handle featured artist strings like "HIEUTHUHAI feat. Negav" or "tlinh x Low G"
        val parts = norm.split(Regex("\\s*(?:,|&|feat\\.?|ft\\.?|\\bx\\b|\\+)\\s*"))
        if (parts.size > 1) {
            return parts.any { it.trim() in KnownVietnameseArtists }
        }
        return false
    }

    fun isVietnameseTrack(track: TrackQuery?): Boolean {
        if (track == null) return false
        return isVietnameseTrack(track.title, track.artists, track.album)
    }

    fun isVietnameseTrack(title: String?, artists: List<String>, album: String? = null): Boolean {
        if (hasUniqueVietnameseDiacritics(title)) return true
        if (artists.any { hasUniqueVietnameseDiacritics(it) }) return true
        if (hasUniqueVietnameseDiacritics(album)) return true
        if (artists.any { isKnownVietnameseArtist(it) }) return true
        if (!title.isNullOrBlank() && VietnameseTagRegex.containsMatchIn(title)) return true
        if (!album.isNullOrBlank() && VietnameseTagRegex.containsMatchIn(album)) return true
        return false
    }
}

/**
 * Pure routing engine computing dynamic provider priority for each song based on:
 * - Network tier (Wi-Fi -> lossless Monochrome / Apple Music; 4G/5G -> fast light Zing MP3 / YouTube Music)
 * - Track language (Vietnamese tracks -> Zing MP3 -> YouTube Music)
 * - Per-song remembered best provider (sticky fast path when compatible with current network)
 */
object SmartProviderRouter {

    private val LosslessProviders = setOf(MonochromeModule.Id)
    private val WifiPriorityOrder = listOf(MonochromeModule.Id, AppleMusicModule.Id)
    private val CellularPriorityOrder = listOf(
        ZingMp3Module.Id,
        SourceModules.YouTubeId,
        SoundCloudModule.Id,
        JioSaavnModule.Id,
        AppleMusicModule.Id,
        MonochromeModule.Id,
    )
    private val VietnameseWifiPriorityOrder = listOf(
        ZingMp3Module.Id,
        SourceModules.YouTubeId,
        MonochromeModule.Id,
        AppleMusicModule.Id,
    )
    private val VietnameseCellularPriorityOrder = listOf(
        ZingMp3Module.Id,
        SourceModules.YouTubeId,
        SoundCloudModule.Id,
    )

    fun computeOrder(
        baseOrder: List<String>,
        usable: (String) -> Boolean,
        skip: Set<String>,
        networkTier: NetworkTier,
        isVietnamese: Boolean,
        remembered: RememberedProvider?,
        policy: SmartRoutingPolicy,
    ): List<String> {
        val eligible = baseOrder.filter { it !in skip && usable(it) }.distinct()
        if (eligible.size <= 1 || !policy.enabled) return eligible

        val prioritized = ArrayList<String>(eligible.size)
        fun promote(id: String) {
            if (id in eligible && id !in prioritized) prioritized += id
        }

        // 1. Per-song remembered best source (unless it's a heavy lossless source while on cellular metered data)
        if (policy.rememberBestPerSong && remembered != null && remembered.providerId in eligible) {
            val isHeavyOnCellular = policy.networkAware &&
                networkTier == NetworkTier.CELLULAR_METERED &&
                remembered.providerId in LosslessProviders
            if (!isHeavyOnCellular) {
                promote(remembered.providerId)
            }
        }

        // 2. Vietnamese tracks prioritize Zing MP3 -> YouTube Music
        if (policy.vietnamesePriority && isVietnamese) {
            val vnOrder = if (policy.networkAware && networkTier == NetworkTier.CELLULAR_METERED) {
                VietnameseCellularPriorityOrder
            } else {
                VietnameseWifiPriorityOrder
            }
            vnOrder.forEach(::promote)
        } else if (policy.networkAware) {
            // 3. Network-aware priority for non-Vietnamese tracks
            when (networkTier) {
                NetworkTier.WIFI_UNMETERED -> WifiPriorityOrder.forEach(::promote)
                NetworkTier.CELLULAR_METERED -> CellularPriorityOrder.forEach(::promote)
                NetworkTier.UNKNOWN -> Unit
            }
        }

        // 4. Preserve user's remaining enabled providers in their configured order as fallback
        eligible.forEach(::promote)
        return prioritized
    }

    fun detectNetworkTier(context: Context): NetworkTier = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkTier.UNKNOWN
        val network = cm.activeNetwork ?: return NetworkTier.UNKNOWN
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkTier.UNKNOWN
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTier.WIFI_UNMETERED
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTier.CELLULAR_METERED
            !cm.isActiveNetworkMetered -> NetworkTier.WIFI_UNMETERED
            else -> NetworkTier.CELLULAR_METERED
        }
    }.getOrDefault(NetworkTier.UNKNOWN)
}

/**
 * Thread-safe persistent LRU store mapping songId -> best working providerId.
 */
class BestProviderStore(context: Context) {
    private val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val entries = ConcurrentHashMap<String, RememberedProvider>()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    init {
        load()
    }

    fun get(songId: String?): RememberedProvider? {
        if (songId.isNullOrBlank()) return null
        return entries[songId]
    }

    fun recordSuccess(songId: String?, providerId: String, networkTier: NetworkTier, nowMs: Long = System.currentTimeMillis()) {
        if (songId.isNullOrBlank() || providerId.isBlank()) return
        val existing = entries[songId]
        val next = RememberedProvider(
            songId = songId,
            providerId = providerId,
            networkTier = networkTier.name,
            updatedAtMs = nowMs,
        )
        entries[songId] = next
        if (existing?.providerId != providerId || existing.networkTier != next.networkTier) {
            trimAndPersist()
        }
    }

    fun forget(songId: String?, providerId: String) {
        if (songId.isNullOrBlank()) return
        val existing = entries[songId] ?: return
        if (existing.providerId == providerId) {
            entries.remove(songId)
            trimAndPersist()
        }
    }

    fun clear() {
        entries.clear()
        _count.value = 0
        prefs.edit { remove(KeyEntries) }
    }

    private fun load() {
        val raw = prefs.getString(KeyEntries, null) ?: return
        val decoded = runCatching {
            json.decodeFromString(ListSerializer(RememberedProvider.serializer()), raw)
        }.getOrDefault(emptyList())
        for (item in decoded) {
            if (item.songId.isNotBlank() && item.providerId.isNotBlank()) {
                entries[item.songId] = item
            }
        }
        _count.value = entries.size
    }

    @Synchronized
    private fun trimAndPersist() {
        val list = entries.values
            .sortedByDescending { it.updatedAtMs }
            .take(MaxEntries)
        if (entries.size > MaxEntries) {
            val keepIds = list.mapTo(HashSet(list.size)) { it.songId }
            entries.keys.retainAll(keepIds)
        }
        _count.value = entries.size
        val encoded = runCatching {
            json.encodeToString(ListSerializer(RememberedProvider.serializer()), list)
        }.getOrNull() ?: return
        prefs.edit { putString(KeyEntries, encoded) }
    }

    private companion object {
        const val PrefsName = "smart_provider_memory"
        const val KeyEntries = "best_providers"
        const val MaxEntries = 500
    }
}
