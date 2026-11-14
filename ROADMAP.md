# Roadmap — Aura Music

Plan de evolución por fases para convertir Aura Music en un reproductor local modular de alto rendimiento en Kotlin + Rust.

## Fase 1 — Base funcional (Completada)
- Estructura multi-módulo en Gradle (`:app`, `:core:*`, `:feature:*`) y crate nativo `aura_core` en Rust Edition 2024.
- Compilación cruzada obligatoria de Rust en `gradlew` vía `cargo-ndk` para 32 bits (`armeabi-v7a`) y 64 bits (`arm64-v8a`, `x86_64`).
- Importación manual con Storage Access Framework (1 canción, selección múltiple y carpeta completa).
- Reproductor Media3 ExoPlayer con `MediaSessionService`, mini reproductor y reproductor completo con carátula cuadrada clásica.
- Sistema de "Me gusta" (corazones) y playlists en Room.
- Generación procedural determinista de carátulas en Rust sin IA (`tiny-skia`).
- Apartado de Ajustes con cambio de paleta de color en tiempo real vía DataStore.

## Fase 2 — Procesamiento de audio y metadatos en Rust
- Lectura rápida de etiquetas (ID3, Vorbis, FLAC, MP4) directamente desde Rust para acelerar importaciones masivas.
- Análisis de ganancia (ReplayGain) y extracción de forma de onda (waveform) en Rust para previsualización de pistas.
- Ecualizador multibanda y refuerzo de graves configurables desde Ajustes.

## Fase 3 — Gestión avanzada de biblioteca y playlists
- Cola de reproducción interactiva ("Reproducir siguiente", añadir a cola, reordenar canciones mediante arrastre).
- Edición de metadatos básicos de pistas dentro de la base de datos local.
- Soporte para letras locales (`.lrc` embebidas o externas).
- Exportación e importación de playlists en formato `.m3u8`.
- Temporizador de apagado automático (*Sleep Timer*).

## Fase 4 — Pulido, personalización y distribución
- Opciones ampliadas en Ajustes (estilo de carátulas procedurales, comportamiento al conectar auriculares/Bluetooth, gestión de caché).
- Widget de escritorio para Android con controles rápidos.
- Firma Release reproducible y publicación de paquetes universales optimizados para Uptodown y tiendas de terceros.
