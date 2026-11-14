# AGENTS.md — Guía para agentes de desarrollo

Reglas operativas para cualquier agente que modifique o amplíe **Aura Music**.

## Reglas de oro antes de editar
1. **No romper lo existente**: No cambiar el diseño, iconos o lógica de pantallas que ya funcionan a menos que el usuario lo solicite expresamente.
2. **Modularidad estricta**:
   - Nuevas entidades o modelos compartidos -> `:core:model`.
   - Nuevos algoritmos DSP / gráficos / hashing -> `android/rust/aura_core/src/` + puente JNI en `:core:rust`.
   - Persistencia y migraciones -> `:core:database` (`AuraDatabase`, `AuraMigrations` sin `fallbackToDestructiveMigration`).
   - Nuevas vistas -> módulo `:feature:*` dedicado (sin meter toda la app en un solo archivo ni una sola pantalla).
3. **Sincronía JNI Kotlin ↔ Rust**:
   - Cada método `private external fun nombre(...)` en `com.auramusic.core.rust.AuraRustBridge` debe corresponder exactamente a `#[unsafe(no_mangle)] pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nombre` en `android/rust/aura_core/src/lib.rs`.
4. **Uso de hilos y motor nativo Rust**:
   - Prohibido ejecutar `ParcelFileDescriptor`, `DocumentFile`, Room, compresión WebP o llamadas JNI (`extractAudioMetadata`, carátulas, hashing, configuración EQ) en `Dispatchers.Main`.
   - No reintroducir `MediaMetadataRetriever` ni `android.media.audiofx.Equalizer` en Kotlin; la lectura de metadatos y el DSP del ecualizador pertenecen a Rust (`aura_core::metadata` y `aura_core::equalizer`).
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
- Pruebas unitarias de Rust (en entorno con toolchain Rust):
  ```bash
  cd android/rust/aura_core && cargo test
  ```
- Compilación APK Debug sin caché (en entorno con Android SDK/NDK + Rust):
  ```bash
  cd android && ./gradlew assembleDebug --no-daemon --no-build-cache
  ```
