# AI Context — Aura Music

Este archivo resume las decisiones de arquitectura, restricciones técnicas y reglas de producto que cualquier modelo de IA debe respetar al trabajar en **Aura Music**.

## 1. Contexto de doble entorno (Web + Móvil)
- **Web (`src/App.tsx`)**: Debe mostrar **únicamente** una pantalla limpia con `"Hello World"` para mantener el entorno de previsualización web compilando sin errores. No añadir interfaz musical ni paneles adicionales en la web salvo petición explícita.
- **Móvil (`android/`)**: Aquí reside la aplicación real en **Kotlin + Jetpack Compose + Rust (Edition 2024) + Python 3.11 ligero (Chaquopy)**.

## 2. Reglas de arquitectura y compilación Android
- **Versiones objetivo**: `minSdk = 26` (Android 8.0), `targetSdk = 35`, `compileSdk = 35`, Gradle `8.14.7`, AGP `8.9.1`, Kotlin `2.1.20`.
- **Arquitecturas obligatorias**: Soporte completo para **32 bits (`armeabi-v7a`)** y **64 bits (`arm64-v8a`, `x86_64`)** tanto en Rust (`aura_core` + `aura_qjs`) como en Chaquopy (`python = "3.11"`).
- **Distribución**: La app está pensada para instalarse vía APK directo o **Uptodown** (no Google Play). No introducir dependencias ligadas a servicios exclusivos de Google Play.
- **Rust sin fallbacks**: Toda funcionalidad delegada a Rust (lectura de metadatos y carátulas embebidas vía `lofty` desde descriptor SAF, generación de carátulas procedurales, fingerprinting SHA-256, el ecualizador DSP de 10 bandas en `aura_core::equalizer` sobre Media3 `DirectByteBuffer` y el runtime JavaScript `QuickJS-NG` vía `rquickjs` / `libaura_qjs.so`) debe compilarse en `gradlew` vía `cargo-ndk` (`:core:rust:buildRustAndroidLibs`). Nunca reemplazar lógica Rust con fallbacks en Kotlin (como `MediaMetadataRetriever` o `android.media.audiofx.Equalizer`).
- **Motor de descargas (`yt-dlp` puro + `yt-dlp-ejs` + `QuickJS-NG`)**:
  - Se ejecuta mediante **Chaquopy 17.2.0** con Python `3.11` compilado a `.pyc`, instalando `yt-dlp` y `yt-dlp-ejs` con `--no-deps` y `extractPackages("yt_dlp_ejs")`.
  - No usar wrappers Java/Kotlin ni librerías copyleft (`youtubedl-android`, `NewPipeExtractor`, `RustyPipe`, `FFmpeg`).
  - No incluir `WebView` para descargas o extracción de cookies/tokens salvo petición explícita del usuario.
  - `aura_qjs` debe empaquetarse como `jniLibs/<abi>/libaura_qjs.so` con `useLegacyPackaging = true` en `:app` para poder ejecutarse desde `nativeLibraryDir` cumpliendo SELinux W^X en Android 10+.
  - La auto-actualización en runtime descarga wheels oficiales desde PyPI (`yt-dlp` y `yt-dlp-ejs`), verifica `sha256`, extrae en `filesDir/py_updates` y antepone las carpetas a `sys.path`.
- **Migraciones Room seguras**: `AuraDatabase` debe mantener `exportSchema = true` (esquemas exportados vía KSP en `android/core/database/schemas`) y registrar toda evolución de esquema en `AuraMigrations.ALL_MIGRATIONS` sin usar `fallbackToDestructiveMigration`.
- **Licencias**: Usar exclusivamente librerías y crates con licencias permisivas (**Apache-2.0, MIT, BSD, Unlicense, ISC**). Prohibido agregar dependencias GPL/LGPL/AGPL/CC-BY que obliguen a liberar código fuente o mostrar créditos obligatorios.
- **Propiedad intelectual**: Prohibido nombrar archivos, paquetes o identificadores con marcas registradas de terceros.

## 3. Reglas de producto y UX
- **Importación explícita y eficiente**: Nunca escanear automáticamente todo el almacenamiento del dispositivo. Solo importar lo que el usuario elija (individual, tanda o carpeta completa con SAF) o lo que descargue manualmente en `:feature:downloader`, usando consulta única de identidades en Room y concurrencia acotada.
- **Carátulas**: Al extraer portadas embebidas, detectar el formato real por magic bytes (sin forzar `.jpg`). Las carátulas personalizadas elegidas por el usuario desde su galería o descargadas como miniatura deben convertirse en segundo plano a **WebP sin pérdida a máxima compresión** (`WEBP_LOSSLESS` / calidad `100`) eliminando portadas huérfanas anteriores.
- **Reproductor sobrio con color dinámico de portada**: Carátula cuadrada normal con bordes suavizados; evitar diseños experimentales confusos. El `MiniPlayerBar` y `PlayerScreen` adaptan sus colores con animaciones suaves a partir de la carátula activa usando `androidx.palette:palette` + `MaterialKolor` (`minSdk = 26`), con interruptor en Ajustes.
- **Ubicación del Ecualizador**: El ecualizador de 10 bandas (`:feature:equalizer`) debe abrirse únicamente desde el reproductor (`PlayerScreen`), nunca como tarjeta dentro de `SettingsScreen`.
- **Diseño no minimalista en exceso**: Usar superficies oscuras con matices tonales del color elegido, tarjetas con jerarquía visual clara, iconos descriptivos y separación en pantallas dedicadas (incluyendo pantalla propia para el Ecualizador y Descargas) + popups/bottom sheets cuando corresponda.
- **Concurrencia**: Usar coroutines (`Dispatchers.IO` para Room/SAF/archivos/red/Chaquopy Python/decodificación de bitmap, `Dispatchers.Default` para JNI/Rust/compresión WebP/Palette) sin bloquear el hilo principal.
- **Comentarios**: Todo archivo de código debe explicar claramente qué hace su lógica.
