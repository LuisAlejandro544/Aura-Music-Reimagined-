package com.auramusic.core.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias puras para validación de URLs y traducción legible de errores de descarga.
 */
class YtDlpUrlValidatorTest {
    @Test
    fun acceptsValidHttpAndHttpsUrlsAndRejectsMalformedInputs() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            YtDlpAudioDownloader.normalizeAndValidateUrl("  https://www.youtube.com/watch?v=dQw4w9WgXcQ  "),
        )
        assertEquals(
            "http://youtu.be/abc123",
            YtDlpAudioDownloader.normalizeAndValidateUrl("http://youtu.be/abc123"),
        )
        assertNull(YtDlpAudioDownloader.normalizeAndValidateUrl(""))
        assertNull(YtDlpAudioDownloader.normalizeAndValidateUrl("ftp://example.com/audio.mp3"))
        assertNull(YtDlpAudioDownloader.normalizeAndValidateUrl("https://youtube.com/watch?v=a b"))
    }

    @Test
    fun humanizesCommonYouTubeRestrictionErrors() {
        val botMsg = YtDlpAudioDownloader.humanizeDownloadError("ERROR: Sign in to confirm you're not a bot")
        assertTrue(botMsg.contains("verificación antibot"))

        val forbiddenMsg = YtDlpAudioDownloader.humanizeDownloadError("HTTP Error 403: Forbidden")
        assertTrue(forbiddenMsg.contains("403"))
    }
}
