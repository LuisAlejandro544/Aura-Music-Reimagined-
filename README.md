# Aura Music

Reproductor de música local y descargador de solo audio para Android construido con **Kotlin (Jetpack Compose + Media3 ExoPlayer + Room)**, un motor nativo en **Rust (Edition 2024)** vía JNI y un intérprete ligero en **Python 3.11 (Chaquopy)** para `yt-dlp`.

## Características actuales

- **Importación manual de música (SAF) con parser en Rust**: Aura Music nunca escanea todo el teléfono. El usuario decide exactamente qué importar:
  - Una canción individual, varias por tandas o una carpeta completa (con subcarpetas) manteniendo permisos persistentes.
  - Procesamiento por lotes optimizado con deduplicación única en Room y concurrencia acotada en segundo plano.
  - La lectura de etiquetas (ID3, Vorbis, FLAC, MP4/M4A, WAV, AIFF), duración y carátula embebida se ejecuta en Rust (`lofty`) directamente desde el descriptor SAF, guardando la imagen con su extensión real (`.jpg`, `.png`, `.webp`, `.gif`, `.bmp`).
- **Descarga de solo audio con `yt-dlp` + `yt-dlp-ejs` + `QuickJS-NG` (sin WebView ni FFmpeg)**:
  - Pantalla dedicada (`:feature:downloader`) accesible desde el menú *Importar* de Biblioteca o desde *Ajustes*.
  - Intérprete ligero **Python 3.11** (Chaquopy MIT) compilado a bytecode `.pyc`, instalando únicamente `yt-dlp` y `yt-dlp-ejs` con `--no-deps` para reducir peso.
  - **QuickJS-NG compilado en Rust (`rquickjs` -> `libaura_qjs.so`)**: resuelve los retos JavaScript EJS (`yt-dlp-ejs`) exigidos por YouTube en 32 y 64 bits sin Node.js, Deno ni WebView.
  - Descarga flujos de audio nativos compatibles con Media3 ExoPlayer (`m4a` / `webm` / `opus`) sin depender de FFmpeg, lee sus metadatos con Rust (`lofty`), convierte su miniatura a WebP sin pérdida y los integra automáticamente en Room.
  - **Auto-actualización independiente en la app**: comprueba y descarga wheels oficiales `py3-none-any.whl` de `yt-dlp` y `yt-dlp-ejs` desde PyPI con verificación criptográfica **SHA-256** y recarga en caliente sin recompilar el APK.
- **Ecualizador gráfico de 10 bandas en Rust (DSP en tiempo real)**: Filtros IIR biquad en precisión `f64` (`31 Hz` a `16 kHz`) ejecutados in-place desde `aura_core` sobre `DirectByteBuffer` de Media3 ExoPlayer, con 8 perfiles predefinidos, curva de respuesta y persistencia en DataStore (accesible directamente desde el botón de ecualizador en el reproductor).
- **Reproductor, Mini Reproductor y colores dinámicos de carátula**: Basados en **AndroidX Media3 ExoPlayer** y `MediaSessionService`, con carátula cuadrada clásica, selector de imagen de galería convertido en segundo plano a **WebP sin pérdida a máxima compresión**, carátulas procedurales en Rust (`tiny-skia`) y extracción animada de colores de portada desde Android 8.0 (`minSdk = 26`) mediante **AndroidX Palette** + **MaterialKolor** (`material-kolor`).
- **Me gusta y Playlists con migraciones seguras**: Botón de corazón en pistas, mini reproductor y reproductor completo; sección dedicada de *Canciones que te gustan* y playlists personalizadas respaldadas por **Room** con exportación de esquemas (`exportSchema = true`) y migraciones explícitas no destructivas (`AuraMigrations`).
- **Ajustes de apariencia**: Selector de 8 paletas de color dinámicas (Material 3), interruptor para activar/desactivar los colores dinámicos de carátula persistido con **Jetpack DataStore**, acceso al motor de descargas/actualizaciones y estado del motor nativo Rust.

## Requisitos técnicos

- **Android**: `minSdk = 26` (Android 8.0) • `targetSdk = 35` • `compileSdk = 35`.
- **Arquitecturas nativas**: 32 bits (`armeabi-v7a`) y 64 bits (`arm64-v8a`, `x86_64`) compiladas en APK universal para instalación directa o distribución en Uptodown.
- **Licencias permisivas**: Solo dependencias MIT / Apache-2.0 / BSD / Unlicense (`Chaquopy 17.2.0` MIT, `rquickjs 0.12.2` MIT, `yt-dlp` Unlicense, `yt-dlp-ejs` Unlicense, `lofty` MIT/Apache-2.0, `MaterialKolor` MIT). Sin FFmpeg ni wrappers copyleft.
- **Rust**: Toolchain `stable` (Edition 2024) + `cargo-ndk` + `clang`/`libclang-dev` (para `rquickjs` bindgen).
- **Python de compilación**: Python `3.11` (requerido por Chaquopy para compilación `.pyc` compatible con 32 y 64 bits).
- **Gradle Wrapper**: `8.14.7` (AGP `8.9.1`, Kotlin `2.1.20`).

## Cómo compilar el APK Debug

### Opción 1: Desde GitHub Actions (sin PC, directo desde el teléfono)
1. Abre la pestaña **Actions** en el repositorio de GitHub.
2. Selecciona el workflow **apk Debug**.
3. Pulsa **Run workflow**.
4. Al finalizar, descarga el artefacto **AuraMusic-apk-Debug** con el APK listo para instalar.

### Opción 2: Compilación local (Linux / macOS)
```bash
cd android
./gradlew assembleDebug --no-daemon --no-build-cache
```
El APK generado se guarda en `android/app/build/outputs/apk/debug/`.

## Entorno web del repositorio

La raíz contiene una vista web mínima (`Hello World` en `src/App.tsx`) para mantener el entorno de previsualización web compilando sin errores mientras la aplicación móvil se desarrolla bajo `android/`.
