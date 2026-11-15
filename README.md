# Aura Music

Reproductor de música local y descargador de solo audio para Android construido con **Kotlin (Jetpack Compose + Media3 ExoPlayer + Room)**, un motor nativo en **Rust (Edition 2024)** vía JNI y un intérprete ligero en **Python 3.11 (Chaquopy)** para `yt-dlp`.

## Características actuales

- **Importación manual de música (SAF) con parser en Rust**: Aura Music nunca escanea todo el teléfono de forma invasiva. El usuario decide exactamente qué importar:
  - Una canción individual, varias por tandas o una carpeta completa (con subcarpetas) manteniendo permisos persistentes.
  - Procesamiento por lotes optimizado con deduplicación única en Room y concurrencia acotada en segundo plano.
  - La lectura de etiquetas (ID3, Vorbis, FLAC, MP4/M4A, WAV, AIFF), duración y carátula embebida se ejecuta en Rust (`lofty`) directamente desde el descriptor SAF, guardando la imagen con su extensión real (`.jpg`, `.png`, `.webp`, `.gif`, `.bmp`).
- **Carpeta del sistema opcional (`Aura Music`, desactivada por defecto)**:
  - Apartado especial en *Ajustes → Carpeta del sistema (Aura Music)* (`SettingsSystemFolderScreen`), desactivado por defecto para mantener la biblioteca en el almacenamiento privado de la app salvo que el usuario quiera exponerla en el explorador del teléfono.
  - Al activarla (con permiso de almacenamiento en Android 8–10 o acceso a archivos en Android 11–15), crea automáticamente `Almacenamiento interno/Aura Music/Música`, `Aura Music/Carátulas` (con `.nomedia` para no llenar la galería) y archivos `.json` (`aura_biblioteca.json`, `aura_player_config.json`, `engine_versions.json`).
  - Transfiere automáticamente las canciones, carátulas y `.json` existentes hacia `Aura Music` actualizando Room sin perder playlists ni *Me gusta*, dirige ahí las nuevas descargas/carátulas y detecta automáticamente cualquier canción que el usuario copie dentro de `Aura Music/Música` al abrir o volver a la app.
- **Descarga de solo audio en segundo plano con `yt-dlp` + `yt-dlp-ejs` + `QuickJS-NG` (sin WebView ni FFmpeg)**:
  - Pantalla dedicada (`:feature:downloader`) accesible únicamente desde el menú *Importar* (o estado vacío) de *Biblioteca*.
  - Intérprete ligero **Python 3.11** (Chaquopy MIT) compilado a bytecode `.pyc`, instalando únicamente `yt-dlp` y `yt-dlp-ejs` con `--no-deps` para reducir peso.
  - **QuickJS-NG compilado en Rust (`rquickjs` -> `libaura_qjs.so`)**: resuelve los retos JavaScript EJS (`yt-dlp-ejs`) exigidos por YouTube en 32 y 64 bits sin Node.js, Deno ni WebView.
  - **Vista previa, selección de calidad/formato y confirmación previa**: antes de analizar puedes elegir tu perfil preferido (*Máxima*, *Equilibrada*, *Ligera*, guardado en DataStore); al analizar un enlace muestra miniatura, título, artista/canal, duración, peso estimado y la lista real de formatos de solo audio disponibles (`M4A (AAC)`, `WEBM (Opus)`, bitrates en kbps y peso aprox.) para elegir exactamente qué flujo descargar antes de confirmar. Durante la descarga reporta peso total, bytes descargados y velocidad de internet en tiempo real.
  - **Servicio en segundo plano (`AuraDownloadForegroundService` / `dataSync`)**: al confirmar la descarga, continúa ejecutándose aunque el usuario salga de Aura Music o apague la pantalla, mostrando una notificación persistente con progreso, velocidad y acción directa para cancelar o avisar al finalizar.
  - Descarga flujos de audio nativos compatibles con Media3 ExoPlayer (`m4a` / `webm` / `opus`) sin depender de FFmpeg, lee sus metadatos con Rust (`lofty`), convierte su miniatura a WebP sin pérdida y los integra automáticamente en Room.
  - **Auto-actualización independiente en la app**: comprueba y descarga wheels oficiales `py3-none-any.whl` de `yt-dlp` y `yt-dlp-ejs` desde PyPI con verificación criptográfica **SHA-256** y recarga en caliente sin recompilar el APK.
