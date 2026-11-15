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
enum class DeleteAnimationEffect(val id: Int) {
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
 */
data class PlaybackTuning(
    val crossfadeEnabled: Boolean = false,
    val crossfadeSeconds: Int = DEFAULT_CROSSFADE_SECONDS,
    val playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
) {
    fun normalized(): PlaybackTuning = copy(
        crossfadeSeconds = PlaybackTuningHelper.clampCrossfadeSeconds(crossfadeSeconds),
        playbackSpeed = PlaybackTuningHelper.clampPlaybackSpeed(playbackSpeed),
    )

    companion object {
        const val MIN_CROSSFADE_SECONDS = 1
        const val MAX_CROSSFADE_SECONDS = 12
        const val DEFAULT_CROSSFADE_SECONDS = 5
        const val MIN_PLAYBACK_SPEED = 0.5f
        const val MAX_PLAYBACK_SPEED = 2.0f
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
        val SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }
}

/**
 * Reglas puras y deterministas para velocidad de reproducción y transición Crossfade.
 */
object PlaybackTuningHelper {

    fun clampCrossfadeSeconds(seconds: Int): Int =
        seconds.coerceIn(PlaybackTuning.MIN_CROSSFADE_SECONDS, PlaybackTuning.MAX_CROSSFADE_SECONDS)

    fun clampPlaybackSpeed(speed: Float): Float {
        if (!speed.isFinite()) return PlaybackTuning.DEFAULT_PLAYBACK_SPEED
        val clamped = speed.coerceIn(PlaybackTuning.MIN_PLAYBACK_SPEED, PlaybackTuning.MAX_PLAYBACK_SPEED)
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
     * la velocidad libre y el modo Slowed (que por defecto reduce tono y tempo juntos).
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
        return ResolvedPlaybackParams(
            speed = clampPlaybackSpeed(tuning.playbackSpeed),
            pitch = 1.0f,
        )
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
)

/**
 * Estado inmutable de efectos de audio en tiempo real (Reverberación estéreo Freeverb,
 * Audio Espacial 8D psicoacústico y Calidez analógica en Rust + Slowed varispeed en ExoPlayer).
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
    val activePresetId: String = PRESET_OFF_ID,
) {
    val isAnyEffectEnabled: Boolean
        get() = reverbEnabled || slowedEnabled || spatial8dEnabled

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
            activePresetId = preset.id,
        ).normalized()

        fun findPresetById(id: String?): AudioEffectPreset? =
            PRESETS.firstOrNull { it.id == id }
    }
}





