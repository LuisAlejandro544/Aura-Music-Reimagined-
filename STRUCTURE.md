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
│   │       ├── AndroidManifest.xml    # Permisos de red (INTERNET), almacenamiento opcional para Aura Music (READ/WRITE/MANAGE_EXTERNAL_STORAGE), foreground service (mediaPlayback/dataSync), WAKE_LOCK e intent-filter ACTION_VIEW
│   │       ├── res/                   # Icono adaptativo, strings y tema base
│   │       └── kotlin/com/auramusic/app/
│   │           ├── AuraApplication.kt           # Contenedor multi-proceso (MAIN, :player, :downloader), Coil acotado RGB_565 y onTrimMemory
│   │           ├── AuraTheme.kt                 # Esquema de color Material 3 dinámico
│   │           ├── AuraPlaybackCoordinator.kt   # Coordinador modular de cola, transporte, velocidad, tono de voz, Crossfade y temporizador
│   │           ├── AuraAudioTuningCoordinator.kt # Coordinador modular de ecualizador, efectos Rust y preferencias de auriculares/AVRCP
│   │           ├── AuraLibraryCoordinator.kt    # Coordinador modular de biblioteca, playlists, importación SAF, carpeta del sistema Aura Music, descargas yt-dlp y tema visual
│   │           ├── AuraViewModel.kt             # Orquestador StateFlow principal conectado a los coordinadores modulares de :app
│   │           ├── AuraAppChrome.kt             # Launchers SAF, flujo de permisos de almacenamiento y barra inferior compartida
│   │           ├── AuraAppRoot.kt               # Navegación (incluye QueueScreen, ArtistDetailScreen, AudioEffectsScreen y SettingsSystemFolderScreen) y modales
│   │           └── MainActivity.kt              # Punto de entrada edge-to-edge, ciclo onStart/onStop y receptor ACTION_VIEW
│   ├── core/
│   │   ├── model/                     # Entidades y lógica pura (Track, Playlist, ArtistPlaylistBuilder, TrackMetadataHelper, AudioDownloadQuality, DownloadFormatHelper, PlaybackQueueHelper, PlaybackTuningHelper, SleepTimerState, SleepTimerHelper, AudioEffectsState, AudioOutputRouteState, HeadphonePreferences, HeadphoneRoutingHelper, SystemFolderStorageState, SystemFolderHelper, AuraProcessRole, MemoryTrimPolicy, etc.)
│   │   ├── rust/                      # Puente JNI (AuraRustBridge), códec y tarea cargo-ndk (--lib --bin aura_qjs)
│   │   ├── database/                  # Room (DAOs, entidades, migración de rutas de almacenamiento, AuraMigrations) y DataStore (tema, EQ, 8D/Reverb/Slowed/Crossfeed, auriculares/AVRCP, carpeta del sistema desactivada por defecto, animaciones, crossfade/velocidad/tono de voz, calidad y auto-update)
│   │   ├── importer/                  # Importador SAF, AuraSystemStorageManager (carpeta pública Aura Music/Música, Carátulas y .json), receptor "Abrir con" (opened_audio/), importación Rust fd y WebP sin pérdida
│   │   ├── downloader/                # Chaquopy Python 3.11 (.pyc), aura_ytdlp.py, AuraDownloadForegroundService (proceso :downloader), AuraDownloaderIpc y AuraDownloaderRemoteClient
│   │   └── player/                    # Media3 ExoPlayer en proceso :player, AuraAudioOutputMonitor, AuraSleepTimerBridge, AuraPlayerConfigStore, AuraPlayerIpc, AuraPlaybackService, AuraPlayerController y RustEqualizerAudioProcessor
│   ├── feature/
│   │   ├── library/                   # Pantalla Biblioteca + buscador + menú de cola/edición de metadatos + hoja de importación / enlace a descargas + animación Rust
│   │   ├── downloader/                # Pantalla dedicada Descargar audio + selector de calidad/formato real + vista previa + cancelación + auto-actualizador (sin WebView)
│   │   ├── playlists/                 # PlaylistsScreen (pestañas Mis playlists y Artistas), LikedSongsScreen, PlaylistDetailScreen y ArtistDetailScreen (con edición de metadatos)
│   │   ├── player/                    # MiniPlayerBar, PlayerScreen (menú de 3 puntos con Velocidad/Tono de voz y Crossfade, Temporizador, 8D/Reverb/Slowed/Crossfeed, edición de metadatos), SleepTimerBottomSheet, QueueScreen y CoverDynamicTheme (Palette + MaterialKolor)
│   │   ├── settings/                  # Hub de Ajustes + subpantallas dedicadas (Tema, Efectos visuales, Auriculares, Botones multimedia y Carpeta del sistema Aura Music)
│   │   ├── equalizer/                 # Pantalla dedicada del ecualizador de 10 bandas (abierta desde PlayerScreen)
│   │   └── effects/                   # Pantalla dedicada AudioEffectsScreen para Crossfeed binaural estático, Audio 8D, Reverb estéreo y modo Slowed en Rust (abierta desde PlayerScreen)
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
│           ├── crossfeed.rs           # Procesador de Crossfeed binaural estático para auriculares (ITD fraccional + LPF sombra craneal f64)
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