- **Ecualizador gráfico de 10 bandas en Rust (DSP en tiempo real)**: Filtros IIR biquad en precisión `f64` (`31 Hz` a `16 kHz`) ejecutados in-place desde `aura_core` sobre `DirectByteBuffer` de Media3 ExoPlayer, con 8 perfiles predefinidos, curva de respuesta y persistencia en DataStore (accesible directamente desde el botón de ecualizador en el reproductor).
- **Efectos de Crossfeed binaural, Audio 8D, Reverb estéreo y modo Slowed en Rust (`:feature:effects` + `aura_core::crossfeed` + `aura_core::spatial_8d` + `aura_core::reverb`)**:
  - **Crossfeed Binaural Estático para auriculares (`StaticCrossfeedProcessor` en Rust `f64`)**: proyecta el escenario estéreo frente al oyente mediante ángulo virtual ajustable (`±15°` a `±45°`), retardo interaural fraccional (**ITD** `0.18–0.38 ms`), filtro paso bajo de sombra craneal (**ILD**) y compensación de ganancia para eliminar la fatiga auditiva del estéreo extremo sin coloraciones artificiales.
  - **Audio Espacial 8D psicoacústico (`Spatial8dProcessor` en Rust `f64`)**: pensado para audífonos, combina cruce estéreo controlado (*cross-feed*) para no perder instrumentos en mezclas abiertas, ancla de graves centrada (`~135 Hz`), micro-retardo interaural interpolado (**ITD** hasta `0.65 ms`) y filtro dinámico de sombra craneal (**ILD**) que suaviza agudos al girar detrás o al lado opuesto, con controles de *Velocidad de giro* (en segundos por vuelta) y *Profundidad / Apertura 360°* más radar animado en vivo.
  - **Reverb estéreo (Freeverb en Rust `f64`)**: 8 filtros *comb* con amortiguación de agudos + 4 filtros *allpass* en serie por canal (`L/R`) sin asignaciones en el hilo de audio y limpieza de subnormales (`< 1e-15`), ejecutado detrás del espacializador 8D con controles de *Tamaño de sala*, *Mezcla (Wet)*, *Amortiguación de agudos* y *Amplitud estéreo*.
  - **Modo Slowed con tono natural de vinilo y calidez analógica**: ajuste de velocidad (`0.70x` – `0.95x`, accesos rápidos `0.75x`, `0.80x`, `0.85x`, `0.90x`), interruptor para bajar el tono junto con el ritmo (*Tono grave natural*) o conservar el tono original vía `PlaybackParameters` de Media3 (incluso durante el Crossfade), más un filtro paso bajo de 1 polo (`AudioEffectsProcessor` en Rust, `15.5 kHz` -> `3.2 kHz`) para suavizar agudos metálicos.
  - Incluye 9 modos rápidos (*Original*, *Crossfeed Auriculares*, *Audio 8D*, *8D + Slowed*, *Slowed + Reverb*, *Solo Slowed*, *Sala íntima*, *Hall*, *Catedral Etérea*), banner en vivo de salida detectada, persistencia en DataStore (`AudioEffectsPreferencesRepository`) y acceso exclusivo desde el menú de **3 puntos (`MoreVert`)** de `PlayerScreen`.
