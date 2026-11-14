# README_AI — Mapa rápido de extensión con IA

Guía práctica para extender **Aura Music** de forma ordenada usando asistentes de código.

## Puntos de extensión principales

| Qué quieres añadir | Dónde empezar | Qué conectar |
| :--- | :--- | :--- |
| Nueva función nativa o DSP en Rust (ej. análisis BPM, waveform) | `android/rust/aura_core/src/` (`Cargo.toml` + módulo `.rs` + `lib.rs`) | Añadir `external fun` y wrapper en `AuraRustBridge.kt` (`:core:rust`); si es audio en tiempo real, integrarlo en `RustEqualizerAudioProcessor` / `DefaultAudioSink` (`:core:player`). |
| Nuevo campo o tabla en base de datos | `AuraEntities.kt`, `AuraMigrations.kt` y `AuraDatabase.kt` (`:core:database`) | Incrementar `AuraMigrations.CURRENT_DATABASE_VERSION`, añadir `Migration(n, n+1)` en `ALL_MIGRATIONS` (nunca destructiva), exponer en `MusicRepository` y `AuraModels.kt`. |
| Nueva opción en Ajustes o Ecualizador | `SettingsScreen.kt` (`:feature:settings`), `EqualizerScreen.kt` (`:feature:equalizer`) y DataStore (`:core:database`) | Exponer `StateFlow` y setter en `AuraViewModel.kt` (el ecualizador se abre solo desde `PlayerScreen`). |
| Nueva pantalla o subvista | Nuevo composable o módulo `:feature:*` | Registrar ruta en `AuraRoutes` dentro de `AuraAppRoot.kt`. |
| Nueva acción de reproducción, carátulas o color dinámico | `AuraPlaybackService.kt` (`:core:player`), `CoverDynamicTheme.kt` (`:feature:player`) y `AudioImporter.kt` (`:core:importer`) | Exponer método en `AuraViewModel.kt` y conectarlo a `PlayerScreen` / `MiniPlayerBar` / `LibraryScreen`. |

## Checklist antes de lanzar el Action `apk Debug`
1. Verifica que `tests/project-integrity.test.ts` pase al 100% (`npx --yes tsx --test tests/project-integrity.test.ts`).
2. Si agregaste un crate en `Cargo.toml`, confirma que su licencia sea MIT, Apache-2.0 o BSD y que compile para `armv7-linux-androideabi`, `aarch64-linux-android` y `x86_64-linux-android`.
3. Si agregaste un módulo en `android/settings.gradle.kts`, confirma que tenga su `build.gradle.kts` y `minSdk = 26`.
4. Ejecuta manualmente el workflow **apk Debug** en la pestaña **Actions** de GitHub para obtener el APK compilado sin caché.
