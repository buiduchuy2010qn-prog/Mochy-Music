package com.mochyy.mochymusic.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/** Verifies Mochy Music's Vietnamese localization integrity across values-vi and values-vi-rVN. */
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
            val bytes = vi.readBytes()
            val hasBom = bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            assertFalse("UTF-8 BOM forbidden in ${vi.name}", hasBom)
            assertEquals("SHA-256 mismatch for ${vi.name}", sha256(vi), sha256(vn))
        }
    }

    @Test
    fun smartFeaturesStringsHaveCompleteVietnameseCoverageAndMatchingPlaceholders() {
        val en = parseStrings(File(resDir, "values/smart_features_strings.xml"))
        val vi = parseStrings(File(resDir, "values-vi/smart_features_strings.xml"))

        assertEquals("Missing or extra Vietnamese keys in smart_features_strings.xml", en.keys, vi.keys)
        for (key in en.keys) {
            val enEl = en.getValue(key)
            val viEl = vi.getValue(key)
            assertEquals("Element tag mismatch for $key", enEl.tagName, viEl.tagName)
            assertEquals("Format placeholder mismatch for $key", placeholders(enEl), placeholders(viEl))
            assertTrue("Blank Vietnamese string for $key", viEl.textContent.isNotBlank())
        }
    }

    @Test
    fun recapStringsHaveCompleteVietnameseCoverageAndMatchingPlaceholders() {
        val en = parseStrings(File(resDir, "values/recap_strings.xml"))
        val vi = parseStrings(File(resDir, "values-vi/recap_strings.xml"))

        assertEquals("Missing or extra Vietnamese keys in recap_strings.xml", en.keys, vi.keys)
        for (key in en.keys) {
            val enEl = en.getValue(key)
            val viEl = vi.getValue(key)
            assertEquals("Element tag mismatch for $key", enEl.tagName, viEl.tagName)
            assertEquals("Format placeholder mismatch for $key", placeholders(enEl), placeholders(viEl))
            assertTrue("Blank Vietnamese string for $key", viEl.textContent.isNotBlank())
        }
    }
}
