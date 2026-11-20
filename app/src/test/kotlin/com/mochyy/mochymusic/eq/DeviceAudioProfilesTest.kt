package com.mochyy.mochymusic.eq

import com.mochyy.mochymusic.cloud.CloudAudioConfig
import com.mochyy.mochymusic.cloud.CloudLibraryExtra
import com.mochyy.mochymusic.cloud.MochyLibraryExtraCodec
import com.mochyy.mochymusic.eq.audio.SpatialMode
import com.mochyy.mochymusic.eq.autoeq.HeadphoneForm
import com.mochyy.mochymusic.eq.data.FilterType
import com.mochyy.mochymusic.eq.data.ParametricEQBand
import com.mochyy.mochymusic.playback.audio.AudioOutputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAudioProfilesTest {

    private fun sampleHeadphoneSnapshot(modelName: String = "Sony WH-1000XM5"): AudioLabProfileSnapshot =
        AudioLabProfileSnapshot(
            eq = EqSettings(
                enabled = true,
                nodes = listOf(ParametricEQBand(62.0, 3.5, 1.4, FilterType.PK)),
                preamp = -1.5,
                presetId = null,
                correction = AutoEqCorrection(
                    model = modelName,
                    source = "oratory1990",
                    path = "results/oratory1990/over-ear/$modelName",
                    form = HeadphoneForm.OverEar,
                    filters = listOf(ParametricEQBand(105.0, -2.1, 0.7, FilterType.LSC)),
                    preamp = -3.2,
                ),
                correctionEnabled = true,
            ),
            spatial = SpatialSettings(enabled = true, mode = SpatialMode.Crossfeed, amount = 0.8f),
            eightD = EightDSettings(enabled = true, rotationSpeedHz = 0.18f, depth = 0.9f, reverbLevel = 0.35f),
            loudness = LoudnessSettings(enabled = true, boostDb = 5f, compression = 0.4f),
            reverb = ReverbSettings(enabled = false),
        )

    @Test
    fun deviceKeyNormalizesNamesConsistentlyAcrossKinds() {
        assertEquals("phone", DeviceAudioProfileEngine.deviceKey(AudioOutputs.Kind.PHONE, "Built-in Speaker"))
        assertEquals("wired", DeviceAudioProfileEngine.deviceKey(AudioOutputs.Kind.WIRED, "Headset"))
        assertEquals(
            "bt:sony wh-1000xm5",
            DeviceAudioProfileEngine.deviceKey(AudioOutputs.Kind.BLUETOOTH, "  Sony   WH-1000XM5 \n "),
        )
        assertEquals(
            "bt:sony wh-1000xm5",
            DeviceAudioProfileEngine.deviceKey(AudioOutputs.Kind.BLUETOOTH, "sony wh-1000xm5"),
        )
        assertEquals("bt", DeviceAudioProfileEngine.deviceKey(AudioOutputs.Kind.BLUETOOTH, "   "))
        assertEquals("usb:fiio ka11", DeviceAudioProfileEngine.deviceKey(AudioOutputs.Kind.USB, "FiiO KA11"))
        assertEquals("hdmi:lg oled", DeviceAudioProfileEngine.deviceKey(AudioOutputs.Kind.HDMI, "LG OLED"))
    }

    @Test
    fun switchesBetweenHeadphonesAndSpeakerSavingAndRestoringProfiles() {
        val phone = DeviceAudioProfileEngine.activeDeviceOf(AudioOutputs.Kind.PHONE, "")
        val sony = DeviceAudioProfileEngine.activeDeviceOf(AudioOutputs.Kind.BLUETOOTH, "Sony WH-1000XM5")
        val dac = DeviceAudioProfileEngine.activeDeviceOf(AudioOutputs.Kind.USB, "FiiO KA11")

        val speakerSnapshot = AudioLabProfileSnapshot(
            loudness = LoudnessSettings(enabled = true, boostDb = 3f, compression = 0.2f),
        )
        val sonySnapshot = sampleHeadphoneSnapshot("Sony WH-1000XM5")
        val dacSnapshot = AudioLabProfileSnapshot(
            eq = EqSettings(enabled = true, preamp = -0.5),
            spatial = SpatialSettings(enabled = false),
            eightD = EightDSettings(enabled = false),
            loudness = LoudnessSettings(enabled = false),
        )

        // Start on phone speaker with speakerSnapshot, then connect Sony Bluetooth
        val s1 = DeviceAudioProfileEngine.onDeviceSwitch(
            enabled = true,
            profiles = emptyMap(),
            previousDevice = phone,
            newDevice = sony,
            currentSnapshot = speakerSnapshot,
            nowMs = 1_000L,
        )
        assertEquals(speakerSnapshot, s1.updatedProfiles["phone"]?.snapshot)
        assertNull("First connect of unseen headset keeps current settings", s1.applySnapshot)

        // User configures Sony XM5 with AutoEq + 8D + Spatial + Loudness
        val afterEditSony = DeviceAudioProfileEngine.recordEdit(
            enabled = true,
            profiles = s1.updatedProfiles,
            activeDevice = sony,
            newSnapshot = sonySnapshot,
            nowMs = 2_000L,
        )
        assertEquals(sonySnapshot, afterEditSony["bt:sony wh-1000xm5"]?.snapshot)

        // Disconnect Sony -> returns to phone speaker -> restores phone speaker snapshot (no 8D/AutoEq!)
        val s2 = DeviceAudioProfileEngine.onDeviceSwitch(
            enabled = true,
            profiles = afterEditSony,
            previousDevice = sony,
            newDevice = phone,
            currentSnapshot = sonySnapshot,
            nowMs = 3_000L,
        )
        assertTrue(s2.restoredSavedProfile)
        assertEquals(speakerSnapshot, s2.applySnapshot)
        assertFalse(s2.applySnapshot!!.eightD.enabled)

        // Connect USB DAC and customize it
        val s3 = DeviceAudioProfileEngine.onDeviceSwitch(
            enabled = true,
            profiles = s2.updatedProfiles,
            previousDevice = phone,
            newDevice = dac,
            currentSnapshot = speakerSnapshot,
            nowMs = 4_000L,
        )
        val afterEditDac = DeviceAudioProfileEngine.recordEdit(
            enabled = true,
            profiles = s3.updatedProfiles,
            activeDevice = dac,
            newSnapshot = dacSnapshot,
            nowMs = 5_000L,
        )

        // Reconnect Sony XM5 -> restores Sony's exact AutoEq + 8D + Spatial + Loudness
        val s4 = DeviceAudioProfileEngine.onDeviceSwitch(
            enabled = true,
            profiles = afterEditDac,
            previousDevice = dac,
            newDevice = sony,
            currentSnapshot = dacSnapshot,
            nowMs = 6_000L,
        )
        assertTrue(s4.restoredSavedProfile)
        assertEquals(sonySnapshot, s4.applySnapshot)
        assertEquals(dacSnapshot, s4.updatedProfiles["usb:fiio ka11"]?.snapshot)
    }

    @Test
    fun disconnectingHeadphonesWithAutoEqToUnseenPhoneSpeakerResetsToNeutral() {
        val sony = DeviceAudioProfileEngine.activeDeviceOf(AudioOutputs.Kind.BLUETOOTH, "Sony WH-1000XM5")
        val phone = DeviceAudioProfileEngine.activeDeviceOf(AudioOutputs.Kind.PHONE, "")
        val sonySnapshot = sampleHeadphoneSnapshot()

        val outcome = DeviceAudioProfileEngine.onDeviceSwitch(
            enabled = true,
            profiles = emptyMap(),
            previousDevice = sony,
            newDevice = phone,
            currentSnapshot = sonySnapshot,
            nowMs = 10_000L,
        )
        assertEquals(sonySnapshot, outcome.updatedProfiles["bt:sony wh-1000xm5"]?.snapshot)
        assertEquals(AudioLabProfileSnapshot.Neutral, outcome.applySnapshot)
        assertFalse(outcome.applySnapshot!!.hasHeadphoneEffects())
    }

    @Test
    fun disabledFeatureDoesNotSwitchOrOverwriteProfiles() {
        val sony = DeviceAudioProfileEngine.activeDeviceOf(AudioOutputs.Kind.BLUETOOTH, "AirPods Pro")
        val phone = DeviceAudioProfileEngine.activeDeviceOf(AudioOutputs.Kind.PHONE, "")
        val existing = mapOf(
            "phone" to DeviceAudioProfile("phone", "", "PHONE", AudioLabProfileSnapshot.Neutral, 100L),
        )
        val outcome = DeviceAudioProfileEngine.onDeviceSwitch(
            enabled = false,
            profiles = existing,
            previousDevice = sony,
            newDevice = phone,
            currentSnapshot = sampleHeadphoneSnapshot(),
            nowMs = 200L,
        )
        assertNull(outcome.applySnapshot)
        assertFalse(outcome.restoredSavedProfile)
        assertEquals(existing, outcome.updatedProfiles)
    }

    @Test
    fun mergeProfilesUsesLastWriteWinsPerDeviceAndEnforcesMaxCap() {
        val local = mapOf(
            "bt:airpods" to DeviceAudioProfile("bt:airpods", "AirPods", "BLUETOOTH", sampleHeadphoneSnapshot("Old"), 1_000L),
            "wired" to DeviceAudioProfile("wired", "", "WIRED", AudioLabProfileSnapshot(loudness = LoudnessSettings(true)), 5_000L),
        )
        val remote = mapOf(
            "bt:airpods" to DeviceAudioProfile("bt:airpods", "AirPods", "BLUETOOTH", sampleHeadphoneSnapshot("New"), 9_000L),
            "wired" to DeviceAudioProfile("wired", "", "WIRED", AudioLabProfileSnapshot.Neutral, 2_000L),
            "usb:dac" to DeviceAudioProfile("usb:dac", "DAC", "USB", AudioLabProfileSnapshot(eq = EqSettings(enabled = true)), 4_000L),
        )
        val merged = DeviceAudioProfileEngine.mergeProfiles(local, remote)
        assertEquals(3, merged.size)
        assertEquals("New", merged["bt:airpods"]?.snapshot?.eq?.correction?.model)
        assertTrue("Newer local wired profile must win over older remote", merged["wired"]?.snapshot?.loudness?.enabled == true)
        assertTrue(merged["usb:dac"]?.snapshot?.eq?.enabled == true)

        val encoded = DeviceAudioProfileEngine.encodeProfiles(merged)
        val decoded = DeviceAudioProfileEngine.decodeProfiles(encoded)
        assertEquals(merged, decoded)
        assertTrue(DeviceAudioProfileEngine.decodeProfiles("{corrupt-json").isEmpty())
    }

    @Test
    fun cloudExtraCodecRoundTripsDeviceProfilesWithinJwtBudget() {
        val profiles = mapOf(
            "bt:sony wh-1000xm5" to DeviceAudioProfile(
                key = "bt:sony wh-1000xm5",
                displayName = "Sony WH-1000XM5",
                kind = AudioOutputs.Kind.BLUETOOTH.name,
                snapshot = sampleHeadphoneSnapshot(),
                updatedAtMs = 1_760_000_000_000L,
            ),
            "phone" to DeviceAudioProfile(
                key = "phone",
                displayName = "",
                kind = AudioOutputs.Kind.PHONE.name,
                snapshot = AudioLabProfileSnapshot(loudness = LoudnessSettings(enabled = true, boostDb = 3f)),
                updatedAtMs = 1_760_000_050_000L,
            ),
        )
        val compact = DeviceAudioProfileEngine.encodeProfilesCompact(profiles)
        assertNotNull(compact)

        val extra = CloudLibraryExtra(
            audio = CloudAudioConfig(
                eqSettingsJson = """{"enabled":true}""",
                deviceProfilesJson = compact,
                deviceProfilesEnabled = true,
            ),
        )
        val encoded = MochyLibraryExtraCodec.encode(extra)
        assertTrue(encoded.isNotBlank())
        assertTrue("Must stay under JWT budget", encoded.length <= MochyLibraryExtraCodec.MaxEncodedChars)

        val decoded = MochyLibraryExtraCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(true, decoded?.audio?.deviceProfilesEnabled)
        val decodedProfiles = DeviceAudioProfileEngine.decodeProfiles(decoded?.audio?.deviceProfilesJson)
        assertEquals(2, decodedProfiles.size)
        assertEquals(
            "Sony WH-1000XM5",
            decodedProfiles["bt:sony wh-1000xm5"]?.snapshot?.eq?.correction?.model,
        )
        assertTrue(decodedProfiles["bt:sony wh-1000xm5"]?.snapshot?.eightD?.enabled == true)
    }
}
