package com.auramusic.core.importer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas unitarias de detección real de formato de imagen para carátulas embebidas.
 */
class AudioImporterFormatTest {
    @Test
    fun detectsPngJpegWebpGifAndBmpMagicBytes() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00,
        )
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.US_ASCII)
        val gif = "GIF89a".toByteArray(Charsets.US_ASCII)
        val bmp = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 0x00, 0x00)
        val unknown = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        assertEquals("png", AudioImporter.detectImageExtension(png))
        assertEquals("jpg", AudioImporter.detectImageExtension(jpg))
        assertEquals("webp", AudioImporter.detectImageExtension(webp))
        assertEquals("gif", AudioImporter.detectImageExtension(gif))
        assertEquals("bmp", AudioImporter.detectImageExtension(bmp))
        assertEquals("img", AudioImporter.detectImageExtension(unknown))
    }
}
