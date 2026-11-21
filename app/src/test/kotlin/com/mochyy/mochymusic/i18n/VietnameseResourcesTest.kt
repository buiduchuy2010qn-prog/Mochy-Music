package com.mochyy.mochymusic.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/** Verifies Mochy Music's Vietnamese localization integrity across values, values-vi and values-vi-rVN. */
class VietnameseResourcesTest {

    private val resDir = listOf(File("src/main/res"), File("app/src/main/res"))
        .first { it.isDirectory }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun parseStrings(file: File): Map<String, Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(file).documentElement.childNodes
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
            .filter { it.tagName in setOf("string", "plurals") && it.getAttribute("translatable") != "false" }
            .associateBy { it.getAttribute("name") }
    }

    private fun placeholders(element: Element): List<String> {
        val regex = Regex("%(?!%)(?:\\d+\\$)?[a-zA-Z]")
        val text = if (element.tagName == "plurals") {
            val nodes = element.childNodes
            (0 until nodes.length)
                .mapNotNull { nodes.item(it) as? Element }
                .first { it.getAttribute("quantity") == "other" }
                .textContent
        } else {
            element.textContent
        }
        return regex.findAll(text).map { it.value }.sorted().toList()
    }

    private fun assertNoUtf8Bom(file: File) {
        val bytes = file.readBytes()
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        assertFalse("UTF-8 BOM forbidden in ${file.parentFile.name}/${file.name}", hasBom)
    }

    private fun assertXmlParity(fileName: String, exactKeys: Boolean = true) {
        val enFile = File(resDir, "values/$fileName")
        val viFile = File(resDir, "values-vi/$fileName")
        assertTrue("Missing base file $fileName", enFile.isFile)
        assertTrue("Missing VI file $fileName", viFile.isFile)
        assertNoUtf8Bom(enFile)
        assertNoUtf8Bom(viFile)

        val en = parseStrings(enFile)
        val vi = parseStrings(viFile)

        val missing = en.keys - vi.keys
        assertTrue("Missing Vietnamese keys in $fileName: $missing", missing.isEmpty())
        if (exactKeys) {
            assertEquals("Key mismatch in $fileName", en.keys, vi.keys)
        }

        for (key in en.keys) {
            val enEl = en.getValue(key)
            val viEl = vi.getValue(key)
            assertEquals("Element tag mismatch for $key in $fileName", enEl.tagName, viEl.tagName)
            assertEquals("Format placeholder mismatch for $key in $fileName", placeholders(enEl), placeholders(viEl))
            assertTrue("Blank Vietnamese string for $key in $fileName", viEl.textContent.isNotBlank())
        }
    }

    @Test
    fun valuesViAndValuesViRVnAreByteIdenticalAndWithoutBom() {
        val viDir = File(resDir, "values-vi")
        val vnDir = File(resDir, "values-vi-rVN")
        val viFiles = viDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        val vnFiles = vnDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }

        assertTrue("values-vi must not be empty", viFiles.isNotEmpty())
        assertEquals(viFiles.map { it.name }, vnFiles.map { it.name })

        for (i in viFiles.indices) {
            val vi = viFiles[i]
            val vn = vnFiles[i]
            assertNoUtf8Bom(vi)
            assertEquals("SHA-256 mismatch for ${vi.name}", sha256(vi), sha256(vn))
        }
    }

    @Test
    fun smartFeaturesStringsHaveCompleteVietnameseCoverageAndMatchingPlaceholders() {
        assertXmlParity("smart_features_strings.xml", exactKeys = true)
    }

    @Test
    fun recapStringsHaveCompleteVietnameseCoverageAndMatchingPlaceholders() {
        assertXmlParity("recap_strings.xml", exactKeys = true)
        val viRecap = parseStrings(File(resDir, "values-vi/recap_strings.xml"))
        val unlockDate = viRecap.getValue("rc_not_yet_unlock_date").textContent
        assertFalse("rc_not_yet_unlock_date must not duplicate 'tháng': $unlockDate", unlockDate.contains("tháng %1\$s"))
    }

    @Test
    fun allPairedXmlFilesHaveZeroMissingKeysAndValidPlaceholders() {
        val viFiles = File(resDir, "values-vi").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "xml" }
            .sortedBy { it.name }
        assertTrue(viFiles.isNotEmpty())

        val strictFiles = setOf(
            "content_screens_strings.xml",
            "updater_strings.xml",
            "profile_strings.xml",
            "player_core_strings.xml",
            "player_extra_strings.xml",
            "strings.xml",
        )
        for (viFile in viFiles) {
            assertXmlParity(viFile.name, exactKeys = viFile.name in strictFiles)
        }
    }

    @Test
    fun baseAndVietnameseXmlsContainNoBrandingRegressions() {
        val forbidden = listOf("mochyEqualizer", "mochydh", "mochyDH", "realidkroo")
        val folders = listOf(File(resDir, "values"), File(resDir, "values-vi"))
        for (dir in folders) {
            val xmls = dir.listFiles().orEmpty().filter { it.isFile && it.extension == "xml" }
            for (xml in xmls) {
                val raw = xml.readText(Charsets.UTF_8)
                for (token in forbidden) {
                    assertFalse("Forbidden branding token '$token' found in ${dir.name}/${xml.name}", raw.contains(token))
                }
            }
        }
    }

    @Test
    fun noDuplicateResourceNamesAcrossXmlFiles() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val folders = listOf(File(resDir, "values"), File(resDir, "values-vi"))
        for (dir in folders) {
            val seenStrings = mutableMapOf<String, String>()
            val seenPlurals = mutableMapOf<String, String>()
            val xmls = dir.listFiles().orEmpty().filter { it.isFile && it.extension == "xml" }.sortedBy { it.name }
            for (xml in xmls) {
                val nodes = factory.newDocumentBuilder().parse(xml).documentElement.childNodes
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as? Element ?: continue
                    val name = el.getAttribute("name")
                    if (name.isBlank()) continue
                    when (el.tagName) {
                        "string" -> {
                            val prev = seenStrings.put(name, xml.name)
                            assertTrue(
                                "Duplicate <string name=\"$name\"> in ${dir.name}: $prev and ${xml.name}",
                                prev == null,
                            )
                        }
                        "plurals" -> {
                            val prev = seenPlurals.put(name, xml.name)
                            assertTrue(
                                "Duplicate <plurals name=\"$name\"> in ${dir.name}: $prev and ${xml.name}",
                                prev == null,
                            )
                        }
                    }
                }
            }
        }
    }
}
