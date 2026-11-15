package com.auramusic.core.rust

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Resultado de metadatos y carátula embebida extraídos directamente por el motor Rust (`lofty`).
 */
data class RustAudioMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val embeddedCoverBytes: ByteArray?,
)

/**
 * Puente Kotlin <-> Rust (`libaura_core.so`).
 *
 * Toda la lógica de lectura de metadatos (ID3/Vorbis/FLAC/MP4/WAV), generación procedural
 * de carátulas y cálculo criptográfico de huellas se ejecuta directamente en Rust (sin fallbacks
 * en Kotlin), despachando el trabajo pesado en [Dispatchers.Default] y la escritura de archivos
 * en [Dispatchers.IO] para no bloquear jamás el hilo principal de la interfaz.
 */
object AuraRustBridge {
    init {
        System.loadLibrary("aura_core")
    }

    private external fun nativeGetEngineInfo(): String

    private external fun nativeGetQuickJsVersion(): String

    private external fun nativeEvalQuickJs(script: String): String

    private external fun nativeGenerateCoverRgba(
        seedKey: String,
        width: Int,
        height: Int,
        accentArgb: Int,
    ): ByteArray

    private external fun nativeComputeTrackFingerprint(
        title: String,
        artist: String,
        durationMs: Long,
        sizeBytes: Long,
    ): String

    private external fun nativeExtractAudioMetadata(fd: Int): ByteArray

    private external fun nativeSetEqualizerConfig(enabled: Boolean, gainsDb: FloatArray)

    private external fun nativeSetAudioEffectsConfig(
        reverbEnabled: Boolean,
        reverbRoomSize: Float,
        reverbDamping: Float,
        reverbWetMix: Float,
        reverbWidth: Float,
        slowedWarmthEnabled: Boolean,
        slowedWarmthAmount: Float,
        spatial8dEnabled: Boolean,
        spatial8dSpeed: Float,
        spatial8dDepth: Float,
        crossfeedEnabled: Boolean,
        crossfeedStrength: Float,
        crossfeedAngle: Float,
    )

    private external fun nativeConfigureEqualizer(sampleRate: Int, channelCount: Int)

    private external fun nativeResetEqualizerState()

    private external fun nativeProcessEqualizerPcm16(buffer: ByteBuffer, byteLength: Int): Boolean

    private external fun nativeProcessEqualizerPcmFloat(buffer: ByteBuffer, byteLength: Int): Boolean

    private external fun nativeRenderDeleteEffectFrame(
        sourceBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        width: Int,
        height: Int,
        effectType: Int,
        progress: Float,
        seed: Int,
        accentArgb: Int,
    ): Boolean

    /**
     * Devuelve la información del motor nativo Rust compilado para la arquitectura actual.
     */
    fun getEngineInfo(): String = nativeGetEngineInfo()

    /**
     * Devuelve la versión del runtime QuickJS-NG compilado dentro de Rust.
     */
    fun getQuickJsVersion(): String = nativeGetQuickJsVersion()

    /**
     * Evalúa JavaScript en QuickJS-NG dentro de Rust fuera del hilo principal.
     */
    suspend fun evalQuickJs(script: String): String = withContext(Dispatchers.Default) {
        nativeEvalQuickJs(script)
    }

