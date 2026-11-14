# README_AI — Mapa rápido de extensión con IA

Guía práctica para extender **Aura Music** de forma ordenada usando asistentes de código.

## Puntos de extensión principales

| Qué quieres añadir | Dónde empezar | Qué conectar |
| :--- | :--- | :--- |
| Nueva función nativa, DSP, efecto visual o capacidad JS en Rust | `android/rust/aura_core/src/` (`Cargo.toml` + módulo `.rs` como `delete_fx.rs` + `lib.rs` o `bin/aura_qjs.rs`) | Añadir `external fun` y wrapper en `AuraRustBridge.kt` (`:core:rust`); si se consume en `:feature:*`, definir interfaz desacoplada en `:core:model` (ej. `DeleteEffectFrameRenderer`); si es audio en tiempo real, integrarlo en `RustEqualizerAudioProcessor` / `DefaultAudioSink` (`:core:player`). |
| Mejora en descargas `yt-dlp`, servicio en segundo plano o auto-actualización PyPI | `aura_ytdlp.py`, `YtDlpAudioDownloader.kt` y `AuraDownloadForegroundService.kt` (`:core:downloader`) | Mantener `AuraDownloadHost` en `AuraApplication`, exponer estados en `DownloadState` / `DownloaderEngineStatus` (`:core:model`), conectar en `AuraViewModel.kt` y mostrarlo en `DownloaderScreen.kt` (`:feature:downloader`). |
| Nuevo campo o tabla en base de datos | `AuraEntities.kt`, `AuraMigrations.kt` y `AuraDatabase.kt` (`:core:database`) | Incrementar `AuraMigrations.CURRENT_DATABASE_VERSION`, añadir `Migration(n, n+1)` en `ALL_MIGRATIONS` (nunca destructiva), exponer en `MusicRepository` y `AuraModels.kt`. |
| Nueva opción en Ajustes, Crossfade/Velocidad o Ecualizador | `SettingsScreen.kt` (`:feature:settings`), `PlayerScreen.kt` (`:feature:player`), `EqualizerScreen.kt` (`:feature:equalizer`) y DataStore (`PlaybackPreferencesRepository`, `ThemePreferencesRepository` en `:core:database`) | Exponer `StateFlow` y setters en `AuraViewModel.kt`; si afecta al servicio en segundo plano, incluirlo en `AuraPlaybackTuningProvider` (`:core:player`) e implementarlo en `AuraApplication.kt`. |
| Nueva pantalla, subvista o receptor de Intents ("Abrir con") | Nuevo composable o módulo `:feature:*` (como `ArtistDetailScreen.kt` o `QueueScreen.kt`); intents en `AndroidManifest.xml` + `MainActivity.kt` | Registrar ruta en `AuraRoutes` dentro de `AuraAppRoot.kt` y ocultar `showBottomChrome` si es vista inmersiva; procesar URIs externas en `AudioImporter.importExternalAudioUri`. |
| Cola de reproducción, Crossfade, velocidad, agrupación por artista o color dinámico | `PlaybackQueueHelper` / `PlaybackTuningHelper` / `ArtistPlaylistBuilder` (`:core:model`), `AuraPlaybackService.kt` / `AuraPlayerController.kt` (`:core:player`), `PlayerScreen.kt` / `QueueScreen.kt` / `CoverDynamicTheme.kt` (`:feature:player`) | Exponer acciones en `AuraViewModel.kt` y conectarlas en `LibraryScreen`, `PlaylistsScreen`, `ArtistDetailScreen`, `PlayerScreen` (menú de 3 puntos) y `QueueScreen`. |

## Checklist antes de lanzar el Action `apk Debug`
1. Verifica que `tests/project-integrity.test.ts` pase al 100% (`npx --yes tsx --test tests/project-integrity.test.ts`).
2. Si agregaste un crate en `Cargo.toml`, confirma que su licencia sea MIT, Apache-2.0 o BSD y que compile para `armv7-linux-androideabi`, `aarch64-linux-android` y `x86_64-linux-android`.
3. Si tocaste `:core:downloader` o Chaquopy, mantén `version = "3.11"` para preservar compatibilidad con 32 bits (`armeabi-v7a`) y `extractPackages("yt_dlp_ejs")` para que `yt-dlp` encuentre los scripts EJS.
4. Si agregaste un módulo en `android/settings.gradle.kts`, confirma que tenga su `build.gradle.kts` y `minSdk = 26`.
5. Ejecuta manualmente el workflow **apk Debug** en la pestaña **Actions** de GitHub para obtener el APK compilado sin caché.
