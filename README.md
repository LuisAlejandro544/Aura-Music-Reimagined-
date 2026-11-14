# Aura Music

Reproductor de música local y descargador de solo audio para Android construido con **Kotlin (Jetpack Compose + Media3 ExoPlayer + Room)**, un motor nativo en **Rust (Edition 2024)** vía JNI y un intérprete ligero en **Python 3.11 (Chaquopy)** para `yt-dlp`.

## Características actuales

- **Importación manual de música (SAF) con parser en Rust**: Aura Music nunca escanea todo el teléfono. El usuario decide exactamente qué importar:
  - Una canción individual, varias por tandas o una carpeta completa (con subcarpetas) manteniendo permisos persistentes.
  - Procesamiento por lotes optimizado con deduplicación única en Room y concurrencia acotada en segundo plano.
  - La lectura de etiquetas (ID3, Vorbis, FLAC, MP4/M4A, WAV, AIFF), duración y carátula embebida se ejecuta en Rust (`lofty`) directamente desde el descriptor SAF, guardando la imagen con su extensión real (`.jpg`, `.png`, `.webp`, `.gif`, `.bmp`).
- **Descarga de solo audio en segundo plano con `yt-dlp` + `yt-dlp-ejs` + `QuickJS-NG` (sin WebView ni FFmpeg)**:
  - Pantalla dedicada (`:feature:downloader`) accesible únicamente desde el menú *Importar* (o estado vacío) de *Biblioteca*.
  - Intérprete ligero **Python 3.11** (Chaquopy MIT) compilado a bytecode `.pyc`, instalando únicamente `yt-dlp` y `yt-dlp-ejs` con `--no-deps` para reducir peso.
  - **QuickJS-NG compilado en Rust (`rquickjs` -> `libaura_qjs.so`)**: resuelve los retos JavaScript EJS (`yt-dlp-ejs`) exigidos por YouTube en 32 y 64 bits sin Node.js, Deno ni WebView.
  - **Vista previa y confirmación previa**: al analizar un enlace muestra miniatura, título, artista/canal, duración, formato y peso estimado antes de iniciar la descarga; durante la descarga reporta peso total, bytes descargados y velocidad de internet en tiempo real.
  - **Servicio en segundo plano (`AuraDownloadForegroundService` / `dataSync`)**: al confirmar la descarga, continúa ejecutándose aunque el usuario salga de Aura Music o apague la pantalla, mostrando una notificación persistente con progreso, velocidad y acción directa para cancelar o avisar al finalizar.
  - Descarga flujos de audio nativos compatibles con Media3 ExoPlayer (`m4a` / `webm` / `opus`) sin depender de FFmpeg, lee sus metadatos con Rust (`lofty`), convierte su miniatura a WebP sin pérdida y los integra automáticamente en Room.
  - **Auto-actualización independiente en la app**: comprueba y descarga wheels oficiales `py3-none-any.whl` de `yt-dlp` y `yt-dlp-ejs` desde PyPI con verificación criptográfica **SHA-256** y recarga en caliente sin recompilar el APK.
- **Ecualizador gráfico de 10 bandas en Rust (DSP en tiempo real)**: Filtros IIR biquad en precisión `f64` (`31 Hz` a `16 kHz`) ejecutados in-place desde `aura_core` sobre `DirectByteBuffer` de Media3 ExoPlayer, con 8 perfiles predefinidos, curva de respuesta y persistencia en DataStore (accesible directamente desde el botón de ecualizador en el reproductor).
- **Reproductor, Mini Reproductor, Cola de reproducción, Crossfade, Velocidad y colores dinámicos de carátula**: Basados en **AndroidX Media3 ExoPlayer** y `MediaSessionService`, con carátula cuadrada clásica, selector de imagen de galería convertido en segundo plano a **WebP sin pérdida a máxima compresión**, carátulas procedurales en Rust (`tiny-skia`) y extracción animada de colores de portada desde Android 8.0 (`minSdk = 26`) mediante **AndroidX Palette** + **MaterialKolor** (`material-kolor`).
  - **Cola de reproducción interactiva (`QueueScreen`)**: accesible desde el reproductor completo (`PlayerScreen`) y desde las opciones de cualquier canción (*Reproducir siguiente*, *Añadir a la cola*), permitiendo saltar a cualquier pista, subir/bajar su orden, quitar pistas individuales o limpiar las canciones siguientes sin detener la reproducción.
  - **Crossfade entre canciones configurable**: transición equal-power en `AuraPlaybackService` que funciona también en segundo plano o con la pantalla apagada, permitiendo activar/desactivar el fundido y elegir **cuántos segundos antes de terminar** empieza (`1 s` a `12 s`), protegiendo el ecualizador Rust DSP sin mezclar buffers IIR.
  - **Control de velocidad de reproducción**: ajuste continuo de `0.50x` a `2.00x` y accesos rápidos (`0.5x`, `0.75x`, `1.0x`, `1.25x`, `1.5x`, `2.0x`) persistidos en DataStore y accesibles junto al Crossfade desde el menú de **3 puntos (`MoreVert`)** del reproductor (`PlayerScreen` -> *Velocidad y Crossfade*).
  - **Navegación limpia con menú de 3 puntos en el reproductor**: las opciones del reproductor (*Velocidad y Crossfade*, *Ecualizador*, *Cola de reproducción*, *Cambiar carátula* y *Añadir a playlist*) se agrupan en un menú desplegable de 3 puntos en `TopAppBar` para mantener limpio el título de la pista.
  - **Mini reproductor del sistema Android simétrico**: la notificación multimedia y la pantalla de bloqueo mantienen los botones *Anterior*, *Reproducir/Pausa* y *Siguiente* simétricos incluso en colas de 1 pista (`SessionControlForwardingPlayer`), con icono monocromo `ic_aura_notification` y toque directo para abrir `PlayerScreen` (`setSessionActivity`).
