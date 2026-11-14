# Estructura del proyecto — Aura Music

## Árbol general

```text
.
├── .github/workflows/
│   └── build-android-apk.yml          # Workflow manual "apk Debug" sin caché (Rust + Python 3.11 + Kotlin)
├── android/                           # Proyecto nativo Android (Gradle 8.14.7)
│   ├── gradlew / gradlew.bat          # Wrapper oficial 8.14.7 con bootstrap automático
│   ├── gradle/
│   │   ├── libs.versions.toml         # Catálogo centralizado (incluye Chaquopy 17.2.0 MIT)
│   │   └── wrapper/
│   │       └── gradle-wrapper.properties
│   ├── settings.gradle.kts            # Registro de los 13 módulos Gradle
│   ├── build.gradle.kts               # Plugins raíz
│   ├── gradle.properties              # Propiedades JVM/AndroidX (caché deshabilitada)
│   ├── app/                           # Módulo ejecutable principal (:app)
│   │   ├── build.gradle.kts           # minSdk 26, targetSdk 35, ABIs 32/64 bits, useLegacyPackaging = true
│   │   └── src/main/
│   │       ├── AndroidManifest.xml    # Permisos de red (INTERNET), servicio multimedia y MainActivity
│   │       ├── res/                   # Icono adaptativo, strings y tema base
│   │       └── kotlin/com/auramusic/app/
│   │           ├── AuraApplication.kt # Contenedor modular de dependencias (incluye YtDlpAudioDownloader)
│   │           ├── AuraTheme.kt       # Esquema de color Material 3 dinámico
│   │           ├── AuraViewModel.kt   # Coordinador StateFlow de UI, descargas y casos de uso
│   │           ├── AuraAppRoot.kt     # Navegación, barra inferior, MiniPlayer y launchers SAF/galería
│   │           └── MainActivity.kt    # Punto de entrada edge-to-edge
│   ├── core/
│   │   ├── model/                     # Entidades puras de dominio (Track, Playlist, DownloadState, EqualizerState, etc.)
│   │   ├── rust/                      # Puente JNI (AuraRustBridge), códec y tarea cargo-ndk (--lib --bin aura_qjs)
│   │   ├── database/                  # Room (DAOs, entidades, AuraMigrations) y DataStore (tema, EQ y auto-update)
│   │   ├── importer/                  # Importador SAF + importación de audio descargado vía Rust fd y WebP sin pérdida
│   │   ├── downloader/                # Chaquopy Python 3.11 (.pyc) + aura_ytdlp.py (yt-dlp, yt-dlp-ejs, PyPI SHA-256)
│   │   └── player/                    # Media3 ExoPlayer, AuraPlaybackService y RustEqualizerAudioProcessor
│   ├── feature/
│   │   ├── library/                   # Pantalla Biblioteca + buscador + hoja de importación / enlace a descargas
│   │   ├── downloader/                # Pantalla dedicada Descargar audio + auto-actualizador (sin WebView)
│   │   ├── playlists/                 # Pantallas Playlists, Favoritos, Detalle y diálogos
│   │   ├── player/                    # MiniPlayerBar, PlayerScreen y CoverDynamicTheme (Palette + MaterialKolor)
│   │   ├── settings/                  # Pantalla Ajustes (color global, toggle carátula dinámica, estado Rust y descargas)
│   │   └── equalizer/                 # Pantalla dedicada del ecualizador de 10 bandas (abierta desde PlayerScreen)
│   └── rust/aura_core/                # Crate Rust (Edition 2024)
│       ├── .cargo/config.toml         # Flags NDK (-latomic para armv7-linux-androideabi)
│       ├── Cargo.toml                 # Lib aura_core + binario aura_qjs (rquickjs)
│       ├── rust-toolchain.toml        # Targets armv7, arm64 y x86_64
│       └── src/
│           ├── lib.rs                 # Exportaciones JNI para AuraRustBridge
│           ├── bin/aura_qjs.rs        # Binario CLI empaquetado como libaura_qjs.so para yt-dlp + QuickJS-NG
│           ├── js_runtime.rs          # Motor JavaScript QuickJS-NG embebido en Rust (rquickjs)
│           ├── metadata.rs            # Lector nativo de tags y carátulas embebidas (lofty)
│           ├── equalizer.rs           # Motor DSP de ecualización de 10 bandas (filtros biquad IIR f64)
│           ├── artwork.rs             # Motor procedural de carátulas (tiny-skia) y SHA-256
│           └── palette.rs             # Generador armónico de paletas cromáticas
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
- Los módulos `:feature:*` (incluyendo `:feature:downloader`) dependen únicamente de `:core:model` (desacoplados entre sí para evitar ciclos y colapsos al escalar).
- `:app` conecta todos los módulos mediante `AuraAppContainer`, `AuraViewModel` y `AuraAppRoot`.
