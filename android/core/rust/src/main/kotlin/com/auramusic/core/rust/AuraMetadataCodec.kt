package com.auramusic.core.rust

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodificador binario puro (sin dependencias JNI) para el buffer de metadatos
 * generado por `aura_core::metadata` en Rust.
 *
 * Separado de [AuraRustBridge] para permitir pruebas unitarias rápidas en JVM
 * sin cargar la librería nativa `.so`.
 */
object AuraMetadataCodec {
    fun decode(raw: ByteArray): RustAudioMetadata? {
        if (raw.size < 4) return null
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val status = buffer.int
        if (status != 1 || buffer.remaining() < 8) return null

        val durationMs = buffer.long.coerceAtLeast(0L)
        val title = readLengthPrefixedString(buffer)
        val artist = readLengthPrefixedString(buffer)
        val album = readLengthPrefixedString(buffer)

        if (buffer.remaining() < 4) return null
        val coverLen = buffer.int
        val coverBytes = if (coverLen > 0 && buffer.remaining() >= coverLen) {
            ByteArray(coverLen).also { buffer.get(it) }
        } else {
            null
        }

        return RustAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            embeddedCoverBytes = coverBytes,
        )
    }

    private fun readLengthPrefixedString(buffer: ByteBuffer): String? {
        if (buffer.remaining() < 4) return null
        val len = buffer.int
        if (len <= 0) return null
        if (buffer.remaining() < len) return null
        val bytes = ByteArray(len)
        buffer.get(bytes)
        return bytes.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }
}