- **Detección inteligente de salida de audio (`AudioDeviceCallback`) y botones multimedia (Cable / Bluetooth AVRCP)**:
  - **Detección automática sin permisos extra de Bluetooth**: `AuraAudioOutputMonitor` utiliza `AudioDeviceCallback` (`minSdk = 26`) para identificar al instante auriculares por cable, adaptadores USB-C, dispositivos Bluetooth (`A2DP`/`SCO`/`BLE`/`Hearing Aid`) o el altavoz del teléfono sin solicitar `BLUETOOTH_CONNECT`.
  - **Enrutamiento inteligente**: pausa al desconectar auriculares, reanudación opcional al conectar, pausa automática del Audio 8D cuando suena por el altavoz (restaurándolo al conectar audífonos sin sobrescribir la preferencia) y activación automática opcional de Crossfeed binaural al usar auriculares.
  - **Ajustes finos de botones multimedia**: soporte para botón único cableado (`KEYCODE_HEADSETHOOK` con **doble toque = Siguiente** y **triple toque = Anterior**), umbral configurable del botón *Anterior* (`Siempre previa`, `3.5 s`, `5 s`, `8 s`), navegación circular en la cola y paso de salto rápido Bluetooth AVRCP (`5 s`, `10 s`, `15 s`, `30 s`).
- **Reproductor, Mini Reproductor, Cola de reproducción, Crossfade, Velocidad, Tono de voz, Temporizador y colores dinámicos de carátula**: Basados en **AndroidX Media3 ExoPlayer** y `MediaSessionService`, con carátula cuadrada clásica, selector de imagen de galería convertido en segundo plano a **WebP sin pérdida a máxima compresión**, carátulas procedurales en Rust (`tiny-skia`) y extracción animada de colores de portada desde Android 8.0 (`minSdk = 26`) mediante **AndroidX Palette** + **MaterialKolor** (`material-kolor`).
  - **Cola de reproducción interactiva (`QueueScreen`)**: accesible desde el reproductor completo (`PlayerScreen`) y desde las opciones de cualquier canción (*Reproducir siguiente*, *Añadir a la cola*), permitiendo saltar a cualquier pista, subir/bajar su orden, quitar pistas individuales o limpiar las canciones siguientes sin detener la reproducción.
  - **Crossfade entre canciones configurable**: transición equal-power en `AuraPlaybackService` que funciona también en segundo plano o con la pantalla apagada, permitiendo activar/desactivar el fundido y elegir **cuántos segundos antes de terminar** empieza (`1 s` a `12 s`), protegiendo el ecualizador, el crossfeed, el espacializador 8D y la cola de reverb Rust DSP sin mezclar buffers IIR.
  - **Control de velocidad de reproducción y velocidad/tono de voz**: ajuste continuo de velocidad (`0.50x` a `2.00x`, accesos `0.5x`, `0.75x`, `1.0x`, `1.25x`, `1.5x`, `2.0x`) complementado con **Velocidad / Tono de voz** (`0.50x` a `2.00x`, accesos `0.75x`, `0.85x`, `1.0x`, `1.15x`, `1.25x`, `1.5x` y etiqueta vocal descriptiva) más interruptor **Vincular voz con velocidad** (efecto cinta/vinilo), persistidos en DataStore y accesibles desde el menú de **3 puntos (`MoreVert`)** del reproductor (`PlayerScreen` -> *Velocidad y Crossfade*).
  - **Temporizador de apagado automático (`SleepTimerBottomSheet`)**: ejecutado en segundo plano dentro de `AuraPlaybackService` para que no se detenga al apagar la pantalla; ofrece duraciones preestablecidas (`5, 10, 15, 30, 45, 60, 90 min`), entrada numérica de **minutos personalizados (`1–480 min`)**, opciones inteligentes para parar **al terminar esta canción** o **al terminar toda la música de la cola** (desactivando limpiamente el Crossfade al cierre), ajuste rápido `-5 / +5 min` y atenuación suave de volumen en los últimos 3 segundos.
  - **Navegación limpia con menú de 3 puntos en el reproductor**: las opciones del reproductor (*Velocidad y Crossfade*, *Temporizador*, *Ecualizador*, *8D, Reverb y Slowed*, *Cola de reproducción*, *Editar metadatos*, *Cambiar carátula* y *Añadir a playlist*) se agrupan en un menú desplegable de 3 puntos en `TopAppBar` para mantener limpio el título de la pista.
  - **Mini reproductor del sistema Android simétrico**: la notificación multimedia y la pantalla de bloqueo mantienen los botones *Anterior*, *Reproducir/Pausa* y *Siguiente* simétricos incluso en colas de 1 pista (`SessionControlForwardingPlayer`), con icono monocromo `ic_aura_notification` y toque directo para abrir `PlayerScreen` (`setSessionActivity`).
