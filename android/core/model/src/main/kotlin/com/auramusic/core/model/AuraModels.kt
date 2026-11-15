package com.auramusic.core.model

/**
 * Representa una canción local importada explícitamente por el usuario en Aura Music.
 *
 * @property id Identificador único en la base de datos Room.
 * @property contentUri URI persistente (SAF) del archivo de audio en el almacenamiento del teléfono.
 * @property title Título de la pista (o nombre del archivo sin extensión si no hay etiqueta ID3).
 * @property artist Nombre del artista extraído de metadatos o "Artista desconocido".
 * @property album Nombre del álbum extraído de metadatos o "Álbum local".
 * @property durationMs Duración total de la pista en milisegundos.
 * @property coverFilePath Ruta local en caché de la carátula (embebida o generada proceduralmente en Rust).
 * @property isProceduralCover Indica si la carátula fue generada proceduralmente por Rust.
 * @property isLiked Marca de "Me gusta" (corazón) estilo biblioteca favorita.
 * @property trackFingerprint Huella SHA-256 calculada en Rust para evitar duplicados.
 * @property addedAt Epoch en milisegundos de cuándo se importó la canción.
 */
data class Track(
    val id: Long = 0L,
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverFilePath: String?,
    val isProceduralCover: Boolean,
    val isLiked: Boolean = false,
    val trackFingerprint: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * Representa una playlist creada por el usuario.
 *
 * @property id Identificador único en Room.
 * @property name Nombre visible de la playlist.
 * @property trackCount Número de canciones vinculadas a la playlist.
 * @property coverFilePath Carátula representativa (primera pista con carátula).
 * @property createdAt Fecha de creación en milisegundos.
 */
data class Playlist(
    val id: Long = 0L,
    val name: String,
    val trackCount: Int = 0,
    val coverFilePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Modos de repetición soportados por el reproductor Media3 ExoPlayer.
 */
enum class RepeatMode {
    OFF,
    ALL,
    ONE,
}

/**
 * Estado inmutable y reactivo de la reproducción actual expuesto a toda la interfaz.
 */
data class PlaybackState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playbackSpeed: Float = PlaybackTuning.DEFAULT_PLAYBACK_SPEED,
    val playbackPitch: Float = PlaybackTuning.DEFAULT_PLAYBACK_PITCH,
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Estado de progreso durante la importación manual de canciones o carpetas completas.
 */
data class ImportProgress(
    val isImporting: Boolean = false,
    val processedFiles: Int = 0,
    val totalFiles: Int = 0,
    val importedCount: Int = 0,
    val skippedDuplicates: Int = 0,
    val lastMessage: String? = null,
)

/**
 * Paletas de color personalizables desde el apartado de Ajustes.
 * Al usuario no le gusta el minimalismo extremo, por lo que cada tema combina
 * un acento vibrante con contenedores tonales ricos y superficies oscuras con contraste.
 */
enum class AppAccentColor(
    val displayName: String,
    val subtitle: String,
    val primaryArgb: Long,
    val onPrimaryArgb: Long,
    val primaryContainerArgb: Long,
    val onPrimaryContainerArgb: Long,
    val secondaryArgb: Long,
    val surfaceTintedArgb: Long,
) {
    AMETHYST(
        displayName = "Amatista Aura",
        subtitle = "Violeta eléctrico profundo",
        primaryArgb = 0xFFA78BFA,
        onPrimaryArgb = 0xFF1E1038,
        primaryContainerArgb = 0xFF3B2768,
        onPrimaryContainerArgb = 0xFFEDE9FE,
        secondaryArgb = 0xFFC084FC,
        surfaceTintedArgb = 0xFF171324,
    ),
    CRIMSON(
        displayName = "Carmesí Pulso",
        subtitle = "Rojo intenso de estudio",
        primaryArgb = 0xFFFB7185,
        onPrimaryArgb = 0xFF3D0814,
        primaryContainerArgb = 0xFF6B182A,
        onPrimaryContainerArgb = 0xFFFFE4E6,
        secondaryArgb = 0xFFF43F5E,
        surfaceTintedArgb = 0xFF211216,
    ),
    EMERALD(
        displayName = "Esmeralda Neón",
        subtitle = "Verde vivo y contraste alto",
        primaryArgb = 0xFF34D399,
        onPrimaryArgb = 0xFF042F1F,
        primaryContainerArgb = 0xFF0E4D36,
        onPrimaryContainerArgb = 0xFFD1FAE5,
        secondaryArgb = 0xFF10B981,
        surfaceTintedArgb = 0xFF101D19,
    ),
    COBALT(
        displayName = "Cobalto Nocturno",
        subtitle = "Azul eléctrico espacial",
        primaryArgb = 0xFF60A5FA,
        onPrimaryArgb = 0xFF0A2245,
        primaryContainerArgb = 0xFF1B3B6F,
        onPrimaryContainerArgb = 0xFFDBEAFE,
        secondaryArgb = 0xFF38BDF8,
        surfaceTintedArgb = 0xFF101724,
    ),
    AMBER(
        displayName = "Ámbar Analógico",
        subtitle = "Dorado cálido tipo válvula",
        primaryArgb = 0xFFFBBF24,
        onPrimaryArgb = 0xFF382302,
        primaryContainerArgb = 0xFF5C3B09,
        onPrimaryContainerArgb = 0xFFFEF3C7,
        secondaryArgb = 0xFFF59E0B,
        surfaceTintedArgb = 0xFF201A10,
    ),
    CYAN(
        displayName = "Cian Sintético",
        subtitle = "Turquesa luminoso",
        primaryArgb = 0xFF22D3EE,
        onPrimaryArgb = 0xFF052B33,
        primaryContainerArgb = 0xFF0E4956,
        onPrimaryContainerArgb = 0xFFCFFAFE,
        secondaryArgb = 0xFF06B6D4,
        surfaceTintedArgb = 0xFF0F1D21,
    ),
    ROSE(
        displayName = "Magenta Vapor",
        subtitle = "Fucsia vibrante",
        primaryArgb = 0xFFF472B6,
        onPrimaryArgb = 0xFF3D0A28,
        primaryContainerArgb = 0xFF681947,
        onPrimaryContainerArgb = 0xFFFCE7F3,
        secondaryArgb = 0xFFEC4899,
        surfaceTintedArgb = 0xFF21121D,
    ),
    TANGERINE(
        displayName = "Mandarina Sunset",
        subtitle = "Naranja cálido enérgico",
        primaryArgb = 0xFFFB923C,
        onPrimaryArgb = 0xFF3D1903,
        primaryContainerArgb = 0xFF662E09,
        onPrimaryContainerArgb = 0xFFFFEDD5,
        secondaryArgb = 0xFFF97316,
        surfaceTintedArgb = 0xFF211710,
    );

    companion object {
        fun fromName(name: String?): AppAccentColor =
            entries.firstOrNull { it.name == name } ?: AMETHYST
    }
}

/**
 * Preset predefinido del ecualizador gráfico de 10 bandas (frecuencias ISO).
 */
data class EqualizerPreset(
    val id: String,
    val displayName: String,
    val gainsDb: List<Float>,
)

/**
 * Estado inmutable del ecualizador de 10 bandas procesado en tiempo real por Rust.
 */
data class EqualizerState(
    val isEnabled: Boolean = false,
    val bandGainsDb: List<Float> = List(BAND_COUNT) { 0f },
    val activePresetId: String = PRESET_FLAT_ID,
) {
    fun normalized(): EqualizerState {
        val safeGains = List(BAND_COUNT) { idx ->
            val raw = bandGainsDb.getOrElse(idx) { 0f }
            if (raw.isFinite()) raw.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) else 0f
        }
        return copy(bandGainsDb = safeGains)
    }

    companion object {
        const val BAND_COUNT = 10
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        const val PRESET_FLAT_ID = "flat"
        const val PRESET_CUSTOM_ID = "custom"

        val BAND_FREQUENCIES_HZ = listOf(
            31.25f, 62.5f, 125f, 250f, 500f,
            1000f, 2000f, 4000f, 8000f, 16000f,
        )

        val BAND_LABELS = listOf(
            "31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz",
            "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz",
        )

        val BAND_SHORT_LABELS = listOf(
            "31", "62", "125", "250", "500",
            "1k", "2k", "4k", "8k", "16k",
        )

        val PRESETS = listOf(
            EqualizerPreset(
                id = PRESET_FLAT_ID,
                displayName = "Plano",
                gainsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            ),
            EqualizerPreset(
                id = "bass_boost",
                displayName = "Graves+",
                gainsDb = listOf(6.5f, 5.5f, 4.0f, 2.0f, 0.5f, 0f, 0f, 0.5f, 1.0f, 1.0f),
            ),
            EqualizerPreset(
                id = "rock",
                displayName = "Rock",
                gainsDb = listOf(4.5f, 3.5f, 2.0f, 0.5f, -1.0f, -0.5f, 1.5f, 3.0f, 4.0f, 4.5f),
            ),
            EqualizerPreset(
                id = "pop",
                displayName = "Pop",
                gainsDb = listOf(-1.0f, 1.5f, 3.5f, 4.0f, 2.5f, 0f, -1.0f, -1.0f, 1.5f, 2.5f),
            ),
            EqualizerPreset(
                id = "electronic",
                displayName = "Electrónica",
                gainsDb = listOf(5.5f, 4.5f, 2.5f, 0f, -1.5f, 1.0f, 2.0f, 3.5f, 4.5f, 5.0f),
            ),
            EqualizerPreset(
                id = "acoustic",
                displayName = "Acústico",
                gainsDb = listOf(2.5f, 2.0f, 1.0f, 0.5f, 1.5f, 1.5f, 2.5f, 3.0f, 2.5f, 1.5f),
            ),
            EqualizerPreset(
                id = "vocal",
                displayName = "Vocal",
                gainsDb = listOf(-2.5f, -2.0f, -1.0f, 1.5f, 3.5f, 4.5f, 4.0f, 2.5f, 0.5f, -0.5f),
            ),
            EqualizerPreset(
                id = "classical",
                displayName = "Clásica",
                gainsDb = listOf(3.0f, 2.0f, 1.0f, 0.5f, -0.5f, -0.5f, 0.5f, 2.0f, 3.0f, 3.5f),
            ),
        )

        fun findPresetById(id: String?): EqualizerPreset? =
            PRESETS.firstOrNull { it.id == id }
    }
}

/**
 * Representa una playlist generada automáticamente agrupando canciones por el nombre del artista.
 *
 * @property artistKey Clave normalizada del artista (minúsculas y espacios colapsados) para agrupar y navegar.
 * @property artistName Nombre visible del artista elegido entre las pistas del grupo.
 * @property tracks Lista ordenada de canciones pertenecientes únicamente a este artista.
 * @property coverFilePath Carátula representativa del artista (prioriza portadas reales sobre procedurales).
 */
data class ArtistPlaylist(
    val artistKey: String,
    val artistName: String,
    val tracks: List<Track>,
    val coverFilePath: String? = null,
) {
    val trackCount: Int
        get() = tracks.size

    val totalDurationMs: Long
        get() = tracks.sumOf { it.durationMs.coerceAtLeast(0L) }
}

/**
 * Constructor puro y determinista de playlists automáticas por artista.
 */
object ArtistPlaylistBuilder {
    private val whitespaceRegex = Regex("\\s+")

    /**
     * Normaliza el nombre del artista para agrupar pistas con distinto uso de mayúsculas o espacios.
     */
    fun normalizeArtistKey(rawArtist: String): String {
        val cleaned = rawArtist.trim().replace(whitespaceRegex, " ")
        return if (cleaned.isEmpty()) {
            "artista desconocido"
        } else {
            cleaned.lowercase()
        }
    }

    /**
     * Agrupa automáticamente las canciones por nombre de artista.
     * Si varias canciones comparten el mismo artista, quedan dentro de la misma playlist automática.
     */
    fun buildFromTracks(tracks: List<Track>): List<ArtistPlaylist> {
        if (tracks.isEmpty()) return emptyList()

        return tracks
            .groupBy { normalizeArtistKey(it.artist) }
            .map { (key, groupTracks) ->
                val displayName = resolveDisplayArtistName(groupTracks)
                val sortedTracks = groupTracks.sortedWith(
                    compareBy<Track> { it.title.lowercase() }.thenByDescending { it.addedAt },
                )
                val cover = sortedTracks.firstOrNull { !it.isProceduralCover && !it.coverFilePath.isNullOrBlank() }
                    ?.coverFilePath
                    ?: sortedTracks.firstOrNull { !it.coverFilePath.isNullOrBlank() }?.coverFilePath

                ArtistPlaylist(
                    artistKey = key,
                    artistName = displayName,
                    tracks = sortedTracks,
                    coverFilePath = cover,
                )
            }
            .sortedWith(
                compareByDescending<ArtistPlaylist> { it.trackCount }
                    .thenBy { it.artistName.lowercase() },
            )
    }

    fun findByKey(tracks: List<Track>, artistKey: String): ArtistPlaylist? {
        if (artistKey.isBlank()) return null
        val targetKey = normalizeArtistKey(artistKey)
        return buildFromTracks(tracks).firstOrNull { it.artistKey == targetKey }
    }

    private fun resolveDisplayArtistName(tracks: List<Track>): String {
        val candidates = tracks
            .map { it.artist.trim().replace(whitespaceRegex, " ") }
            .filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return "Artista desconocido"

        return candidates
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first()
            .key
    }
}

/**
 * Operaciones puras y seguras de índice para manipular la cola de reproducción activa.
 */
object PlaybackQueueHelper {
    data class QueueMutationResult(
        val queue: List<Track>,
        val currentIndex: Int,
        val insertedAt: Int = -1,
    )

    fun insertPlayNext(
        queue: List<Track>,
        currentIndex: Int,
        track: Track,
    ): QueueMutationResult {
        if (queue.isEmpty()) {
            return QueueMutationResult(queue = listOf(track), currentIndex = 0, insertedAt = 0)
        }
        val safeCurrent = currentIndex.coerceIn(0, queue.lastIndex)
        val insertIndex = (safeCurrent + 1).coerceIn(0, queue.size)
        val updated = queue.toMutableList().apply {
            add(insertIndex, track)
        }
        return QueueMutationResult(queue = updated, currentIndex = safeCurrent, insertedAt = insertIndex)
    }

    fun appendToQueue(
        queue: List<Track>,
        currentIndex: Int,
        track: Track,
    ): QueueMutationResult {
        if (queue.isEmpty()) {
            return QueueMutationResult(queue = listOf(track), currentIndex = 0, insertedAt = 0)
        }
        val safeCurrent = currentIndex.coerceIn(0, queue.lastIndex)
        val updated = queue + track
        return QueueMutationResult(queue = updated, currentIndex = safeCurrent, insertedAt = updated.lastIndex)
    }

    fun moveItem(
        queue: List<Track>,
        currentIndex: Int,
        fromIndex: Int,
        toIndex: Int,
    ): QueueMutationResult {
        if (queue.isEmpty() ||
            fromIndex !in queue.indices ||
            toIndex !in queue.indices ||
            fromIndex == toIndex
        ) {
            return QueueMutationResult(queue = queue, currentIndex = currentIndex)
        }

        val updated = queue.toMutableList()
        val moved = updated.removeAt(fromIndex)
        updated.add(toIndex, moved)

        val nextCurrent = when {
            currentIndex == fromIndex -> toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }

        return QueueMutationResult(queue = updated, currentIndex = nextCurrent)
    }

    fun removeItem(
        queue: List<Track>,
        currentIndex: Int,
        removeIndex: Int,
    ): QueueMutationResult {
        if (queue.isEmpty() || removeIndex !in queue.indices) {
            return QueueMutationResult(queue = queue, currentIndex = currentIndex)
        }
        val updated = queue.toMutableList().apply { removeAt(removeIndex) }
        if (updated.isEmpty()) {
            return QueueMutationResult(queue = emptyList(), currentIndex = -1)
        }
        val nextCurrent = when {
            removeIndex < currentIndex -> (currentIndex - 1).coerceAtLeast(0)
            removeIndex == currentIndex -> currentIndex.coerceIn(0, updated.lastIndex)
            else -> currentIndex.coerceIn(0, updated.lastIndex)
        }
        return QueueMutationResult(queue = updated, currentIndex = nextCurrent)
    }

    fun clearUpcoming(
        queue: List<Track>,
        currentIndex: Int,
    ): QueueMutationResult {
        if (queue.isEmpty()) return QueueMutationResult(emptyList(), -1)
        val safeCurrent = currentIndex.coerceIn(0, queue.lastIndex)
        val updated = queue.subList(0, safeCurrent + 1).toList()
        return QueueMutationResult(queue = updated, currentIndex = safeCurrent)
    }
}

/**
 * Estados del ciclo de vida de descarga de audio desde YouTube mediante yt-dlp + QuickJS-NG.
 */
enum class DownloadStatus {
    IDLE,
    CHECKING_UPDATES,
    FETCHING_INFO,
    PREVIEW_READY,
    RESOLVING,
    DOWNLOADING,
    IMPORTING,
    COMPLETED,
    CANCELLED,
    ERROR,
}

/**
 * Niveles de calidad preferidos para descargar solo audio mediante `yt-dlp` sin FFmpeg.
 */
enum class AudioDownloadQuality(
    val key: String,
    val shortLabel: String,
    val label: String,
    val description: String,
) {
    MAX(
        key = "max",
        shortLabel = "Máxima",
        label = "Máxima calidad",
        description = "Mayor bitrate disponible (M4A / Opus)",
    ),
    BALANCED(
        key = "balanced",
        shortLabel = "Equilibrada",
        label = "Equilibrada (~128 kbps)",
        description = "Buen sonido con peso moderado",
    ),
    DATA_SAVER(
        key = "data_saver",
        shortLabel = "Ligera",
        label = "Ahorro de datos",
        description = "Menor peso posible para datos móviles",
    );

    companion object {
        fun fromKey(key: String?): AudioDownloadQuality =
            entries.firstOrNull { it.key == key } ?: MAX
    }
}

/**
 * Flujo de audio real extraído por `yt-dlp` durante la vista previa para que el usuario
 * pueda elegir exactamente el formato, códec, bitrate y peso aproximado antes de descargar.
 */
data class DownloadAudioFormatOption(
    val formatId: String,
    val extension: String,
    val codec: String = "",
    val bitrateKbps: Int = 0,
    val estimatedSizeBytes: Long = 0L,
    val label: String = "",
)

/**
 * Vista previa de una pista o vídeo antes de confirmar la descarga de audio.
 */
data class DownloadPreview(
    val sourceUrl: String,
    val videoId: String = "",
    val title: String,
    val channelOrArtist: String,
    val durationMs: Long = 0L,
    val estimatedSizeBytes: Long = 0L,
    val audioFormatLabel: String = "",
    val localThumbnailPath: String? = null,
    val availableFormats: List<DownloadAudioFormatOption> = emptyList(),
    val selectedFormatId: String? = null,
)

/**
 * Reglas puras para seleccionar la calidad/formato de audio en la vista previa de descarga.
 */
object DownloadFormatHelper {
    fun pickDefaultOption(
        options: List<DownloadAudioFormatOption>,
        quality: AudioDownloadQuality,
    ): DownloadAudioFormatOption? {
        if (options.isEmpty()) return null
        val sortedDesc = options.sortedWith(
            compareByDescending<DownloadAudioFormatOption> { it.bitrateKbps }
                .thenByDescending { it.extension.equals("m4a", ignoreCase = true) }
                .thenByDescending { it.estimatedSizeBytes },
        )
        return when (quality) {
            AudioDownloadQuality.MAX -> sortedDesc.first()
            AudioDownloadQuality.BALANCED -> {
                sortedDesc.firstOrNull { it.bitrateKbps in 1..160 }
                    ?: sortedDesc.lastOrNull { it.bitrateKbps > 0 }
                    ?: sortedDesc.first()
            }
            AudioDownloadQuality.DATA_SAVER -> {
                sortedDesc.lastOrNull { it.bitrateKbps >= 48 }
                    ?: sortedDesc.lastOrNull { it.bitrateKbps > 0 }
                    ?: sortedDesc.last()
            }
        }
    }

    fun selectOption(preview: DownloadPreview, formatId: String): DownloadPreview {
        val chosen = preview.availableFormats.firstOrNull { it.formatId == formatId } ?: return preview
        val resolvedLabel = chosen.label.ifBlank {
            buildString {
                append(chosen.extension.uppercase())
                if (chosen.codec.isNotBlank()) append(" (${chosen.codec})")
                if (chosen.bitrateKbps > 0) append(" • ${chosen.bitrateKbps} kbps")
            }
        }
        return preview.copy(
            selectedFormatId = chosen.formatId,
            audioFormatLabel = resolvedLabel.ifBlank { preview.audioFormatLabel },
            estimatedSizeBytes = if (chosen.estimatedSizeBytes > 0L) {
                chosen.estimatedSizeBytes
            } else {
                preview.estimatedSizeBytes
            },
        )
    }
}

/**
 * Estado reactivo de la descarga de audio en curso expuesto a la interfaz de Descargas.
 */
data class DownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val sourceUrl: String = "",
    val title: String? = null,
    val progressPercent: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val preview: DownloadPreview? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val lastCompletedTrack: Track? = null,
) {
    val isBusy: Boolean
        get() = status == DownloadStatus.CHECKING_UPDATES ||
            status == DownloadStatus.FETCHING_INFO ||
            status == DownloadStatus.RESOLVING ||
            status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.IMPORTING

    val normalizedProgress: Float
        get() = if (progressPercent.isFinite()) {
            (progressPercent / 100f).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Estado e información de versiones de los componentes del motor de descargas
 * (Python 3.11 ligero, yt-dlp, yt-dlp-ejs y QuickJS-NG compilado en Rust).
 */
data class DownloaderEngineStatus(
    val pythonVersion: String = "Python 3.11 (ligero)",
    val ytDlpVersion: String = "Cargando...",
    val ytDlpEjsVersion: String = "Cargando...",
    val quickJsVersion: String = "QuickJS-ng 0.12.2 (Rust)",
    val ytDlpUpdatedAt: String? = null,
    val ytDlpEjsUpdatedAt: String? = null,
    val autoUpdateEnabled: Boolean = true,
    val preferredQuality: AudioDownloadQuality = AudioDownloadQuality.MAX,
    val isUpdating: Boolean = false,
    val updateMessage: String? = null,
)

/**
 * Efectos creativos ejecutados en Rust al eliminar o quitar una canción.
 */
enum class DeleteAnimationEffect(
    val id: Int,
) {
    ASHES(0),
    BURNING_PAPER(1),
    STRANGE_AURA(2);

    companion object {
        fun random(): DeleteAnimationEffect = entries.random()
    }
}

/**
 * Contrato desacoplado para que las pantallas de `:feature:*` invoquen el motor Rust
 * de animaciones de eliminación sin acoplarse directamente a `:core:rust`.
 */
fun interface DeleteEffectFrameRenderer {
    suspend fun renderFrame(
        sourceDirectRgba: java.nio.ByteBuffer,
        outputDirectRgba: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        effect: DeleteAnimationEffect,
        progress: Float,
        seed: Int,
        accentArgb: Int,
    ): Boolean
}

/**
 * Configuración reactiva y persistente del motor de reproducción:
 * - Crossfade activado/desactivado.
 * - Segundos antes de terminar la canción en los que comienza el crossfade.
 * - Velocidad de reproducción (0.50x a 2.00x).
 * - Velocidad / tono de voz independiente o vinculada a la velocidad (0.50x a 2.00x).
 */
data class PlaybackTuning(
    val crossfadeEnabled: Boolean = false,
    val crossfadeSeconds: Int = DEFAULT_CROSSFADE_SECONDS,
    val playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    val playbackPitch: Float = DEFAULT_PLAYBACK_PITCH,
    val linkPitchToSpeed: Boolean = false,
) {
    fun normalized(): PlaybackTuning = copy(
        crossfadeSeconds = PlaybackTuningHelper.clampCrossfadeSeconds(crossfadeSeconds),
        playbackSpeed = PlaybackTuningHelper.clampPlaybackSpeed(playbackSpeed),
        playbackPitch = PlaybackTuningHelper.clampPlaybackPitch(playbackPitch),
    )

    companion object {
        const val MIN_CROSSFADE_SECONDS = 1
        const val MAX_CROSSFADE_SECONDS = 12
        const val DEFAULT_CROSSFADE_SECONDS = 5
        const val MIN_PLAYBACK_SPEED = 0.5f
        const val MAX_PLAYBACK_SPEED = 2.0f
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
        const val MIN_PLAYBACK_PITCH = 0.5f
        const val MAX_PLAYBACK_PITCH = 2.0f
        const val DEFAULT_PLAYBACK_PITCH = 1.0f
        val SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val PITCH_PRESETS = listOf(0.75f, 0.85f, 1.0f, 1.15f, 1.25f, 1.5f)
    }
}

/**
 * Reglas puras y deterministas para velocidad de reproducción, tono/velocidad de voz y Crossfade.
 */
object PlaybackTuningHelper {
    fun clampCrossfadeSeconds(seconds: Int): Int =
        seconds.coerceIn(PlaybackTuning.MIN_CROSSFADE_SECONDS, PlaybackTuning.MAX_CROSSFADE_SECONDS)

    fun clampPlaybackSpeed(speed: Float): Float {
        if (!speed.isFinite()) return PlaybackTuning.DEFAULT_PLAYBACK_SPEED
        val clamped = speed.coerceIn(PlaybackTuning.MIN_PLAYBACK_SPEED, PlaybackTuning.MAX_PLAYBACK_SPEED)
        return kotlin.math.round(clamped * 100f) / 100f
    }

    fun clampPlaybackPitch(pitch: Float): Float {
        if (!pitch.isFinite()) return PlaybackTuning.DEFAULT_PLAYBACK_PITCH
        val clamped = pitch.coerceIn(PlaybackTuning.MIN_PLAYBACK_PITCH, PlaybackTuning.MAX_PLAYBACK_PITCH)
        return kotlin.math.round(clamped * 100f) / 100f
    }

    fun formatSpeedLabel(speed: Float): String {
        val safe = clampPlaybackSpeed(speed)
        val roundedTwo = kotlin.math.round(safe * 100f).toInt()
        return if (roundedTwo % 10 == 0) {
            String.format(java.util.Locale.US, "%.1fx", safe)
        } else {
            String.format(java.util.Locale.US, "%.2fx", safe)
        }
    }

    fun formatPitchLabel(pitch: Float): String = formatSpeedLabel(clampPlaybackPitch(pitch))

    /**
     * Describe de forma legible el carácter vocal resultante del ajuste de tono/velocidad de voz.
     */
    fun describeVoicePitch(pitch: Float): String {
        val safe = clampPlaybackPitch(pitch)
        return when {
            safe < 0.80f -> "Voz muy grave"
            safe < 0.98f -> "Voz más grave"
            safe > 1.25f -> "Voz muy aguda"
            safe > 1.02f -> "Voz más aguda"
            else -> "Voz natural"
        }
    }

    /**
     * Curva equal-power (coseno / seno) para mantener volumen constante durante el crossfade.
     * Retorna (volumenSaliente, volumenEntrante).
     */
    fun computeEqualPowerGains(progress: Float): Pair<Float, Float> {
        val clamped = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        val angle = clamped * (Math.PI / 2.0)
        val outgoing = kotlin.math.cos(angle).toFloat().coerceIn(0f, 1f)
        val incoming = kotlin.math.sin(angle).toFloat().coerceIn(0f, 1f)
        return outgoing to incoming
    }

    fun remainingWallClockMs(
        durationMs: Long,
        positionMs: Long,
        playbackSpeed: Float,
    ): Long {
        if (durationMs <= 0L) return -1L
        val safeSpeed = clampPlaybackSpeed(playbackSpeed)
        val trackRemaining = (durationMs - positionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        return (trackRemaining / safeSpeed).toLong()
    }

    fun shouldTriggerCrossfade(
        crossfadeEnabled: Boolean,
        isPlaying: Boolean,
        isAlreadyCrossfading: Boolean,
        hasNextMediaItem: Boolean,
        repeatOneEnabled: Boolean,
        durationMs: Long,
        positionMs: Long,
        playbackSpeed: Float,
        crossfadeSeconds: Int,
    ): Boolean {
        if (!crossfadeEnabled || !isPlaying || isAlreadyCrossfading || !hasNextMediaItem || repeatOneEnabled) {
            return false
        }
        if (durationMs <= 0L) return false

        val safeSpeed = clampPlaybackSpeed(playbackSpeed)
        val safeSeconds = clampCrossfadeSeconds(crossfadeSeconds)
        val crossfadeWallMs = safeSeconds * 1000L
        val totalWallDurationMs = (durationMs / safeSpeed).toLong()
        if (totalWallDurationMs < crossfadeWallMs + 2000L) {
            return false
        }

        val wallRemainingMs = remainingWallClockMs(durationMs, positionMs, safeSpeed)
        return wallRemainingMs in 250L..crossfadeWallMs
    }

    /**
     * Parámetros finales de velocidad y tono enviados a Media3 ExoPlayer combinando
     * la velocidad libre, el control de velocidad/tono de voz y el modo Slowed.
     */
    data class ResolvedPlaybackParams(
        val speed: Float,
        val pitch: Float,
    )

    fun resolvePlaybackParameters(
        tuning: PlaybackTuning,
        effects: AudioEffectsState,
    ): ResolvedPlaybackParams {
        val safeEffects = effects.normalized()
        if (safeEffects.slowedEnabled) {
            val speed = safeEffects.slowedSpeed
            val pitch = if (safeEffects.slowedPreservePitch) 1.0f else speed
            return ResolvedPlaybackParams(speed = speed, pitch = pitch)
        }
        val speed = clampPlaybackSpeed(tuning.playbackSpeed)
        val pitch = if (tuning.linkPitchToSpeed) {
            clampPlaybackPitch(speed)
        } else {
            clampPlaybackPitch(tuning.playbackPitch)
        }
        return ResolvedPlaybackParams(speed = speed, pitch = pitch)
    }

    fun clampSlowedSpeed(speed: Float): Float {
        if (!speed.isFinite()) return AudioEffectsState.DEFAULT_SLOWED_SPEED
        val clamped = speed.coerceIn(
            AudioEffectsState.MIN_SLOWED_SPEED,
            AudioEffectsState.MAX_SLOWED_SPEED,
        )
        return kotlin.math.round(clamped * 100f) / 100f
    }

    fun clampUnit(value: Float, fallback: Float): Float {
        if (!value.isFinite()) return fallback
        return value.coerceIn(0f, 1f)
    }

    fun formatEffectsSummary(effects: AudioEffectsState): String? {
        val safe = effects.normalized()
        if (!safe.isAnyEffectEnabled) return null
        val parts = buildList {
            if (safe.crossfeedEnabled) {
                add("Crossfeed ${(safe.crossfeedStrength * 100f).toInt()}%")
            }
            if (safe.spatial8dEnabled) {
                add("8D")
            }
            if (safe.slowedEnabled) {
                add("Slowed ${formatSpeedLabel(safe.slowedSpeed)}")
            }
            if (safe.reverbEnabled) {
                add("Reverb ${(safe.reverbWetMix * 100f).toInt()}%")
            }
        }
        return parts.joinToString(" • ").ifEmpty { null }
    }

    /**
     * Calcula la duración aproximada en segundos de una órbita completa 360° en el motor 8D.
     */
    fun spatial8dOrbitPeriodSeconds(speed: Float): Float {
        val safe = clampUnit(speed, AudioEffectsState.DEFAULT_SPATIAL_8D_SPEED)
        val hz = 0.05f + safe * 0.30f
        return 1f / hz
    }

    /**
     * Convierte el parámetro normalizado de ángulo [0..1] a grados virtuales de escena (15° a 45°).
     */
    fun crossfeedVirtualAngleDegrees(angle: Float): Int {
        val safe = clampUnit(angle, AudioEffectsState.DEFAULT_CROSSFEED_ANGLE)
        return (15f + safe * 30f).toInt()
    }
}

/**
 * Resultado validado y normalizado al editar el título y el artista de una canción.
 */
data class EditedTrackMetadata(
    val title: String,
    val artist: String,
)

/**
 * Reglas puras de validación y limpieza de espacios para edición local de metadatos.
 */
object TrackMetadataHelper {
    const val DEFAULT_ARTIST = "Artista desconocido"
    const val MAX_TITLE_LENGTH = 160
    const val MAX_ARTIST_LENGTH = 120
    private val whitespaceRegex = Regex("\\s+")

    /**
     * Limpia espacios sobrantes y valida que el título no quede vacío.
     * Si el artista se deja en blanco, asigna [DEFAULT_ARTIST] para mantener
     * consistencia con la agrupación automática por artista.
     */
    fun normalizeOrNull(rawTitle: String, rawArtist: String): EditedTrackMetadata? {
        val cleanTitle = rawTitle.trim().replace(whitespaceRegex, " ").take(MAX_TITLE_LENGTH)
        if (cleanTitle.isEmpty()) return null

        val cleanArtist = rawArtist.trim().replace(whitespaceRegex, " ")
            .take(MAX_ARTIST_LENGTH)
            .ifEmpty { DEFAULT_ARTIST }

        return EditedTrackMetadata(
            title = cleanTitle,
            artist = cleanArtist,
        )
    }
}

/**
 * Preset predefinido de efectos espaciales (Reverb / Audio 8D en Rust) y modo Slowed.
 */
data class AudioEffectPreset(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val reverbEnabled: Boolean,
    val reverbRoomSize: Float,
    val reverbDamping: Float,
    val reverbWetMix: Float,
    val reverbWidth: Float,
    val slowedEnabled: Boolean,
    val slowedSpeed: Float,
    val slowedPreservePitch: Boolean,
    val slowedWarmth: Float,
    val spatial8dEnabled: Boolean = false,
    val spatial8dSpeed: Float = AudioEffectsState.DEFAULT_SPATIAL_8D_SPEED,
    val spatial8dDepth: Float = AudioEffectsState.DEFAULT_SPATIAL_8D_DEPTH,
    val crossfeedEnabled: Boolean = false,
    val crossfeedStrength: Float = AudioEffectsState.DEFAULT_CROSSFEED_STRENGTH,
    val crossfeedAngle: Float = AudioEffectsState.DEFAULT_CROSSFEED_ANGLE,
)

/**
 * Estado inmutable de efectos de audio en tiempo real (Reverberación estéreo Freeverb,
 * Crossfeed binaural estático, Audio Espacial 8D psicoacústico y Calidez analógica en Rust +
 * Slowed varispeed en ExoPlayer).
 */
data class AudioEffectsState(
    val reverbEnabled: Boolean = false,
    val reverbRoomSize: Float = DEFAULT_ROOM_SIZE,
    val reverbDamping: Float = DEFAULT_DAMPING,
    val reverbWetMix: Float = DEFAULT_WET_MIX,
    val reverbWidth: Float = DEFAULT_WIDTH,
    val slowedEnabled: Boolean = false,
    val slowedSpeed: Float = DEFAULT_SLOWED_SPEED,
    val slowedPreservePitch: Boolean = false,
    val slowedWarmth: Float = DEFAULT_SLOWED_WARMTH,
    val spatial8dEnabled: Boolean = false,
    val spatial8dSpeed: Float = DEFAULT_SPATIAL_8D_SPEED,
    val spatial8dDepth: Float = DEFAULT_SPATIAL_8D_DEPTH,
    val crossfeedEnabled: Boolean = false,
    val crossfeedStrength: Float = DEFAULT_CROSSFEED_STRENGTH,
    val crossfeedAngle: Float = DEFAULT_CROSSFEED_ANGLE,
    val activePresetId: String = PRESET_OFF_ID,
) {
    val isAnyEffectEnabled: Boolean
        get() = reverbEnabled || slowedEnabled || spatial8dEnabled || crossfeedEnabled

    val isSlowedWarmthActive: Boolean
        get() = slowedEnabled && slowedWarmth > 0.01f

    fun normalized(): AudioEffectsState = copy(
        reverbRoomSize = PlaybackTuningHelper.clampUnit(reverbRoomSize, DEFAULT_ROOM_SIZE),
        reverbDamping = PlaybackTuningHelper.clampUnit(reverbDamping, DEFAULT_DAMPING),
        reverbWetMix = PlaybackTuningHelper.clampUnit(reverbWetMix, DEFAULT_WET_MIX),
        reverbWidth = PlaybackTuningHelper.clampUnit(reverbWidth, DEFAULT_WIDTH),
        slowedSpeed = PlaybackTuningHelper.clampSlowedSpeed(slowedSpeed),
        slowedWarmth = PlaybackTuningHelper.clampUnit(slowedWarmth, DEFAULT_SLOWED_WARMTH),
        spatial8dSpeed = PlaybackTuningHelper.clampUnit(spatial8dSpeed, DEFAULT_SPATIAL_8D_SPEED),
        spatial8dDepth = PlaybackTuningHelper.clampUnit(spatial8dDepth, DEFAULT_SPATIAL_8D_DEPTH),
        crossfeedStrength = PlaybackTuningHelper.clampUnit(crossfeedStrength, DEFAULT_CROSSFEED_STRENGTH),
        crossfeedAngle = PlaybackTuningHelper.clampUnit(crossfeedAngle, DEFAULT_CROSSFEED_ANGLE),
        activePresetId = activePresetId.ifBlank { PRESET_CUSTOM_ID },
    )

    companion object {
        const val MIN_SLOWED_SPEED = 0.70f
        const val MAX_SLOWED_SPEED = 0.95f
        const val DEFAULT_SLOWED_SPEED = 0.85f
        const val DEFAULT_SLOWED_WARMTH = 0.35f
        const val DEFAULT_ROOM_SIZE = 0.65f
        const val DEFAULT_DAMPING = 0.45f
        const val DEFAULT_WET_MIX = 0.32f
        const val DEFAULT_WIDTH = 0.85f
        const val DEFAULT_SPATIAL_8D_SPEED = 0.38f
        const val DEFAULT_SPATIAL_8D_DEPTH = 0.80f
        const val DEFAULT_CROSSFEED_STRENGTH = 0.45f
        const val DEFAULT_CROSSFEED_ANGLE = 0.50f

        const val PRESET_OFF_ID = "off"
        const val PRESET_CUSTOM_ID = "custom"

        val SLOWED_SPEED_PRESETS = listOf(0.75f, 0.80f, 0.85f, 0.90f)

        val PRESETS = listOf(
            AudioEffectPreset(
                id = PRESET_OFF_ID,
                displayName = "Original",
                subtitle = "Sin efectos activos (Bypass)",
                reverbEnabled = false,
                reverbRoomSize = DEFAULT_ROOM_SIZE,
                reverbDamping = DEFAULT_DAMPING,
                reverbWetMix = DEFAULT_WET_MIX,
                reverbWidth = DEFAULT_WIDTH,
                slowedEnabled = false,
                slowedSpeed = DEFAULT_SLOWED_SPEED,
                slowedPreservePitch = false,
                slowedWarmth = DEFAULT_SLOWED_WARMTH,
                spatial8dEnabled = false,
                spatial8dSpeed = DEFAULT_SPATIAL_8D_SPEED,
                spatial8dDepth = DEFAULT_SPATIAL_8D_DEPTH,
                crossfeedEnabled = false,
                crossfeedStrength = DEFAULT_CROSSFEED_STRENGTH,
                crossfeedAngle = DEFAULT_CROSSFEED_ANGLE,
            ),
            AudioEffectPreset(
                id = "headphone_crossfeed",
                displayName = "Crossfeed Auriculares",
                subtitle = "Escenario binaural natural al frente sin fatiga auditiva",
                reverbEnabled = false,
                reverbRoomSize = DEFAULT_ROOM_SIZE,
                reverbDamping = DEFAULT_DAMPING,
                reverbWetMix = DEFAULT_WET_MIX,
                reverbWidth = DEFAULT_WIDTH,
                slowedEnabled = false,
                slowedSpeed = DEFAULT_SLOWED_SPEED,
                slowedPreservePitch = false,
                slowedWarmth = DEFAULT_SLOWED_WARMTH,
                spatial8dEnabled = false,
                spatial8dSpeed = DEFAULT_SPATIAL_8D_SPEED,
                spatial8dDepth = DEFAULT_SPATIAL_8D_DEPTH,
                crossfeedEnabled = true,
                crossfeedStrength = 0.48f,
                crossfeedAngle = 0.50f,
            ),
            AudioEffectPreset(
                id = "spatial_8d",
                displayName = "Audio 8D",
                subtitle = "Órbita 360° alrededor de la cabeza con sala suave",
                reverbEnabled = true,
                reverbRoomSize = 0.56f,
                reverbDamping = 0.48f,
                reverbWetMix = 0.22f,
                reverbWidth = 0.90f,
                slowedEnabled = false,
                slowedSpeed = DEFAULT_SLOWED_SPEED,
                slowedPreservePitch = false,
                slowedWarmth = 0.15f,
                spatial8dEnabled = true,
                spatial8dSpeed = 0.38f,
                spatial8dDepth = 0.85f,
            ),
            AudioEffectPreset(
                id = "spatial_8d_slowed",
                displayName = "8D + Slowed",
                subtitle = "Rotación 360°, tono grave de vinilo y eco profundo",
                reverbEnabled = true,
                reverbRoomSize = 0.74f,
                reverbDamping = 0.45f,
                reverbWetMix = 0.34f,
                reverbWidth = 0.92f,
                slowedEnabled = true,
                slowedSpeed = 0.85f,
                slowedPreservePitch = false,
                slowedWarmth = 0.38f,
                spatial8dEnabled = true,
                spatial8dSpeed = 0.30f,
                spatial8dDepth = 0.82f,
            ),
            AudioEffectPreset(
                id = "slowed_reverb",
                displayName = "Slowed + Reverb",
                subtitle = "Ritmo lento, tono grave y eco atmosférico",
                reverbEnabled = true,
                reverbRoomSize = 0.74f,
                reverbDamping = 0.46f,
                reverbWetMix = 0.38f,
                reverbWidth = 0.92f,
                slowedEnabled = true,
                slowedSpeed = 0.85f,
                slowedPreservePitch = false,
                slowedWarmth = 0.42f,
            ),
            AudioEffectPreset(
                id = "slowed_only",
                displayName = "Solo Slowed",
                subtitle = "Efecto vinilo lento y cálido sin eco",
                reverbEnabled = false,
                reverbRoomSize = DEFAULT_ROOM_SIZE,
                reverbDamping = DEFAULT_DAMPING,
                reverbWetMix = DEFAULT_WET_MIX,
                reverbWidth = DEFAULT_WIDTH,
                slowedEnabled = true,
                slowedSpeed = 0.85f,
                slowedPreservePitch = false,
                slowedWarmth = 0.35f,
            ),
            AudioEffectPreset(
                id = "room",
                displayName = "Sala íntima",
                subtitle = "Reverberación corta y natural de estudio",
                reverbEnabled = true,
                reverbRoomSize = 0.42f,
                reverbDamping = 0.56f,
                reverbWetMix = 0.22f,
                reverbWidth = 0.75f,
                slowedEnabled = false,
                slowedSpeed = DEFAULT_SLOWED_SPEED,
                slowedPreservePitch = false,
                slowedWarmth = 0.20f,
            ),
            AudioEffectPreset(
                id = "hall",
                displayName = "Hall",
                subtitle = "Espacio amplio tipo auditorio",
                reverbEnabled = true,
                reverbRoomSize = 0.76f,
                reverbDamping = 0.40f,
                reverbWetMix = 0.34f,
                reverbWidth = 0.90f,
                slowedEnabled = false,
                slowedSpeed = DEFAULT_SLOWED_SPEED,
                slowedPreservePitch = false,
                slowedWarmth = 0.20f,
            ),
            AudioEffectPreset(
                id = "dreamy",
                displayName = "Catedral Etérea",
                subtitle = "Cola larga envolvente y cálida",
                reverbEnabled = true,
                reverbRoomSize = 0.92f,
                reverbDamping = 0.30f,
                reverbWetMix = 0.48f,
                reverbWidth = 1.0f,
                slowedEnabled = false,
                slowedSpeed = DEFAULT_SLOWED_SPEED,
                slowedPreservePitch = false,
                slowedWarmth = 0.25f,
            ),
        )

        fun fromPreset(preset: AudioEffectPreset): AudioEffectsState = AudioEffectsState(
            reverbEnabled = preset.reverbEnabled,
            reverbRoomSize = preset.reverbRoomSize,
            reverbDamping = preset.reverbDamping,
            reverbWetMix = preset.reverbWetMix,
            reverbWidth = preset.reverbWidth,
            slowedEnabled = preset.slowedEnabled,
            slowedSpeed = preset.slowedSpeed,
            slowedPreservePitch = preset.slowedPreservePitch,
            slowedWarmth = preset.slowedWarmth,
            spatial8dEnabled = preset.spatial8dEnabled,
            spatial8dSpeed = preset.spatial8dSpeed,
            spatial8dDepth = preset.spatial8dDepth,
            crossfeedEnabled = preset.crossfeedEnabled,
            crossfeedStrength = preset.crossfeedStrength,
            crossfeedAngle = preset.crossfeedAngle,
            activePresetId = preset.id,
        ).normalized()

        fun findPresetById(id: String?): AudioEffectPreset? =
            PRESETS.firstOrNull { it.id == id }
    }
}

/**
 * Tipo de salida de audio detectada mediante `AudioDeviceCallback` (Android 8.0+).
 */
enum class AudioOutputType(
    val displayLabel: String,
    val isHeadphones: Boolean,
) {
    SPEAKER("Altavoz del teléfono", isHeadphones = false),
    WIRED_HEADSET("Auriculares por cable", isHeadphones = true),
    USB_HEADSET("Auriculares USB-C", isHeadphones = true),
    BLUETOOTH_HEADSET("Auriculares Bluetooth", isHeadphones = true),
    OTHER("Salida de audio externa", isHeadphones = false),
}

/**
 * Estado reactivo de la ruta de salida de audio activa en el dispositivo.
 */
data class AudioOutputRouteState(
    val outputType: AudioOutputType = AudioOutputType.SPEAKER,
    val deviceName: String = AudioOutputType.SPEAKER.displayLabel,
    val isHeadphonesConnected: Boolean = false,
)

/**
 * Preferencias de comportamiento inteligente para auriculares y botones multimedia (cable / Bluetooth AVRCP).
 */
data class HeadphonePreferences(
    val autoPauseOnDisconnect: Boolean = true,
    val autoResumeOnConnect: Boolean = false,
    val autoDisable8dOnSpeaker: Boolean = true,
    val autoEnableCrossfeedOnHeadphones: Boolean = false,
    val doubleTapNextEnabled: Boolean = true,
    val tripleTapPreviousEnabled: Boolean = true,
    val previousRestartThresholdMs: Long = DEFAULT_PREVIOUS_RESTART_MS,
    val wrapQueueNavigation: Boolean = true,
    val seekStepSeconds: Int = DEFAULT_SEEK_STEP_SECONDS,
) {
    fun normalized(): HeadphonePreferences = copy(
        previousRestartThresholdMs = HeadphoneRoutingHelper.clampPreviousRestartThresholdMs(previousRestartThresholdMs),
        seekStepSeconds = HeadphoneRoutingHelper.clampSeekStepSeconds(seekStepSeconds),
    )

    companion object {
        const val DEFAULT_PREVIOUS_RESTART_MS = 3_500L
        const val DEFAULT_SEEK_STEP_SECONDS = 10
        val PREVIOUS_THRESHOLD_OPTIONS_MS = listOf(0L, 3_500L, 5_000L, 8_000L)
        val SEEK_STEP_OPTIONS_SECONDS = listOf(5, 10, 15, 30)
    }
}

/**
 * Parámetros efectivos finalmente enviados al motor Rust tras combinar las preferencias
 * del usuario con la salida de audio activa (altavoz vs. auriculares).
 */
data class EffectiveRustAudioEffects(
    val spatial8dEnabled: Boolean,
    val spatial8dBypassedBySpeaker: Boolean,
    val crossfeedEnabled: Boolean,
    val crossfeedAutoEnabledByHeadphones: Boolean,
)

/**
 * Acción resuelta al pulsar repetidamente el botón físico único (`KEYCODE_HEADSETHOOK`)
 * de unos auriculares con cable o adaptador.
 */
enum class MediaHookTapAction {
    NONE,
    TOGGLE_PLAY_PAUSE,
    SKIP_NEXT,
    SKIP_PREVIOUS,
}

/**
 * Reglas puras y deterministas para enrutamiento de efectos por salida de audio
 * y comportamiento de botones multimedia cableados / Bluetooth AVRCP.
 */
object HeadphoneRoutingHelper {
    fun clampPreviousRestartThresholdMs(ms: Long): Long =
        ms.coerceIn(0L, 15_000L)

    fun clampSeekStepSeconds(seconds: Int): Int =
        seconds.coerceIn(5, 60)

    fun resolveEffectiveRustEffects(
        effects: AudioEffectsState,
        outputRoute: AudioOutputRouteState,
        prefs: HeadphonePreferences,
    ): EffectiveRustAudioEffects {
        val safeEffects = effects.normalized()
        val safePrefs = prefs.normalized()
        val headphonesActive = outputRoute.isHeadphonesConnected

        val spatial8dBypassedBySpeaker =
            safeEffects.spatial8dEnabled && !headphonesActive && safePrefs.autoDisable8dOnSpeaker
        val effectiveSpatial8d = safeEffects.spatial8dEnabled && !spatial8dBypassedBySpeaker

        val crossfeedAutoEnabled =
            !safeEffects.crossfeedEnabled && headphonesActive && safePrefs.autoEnableCrossfeedOnHeadphones
        val effectiveCrossfeed = safeEffects.crossfeedEnabled || crossfeedAutoEnabled

        return EffectiveRustAudioEffects(
            spatial8dEnabled = effectiveSpatial8d,
            spatial8dBypassedBySpeaker = spatial8dBypassedBySpeaker,
            crossfeedEnabled = effectiveCrossfeed,
            crossfeedAutoEnabledByHeadphones = crossfeedAutoEnabled,
        )
    }

    fun resolveMediaHookTapAction(
        tapCount: Int,
        prefs: HeadphonePreferences,
    ): MediaHookTapAction {
        if (tapCount <= 0) return MediaHookTapAction.NONE
        return when {
            tapCount >= 3 && prefs.tripleTapPreviousEnabled -> MediaHookTapAction.SKIP_PREVIOUS
            tapCount >= 2 && prefs.doubleTapNextEnabled -> MediaHookTapAction.SKIP_NEXT
            else -> MediaHookTapAction.TOGGLE_PLAY_PAUSE
        }
    }

    fun formatPreviousThresholdLabel(ms: Long): String {
        val safe = clampPreviousRestartThresholdMs(ms)
        return when {
            safe == 0L -> "Siempre previa"
            safe % 1000L == 0L -> "${safe / 1000L} s"
            else -> String.format(java.util.Locale.US, "%.1f s", safe / 1000f)
        }
    }
}

/**
 * Modos soportados por el temporizador de apagado automático de Aura Music.
 */
enum class SleepTimerMode {
    OFF,
    DURATION,
    END_OF_TRACK,
    END_OF_QUEUE,
}

/**
 * Estado reactivo del temporizador de apagado automático ejecutado en segundo plano
 * dentro de `AuraPlaybackService`.
 */
data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val configuredMinutes: Int = 0,
    val totalDurationMs: Long = 0L,
    val remainingMs: Long = 0L,
    val fadeOutOnFinish: Boolean = true,
    val endElapsedMs: Long = 0L,
) {
    val isActive: Boolean
        get() = mode != SleepTimerMode.OFF

    val progressFraction: Float
        get() = if (mode == SleepTimerMode.DURATION && totalDurationMs > 0L) {
            (remainingMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Reglas puras para validación de minutos personalizados, presets, formato de cuenta atrás
 * y detección del fin de pista/cola del temporizador de apagado.
 */
object SleepTimerHelper {
    const val MIN_CUSTOM_MINUTES = 1
    const val MAX_CUSTOM_MINUTES = 480
    val PRESET_MINUTES = listOf(5, 10, 15, 30, 45, 60, 90)

    fun clampCustomMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_CUSTOM_MINUTES, MAX_CUSTOM_MINUTES)

    fun parseCustomMinutesOrNull(rawInput: String): Int? {
        val cleaned = rawInput.trim()
        if (cleaned.isEmpty()) return null
        val parsed = cleaned.toIntOrNull() ?: return null
        return parsed.takeIf { it in MIN_CUSTOM_MINUTES..MAX_CUSTOM_MINUTES }
    }

    fun formatPresetLabel(minutes: Int): String {
        val safe = clampCustomMinutes(minutes)
        return if (safe >= 60 && safe % 60 == 0) {
            val hours = safe / 60
            if (hours == 1) "1 h" else "$hours h"
        } else {
            "$safe min"
        }
    }

    fun formatCountdown(remainingMs: Long): String {
        val totalSeconds = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatShortLabel(state: SleepTimerState): String = when (state.mode) {
        SleepTimerMode.OFF -> "Desactivado"
        SleepTimerMode.DURATION -> formatCountdown(state.remainingMs)
        SleepTimerMode.END_OF_TRACK -> "Fin de canción"
        SleepTimerMode.END_OF_QUEUE -> "Fin de la música"
    }

    /**
     * Calcula los milisegundos restantes en tiempo real a partir de un timestamp monotónico
     * (`SystemClock.elapsedRealtime()`) recibido desde el proceso `:player`.
     */
    fun computeRemainingMs(endElapsedMs: Long, nowElapsedMs: Long): Long {
        if (endElapsedMs <= 0L) return 0L
        return (endElapsedMs - nowElapsedMs).coerceAtLeast(0L)
    }

    /**
     * Evita que el Crossfade avance automáticamente a la siguiente canción cuando el usuario
     * programó detener la reproducción al terminar la pista actual o la última de la cola.
     */
    fun shouldBypassCrossfadeForSleepTimer(
        state: SleepTimerState,
        hasNextMediaItem: Boolean,
    ): Boolean = when (state.mode) {
        SleepTimerMode.END_OF_TRACK -> true
        SleepTimerMode.END_OF_QUEUE -> !hasNextMediaItem
        SleepTimerMode.OFF,
        SleepTimerMode.DURATION,
        -> false
    }

    /**
     * Determina si una transición automática de pista debe pausar la reproducción por el temporizador.
     */
    fun shouldPauseOnAutoTransition(
        mode: SleepTimerMode,
        previousIndex: Int,
        newIndex: Int,
        itemCount: Int,
        reasonIsAuto: Boolean,
        reasonIsRepeat: Boolean,
    ): Boolean = when (mode) {
        SleepTimerMode.END_OF_TRACK -> reasonIsAuto || reasonIsRepeat
        SleepTimerMode.END_OF_QUEUE -> {
            val wrappedQueue = reasonIsAuto && itemCount > 0 && previousIndex >= itemCount - 1 && newIndex == 0
            reasonIsRepeat || wrappedQueue
        }
        SleepTimerMode.OFF,
        SleepTimerMode.DURATION,
        -> false
    }
}

/**
 * Rol del proceso Linux en el que se ejecuta una instancia de AuraApplication.
 * Permite aislar la memoria de ExoPlayer/Rust DSP (`:player`) y Python/yt-dlp (`:downloader`)
 * respecto a la interfaz Compose/Coil/Room (`MAIN`).
 */
enum class AuraProcessRole {
    MAIN,
    PLAYER,
    DOWNLOADER,
}

/**
 * Resolución pura del rol de proceso a partir del nombre reportado por Android o `/proc/self/cmdline`.
 */
object AuraProcessRoleResolver {
    fun resolve(rawProcessName: String?): AuraProcessRole {
        val clean = rawProcessName
            ?.trim { it <= ' ' || it == '\u0000' }
            .orEmpty()
        return when {
            clean.endsWith(":player") -> AuraProcessRole.PLAYER
            clean.endsWith(":downloader") -> AuraProcessRole.DOWNLOADER
            else -> AuraProcessRole.MAIN
        }
    }
}

/**
 * Acciones de liberación de RAM que la aplicación ejecuta cuando Android reporta presión
 * de memoria o salida a segundo plano (por ejemplo, cuando el usuario abre un juego).
 */
data class MemoryTrimAction(
    val clearImageMemoryCache: Boolean,
    val clearDynamicColorCache: Boolean,
    val clearTransientControllerCaches: Boolean,
)

/**
 * Política pura para decidir qué cachés purgar según el nivel de `ComponentCallbacks2.onTrimMemory`.
 */
object MemoryTrimPolicy {
    // Valores oficiales de ComponentCallbacks2 en Android SDK.
    const val LEVEL_RUNNING_MODERATE = 5
    const val LEVEL_RUNNING_LOW = 10
    const val LEVEL_RUNNING_CRITICAL = 15
    const val LEVEL_UI_HIDDEN = 20
    const val LEVEL_BACKGROUND = 40
    const val LEVEL_MODERATE = 60
    const val LEVEL_COMPLETE = 80

    fun resolve(level: Int): MemoryTrimAction {
        val uiHiddenOrPressure =
            level >= LEVEL_UI_HIDDEN ||
                level == LEVEL_RUNNING_LOW ||
                level == LEVEL_RUNNING_CRITICAL
        val heavyPressure =
            level >= LEVEL_BACKGROUND ||
                level == LEVEL_RUNNING_CRITICAL
        return MemoryTrimAction(
            clearImageMemoryCache = uiHiddenOrPressure,
            clearDynamicColorCache = uiHiddenOrPressure,
            clearTransientControllerCaches = heavyPressure,
        )
    }
}

/**
 * Estado reactivo de la función opcional de almacenamiento en carpeta pública del sistema (`Aura Music`).
 * Por defecto está desactivada (`isEnabled = false`) y solo crea/sincroniza carpetas cuando el usuario la activa.
 */
data class SystemFolderStorageState(
    val isEnabled: Boolean = false,
    val hasStoragePermission: Boolean = false,
    val isBusy: Boolean = false,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val lastSyncedCount: Int = 0,
    val folderDisplayPath: String = SystemFolderHelper.DISPLAY_ROOT_PATH,
    val statusMessage: String? = null,
) {
    val progressFraction: Float
        get() = if (totalCount > 0) {
            (processedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Reglas puras y constantes para la carpeta pública del sistema `Aura Music`, sus subcarpetas
 * (`Música`, `Carátulas`), archivos `.json` y normalización segura de nombres de archivo.
 */
object SystemFolderHelper {
    const val ROOT_FOLDER_NAME = "Aura Music"
    const val MUSIC_SUBFOLDER_NAME = "Música"
    const val COVERS_SUBFOLDER_NAME = "Carátulas"
    const val LIBRARY_JSON_FILE_NAME = "aura_biblioteca.json"
    const val PLAYER_CONFIG_JSON_FILE_NAME = "aura_player_config.json"
    const val ENGINE_VERSIONS_JSON_FILE_NAME = "engine_versions.json"
    const val DISPLAY_ROOT_PATH = "Almacenamiento interno/Aura Music"

    val SUPPORTED_AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "alac", "aiff",
    )

    private val invalidFileCharsRegex = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    private val whitespaceRegex = Regex("\\s+")

    /**
     * Limpia un segmento de nombre de archivo para hacerlo compatible con FAT32/exFAT/Android
     * evitando caracteres ilegales, espacios duplicados o puntos finales inválidos.
     */
    fun sanitizeFileNameSegment(raw: String, maxLength: Int = 72): String {
        val cleaned = raw
            .replace(invalidFileCharsRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()
            .trim('.')
            .trim()
            .take(maxLength)
            .trim()
        return cleaned.ifEmpty { "Pista" }
    }

    /**
     * Construye un nombre legible y seguro para guardar un archivo de audio dentro de `Aura Music/Música`.
     */
    fun buildTrackAudioFileName(
        title: String,
        artist: String,
        extension: String,
        fingerprint: String,
        disambiguate: Boolean = false,
    ): String {
        val safeTitle = sanitizeFileNameSegment(title, maxLength = 64)
        val cleanArtist = artist.trim()
        val includeArtist = cleanArtist.isNotEmpty() &&
            !cleanArtist.equals(TrackMetadataHelper.DEFAULT_ARTIST, ignoreCase = true)
        val baseName = if (includeArtist) {
            val safeArtist = sanitizeFileNameSegment(cleanArtist, maxLength = 44)
            "$safeArtist - $safeTitle"
        } else {
            safeTitle
        }
        val cleanExt = extension.lowercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotEmpty() }
            ?: "mp3"
        val suffix = if (disambiguate) {
            "_${fingerprint.take(6).ifEmpty { "aura" }}"
        } else {
            ""
        }
        return "$baseName$suffix.$cleanExt"
    }

    /**
     * Verifica si un nombre de archivo corresponde a un formato de audio soportado y no es oculto ni temporal.
     */
    fun isSupportedAudioFileName(fileName: String): Boolean {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(".") || trimmed.endsWith(".tmp", ignoreCase = true)) {
            return false
        }
        val ext = trimmed.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_AUDIO_EXTENSIONS
    }
}

/**
 * Páginas del flujo de bienvenida inicial (mostrado únicamente la primera vez que se instala la app).
 */
enum class OnboardingPage {
    WELCOME_GUIDE,
    PERMISSIONS,
}

/**
 * Estado en vivo de los permisos reales que utiliza Aura Music durante el onboarding.
 */
data class OnboardingPermissionStatus(
    val notificationsGranted: Boolean = false,
    val notificationsRequiredBySystem: Boolean = true,
    val storageGranted: Boolean = false,
) {
    val isNotificationReady: Boolean
        get() = !notificationsRequiredBySystem || notificationsGranted
}

/**
 * Reglas y catálogos puros compartidos entre el onboarding inicial y el descargador multipágina.
 */
object OnboardingHelper {
    val SUPPORTED_YTDLP_PLATFORMS = listOf(
        "YouTube",
        "TikTok",
        "Instagram",
        "Facebook",
        "SoundCloud",
        "X (Twitter)",
    )

    fun buildSupportedPlatformsSummary(): String =
        "${SUPPORTED_YTDLP_PLATFORMS.joinToString(", ")} y cientos de sitios más compatibles con yt-dlp."

    fun countPendingInteractivePermissions(status: OnboardingPermissionStatus): Int {
        var pending = 0
        if (!status.isNotificationReady) pending += 1
        if (!status.storageGranted) pending += 1
        return pending
    }
}

