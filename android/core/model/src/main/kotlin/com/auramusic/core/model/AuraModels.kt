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