    /**
     * Resuelve la ruta ejecutable del binario `libaura_qjs.so` dentro de `nativeLibraryDir`,
     * cumpliendo con la política SELinux W^X de Android 10+ para ejecutar QuickJS desde `yt-dlp`.
     */
    fun resolveQuickJsBinaryPath(context: Context): String? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        val candidate = File(nativeDir, "libaura_qjs.so")
        return candidate.takeIf { it.exists() }?.absolutePath
    }

    /**
     * Lee etiquetas, duración y carátula embebida directamente en Rust a partir de un
     * descriptor de archivo (`fd`) abierto por SAF. Devuelve `null` si el archivo no es válido.
     */
    suspend fun extractAudioMetadata(fd: Int): RustAudioMetadata? = withContext(Dispatchers.Default) {
        if (fd < 0) return@withContext null
        val encoded = nativeExtractAudioMetadata(fd)
        AuraMetadataCodec.decode(encoded)
    }

    /**
     * Calcula en Rust la huella digital SHA-256 de una pista en un hilo de cómputo.
     */
    suspend fun computeTrackFingerprint(
        title: String,
        artist: String,
        durationMs: Long,
        sizeBytes: Long,
    ): String = withContext(Dispatchers.Default) {
        nativeComputeTrackFingerprint(title, artist, durationMs, sizeBytes)
    }

    /**
     * Genera en Rust una carátula procedural cuadrada y la guarda como imagen PNG en caché local.
     * Devuelve la ruta absoluta del archivo generado para cargarlo de forma fluida con Coil.
     */
    suspend fun generateAndSaveProceduralCover(
        context: Context,
        fingerprint: String,
        seedKey: String,
        accentArgb: Int = 0,
        sizePx: Int = 640,
        outputDirectory: File? = null,
    ): String = withContext(Dispatchers.Default) {
        val coversDir = (outputDirectory ?: File(context.filesDir, "covers")).apply { mkdirs() }
        val outputFile = File(coversDir, "proc_${fingerprint.take(24)}.png")
        if (outputFile.exists() && outputFile.length() > 0L) {
            return@withContext outputFile.absolutePath
        }

        val rgbaBytes = nativeGenerateCoverRgba(
            seedKey = seedKey,
            width = sizePx,
            height = sizePx,
            accentArgb = accentArgb,
        )
        require(rgbaBytes.size == sizePx * sizePx * 4) {
            "Tamaño de buffer RGBA inesperado desde Rust: ${rgbaBytes.size}"
        }

        val bitmap = createBitmap(sizePx, sizePx)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgbaBytes))

        withContext(Dispatchers.IO) {
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            bitmap.recycle()
        }

        outputFile.absolutePath
    }

    /**
     * Actualiza la configuración del ecualizador de 10 bandas en el motor nativo Rust.
     */
    suspend fun setEqualizerConfig(enabled: Boolean, gainsDb: List<Float>) =
        withContext(Dispatchers.Default) {
            val array = FloatArray(10) { idx -> gainsDb.getOrElse(idx) { 0f } }
            nativeSetEqualizerConfig(enabled, array)
        }

    /**
     * Actualiza la configuración de Reverberación estéreo, Calidez Slowed, Audio 8D y Crossfeed binaural en el motor nativo Rust.
     */
    suspend fun setAudioEffectsConfig(
        reverbEnabled: Boolean,
        reverbRoomSize: Float,
        reverbDamping: Float,
        reverbWetMix: Float,
        reverbWidth: Float,
        slowedWarmthEnabled: Boolean,
        slowedWarmthAmount: Float,
        spatial8dEnabled: Boolean,
        spatial8dSpeed: Float,
        spatial8dDepth: Float,
        crossfeedEnabled: Boolean = false,
        crossfeedStrength: Float = 0.45f,
        crossfeedAngle: Float = 0.50f,
    ) = withContext(Dispatchers.Default) {
        nativeSetAudioEffectsConfig(
            reverbEnabled = reverbEnabled,
            reverbRoomSize = reverbRoomSize,
            reverbDamping = reverbDamping,
            reverbWetMix = reverbWetMix,
            reverbWidth = reverbWidth,
            slowedWarmthEnabled = slowedWarmthEnabled,
            slowedWarmthAmount = slowedWarmthAmount,
            spatial8dEnabled = spatial8dEnabled,
            spatial8dSpeed = spatial8dSpeed,
            spatial8dDepth = spatial8dDepth,
            crossfeedEnabled = crossfeedEnabled,
            crossfeedStrength = crossfeedStrength,
            crossfeedAngle = crossfeedAngle,
        )
    }

    /**
     * Configura el formato de audio en el procesador DSP de Rust (llamado desde ExoPlayer).
     */
    fun configureEqualizer(sampleRate: Int, channelCount: Int) {
        nativeConfigureEqualizer(sampleRate, channelCount)
    }

    /**
     * Reinicia el historial interno de los filtros biquad al hacer seek o limpiar el buffer.
     */
    fun resetEqualizerState() {
        nativeResetEqualizerState()
    }

    /**
     * Ecualiza un DirectByteBuffer PCM de 16 bits little-endian in-place en Rust.
     * Solo debe invocarse desde el hilo interno de reproducción de ExoPlayer.
     */
    fun processEqualizerPcm16(buffer: ByteBuffer, byteLength: Int): Boolean {
        if (!buffer.isDirect || byteLength <= 0) return false
        return nativeProcessEqualizerPcm16(buffer, byteLength)
    }

    /**
     * Ecualiza un DirectByteBuffer PCM de 32 bits flotante little-endian in-place en Rust.
     * Solo debe invocarse desde el hilo interno de reproducción de ExoPlayer.
     */
    fun processEqualizerPcmFloat(buffer: ByteBuffer, byteLength: Int): Boolean {
        if (!buffer.isDirect || byteLength <= 0) return false
        return nativeProcessEqualizerPcmFloat(buffer, byteLength)
    }

    /**
     * Procesa en Rust un fotograma del efecto de eliminación (cenizas, papel quemándose o
     * aura extraña) sobre DirectByteBuffers RGBA8888 sin bloquear el hilo principal.
     */
    suspend fun renderDeleteEffectFrame(
        sourceBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        width: Int,
        height: Int,
        effectType: Int,
        progress: Float,
        seed: Int,
        accentArgb: Int,
    ): Boolean = withContext(Dispatchers.Default) {
        if (!sourceBuffer.isDirect || !outputBuffer.isDirect || width <= 0 || height <= 0) {
            return@withContext false
        }
        nativeRenderDeleteEffectFrame(
            sourceBuffer = sourceBuffer,
            outputBuffer = outputBuffer,
            width = width,
            height = height,
            effectType = effectType,
            progress = progress,
            seed = seed,
            accentArgb = accentArgb,
        )
    }
}
