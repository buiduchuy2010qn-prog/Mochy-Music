package com.mochyy.mochymusic.eq

import com.mochyy.mochymusic.playback.audio.AudioOutputs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Snapshot of the Audio Lab DSP state stored per audio output device:
 * EQ (including AutoEq correction), Spatial, 8D, Loudness, and Reverb.
 */
@Serializable
data class AudioLabProfileSnapshot(
    val eq: EqSettings = EqSettings(),
    val spatial: SpatialSettings = SpatialSettings(),
    val eightD: EightDSettings = EightDSettings(),
    val loudness: LoudnessSettings = LoudnessSettings(),
    val reverb: ReverbSettings = ReverbSettings(),
) {
    /** True if any headphone-specific spatial / surround / 8D / AutoEq correction is active. */
    fun hasHeadphoneEffects(): Boolean =
        eightD.enabled ||
            spatial.enabled ||
            spatial.surround ||
            (eq.enabled && eq.correctionEnabled && eq.correction != null)

    /** True if every effect is in its default off state. */
    fun isNeutral(): Boolean =
        !eq.enabled &&
            !spatial.enabled &&
            !spatial.mono &&
            !spatial.surround &&
            !eightD.enabled &&
            !loudness.enabled &&
            !reverb.enabled

    fun normalized(): AudioLabProfileSnapshot = copy(eightD = eightD.normalized())

    companion object {
        val Neutral = AudioLabProfileSnapshot()
    }
}

/**
 * Saved Audio Lab configuration bound to one output device (Bluetooth headset, wired headphones,
 * USB DAC, HDMI, or built-in phone speaker).
 */
@Serializable
data class DeviceAudioProfile(
    val key: String,
    val displayName: String,
    val kind: String,
    val snapshot: AudioLabProfileSnapshot = AudioLabProfileSnapshot(),
    val updatedAtMs: Long = 0L,
)

/**
 * Identifies the currently active audio output route.
 */
data class ActiveAudioDevice(
    val key: String,
    val displayName: String,
    val kind: AudioOutputs.Kind,
)

/**
 * Pure state machine and codec for per-device Audio Lab profiles.
 * Keeps switching, bleed protection, LWW cloud merging, and compact serialization testable on JVM.
 */
object DeviceAudioProfileEngine {

    const val MaxProfiles = 16
    const val MaxCloudProfiles = 8

    private val whitespaceRegex = Regex("\\s+")
    private val controlCharsRegex = Regex("[\\p{Cntrl}]")

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val cloudCompactJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /**
     * Produces a stable cross-device key for an audio output route so the same headset / DAC
     * matches consistently across reconnects and Mochy Cloud sync.
     */
    fun deviceKey(kind: AudioOutputs.Kind, rawName: String?): String {
        val slug = normalizeDeviceName(rawName)
        return when (kind) {
            AudioOutputs.Kind.PHONE -> "phone"
            AudioOutputs.Kind.WIRED -> "wired"
            AudioOutputs.Kind.BLUETOOTH -> if (slug.isNotEmpty()) "bt:$slug" else "bt"
            AudioOutputs.Kind.USB -> if (slug.isNotEmpty()) "usb:$slug" else "usb"
            AudioOutputs.Kind.HDMI -> if (slug.isNotEmpty()) "hdmi:$slug" else "hdmi"
        }
    }

    fun normalizeDeviceName(rawName: String?): String = rawName
        .orEmpty()
        .replace(controlCharsRegex, " ")
        .trim()
        .replace(whitespaceRegex, " ")
        .lowercase()
        .take(48)

    fun cleanDisplayName(kind: AudioOutputs.Kind, rawName: String?): String {
        val trimmed = rawName.orEmpty().replace(controlCharsRegex, " ").trim().replace(whitespaceRegex, " ").take(48)
        if (kind == AudioOutputs.Kind.PHONE || kind == AudioOutputs.Kind.WIRED) return ""
        return trimmed
    }

    fun activeDeviceOf(kind: AudioOutputs.Kind, rawName: String?): ActiveAudioDevice = ActiveAudioDevice(
        key = deviceKey(kind, rawName),
        displayName = cleanDisplayName(kind, rawName),
        kind = kind,
    )

    data class SwitchOutcome(
        val updatedProfiles: Map<String, DeviceAudioProfile>,
        /** Non-null when AudioLabStore should immediately apply this snapshot to DSP processors. */
        val applySnapshot: AudioLabProfileSnapshot?,
        /** True when a previously saved profile for the incoming device was restored. */
        val restoredSavedProfile: Boolean,
    )

