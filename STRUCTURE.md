# Estructura del proyecto — Aura Music

## Árbol general

```text
.
├── .github/workflows/
│   └── build-android-apk.yml          # Workflow manual "apk Debug" sin caché (Rust + Kotlin)
├── android/                           # Proyecto nativo Android (Gradle 8.14.7)
│   ├── gradlew / gradlew.bat          # Wrapper oficial 8.14.7 con bootstrap automático
│   ├── gradle/
│   │   ├── libs.versions.toml         # Catálogo centralizado de dependencias
│   │   └── wrapper/
│   │       └── gradle-wrapper.properties
│   ├── settings.gradle.kts            # Registro de los 10 módulos Gradle
│   ├── build.gradle.kts               # Plugins raíz
│   ├── gradle.properties              # Propiedades JVM/AndroidX (caché deshabilitada)
│   ├── app/                           # Módulo ejecutable principal (:app)
│   │   ├── build.gradle.kts           # minSdk 26, targetSdk 35, ABIs 32/64 bits
│   │   └── src/main/
│   │       ├── AndroidManifest.xml    # Permisos de servicio multimedia y MainActivity
│   │       ├── res/                   # Icono adaptativo, strings y tema base
│   │       └── kotlin/com/auramusic/app/
│   │           ├── AuraApplication.kt # Contenedor modular de dependencias
│   │           ├── AuraTheme.kt       # Esquema de color Material 3 dinámico
│   │           ├── AuraViewModel.kt   # Coordinador StateFlow de UI y casos de uso
│   │           ├── AuraAppRoot.kt     # Navegación, barra inferior, MiniPlayer y SAF
│   │           └── MainActivity.kt    # Punto de entrada edge-to-edge
│   ├── core/
│   │   ├── model/                     # Entidades puras de dominio (Track, Playlist, etc.)
│   │   ├── rust/                      # Puente JNI (AuraRustBridge), códec y tarea cargo-ndk en Gradle
│   │   ├── database/                  # Room (DAOs, entidades, AuraMigrations) y DataStore de tema
│   │   ├── importer/                  # Importador manual SAF (individual, tanda, carpeta) vía Rust fd
│   │   └── player/                    # Media3 ExoPlayer + AuraPlaybackService
│   ├── feature/
│   │   ├── library/                   # Pantalla Biblioteca + buscador + hoja de importación
│   │   ├── playlists/                 # Pantallas Playlists, Favoritos, Detalle y diálogos
│   │   ├── player/                    # MiniPlayerBar y PlayerScreen (carátula cuadrada)
│   │   └── settings/                  # Pantalla Ajustes (color de la app y estado Rust)
│   └── rust/aura_core/                # Crate Rust (Edition 2024)
│       ├── Cargo.toml
│       ├── rust-toolchain.toml        # Targets armv7, arm64 y x86_64
│       └── src/
│           ├── lib.rs                 # Exportaciones JNI para AuraRustBridge
│           ├── metadata.rs            # Lector nativo de tags y carátulas embebidas (lofty)
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
- `:core:rust` compila `rust/aura_core` antes de `preBuild` y expone JNI a `:core:importer` y `:app`.
- `:core:database` depende solo de `:core:model`.
- `:core:importer` depende de `:core:model`, `:core:database` y `:core:rust`.
- `:core:player` depende de `:core:model` y encapsula Media3 ExoPlayer.
- Los módulos `:feature:*` dependen únicamente de `:core:model` (desacoplados entre sí para evitar ciclos y colapsos al escalar).
- `:app` conecta todos los módulos mediante `AuraAppContainer`, `AuraViewModel` y `AuraAppRoot`.
