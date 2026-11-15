# Roadmap — Aura Music

Plan de evolución por fases para convertir Aura Music en un reproductor local modular de alto rendimiento en Kotlin + Rust + Python ligero.

## Fase 1 — Base funcional (Completada)
- Estructura multi-módulo en Gradle (`:app`, `:core:*`, `:feature:*`) y crate nativo `aura_core` en Rust Edition 2024.
- Compilación cruzada obligatoria de Rust en `gradlew` vía `cargo-ndk` para 32 bits (`armeabi-v7a`) y 64 bits (`arm64-v8a`, `x86_64`).
- Importación manual con Storage Access Framework (1 canción, selección múltiple y carpeta completa).
- Reproductor Media3 ExoPlayer con `MediaSessionService`, mini reproductor y reproductor completo con carátula cuadrada clásica.
- Sistema de "Me gusta" (corazones) y playlists en Room.
- Infraestructura de migraciones no destructivas en Room (`AuraMigrations` + `exportSchema = true` en KSP) lista antes de ampliar tablas.
- Generación procedural determinista de carátulas en Rust sin IA (`tiny-skia`).
- Apartado de Ajustes con cambio de paleta de color en tiempo real vía DataStore.

## Fase 2 — Procesamiento de audio, carátulas y descargas nativas (En curso)
- [x] Lectura rápida de etiquetas (ID3, Vorbis, FLAC, MP4, WAV, AIFF), duración y carátulas embebidas directamente desde Rust (`lofty`) vía descriptor de archivo SAF (`ParcelFileDescriptor`), guardando el formato de imagen real (`.png`, `.jpg`, `.webp`, etc.).
- [x] Importación paralela acotada con consulta única de identidades en Room y carátulas personalizadas desde galería convertidas en segundo plano a WebP sin pérdida a máxima compresión.
- [x] Ecualizador gráfico de 10 bandas en Rust (`aura_core::equalizer`) conectado en tiempo real a Media3 ExoPlayer y configurable exclusivamente desde el reproductor.
- [x] **Efectos Crossfeed binaural, Audio 8D, Reverb estéreo y Slowed en Rust** (`aura_core::crossfeed` + `aura_core::spatial_8d` + `aura_core::reverb` + `:feature:effects`): Crossfeed binaural estático (`StaticCrossfeedProcessor` `f64` con ITD fraccional y sombra craneal LPF para escucha prolongada con auriculares), espacializador 8D psicoacústico (órbita 360° con ITD fraccionario, sombra de cabeza ILD, cross-feed controlado y ancla de graves centrada + radar animado en UI), reverberación Freeverb (8 combs + 4 allpass `f64`), filtro paso bajo de calidez analógica (`AudioEffectsProcessor`), 9 modos rápidos (*Crossfeed Auriculares*, *Audio 8D*, *8D + Slowed*, *Slowed + Reverb*, *Solo Slowed*, *Sala íntima*, *Hall*, *Catedral Etérea*) y *varispeed* con tono natural estilo vinilo vía Media3 `PlaybackParameters`, persistidos en DataStore desde el menú de 3 puntos de `PlayerScreen`.
- [x] Colores dinámicos animados desde la carátula para `MiniPlayerBar` y `PlayerScreen` (`androidx.palette:palette` + `MaterialKolor`) compatibles desde Android 8.0 y configurables desde Ajustes.
- [x] Descarga de solo audio en segundo plano (`AuraDownloadForegroundService`) con **Python 3.11 ligero (Chaquopy)** + **`yt-dlp`** + **`yt-dlp-ejs`** + **QuickJS-NG compilado en Rust (`libaura_qjs.so`)**, con selector de **calidad/formato de audio** (*Máxima*, *Equilibrada*, *Ligera* y flujos reales por bitrate/códec en vista previa), notificación de progreso/cancelación y auto-actualización verificada por SHA-256 desde PyPI sin WebView ni FFmpeg.
- [x] Animaciones creativas de eliminación en **Rust** (`aura_core::delete_fx`): al confirmar borrar o quitar una canción, ejecuta aleatoriamente *Cenizas*, *Papel quemándose* o *Aura extraña* sobre `DirectByteBuffer` RGBA8888, con interruptor persistido en *Ajustes*.
- [ ] Análisis de ganancia (ReplayGain) y extracción de forma de onda (waveform) en Rust para previsualización de pistas.

## Fase 3 — Gestión avanzada de biblioteca, reproducción e integración del sistema (En curso)
- [x] Playlists automáticas por nombre de artista (`ArtistPlaylistBuilder` + `ArtistDetailScreen`) que agrupan canciones del mismo artista y reproducen únicamente sus temas al entrar en su lista.
- [x] Cola de reproducción interactiva (`QueueScreen` + `PlaybackQueueHelper`: "Reproducir siguiente", "Añadir a la cola", saltar a pista, reordenar posiciones, quitar elementos y limpiar siguientes).
- [x] Sistema **"Abrir con Aura Music"** (`Intent.ACTION_VIEW` para `content://` y `file://`), con deduplicación en Room, copia segura de grants temporales en `filesDir/opened_audio/` y reproducción directa en `PlayerScreen`.
- [x] **Crossfade entre canciones, velocidad y velocidad/tono de voz**: transición equal-power en segundo plano (`AuraPlaybackService`) configurable (`1 s – 12 s`), control de velocidad (`0.50x – 2.00x`) complementado con **velocidad/tono de voz** (`0.50x – 2.00x` e interruptor *Vincular voz con velocidad*) persistidos en DataStore y organizados en el **menú de 3 puntos** de `PlayerScreen`.
- [x] **Edición de metadatos básicos en Room** (`TrackMetadataHelper` + `EditTrackMetadataDialog`): permite editar localmente el nombre de la canción y el nombre del artista desde *Biblioteca*, *Favoritos*, *Playlists*, *Artistas* y el menú de 3 puntos de `PlayerScreen`, actualizando reactivamente las listas por artista, la cola y la notificación del sistema (`replaceMediaItem`) sin modificar el archivo SAF original.
- [x] **Detección inteligente de salida de audio y botones multimedia (Cable / Bluetooth AVRCP)**: `AuraAudioOutputMonitor` (`AudioDeviceCallback`, `minSdk = 26` sin permisos extra de Bluetooth) para pausa/reanudación, pausa automática de Audio 8D en altavoz y activación automática de Crossfeed en auriculares, más `AuraMediaSessionCallback` para gestos de botón único (`KEYCODE_HEADSETHOOK` doble/triple toque), umbral de botón *Anterior*, navegación circular en cola y salto rápido AVRCP (`5/10/15/30 s`).
- [x] **Temporizador de apagado automático (*Sleep Timer*)**: ejecutado en segundo plano dentro de `AuraPlaybackService` (`AuraSleepTimerBridge` + `SleepTimerBottomSheet`) con minutos preestablecidos (`5, 10, 15, 30, 45, 60, 90 min`), tiempo personalizado (`1–480 min`), opciones para parar al terminar la canción actual o toda la música de la cola, ajuste `-5 / +5 min` y atenuación suave al finalizar.
- [ ] Soporte para letras locales (`.lrc` embebidas o externas).
- [ ] Exportación e importación de playlists en formato `.m3u8`.

## Fase 4 — Pulido, personalización y distribución
- Opciones ampliadas en Ajustes (estilo de carátulas procedurales, gestión de caché y limpieza de portadas).
- Widget de escritorio para Android con controles rápidos.
- Firma Release reproducible y publicación de paquetes universales optimizados para Uptodown y tiendas de terceros.
