# Estructura del proyecto — Aura Music

## Árbol general

```text
.
├── .github/workflows/
│   └── build-android-apk.yml          # Workflow manual "apk Debug" sin caché (universal o individuales 32/64 bits)
├── android/                           # Proyecto nativo Android (Gradle 8.14.7)
│   ├── gradlew / gradlew.bat          # Wrapper oficial 8.14.7 con bootstrap automático
│   ├── gradle/
│   │   ├── libs.versions.toml         # Catálogo centralizado (incluye Chaquopy 17.2.0 MIT)
│   │   └── wrapper/
│   │       └── gradle-wrapper.properties
│   ├── settings.gradle.kts            # Registro de los 14 módulos Gradle
│   ├── build.gradle.kts               # Plugins raíz
│   ├── gradle.properties              # Propiedades JVM/AndroidX (caché deshabilitada)
│   ├── app/                           # Módulo ejecutable principal (:app)
│   │   ├── build.gradle.kts           # minSdk 26, targetSdk 35, ABIs 32/64 bits, useLegacyPackaging = true
│   │   └── src/main/
│   │       ├── AndroidManifest.xml    # Permisos de red (INTERNET), foreground service (mediaPlayback/dataSync), WAKE_LOCK e intent-filter ACTION_VIEW ("Abrir con")
│   │       ├── res/                   # Icono adaptativo, strings y tema base
│   │       └── kotlin/com/auramusic/app/
│   │           ├── AuraApplication.kt # Contenedor modular de dependencias (implementa AuraDownloadHost y AuraPlaybackTuningProvider)
│   │           ├── AuraTheme.kt       # Esquema de color Material 3 dinámico
│   │           ├── AuraViewModel.kt     # Coordinador StateFlow de biblioteca, edición de metadatos, artistas automáticos, descargas, cola, velocidad, crossfade, Reverb/Slowed y "Abrir con"
│   │           ├── AuraAppRoot.kt     # Navegación (incluye QueueScreen, ArtistDetailScreen y AudioEffectsScreen), EditTrackMetadataDialog, apertura desde intents externos, barra inferior, MiniPlayer y Snackbars
│   │           └── MainActivity.kt    # Punto de entrada edge-to-edge y receptor onCreate/onNewIntent para ACTION_VIEW
│   ├── core/
│   │   ├── model/                     # Entidades y lógica pura (Track, Playlist, ArtistPlaylistBuilder, TrackMetadataHelper, AudioDownloadQuality, DownloadFormatHelper, PlaybackQueueHelper, PlaybackTuningHelper, AudioEffectsState, AudioEffectPreset, DownloadState, EqualizerState, etc.)
│   │   ├── rust/                      # Puente JNI (AuraRustBridge), códec y tarea cargo-ndk (--lib --bin aura_qjs)
│   │   ├── database/                  # Room (DAOs, entidades, actualización local de metadatos, AuraMigrations) y DataStore (tema, EQ, 8D/Reverb/Slowed, animaciones, crossfade/velocidad, calidad de descarga y auto-update)
│   │   ├── importer/                  # Importador SAF, receptor seguro "Abrir con" (opened_audio/), importación de audio descargado vía Rust fd y WebP sin pérdida
│   │   ├── downloader/                # Chaquopy Python 3.11 (.pyc), aura_ytdlp.py, AuraDownloadForegroundService (dataSync) y AndroidManifest.xml
│   │   └── player/                    # Media3 ExoPlayer, AuraPlaybackService (Crossfade + Velocidad + Slowed varispeed + SessionControlForwardingPlayer + setSessionActivity), AuraPlayerController (syncMediaItemsMetadata), RustEqualizerAudioProcessor y res/drawable/ic_aura_notification.xml
│   ├── feature/
│   │   ├── library/                   # Pantalla Biblioteca + buscador + menú de cola/edición de metadatos + hoja de importación / enlace a descargas + animación Rust
│   │   ├── downloader/                # Pantalla dedicada Descargar audio + selector de calidad/formato real + vista previa + cancelación + auto-actualizador (sin WebView)
│   │   ├── playlists/                 # PlaylistsScreen (pestañas Mis playlists y Artistas), LikedSongsScreen, PlaylistDetailScreen y ArtistDetailScreen (con edición de metadatos)
│   │   ├── player/                    # MiniPlayerBar, PlayerScreen (menú de 3 puntos con 8D/Reverb/Slowed, edición de metadatos + hoja modal Velocidad y Crossfade), QueueScreen y CoverDynamicTheme (Palette + MaterialKolor)
│   │   ├── settings/                  # Pantalla Ajustes (color global, toggles carátula dinámica/animaciones Rust y estado Rust)
│   │   ├── equalizer/                 # Pantalla dedicada del ecualizador de 10 bandas (abierta desde PlayerScreen)
│   │   └── effects/                   # Pantalla dedicada AudioEffectsScreen para Audio 8D, Reverb estéreo y modo Slowed en Rust (abierta desde PlayerScreen)
│   └── rust/aura_core/                # Crate Rust (Edition 2024)
│       ├── .cargo/config.toml         # Flags NDK (-latomic para armv7-linux-androideabi)
│       ├── Cargo.toml                 # Lib aura_core + binario aura_qjs (rquickjs)
│       ├── rust-toolchain.toml        # Targets armv7, arm64 y x86_64
│       └── src/
│           ├── lib.rs                 # Exportaciones JNI para AuraRustBridge
│           ├── bin/aura_qjs.rs        # Binario CLI empaquetado como libaura_qjs.so para yt-dlp + QuickJS-NG
│           ├── js_runtime.rs          # Motor JavaScript QuickJS-NG embebido en Rust (rquickjs)
│           ├── metadata.rs            # Lector nativo de tags y carátulas embebidas (lofty)
│           ├── equalizer.rs           # Motor DSP de ecualización de 10 bandas (biquad IIR f64) + etapa final AudioEffectsProcessor
│           ├── reverb.rs              # Procesador Freeverb estéreo (8 combs + 4 allpass f64) y filtro paso bajo de calidez analógica Slowed
│           ├── spatial_8d.rs          # Procesador Audio Espacial 8D (órbita 360°, ITD interpolado, sombra craneal ILD y ancla de graves)
│           ├── artwork.rs             # Motor procedural de carátulas (tiny-skia) y SHA-256
│           ├── palette.rs             # Generador armónico de paletas cromáticas
│           └── delete_fx.rs           # Renderizador de animaciones de eliminación (cenizas, papel quemándose, aura extraña)
├── src/                               # Entorno web mínimo ("Hello World") para previsualización
├── tests/
│   └── project-integrity.test.ts      # Validación estructural automatizada (node:test)
├── README.md
├── ROADMAP.md
├── STRUCTURE.md
├── AI_CONTEXT.md
├── AGENTS.md
└── README_AI.md
```

## Flujo de dependencias entre módulos

- `:core:model` no depende de Android SDK; es compartido por todas las capas.
- `:core:rust` compila `libaura_core.so` y `libaura_qjs.so` antes de `preBuild` y expone JNI + ruta ejecutable QuickJS-NG a `:core:importer`, `:core:downloader`, `:core:player` y `:app`.
- `:core:database` depende solo de `:core:model`.
- `:core:importer` depende de `:core:model`, `:core:database` y `:core:rust`.
- `:core:downloader` depende de `:core:model`, `:core:database`, `:core:rust` y `:core:importer`; encapsula Chaquopy Python 3.11 (`yt-dlp` y `yt-dlp-ejs`) sin exponer detalles Python al resto de módulos.
- `:core:player` depende de `:core:model` y `:core:rust` para encapsular Media3 ExoPlayer junto al procesador DSP Rust.
- Los módulos `:feature:*` (incluyendo `:feature:downloader`) dependen únicamente de `:core:model` (desacoplados entre sí para evitar ciclos y colapsos al escalar; por ejemplo, las animaciones Rust se invocan mediante `DeleteEffectFrameRenderer`).
- `:app` conecta todos los módulos mediante `AuraAppContainer`, `AuraViewModel` y `AuraAppRoot`.