- **"Abrir con Aura Music" (sistema de apertura externa `ACTION_VIEW`)**: permite abrir archivos de audio (`content://` y `file://`) directamente desde exploradores de archivos, descargas o apps de mensajería; extrae metadatos en Rust, evita duplicados en Room, respalda grants temporales en `filesDir/opened_audio/` para que no fallen al salir de la app y abre el reproductor al instante.
- **Playlists manuales, Favoritos y Playlists automáticas por Artista**:
  - Botón de corazón en pistas, mini reproductor y reproductor completo con sección dedicada de *Canciones que te gustan*.
  - Playlists personalizadas en **Room** con exportación de esquemas (`exportSchema = true`) y migraciones explícitas no destructivas (`AuraMigrations`).
  - **Playlists automáticas por artista**: en la pestaña *Artistas* dentro de *Playlists y Artistas*, Aura Music agrupa automáticamente todas las canciones que comparten el mismo artista (`ArtistPlaylistBuilder`); al abrir un artista (`ArtistDetailScreen`) o reproducir desde su lista, la cola carga **únicamente** las canciones de ese artista (en orden o en aleatorio).
- **Animaciones creativas de eliminación en Rust (`aura_core::delete_fx`)**: Al confirmar la eliminación de una canción en *Biblioteca* o al quitarla de una *Playlist*, se reproduce aleatoriamente uno de tres efectos procesados por píxel en **Rust** sobre `DirectByteBuffer` RGBA8888 (`Cenizas`, `Papel quemándose` o `Aura extraña` adaptada al color acento activo), sin ralentizar la UI en Kotlin y con interruptor para activar/desactivar en *Ajustes*.
- **Ajustes de apariencia**: Selector de 8 paletas de color dinámicas (Material 3), interruptores para colores dinámicos de carátula y animaciones de eliminación en Rust persistidos con **Jetpack DataStore**, y estado del motor nativo Rust.

## Requisitos técnicos

- **Android**: `minSdk = 26` (Android 8.0) • `targetSdk = 35` • `compileSdk = 35`.
- **Arquitecturas nativas**: 32 bits (`armeabi-v7a`) y 64 bits (`arm64-v8a`, `x86_64`) compilables como APK universal o APKs individuales para instalación directa o distribución en Uptodown.
- **Licencias permisivas**: Solo dependencias MIT / Apache-2.0 / BSD / Unlicense (`Chaquopy 17.2.0` MIT, `rquickjs 0.12.2` MIT, `yt-dlp` Unlicense, `yt-dlp-ejs` Unlicense, `lofty` MIT/Apache-2.0, `MaterialKolor` MIT). Sin FFmpeg ni wrappers copyleft.
- **Rust**: Toolchain `stable` (Edition 2024) + `cargo-ndk` + `clang`/`libclang-dev` (para `rquickjs` bindgen).
- **Python de compilación**: Python `3.11` (requerido por Chaquopy para compilación `.pyc` compatible con 32 y 64 bits).
- **Gradle Wrapper**: `8.14.7` (AGP `8.9.1`, Kotlin `2.1.20`).

## Cómo compilar el APK Debug

### Opción 1: Desde GitHub Actions (sin PC, directo desde el teléfono)
1. Abre la pestaña **Actions** en el repositorio de GitHub.
2. Selecciona el workflow **apk Debug**.
3. Pulsa **Run workflow** y elige `apk_mode` (`universal`, `individuales` para generar un APK de 32 bits y otro de 64 bits, `solo-32bits` o `solo-64bits`).
4. Al finalizar, descarga el artefacto **AuraMusic-apk-Debug-...** con el APK o APKs listos para instalar.

### Opción 2: Compilación local (Linux / macOS)
```bash
cd android
./gradlew assembleDebug --no-daemon --no-build-cache
```
El APK generado se guarda en `android/app/build/outputs/apk/debug/`.

## Entorno web del repositorio

La raíz contiene una vista web mínima (`Hello World` en `src/App.tsx`) para mantener el entorno de previsualización web compilando sin errores mientras la aplicación móvil se desarrolla bajo `android/`.
