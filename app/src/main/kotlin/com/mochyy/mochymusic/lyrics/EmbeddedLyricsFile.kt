// EmbeddedLyricsFile.kt

package com.mochyy.mochymusic.lyrics

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.mochyy.mochymusic.importer.LocalFileNames
import com.mochyy.mochymusic.importer.LocalFolders
import com.mochyy.mochymusic.importer.LocalSidecars
import com.mochyy.mochymusic.songedit.LyricsFileReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** The lyrics in the file at [uri], as [EmbeddedLyrics.find] reads them; null when none or unreadable. */
fun readEmbeddedLyrics(context: Context, uri: Uri): String? = runCatching {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
    // the stream owns the descriptor and closes it once, with the channel: closing the raw file
    // descriptor and the ParcelFileDescriptor separately is a double close Android can abort on
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel.use { channel ->
        EmbeddedLyrics.find(ChannelSource(channel))
    }
}.getOrNull()

private class ChannelSource(private val channel: FileChannel) : EmbeddedLyrics.Source {
    override val size: Long = channel.size()

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || position >= size || length <= 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(minOf(length.toLong(), size - position).toInt())
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read <= 0) break
            at += read
        }
        return buffer.array().copyOf(buffer.position())
    }
}

/**
 * Reads the best offline lyrics for a local audio [uri]: compares embedded tag lyrics with any
 * sidecar `.lrc` / `.elrc` / `.ttml` / `.srt` / `.krc` / `.txt` file sitting next to the audio
 * file (via `file://`, MediaStore `DATA` path, SAF document tree, or saved `LocalFolders`).
 */
fun readLocalSongLyrics(context: Context, uri: Uri): String? {
    val embedded = readEmbeddedLyrics(context, uri)
    val sidecar = readSidecarLyrics(context, uri)
    return LocalSidecars.betterLyrics(embedded = embedded, beside = sidecar)
}

/** Finds and parses a sidecar lyrics file next to [audioUri] without network access. */
fun readSidecarLyrics(context: Context, audioUri: Uri): String? = runCatching {
    findSidecarFileOnDisk(context, audioUri)?.let { file ->
        if (file.length() in 1..1_000_000L) {
            LyricsFileReader.parse(file.readBytes())?.let { return@runCatching it }
        }
    }
    findSidecarUriInSaf(context, audioUri)?.let { lrcUri ->
        context.contentResolver.openInputStream(lrcUri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.isNotEmpty() && bytes.size <= 1_000_000) {
                LyricsFileReader.parse(bytes)?.let { return@runCatching it }
            }
        }
    }
    null
}.getOrNull()

private val SidecarExtensions = listOf("lrc", "elrc", "lrcx", "ttml", "srt", "vtt", "krc", "yrc", "qrc", "txt")

private fun findSidecarFileOnDisk(context: Context, audioUri: Uri): File? {
    val audioFile = when (audioUri.scheme) {
        "file" -> audioUri.path?.let(::File)
        "content" -> queryMediaStoreFilePath(context, audioUri)?.let(::File)
        else -> null
    } ?: return null
    val parent = audioFile.parentFile?.takeIf { it.isDirectory } ?: return null
    val baseName = LocalFileNames.withoutExtension(audioFile.name)
    for (ext in SidecarExtensions) {
        val exact = File(parent, "$baseName.$ext")
        if (exact.isFile && exact.canRead()) return exact
    }
    val targetKey = LocalFileNames.matchKey(audioFile.name)
    val siblings = parent.listFiles() ?: return null
    return siblings
        .filter { it.isFile && it.canRead() && LocalFileNames.kind(it.name) == LocalFileNames.Kind.Lyrics && LocalFileNames.matchKey(it.name) == targetKey }
        .minByOrNull { if (it.name.endsWith(".txt", ignoreCase = true)) 1 else 0 }
}

@Suppress("DEPRECATION")
private fun queryMediaStoreFilePath(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
    }
}.getOrNull()

private fun findSidecarUriInSaf(context: Context, audioUri: Uri): Uri? {
    if (audioUri.scheme == "content" && DocumentsContract.isDocumentUri(context, audioUri)) {
        val docId = runCatching { DocumentsContract.getDocumentId(audioUri) }.getOrNull()
        val slash = docId?.lastIndexOf('/') ?: -1
        if (docId != null && slash > 0) {
            val parentDocId = docId.substring(0, slash)
            val fileName = docId.substring(slash + 1)
            val baseName = LocalFileNames.withoutExtension(fileName)
            val authority = audioUri.authority
            for (tree in LocalFolders.read(context)) {
                if (tree.authority != authority) continue
                for (ext in SidecarExtensions) {
                    val candidateDocId = "$parentDocId/$baseName.$ext"
                    val candidateUri = DocumentsContract.buildDocumentUriUsingTree(tree, candidateDocId)
                    val exists = runCatching {
                        context.contentResolver.openFileDescriptor(candidateUri, "r")?.use { true } ?: false
                    }.getOrDefault(false)
                    if (exists) return candidateUri
                }
            }
        }
    }
    val audioName = LocalFileNames.of(context, audioUri) ?: return null
    val targetKey = LocalFileNames.matchKey(audioName)
    if (targetKey.isBlank()) return null
    return LocalFolders.scanSaved(context)
        .firstOrNull { it.uri == audioUri || (it.name != null && LocalFileNames.matchKey(it.name) == targetKey) }
        ?.lyrics
}