- **"Abrir con Aura Music" (sistema de apertura externa `ACTION_VIEW`)**: permite abrir archivos de audio (`content://` y `file://`) directamente desde exploradores de archivos, descargas o apps de mensajería; extrae metadatos en Rust, evita duplicados en Room, respalda grants temporales en `filesDir/opened_audio/` para que no fallen al salir de la app y abre el reproductor al instante.
- **Playlists manuales, Favoritos y Playlists automáticas por Artista**:
  - Botón de corazón en pistas, mini reproductor y reproductor completo con sección dedicada de *Canciones que te gustan*.
  - Playlists personalizadas en **Room** con exportación de esquemas (`exportSchema = true`) y migraciones explícitas no destructivas (`AuraMigrations`).
  - **Playlists automáticas por artista**: en la pestaña *Artistas* dentro de *Playlists y Artistas*, Aura Music agrupa automáticamente todas las canciones que comparten el mismo artista (`ArtistPlaylistBuilder`); al abrir un artista (`ArtistDetailScreen`) o reproducir desde su lista, la cola carga **únicamente** las canciones de ese artista (en orden o en aleatorio).
- **Edición local de metadatos (título y artista)**: desde el menú de opciones de cualquier pista (en *Biblioteca*, *Favoritos*, *Playlists* o *Artistas*) o desde el menú de 3 puntos del reproductor (`PlayerScreen`), el usuario puede editar el **nombre de la canción** y el **nombre del artista** (`EditTrackMetadataDialog` + `TrackMetadataHelper`). Los cambios se guardan en Room sin alterar el archivo de audio original y sincronizan al instante las agrupaciones por artista, la cola, el mini reproductor y la notificación del sistema (`replaceMediaItem`).
- **Animaciones creativas de eliminación en Rust (`aura_core::delete_fx`)**: Al confirmar la eliminación de una canción en *Biblioteca* o al quitarla de una *Playlist*, se reproduce aleatoriamente uno de tres efectos procesados por píxel en **Rust** sobre `DirectByteBuffer` RGBA8888 (`Cenizas`, `Papel quemándose` o `Aura extraña` adaptada al color acento activo), sin ralentizar la UI en Kotlin y con interruptor para activar/desactivar en *Ajustes*.
- **Ajustes de apariencia, carpeta del sistema, auriculares y controles AVRCP**: Selector de 8 paletas de color dinámicas (Material 3), interruptores para colores dinámicos de carátula y animaciones de eliminación en Rust, configuración opcional de la **carpeta del sistema `Aura Music`**, estado en vivo de la salida de audio, comportamiento automático para auriculares, ajustes finos de botones multimedia (cable y Bluetooth AVRCP) persistidos con **Jetpack DataStore**, y estado del motor nativo Rust.
- **Aislamiento multi-proceso y bajo consumo de RAM en segundo plano**:
  - `:player` (`android:process=":player"`) aísla `AuraPlaybackService`, ExoPlayer ligero (`DefaultLoadControl` acotado), `AudioDeviceCallback` y el DSP Rust sin cargar Compose, Coil, Room ni Python.
  - `:downloader` (`android:process=":downloader"`) aísla Chaquopy / Python 3.11 / `yt-dlp` bajo demanda y libera el proceso (`Process.killProcess`) al quedar inactivo tras finalizar la descarga.
  - El proceso principal (`MAIN`) limita la caché en memoria de Coil al 8 % del heap en `RGB_565` y responde a `onTrimMemory()` / `onLowMemory()` (`MemoryTrimPolicy`) liberando bitmaps y caché dinámica de colores cuando el usuario está jugando u operando otras apps.

## Requisitos técnicos

