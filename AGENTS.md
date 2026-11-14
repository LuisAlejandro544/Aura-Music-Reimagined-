# AGENTS.md — Guía para agentes de desarrollo

Reglas operativas para cualquier agente que modifique o amplíe **Aura Music**.

## Reglas de oro antes de editar
1. **No romper lo existente**: No cambiar el diseño, iconos o lógica de pantallas que ya funcionan a menos que el usuario lo solicite expresamente.
2. **Modularidad estricta**:
   - Nuevas entidades o modelos compartidos -> `:core:model`.
   - Nuevos algoritmos DSP / gráficos / hashing / JS runtime -> `android/rust/aura_core/src/` + puente JNI en `:core:rust`.
   - Persistencia y migraciones -> `:core:database` (`AuraDatabase`, `AuraMigrations` sin `fallbackToDestructiveMigration`).
   - Lógica de descargas Python/`yt-dlp` -> `:core:downloader` (`YtDlpAudioDownloader` + `aura_ytdlp.py`), manteniendo Chaquopy encapsulado en este único módulo Gradle.
   - Nuevas vistas -> módulo `:feature:*` dedicado (sin meter toda la app en un solo archivo ni una sola pantalla).
3. **Sincronía JNI Kotlin ↔ Rust**:
   - Cada método `private external fun nombre(...)` en `com.auramusic.core.rust.AuraRustBridge` debe corresponder exactamente a `#[unsafe(no_mangle)] pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nombre` en `android/rust/aura_core/src/lib.rs`.
   - El binario CLI `aura_qjs` (`android/rust/aura_core/src/bin/aura_qjs.rs`) debe seguir copiándose como `jniLibs/<abi>/libaura_qjs.so` en `:core:rust` con `useLegacyPackaging = true` en `:app`.
4. **Uso de hilos y motor nativo Rust/Python**:
   - Prohibido ejecutar `ParcelFileDescriptor`, `DocumentFile`, Room, compresión WebP, Chaquopy (`Python.start` / `yt-dlp`), red PyPI o llamadas JNI (`extractAudioMetadata`, carátulas, hashing, configuración EQ, `renderDeleteEffectFrame`, `evalQuickJs`) en `Dispatchers.Main`.
   - No reintroducir `MediaMetadataRetriever` ni `android.media.audiofx.Equalizer` en Kotlin ni simular en Kotlin los efectos de eliminación; la lectura de metadatos, el DSP del ecualizador y los efectos de eliminación pertenecen a Rust (`aura_core::metadata`, `aura_core::equalizer` y `aura_core::delete_fx`).
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