    /**
     * Computes what should be saved and applied when the active audio output switches from
     * [previousDevice] to [newDevice].
     *
     * Rules:
     * 1. When [enabled] is false, no DSP state is changed and no profiles are overwritten.
     * 2. Switching away from a known [previousDevice] persists [currentSnapshot] into its profile
     *    so changes made while listening on that device are never lost.
     * 3. If [newDevice] already has a saved profile, it is restored immediately.
     * 4. If [newDevice] is the built-in phone speaker and has no saved profile yet, while the
     *    previous headphone/DAC had headphone-only effects (AutoEq, Spatial, or 8D), the speaker
     *    receives a clean neutral profile so headphone corrections never bleed onto phone speakers.
     * 5. Otherwise (e.g. connecting a brand-new headset for the first time), current settings stay
     *    active as a starting point without unexpectedly silencing/resetting the user's EQ.
     */
    fun onDeviceSwitch(
        enabled: Boolean,
        profiles: Map<String, DeviceAudioProfile>,
        previousDevice: ActiveAudioDevice?,
        newDevice: ActiveAudioDevice,
        currentSnapshot: AudioLabProfileSnapshot,
        nowMs: Long,
    ): SwitchOutcome {
        if (!enabled) {
            return SwitchOutcome(
                updatedProfiles = profiles,
                applySnapshot = null,
                restoredSavedProfile = false,
            )
        }

        val mutable = LinkedHashMap(profiles)

        // Same device re-emitted (e.g. route refresh): only apply if this is initial bind and saved profile exists
        if (previousDevice != null && previousDevice.key == newDevice.key) {
            return SwitchOutcome(
                updatedProfiles = mutable,
                applySnapshot = null,
                restoredSavedProfile = false,
            )
        }

        // Save outgoing device state so switching back later restores it verbatim
        if (previousDevice != null) {
            val prevExisting = mutable[previousDevice.key]
            val prevSnapshot = currentSnapshot.normalized()
            // Persist outgoing state if it had an existing profile or non-neutral settings or is phone speaker baseline
            if (prevExisting != null || !prevSnapshot.isNeutral() || previousDevice.kind == AudioOutputs.Kind.PHONE) {
                mutable[previousDevice.key] = DeviceAudioProfile(
                    key = previousDevice.key,
                    displayName = previousDevice.displayName.ifBlank { prevExisting?.displayName.orEmpty() },
                    kind = previousDevice.kind.name,
                    snapshot = prevSnapshot,
                    updatedAtMs = nowMs,
                )
            }
        }

        val savedTarget = mutable[newDevice.key]
        if (savedTarget != null) {
            // Keep displayName fresh if OS reported a nicer product name
            val refreshed = if (newDevice.displayName.isNotBlank() && savedTarget.displayName != newDevice.displayName) {
                savedTarget.copy(displayName = newDevice.displayName)
            } else {
                savedTarget
            }
            mutable[newDevice.key] = refreshed
            val normalizedTarget = refreshed.snapshot.normalized()
            val needsApply = normalizedTarget != currentSnapshot.normalized()
            return SwitchOutcome(
                updatedProfiles = trimProfiles(mutable, pinnedKey = newDevice.key),
                applySnapshot = if (needsApply) normalizedTarget else null,
                restoredSavedProfile = true,
            )
        }

        // Unseen target device: prevent headphone AutoEq/Spatial/8D from bleeding onto phone speaker
        val comingFromHeadphone = previousDevice != null &&
            previousDevice.kind != AudioOutputs.Kind.PHONE &&
            newDevice.kind == AudioOutputs.Kind.PHONE &&
            currentSnapshot.hasHeadphoneEffects()

        if (comingFromHeadphone) {
            val speakerBaseline = AudioLabProfileSnapshot.Neutral
            mutable[newDevice.key] = DeviceAudioProfile(
                key = newDevice.key,
                displayName = "",
                kind = newDevice.kind.name,
                snapshot = speakerBaseline,
                updatedAtMs = nowMs,
            )
            return SwitchOutcome(
                updatedProfiles = trimProfiles(mutable, pinnedKey = newDevice.key),
                applySnapshot = speakerBaseline,
                restoredSavedProfile = false,
            )
        }

        // First launch on a device that already has no saved profile yet: keep user's current settings
        return SwitchOutcome(
            updatedProfiles = trimProfiles(mutable, pinnedKey = newDevice.key),
            applySnapshot = null,
            restoredSavedProfile = false,
        )
    }

    /**
     * Records a user edit in Audio Lab against the currently active device.
     */
    fun recordEdit(
        enabled: Boolean,
        profiles: Map<String, DeviceAudioProfile>,
        activeDevice: ActiveAudioDevice?,
        newSnapshot: AudioLabProfileSnapshot,
        nowMs: Long,
    ): Map<String, DeviceAudioProfile> {
        if (!enabled || activeDevice == null) return profiles
        val mutable = LinkedHashMap(profiles)
        mutable[activeDevice.key] = DeviceAudioProfile(
            key = activeDevice.key,
            displayName = activeDevice.displayName.ifBlank { profiles[activeDevice.key]?.displayName.orEmpty() },
            kind = activeDevice.kind.name,
            snapshot = newSnapshot.normalized(),
            updatedAtMs = nowMs,
        )
        return trimProfiles(mutable, pinnedKey = activeDevice.key)
    }

