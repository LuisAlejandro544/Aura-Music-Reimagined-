package com.auramusic.core.rust

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Pruebas unitarias del protocolo binario JNI entre Rust (`aura_core::metadata`) y Kotlin.
 */
class AuraMetadataCodecTest {

    @Test
    fun decodesValidPayloadWithTagsAndCover() {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(1)
            out.writeLong(185_000L)
            val title = "Aura".toByteArray(Charsets.UTF_8)
            out.writeInt(title.size)
            out.write(title)
            out.writeInt(-1) // artist null
            val album = "Pulse".toByteArray(Charsets.UTF_8)
            out.writeInt(album.size)
            out.write(album)
            val cover = byteArrayOf(0x01, 0x02, 0x03)
            out.writeInt(cover.size)
            out.write(cover)
        }

        val parsed = AuraMetadataCodec.decode(baos.toByteArray())
        assertNotNull(parsed)
        assertEquals("Aura", parsed?.title)
        assertNull(parsed?.artist)
        assertEquals("Pulse", parsed?.album)
        assertEquals(185_000L, parsed?.durationMs)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), parsed?.embeddedCoverBytes)
    }

    @Test
    fun returnsNullOnErrorStatusOrTruncatedPayload() {
        assertNull(AuraMetadataCodec.decode(byteArrayOf(0, 0, 0, 0)))
        assertNull(AuraMetadataCodec.decode(byteArrayOf(0, 1)))
    }
}
