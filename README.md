# Aura Music

Reproductor de música local para Android construido con **Kotlin (Jetpack Compose + Media3 ExoPlayer + Room)** y un motor nativo en **Rust (Edition 2024)** enlazado vía JNI.

## Características actuales

- **Importación manual de música (SAF) con parser en Rust**: Aura Music nunca escanea todo el teléfono. El usuario decide exactamente qué importar:
  - Una canción individual, varias por tandas o una carpeta completa (con subcarpetas) manteniendo permisos persistentes.
  - Procesamiento por lotes optimizado con deduplicación única en Room y concurrencia acotada en segundo plano.
  - La lectura de etiquetas (ID3, Vorbis, FLAC, MP4/M4A, WAV, AIFF), duración y carátula embebida se ejecuta en Rust (`lofty`) directamente desde el descriptor SAF, guardando la imagen con su extensión real (`.jpg`, `.png`, `.webp`, `.gif`, `.bmp`).
- **Ecualizador gráfico de 10 bandas en Rust (DSP en tiempo real)**: Filtros IIR biquad en precisión `f64` (`31 Hz` a `16 kHz`) ejecutados in-place desde `aura_core` sobre `DirectByteBuffer` de Media3 ExoPlayer, con 8 perfiles predefinidos, curva de respuesta y persistencia en DataStore.
- **Reproductor, Mini Reproductor y carátulas personalizadas**: Basados en **AndroidX Media3 ExoPlayer** y `MediaSessionService`, con carátula cuadrada clásica, selector de imagen de galería convertido en segundo plano a **WebP sin pérdida a máxima compresión** y carátulas procedurales deterministas en Rust (`tiny-skia`) cuando no hay portada.
- **Me gusta y Playlists con migraciones seguras**: Botón de corazón en pistas, mini reproductor y reproductor completo; sección dedicada de *Canciones que te gustan* y playlists personalizadas respaldadas por **Room** con exportación de esquemas (`exportSchema = true`) y migraciones explícitas no destructivas (`AuraMigrations`).
- **Ajustes de apariencia y audio**: Selector de 8 paletas de color dinámicas (Material 3) persistidas con **Jetpack DataStore**, acceso al ecualizador de 10 bandas y estado del motor nativo Rust.

## Requisitos técnicos

- **Android**: `minSdk = 26` (Android 8.0) • `targetSdk = 35` • `compileSdk = 35`.
- **Arquitecturas nativas**: 32 bits (`armeabi-v7a`) y 64 bits (`arm64-v8a`, `x86_64`) compiladas en APK universal para instalación directa o distribución en Uptodown.
- **Rust**: Toolchain `stable` (Edition 2024) + `cargo-ndk` + crate `lofty` (MIT/Apache-2.0).
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