    /**
     * Merges local and remote device profiles using per-device Last-Write-Wins ([DeviceAudioProfile.updatedAtMs]).
     */
    fun mergeProfiles(
        local: Map<String, DeviceAudioProfile>,
        remote: Map<String, DeviceAudioProfile>,
        maxCount: Int = MaxProfiles,
    ): Map<String, DeviceAudioProfile> {
        val merged = LinkedHashMap<String, DeviceAudioProfile>(local.size + remote.size)
        for ((key, profile) in local) {
            if (key.isNotBlank()) merged[key] = profile.copy(snapshot = profile.snapshot.normalized())
        }
        for ((key, remoteProfile) in remote) {
            if (key.isBlank()) continue
            val normalizedRemote = remoteProfile.copy(snapshot = remoteProfile.snapshot.normalized())
            val existing = merged[key]
            if (existing == null || normalizedRemote.updatedAtMs > existing.updatedAtMs) {
                merged[key] = normalizedRemote
            }
        }
        return trimProfiles(merged, maxCount = maxCount)
    }

    fun trimProfiles(
        profiles: Map<String, DeviceAudioProfile>,
        pinnedKey: String? = null,
        maxCount: Int = MaxProfiles,
    ): Map<String, DeviceAudioProfile> {
        if (profiles.size <= maxCount) return LinkedHashMap(profiles)
        val sorted = profiles.values
            .sortedWith(
                compareByDescending<DeviceAudioProfile> { it.key == pinnedKey }
                    .thenByDescending { it.updatedAtMs }
                    .thenBy { it.key },
            )
            .take(maxCount)
        return sorted.associateByTo(LinkedHashMap()) { it.key }
    }

    fun encodeProfiles(profiles: Map<String, DeviceAudioProfile>): String {
        if (profiles.isEmpty()) return "[]"
        val ordered = profiles.values.sortedByDescending { it.updatedAtMs }.take(MaxProfiles)
        return json.encodeToString(ListSerializer(DeviceAudioProfile.serializer()), ordered)
    }

    /**
     * Compact encoding omitting default fields for Mochy Cloud JWT metadata budget.
     * Progressively trims the oldest profiles if [maxChars] is specified so large AutoEq curves
     * never cause the entire profile set to be dropped.
     */
    fun encodeProfilesCompact(
        profiles: Map<String, DeviceAudioProfile>,
        maxProfiles: Int = MaxCloudProfiles,
        maxChars: Int = Int.MAX_VALUE,
    ): String? {
        if (profiles.isEmpty() || maxProfiles <= 0 || maxChars <= 2) return null
        val ordered = profiles.values
            .sortedByDescending { it.updatedAtMs }
            .take(maxProfiles)
        if (ordered.isEmpty()) return null
        for (count in ordered.size downTo 1) {
            val encoded = cloudCompactJson.encodeToString(
                ListSerializer(DeviceAudioProfile.serializer()),
                ordered.take(count),
            )
            if (encoded.length <= maxChars) return encoded
        }
        return null
    }

    /**
     * Trims an already-serialized compact device profiles JSON to fit [maxProfiles] and [maxChars].
     */
    fun trimEncodedCompactJson(
        raw: String?,
        maxChars: Int,
        maxProfiles: Int = MaxCloudProfiles,
    ): String? {
        if (raw.isNullOrBlank()) return null
        val decoded = decodeProfiles(raw)
        if (decoded.isEmpty()) return null
        return encodeProfilesCompact(decoded, maxProfiles = maxProfiles, maxChars = maxChars)
    }

    fun decodeProfiles(raw: String?): Map<String, DeviceAudioProfile> {
        if (raw.isNullOrBlank()) return emptyMap()
        val list = runCatching {
            json.decodeFromString(ListSerializer(DeviceAudioProfile.serializer()), raw)
        }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, DeviceAudioProfile>(list.size)
        for (item in list) {
            val cleanKey = item.key.trim()
            if (cleanKey.isBlank()) continue
            out[cleanKey] = item.copy(
                key = cleanKey,
                snapshot = item.snapshot.normalized(),
            )
        }
        return trimProfiles(out)
    }

    /**
     * Deterministic canonical representation for Mochy Cloud digest comparison.
     */
    fun canonicalKey(profiles: Map<String, DeviceAudioProfile>): String {
        if (profiles.isEmpty()) return ""
        val ordered = profiles.values.sortedBy { it.key }
        return cloudCompactJson.encodeToString(ListSerializer(DeviceAudioProfile.serializer()), ordered)
    }

    fun canonicalKeyOfJson(raw: String?): String = canonicalKey(decodeProfiles(raw))

    fun kindOf(name: String): AudioOutputs.Kind =
        runCatching { AudioOutputs.Kind.valueOf(name) }.getOrDefault(AudioOutputs.Kind.PHONE)
}
