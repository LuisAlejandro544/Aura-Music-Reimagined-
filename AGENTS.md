# AGENTS.md — Guía para agentes de desarrollo

Reglas operativas para cualquier agente que modifique o amplíe **Aura Music**.

## Reglas de oro antes de editar
1. **No romper lo existente**: No cambiar el diseño, iconos o lógica de pantallas que ya funcionan a menos que el usuario lo solicite expresamente.
2. **Modularidad estricta**:
   - Nuevas entidades, modelos o helpers puros compartidos -> `:core:model` (ej. `ArtistPlaylistBuilder`, `PlaybackQueueHelper`, `PlaybackTuningHelper`, `SleepTimerHelper`, `TrackMetadataHelper`, `AudioEffectsState`, `HeadphonePreferences`, `HeadphoneRoutingHelper`, `SystemFolderStorageState`, `SystemFolderHelper`, `AuraProcessRoleResolver`, `MemoryTrimPolicy`).
   - Nuevos algoritmos DSP / gráficos / hashing / JS runtime -> `android/rust/aura_core/src/` (incluyendo `equalizer.rs`, `crossfeed.rs`, `reverb.rs` y `spatial_8d.rs`) + puente JNI en `:core:rust`.
   - Persistencia y migraciones -> `:core:database` (`AuraDatabase`, `AuraMigrations` sin `fallbackToDestructiveMigration`, `PlaybackPreferencesRepository`, `AudioEffectsPreferencesRepository`, `HeadphonePreferencesRepository`, `StoragePreferencesRepository`).
   - Importación y carpeta del sistema opcional (`Aura Music`) -> `:core:importer` (`AudioImporter`, `AuraSystemStorageManager`), manteniendo la carpeta pública desactivada por defecto (`false`) y ejecutando toda copia/escaneo en `Dispatchers.IO` con parser Rust `lofty`.
   - Lógica de descargas Python/`yt-dlp` y servicio `dataSync` (`android:process=":downloader"`) -> `:core:downloader` (`YtDlpAudioDownloader`, `AuraDownloadForegroundService` vía `AuraDownloadHost` + `aura_ytdlp.py` + `AuraDownloaderRemoteClient`), manteniendo Chaquopy encapsulado en este único módulo Gradle y sin convivir en RAM con el reproductor.
   - Reproducción (`android:process=":player"`), Crossfade, Velocidad, Tono de voz, Temporizador, Slowed, detección de salida y botones multimedia -> `:core:player` (`AuraAudioOutputMonitor`, `AuraSleepTimerBridge`, `AuraPlayerConfigStore`, `AuraPlaybackService` vía `AuraPlaybackTuningProvider` y `AuraMediaSessionCallback`, `AuraPlayerController`), manteniendo el ecualizador, el crossfeed, el espacializador 8D y la reverberación Rust DSP (`RustEqualizerAudioProcessor`) exclusivamente en el reproductor principal para no corromper las líneas de retardo IIR/ITD/Freeverb durante el crossfade, mientras `outgoingFadePlayer` replica `speed` y `pitch`.
   - Orquestación y UI raíz en `:app` -> dividir la lógica de estado en coordinadores dedicados (`AuraPlaybackCoordinator`, `AuraAudioTuningCoordinator`, `AuraLibraryCoordinator`) y el chrome/launchers en `AuraAppChrome` sin sobrecargar `AuraViewModel` ni `AuraAppRoot`.
   - Nuevas vistas -> módulo `:feature:*` dedicado dependiendo solo de `:core:model` (sin meter toda la app en un solo archivo ni una sola pantalla).
3. **Sincronía JNI Kotlin ↔ Rust**:
   - Cada método `private external fun nombre(...)` en `com.auramusic.core.rust.AuraRustBridge` debe corresponder exactamente a `#[unsafe(no_mangle)] pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nombre` en `android/rust/aura_core/src/lib.rs`.
   - El binario CLI `aura_qjs` (`android/rust/aura_core/src/bin/aura_qjs.rs`) debe seguir copiándose como `jniLibs/<abi>/libaura_qjs.so` en `:core:rust` con `useLegacyPackaging = true` en `:app`.
4. **Uso de hilos y motor nativo Rust/Python**:
   - Prohibido ejecutar `ParcelFileDescriptor`, `DocumentFile`, Room, compresión WebP, Chaquopy (`Python.start` / `yt-dlp`), red PyPI o llamadas JNI (`extractAudioMetadata`, carátulas, hashing, configuración EQ, `setAudioEffectsConfig`, `renderDeleteEffectFrame`, `evalQuickJs`) en `Dispatchers.Main`.
   - No reintroducir `MediaMetadataRetriever`, `android.media.audiofx.Equalizer`, `PresetReverb` ni `EnvironmentalReverb` en Kotlin ni simular en Kotlin el Crossfeed binaural, el Audio 8D o los efectos de eliminación; la lectura de metadatos, el DSP del ecualizador/crossfeed/8D/reverb/calidez Slowed y los efectos de eliminación pertenecen a Rust (`aura_core::metadata`, `aura_core::equalizer`, `aura_core::crossfeed`, `aura_core::spatial_8d`, `aura_core::reverb` y `aura_core::delete_fx`).
   - No añadir permisos `BLUETOOTH_CONNECT` ni `BLUETOOTH_SCAN` si la detección funciona vía `AudioDeviceCallback` (`AudioManager.GET_DEVICES_OUTPUTS`).
   - No añadir `WebView` en descargas ni wrappers copyleft (`youtubedl-android`, `NewPipeExtractor`, `FFmpeg`).
5. **Documentación en código**:
   - Incluir comentarios explicativos claros en español en archivos nuevos o modificados.
6. **Prohibiciones**:
   - No usar `persist.sys.*`.
   - No añadir dependencias copyleft.
   - No tocar `commit_message.txt` (si existiera) salvo petición expresa, y mantenerlo en español.

## Comandos de validación
- Pruebas estructurales del proyecto:
  ```bash
  npx --yes tsx --test tests/project-integrity.test.ts
  ```
- Verificación del entorno web mínimo:
  ```bash
  npm run lint && npm run build
  ```
- Pruebas unitarias de Rust (en entorno con toolchain Rust + libclang):
  ```bash
  cd android/rust/aura_core && cargo test
  ```
- Compilación APK Debug sin caché (en entorno con Android SDK/NDK + Rust + Python 3.11):
  ```bash
  cd android && ./gradlew assembleDebug --no-daemon --no-build-cache
  ```
- Compilación APK Release móvil (32 y 64 bits ARM sin `x86_64`, con poda Rust + R8/ProGuard protegido para Chaquopy/`yt-dlp` y JNI):
  ```bash
  cd android && ./gradlew assembleRelease -PauraAbis=armeabi-v7a,arm64-v8a --no-daemon --no-build-cache
  ```
