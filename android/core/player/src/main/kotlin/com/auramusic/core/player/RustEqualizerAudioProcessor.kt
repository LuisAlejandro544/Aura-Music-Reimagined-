package com.auramusic.core.player

import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.auramusic.core.rust.AuraRustBridge
import java.nio.ByteBuffer

/**
 * Procesador de audio Media3 conectado en tiempo real al ecualizador de 10 bandas en Rust.
 *
 * Opera directamente sobre los buffers directos PCM de ExoPlayer (16-bit entero y 32-bit flotante)
 * mediante JNI sin copias adicionales ni bloqueos en el hilo principal.
 */
@OptIn(UnstableApi::class)
class RustEqualizerAudioProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }

        AuraRustBridge.configureEqualizer(
            sampleRate = inputAudioFormat.sampleRate,
            channelCount = inputAudioFormat.channelCount,
        )
        return inputAudioFormat
    }

    @SuppressLint("SwitchIntDef")
    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()

        if (outputBuffer.isDirect) {
            when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> AuraRustBridge.processEqualizerPcm16(outputBuffer, remaining)
                C.ENCODING_PCM_FLOAT -> AuraRustBridge.processEqualizerPcmFloat(outputBuffer, remaining)
                else -> Unit
            }
        }
    }

    // Media3 1.11.1 deprecó onFlush() sin argumentos; usar StreamMetadata asegura el reinicio de filtros DSP.
    override fun onFlush(streamMetadata: StreamMetadata) {
        AuraRustBridge.resetEqualizerState()
    }

    override fun onReset() {
        AuraRustBridge.resetEqualizerState()
    }
}