- **Versión oficial**: `v1.0.0` (`versionCode = 1`, `versionName = "1.0.0"`).
- **Android**: `minSdk = 26` (Android 8.0) • `targetSdk = 36` • `compileSdk = 37`.
- **Arquitecturas nativas**:
  - **Release móvil**: estrictamente 32 bits (`armeabi-v7a`) y 64 bits (`arm64-v8a`), sin librerías `x86_64` de PC/emulador.
  - **Debug / pruebas**: `armeabi-v7a`, `arm64-v8a` y `x86_64` configurables vía `-PauraAbis=...`.
- **Licencias permisivas**: Solo dependencias MIT / Apache-2.0 / BSD / Unlicense (`Chaquopy 17.2.0` MIT, `rquickjs 0.14.0` MIT, `yt-dlp` Unlicense, `yt-dlp-ejs` Unlicense, `lofty 0.25.5` MIT/Apache-2.0, `MaterialKolor 5.0.1` MIT). Sin FFmpeg ni wrappers copyleft.
- **Rust**: Toolchain `stable` (Edition 2024) + `cargo-ndk` + `clang`/`libclang-dev` (para `rquickjs` bindgen), con poda agresiva de código muerto en Release (`lto = "fat"`, `panic = "abort"`, `strip = "symbols"`, `-Wl,--gc-sections`, `-Wl,--exclude-libs,ALL`).
- **Python de compilación**: Python `3.11` (requerido por Chaquopy para compilación `.pyc` compatible con 32 y 64 bits).
- **Gradle Wrapper**: `9.9.1` (AGP `9.5.1`, Kotlin `2.4.21`, KSP `2.3.14`, Media3 `1.11.1`, Room `2.8.5`, Compose BOM `2026.09.00`).

## Cómo compilar los APKs (Debug y Release v1.0.0)

### Opción 1: Desde GitHub Actions (sin PC, directo desde el teléfono)
- **APK Debug** (`apk Debug` — `.github/workflows/build-android-apk.yml`):
  1. Abre la pestaña **Actions** en el repositorio de GitHub.
  2. Selecciona **apk Debug**, pulsa **Run workflow** y elige `apk_mode` (`universal`, `individuales`, `solo-32bits` o `solo-64bits`).
  3. Descarga el artefacto **AuraMusic-apk-Debug-...**.
- **APK Release oficial v1.0.0** (`apk Release` — `.github/workflows/build-android-release.yml`):
  1. Configura los 4 Secrets de firma en **Settings → Secrets and variables → Actions**: `AURA_KEYSTORE_BASE64`, `AURA_KEYSTORE_PASSWORD`, `AURA_KEY_ALIAS` y `AURA_KEY_PASSWORD`.
  2. Abre **Actions → apk Release**, pulsa **Run workflow**, deja `version_name = 1.0.0` y elige `apk_mode` (`universal-movil` con 32 y 64 bits ARM, `individuales`, `solo-32bits` o `solo-64bits`; excluye `x86_64`).
  3. Descarga el artefacto firmado **AuraMusic-apk-Release-v1.0.0-...** listo para instalar o subir a Uptodown.
- **Auditoría total** (`Lint y Auditoria Total` — `.github/workflows/lint-and-audit.yml`): ejecuta pruebas estructurales, Python (`ruff`/`mypy`), Rust (`fmt`/`clippy`/`cargo test`/`cargo-ndk`), `ktlint`, `lintDebug`, unit tests y reporte de dependencias desactualizadas en un `.zip` interactivo.

### Opción 2: Compilación local (Linux / macOS)
```bash
cd android
./gradlew assembleDebug --no-daemon --no-build-cache
# Para Release móvil (32 y 64 bits ARM sin x86_64):
./gradlew assembleRelease -PauraAbis=armeabi-v7a,arm64-v8a --no-daemon --no-build-cache
```
Los APKs generados se guardan en `android/app/build/outputs/apk/debug/` y `android/app/build/outputs/apk/release/`.

## Entorno web del repositorio

La raíz contiene una vista web mínima (`Hello World` en `src/App.tsx`) para mantener el entorno de previsualización web compilando sin errores mientras la aplicación móvil se desarrolla bajo `android/`.
