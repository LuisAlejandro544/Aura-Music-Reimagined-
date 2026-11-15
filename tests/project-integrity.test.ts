/**
 * Suite de verificación automatizada (node:test) para validar la integridad estructural
 * del proyecto híbrido Web (Hello World) + Móvil (Kotlin + Rust JNI + Gradle).
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const rootDir = process.cwd();

test('metadata.json and index.html are synchronized with Aura Music', () => {
  const metadata = JSON.parse(fs.readFileSync(path.join(rootDir, 'metadata.json'), 'utf8'));
  const html = fs.readFileSync(path.join(rootDir, 'index.html'), 'utf8');

  assert.equal(metadata.name, 'Aura Music');
  assert.ok(metadata.majorCapabilities.includes('MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API'));
  assert.match(html, /<title>Aura Music<\/title>/);
  assert.match(html, /og:title" content="Aura Music"/);
});

test('all Gradle modules declared in settings.gradle.kts exist with build scripts', () => {
  const settings = fs.readFileSync(path.join(rootDir, 'android/settings.gradle.kts'), 'utf8');
  const includes = [...settings.matchAll(/include\("([^"]+)"\)/g)].map((m) => m[1]);

  assert.ok(includes.length >= 14, 'Expected full multi-module Gradle structure including :feature:equalizer and :feature:effects');
  assert.ok(includes.includes(':feature:equalizer'), 'Expected dedicated :feature:equalizer module');
  assert.ok(includes.includes(':feature:effects'), 'Expected dedicated :feature:effects module');

  for (const mod of includes) {
    const relPath = mod.replace(/^:/, '').replace(/:/g, '/');
    const buildFile = path.join(rootDir, 'android', relPath, 'build.gradle.kts');
    assert.ok(fs.existsSync(buildFile), `Missing build.gradle.kts for module ${mod}`);
  }
});

test('Rust crate uses edition 2024, 32/64-bit targets, and matches all JNI Kotlin methods', () => {
  const cargoToml = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/Cargo.toml'), 'utf8');
  const toolchain = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/rust-toolchain.toml'), 'utf8');
  const rustLib = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/src/lib.rs'), 'utf8');
  const ktBridge = fs.readFileSync(
    path.join(rootDir, 'android/core/rust/src/main/kotlin/com/auramusic/core/rust/AuraRustBridge.kt'),
    'utf8',
  );
  const rustGradle = fs.readFileSync(path.join(rootDir, 'android/core/rust/build.gradle.kts'), 'utf8');

  assert.match(cargoToml, /edition = "2024"/);
  assert.match(toolchain, /aarch64-linux-android/);
  assert.match(toolchain, /armv7-linux-androideabi/);
  assert.match(toolchain, /x86_64-linux-android/);

  // Gradle debe compilar Rust obligatoriamente antes de preBuild para 32 y 64 bits
  assert.match(rustGradle, /cargo/);
  assert.match(rustGradle, /--platform/);
  assert.match(rustGradle, /armeabi-v7a/);
  assert.match(rustGradle, /arm64-v8a/);
  assert.match(rustGradle, /preBuild/);

  // Verifica correspondencia exacta entre external fun en Kotlin y símbolos #[unsafe(no_mangle)] en Rust
  const jniMethods = [...ktBridge.matchAll(/private external fun (\w+)/g)].map((m) => m[1]);
  assert.ok(jniMethods.length >= 3);
  for (const fnName of jniMethods) {
    const expectedSymbol = `Java_com_auramusic_core_rust_AuraRustBridge_${fnName}`;
    assert.ok(rustLib.includes(expectedSymbol), `Missing Rust JNI symbol: ${expectedSymbol}`);
  }
});

test('no forbidden persist.sys properties or third-party trademark names in source files', () => {
  const forbidden = ['persist.sys.', 'spotify'];
  const scanDirs = [path.join(rootDir, 'android'), path.join(rootDir, 'src')];

  function walk(dir: string) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (/\.(kt|kts|rs|toml|xml|ts|tsx)$/.test(entry.name)) {
        const lowerName = entry.name.toLowerCase();
        const content = fs.readFileSync(full, 'utf8').toLowerCase();
        for (const word of forbidden) {
          assert.ok(!lowerName.includes(word), `Forbidden name in ${full}`);
          assert.ok(!content.includes(word), `Forbidden token "${word}" in ${full}`);
        }
      }
    }
  }

  for (const d of scanDirs) walk(d);
});

test('GitHub Action apk Debug is manual-only, zero-cache, and uses Gradle 9.9.1', () => {
  const workflow = fs.readFileSync(
    path.join(rootDir, '.github/workflows/build-android-apk.yml'),
    'utf8',
  );
  const wrapperProps = fs.readFileSync(
    path.join(rootDir, 'android/gradle/wrapper/gradle-wrapper.properties'),
    'utf8',
  );

  assert.match(workflow, /^name:\s*apk Debug$/m);
  assert.match(workflow, /workflow_dispatch:/);
  assert.doesNotMatch(workflow, /^\s*push:/m);
  assert.doesNotMatch(workflow, /^\s*pull_request:/m);
  assert.match(workflow, /cache-disabled:\s*true/);
  assert.match(workflow, /--no-build-cache/);
  assert.match(workflow, /cargo fetch/);
  assert.match(workflow, /assembleDebug/);
  assert.match(workflow, /9\.9\.1/);
  assert.match(workflow, /android-actions\/setup-android@v4/);
  assert.match(workflow, /platforms;android-37/);
  assert.doesNotMatch(workflow, /^\s*tools$/m);
  assert.match(wrapperProps, /gradle-9\.9\.1-bin\.zip/);
  assert.ok(
    fs.existsSync(path.join(rootDir, 'android/gradle/wrapper/gradle-wrapper.jar')),
    'gradle-wrapper.jar must be committed so gradlew works everywhere',
  );
});

test('project documentation suite exists at repository root', () => {
  for (const doc of ['README.md', 'ROADMAP.md', 'STRUCTURE.md', 'AI_CONTEXT.md', 'AGENTS.md', 'README_AI.md']) {
    const fullPath = path.join(rootDir, doc);
    assert.ok(fs.existsSync(fullPath), `Missing documentation file: ${doc}`);
    assert.ok(fs.readFileSync(fullPath, 'utf8').trim().length > 100, `Documentation file too short: ${doc}`);
  }
});

test('audio metadata extraction runs in Rust (lofty) without MediaMetadataRetriever fallback', () => {
  const cargoToml = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/Cargo.toml'), 'utf8');
  const rustLib = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/src/lib.rs'), 'utf8');
  const metadataRs = path.join(rootDir, 'android/rust/aura_core/src/metadata.rs');
  const importerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/importer/src/main/kotlin/com/auramusic/core/importer/AudioImporter.kt'),
    'utf8',
  );

  assert.match(cargoToml, /lofty\s*=/);
  assert.ok(fs.existsSync(metadataRs), 'Missing android/rust/aura_core/src/metadata.rs');
  assert.match(rustLib, /nativeExtractAudioMetadata/);
  assert.doesNotMatch(importerKt, /MediaMetadataRetriever/);
  assert.match(importerKt, /AuraRustBridge\.extractAudioMetadata/);
});

test('Room database enables schema export and registers non-destructive migrations', () => {
  const dbGradle = fs.readFileSync(path.join(rootDir, 'android/core/database/build.gradle.kts'), 'utf8');
  const dbKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraDatabase.kt'),
    'utf8',
  );
  const migrationsKt = path.join(
    rootDir,
    'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraMigrations.kt',
  );
  const repoKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );

  assert.match(dbGradle, /room\.schemaLocation/);
  assert.match(dbKt, /exportSchema\s*=\s*true/);
  assert.ok(fs.existsSync(migrationsKt), 'Missing AuraMigrations.kt');
  assert.match(repoKt, /addMigrations\(\*AuraMigrations\.ALL_MIGRATIONS\)/);
  assert.doesNotMatch(repoKt, /fallbackToDestructiveMigration/);

  const schemaV1Path = path.join(
    rootDir,
    'android/core/database/schemas/com.auramusic.core.database.AuraDatabase/1.json',
  );
  assert.ok(fs.existsSync(schemaV1Path), 'Missing versioned Room schema 1.json');
  const schemaV1 = JSON.parse(fs.readFileSync(schemaV1Path, 'utf8'));
  assert.equal(schemaV1.database.version, 1);
  const entityNames = schemaV1.database.entities.map((e: { tableName: string }) => e.tableName);
  assert.ok(entityNames.includes('tracks'));
  assert.ok(entityNames.includes('playlists'));
  assert.ok(entityNames.includes('playlist_tracks'));
});

test('cover cleanup deletes unreferenced covers when tracks are removed', () => {
  const dbKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraDatabase.kt'),
    'utf8',
  );
  const repoKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );
  const importerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/importer/src/main/kotlin/com/auramusic/core/importer/AudioImporter.kt'),
    'utf8',
  );

  assert.match(dbKt, /getCoverFilePathById/);
  assert.match(dbKt, /countTracksUsingCoverPath/);
  assert.match(dbKt, /getAllReferencedCoverPaths/);
  assert.match(repoKt, /countTracksUsingCoverPath\(coverPath\)\s*==\s*0/);
  assert.match(importerKt, /cleanupOrphanCovers/);
});

test('player screen uses framed square cover and circular progress thumb', () => {
  const playerScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/player/src/main/kotlin/com/auramusic/feature/player/PlayerScreen.kt'),
    'utf8',
  );

  assert.match(playerScreenKt, /BoxWithConstraints/);
  assert.match(playerScreenKt, /thumb\s*=\s*\{/);
  assert.match(playerScreenKt, /CircleShape/);
});

test('player controller reconnects after UI recreation and ViewModel does not release singleton controller', () => {
  const controllerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlayerController.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );

  assert.match(controllerKt, /fun onUiAttached\(\)/);
  assert.match(controllerKt, /fun onUiDetached\(\)/);
  assert.match(controllerKt, /rebuildQueueFromPlayer/);
  assert.match(vmKt, /onUiAttached\(\)/);
  assert.match(vmKt, /onUiDetached\(\)/);
  assert.doesNotMatch(vmKt, /playerController\.release\(\)/);
});

test('startup loading screen waits for initial library load and theme fixes comfortable fontScale', () => {
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const mainKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/MainActivity.kt'),
    'utf8',
  );
  const startupKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraStartupScreen.kt'),
    'utf8',
  );
  const themeKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraTheme.kt'),
    'utf8',
  );

  assert.match(vmKt, /val isAppReady: StateFlow<Boolean>/);
  assert.match(mainKt, /AuraStartupLoadingScreen/);
  assert.match(startupKt, /fun AuraStartupLoadingScreen\(\)/);
  assert.match(themeKt, /AuraTypography/);
  assert.match(themeKt, /fontScale\s*=\s*1\.0f/);
});

test('embedded cover extraction detects real format and custom gallery covers convert to lossless WebP off main thread', () => {
  const importerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/importer/src/main/kotlin/com/auramusic/core/importer/AudioImporter.kt'),
    'utf8',
  );
  const repoKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );

  assert.match(importerKt, /detectImageExtension\(bytes\)/);
  assert.doesNotMatch(importerKt, /emb_\$\{fingerprint\.take\(24\)\}\.jpg/);
  assert.match(importerKt, /saveCustomCoverAsLosslessWebp/);
  assert.match(importerKt, /WEBP_LOSSLESS/);
  assert.match(importerKt, /compress\(format,\s*100,\s*out\)/);
  assert.match(importerKt, /withContext\(Dispatchers\.IO\)/);
  assert.match(importerKt, /withContext\(Dispatchers\.Default\)/);
  assert.match(repoKt, /suspend fun updateTrackCover/);
});

test('batch audio import uses single DB identity query and bounded coroutine concurrency', () => {
  const importerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/importer/src/main/kotlin/com/auramusic/core/importer/AudioImporter.kt'),
    'utf8',
  );
  const dbKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraDatabase.kt'),
    'utf8',
  );

  assert.match(dbKt, /getAllTrackIdentities/);
  assert.match(importerKt, /repository\.getAllTrackIdentities\(\)/);
  assert.match(importerKt, /Semaphore\(/);
  assert.match(importerKt, /awaitAll\(\)/);
});

test('10-band equalizer runs in Rust DSP wired to Media3 AudioSink with dedicated feature screen', () => {
  const eqRs = path.join(rootDir, 'android/rust/aura_core/src/equalizer.rs');
  const rustLib = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/src/lib.rs'), 'utf8');
  const processorKt = path.join(
    rootDir,
    'android/core/player/src/main/kotlin/com/auramusic/core/player/RustEqualizerAudioProcessor.kt',
  );
  const serviceKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlaybackService.kt'),
    'utf8',
  );
  const eqScreenKt = path.join(
    rootDir,
    'android/feature/equalizer/src/main/kotlin/com/auramusic/feature/equalizer/EqualizerScreen.kt',
  );

  assert.ok(fs.existsSync(eqRs), 'Missing android/rust/aura_core/src/equalizer.rs');
  assert.ok(fs.existsSync(processorKt), 'Missing RustEqualizerAudioProcessor.kt');
  assert.ok(fs.existsSync(eqScreenKt), 'Missing EqualizerScreen.kt');
  assert.match(rustLib, /mod equalizer;/);
  assert.match(rustLib, /nativeProcessEqualizerPcm16/);
  assert.match(rustLib, /nativeProcessEqualizerPcmFloat/);
  assert.match(serviceKt, /RustEqualizerAudioProcessor/);
  assert.match(serviceKt, /DefaultAudioSink\.Builder/);
  assert.doesNotMatch(serviceKt, /android\.media\.audiofx\.Equalizer/);
});

test('dynamic cover colors use androidx.palette + MaterialKolor with smooth animations and DataStore toggle', () => {
  const catalog = fs.readFileSync(path.join(rootDir, 'android/gradle/libs.versions.toml'), 'utf8');
  const playerGradle = fs.readFileSync(path.join(rootDir, 'android/feature/player/build.gradle.kts'), 'utf8');
  const coverThemeKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/player/src/main/kotlin/com/auramusic/feature/player/CoverDynamicTheme.kt'),
    'utf8',
  );
  const playerScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/player/src/main/kotlin/com/auramusic/feature/player/PlayerScreen.kt'),
    'utf8',
  );
  const reposKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );

  assert.match(catalog, /androidx\.palette/);
  assert.match(catalog, /material-kolor\s*=/);
  assert.match(playerGradle, /libs\.androidx\.palette/);
  assert.match(playerGradle, /libs\.material\.kolor/);

  assert.match(coverThemeKt, /androidx\.palette\.graphics\.Palette/);
  assert.match(coverThemeKt, /com\.materialkolor\.rememberDynamicColorScheme/);
  assert.match(coverThemeKt, /com\.materialkolor\.ktx\.themeColorOrNull/);
  assert.match(coverThemeKt, /animateColorAsState/);
  assert.match(coverThemeKt, /Dispatchers\.IO/);
  assert.match(coverThemeKt, /Dispatchers\.Default/);

  assert.match(playerScreenKt, /CoverDynamicTheme\(/);
  assert.match(reposKt, /dynamic_cover_colors/);
  assert.match(reposKt, /setDynamicCoverColorsEnabled/);
});

test('equalizer entry is removed from SettingsScreen and kept only in PlayerScreen', () => {
  const settingsKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/settings/src/main/kotlin/com/auramusic/feature/settings/SettingsScreen.kt'),
    'utf8',
  );
  const playerKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/player/src/main/kotlin/com/auramusic/feature/player/PlayerScreen.kt'),
    'utf8',
  );

  assert.doesNotMatch(settingsKt, /Ecualizador de 10 bandas/);
  assert.doesNotMatch(settingsKt, /onOpenEqualizer/);
  assert.match(settingsKt, /Colores dinámicos de carátula/);
  assert.match(settingsKt, /Switch\(/);
  assert.match(playerKt, /onOpenEqualizer/);
});

test('audio downloader integrates lightweight Python 3.11 + yt-dlp + yt-dlp-ejs + Rust QuickJS-NG without WebView or copyleft FFmpeg', () => {
  const settings = fs.readFileSync(path.join(rootDir, 'android/settings.gradle.kts'), 'utf8');
  const includes = [...settings.matchAll(/include\("([^"]+)"\)/g)].map((m) => m[1]);
  assert.ok(includes.length >= 13, 'Expected :core:downloader and :feature:downloader in settings.gradle.kts');
  assert.ok(includes.includes(':core:downloader'));
  assert.ok(includes.includes(':feature:downloader'));

  const downloaderGradle = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/build.gradle.kts'),
    'utf8',
  );
  const appGradle = fs.readFileSync(path.join(rootDir, 'android/app/build.gradle.kts'), 'utf8');
  const manifest = fs.readFileSync(path.join(rootDir, 'android/app/src/main/AndroidManifest.xml'), 'utf8');
  const cargoToml = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/Cargo.toml'), 'utf8');
  const rustGradle = fs.readFileSync(path.join(rootDir, 'android/core/rust/build.gradle.kts'), 'utf8');
  const qjsBin = path.join(rootDir, 'android/rust/aura_core/src/bin/aura_qjs.rs');
  const qjsMod = path.join(rootDir, 'android/rust/aura_core/src/js_runtime.rs');
  const pyScript = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/python/aura_ytdlp.py'),
    'utf8',
  );
  const downloaderScreen = fs.readFileSync(
    path.join(rootDir, 'android/feature/downloader/src/main/kotlin/com/auramusic/feature/downloader/DownloaderScreen.kt'),
    'utf8',
  );

  assert.match(downloaderGradle, /project\(":core:database"\)/);
  assert.match(downloaderGradle, /version\s*=\s*"3\.11"/);
  assert.match(downloaderGradle, /options\("--no-deps"\)/);
  assert.match(downloaderGradle, /install\("yt-dlp"\)/);
  assert.match(downloaderGradle, /install\("yt-dlp-ejs"\)/);
  assert.match(downloaderGradle, /extractPackages\("yt_dlp_ejs"\)/);
  assert.match(downloaderGradle, /armeabi-v7a/);
  assert.match(downloaderGradle, /arm64-v8a/);
  assert.match(appGradle, /useLegacyPackaging\s*=\s*true/);
  assert.match(manifest, /android\.permission\.INTERNET/);

  assert.match(cargoToml, /rquickjs\s*=/);
  assert.match(cargoToml, /name\s*=\s*"aura_qjs"/);
  assert.ok(fs.existsSync(qjsBin), 'Missing aura_qjs.rs binary');
  assert.ok(fs.existsSync(qjsMod), 'Missing js_runtime.rs module');
  assert.match(rustGradle, /libaura_qjs\.so/);

  assert.match(pyScript, /sha256/i);
  assert.match(pyScript, /js_runtimes/);
  assert.match(pyScript, /quickjs/);
  assert.match(pyScript, /pypi\.org\/pypi/);

  // Sin WebView ni dependencias copyleft
  const catalog = fs.readFileSync(path.join(rootDir, 'android/gradle/libs.versions.toml'), 'utf8');
  assert.doesNotMatch(downloaderScreen, /android\.webkit\.WebView|\bWebView\s*\(/);
  assert.doesNotMatch(downloaderGradle, /implementation\([^\n]*(youtubedl-android|newpipe|ffmpeg)/i);
  assert.doesNotMatch(downloaderGradle, /install\([^\n]*(ffmpeg|pycryptodomex)/i);
  assert.doesNotMatch(catalog, /youtubedl-android|newpipe|ffmpeg|rustypipe/i);
  assert.doesNotMatch(cargoToml, /rustypipe|ffmpeg/i);
});

test('downloader is removed from SettingsScreen, supports preview confirmation + speed metrics, and workflow supports universal/individual APKs', () => {
  const settingsKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/settings/src/main/kotlin/com/auramusic/feature/settings/SettingsScreen.kt'),
    'utf8',
  );
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const pyScript = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/python/aura_ytdlp.py'),
    'utf8',
  );
  const downloaderScreen = fs.readFileSync(
    path.join(rootDir, 'android/feature/downloader/src/main/kotlin/com/auramusic/feature/downloader/DownloaderScreen.kt'),
    'utf8',
  );
  const libraryScreen = fs.readFileSync(
    path.join(rootDir, 'android/feature/library/src/main/kotlin/com/auramusic/feature/library/LibraryScreen.kt'),
    'utf8',
  );
  const playlistsScreen = fs.readFileSync(
    path.join(rootDir, 'android/feature/playlists/src/main/kotlin/com/auramusic/feature/playlists/PlaylistsScreen.kt'),
    'utf8',
  );
  const workflow = fs.readFileSync(
    path.join(rootDir, '.github/workflows/build-android-apk.yml'),
    'utf8',
  );

  // yt-dlp solo en Importar, no en Ajustes
  assert.doesNotMatch(settingsKt, /onOpenDownloader/);
  assert.doesNotMatch(settingsKt, /Descargas y actualizaciones \(yt-dlp\)/);
  assert.match(libraryScreen, /onOpenDownloader/);

  // Vista previa antes de confirmar + peso y velocidad en tiempo real
  assert.match(modelsKt, /data class DownloadPreview/);
  assert.match(modelsKt, /PREVIEW_READY/);
  assert.match(modelsKt, /speedBytesPerSec/);
  assert.match(pyScript, /def inspect_audio_preview/);
  assert.match(pyScript, /speed_bps/);
  assert.match(downloaderScreen, /Confirmar descarga/);
  assert.match(downloaderScreen, /Velocidad/);

  // Modales de confirmación al eliminar canción o quitar de playlist
  assert.match(libraryScreen, /trackPendingDelete/);
  assert.match(libraryScreen, /AlertDialog/);
  assert.match(playlistsScreen, /trackPendingRemove/);
  assert.match(playlistsScreen, /AlertDialog/);

  // GitHub Actions permite elegir APK universal o individuales (32 y 64 bits)
  assert.match(workflow, /apk_mode:/);
  assert.match(workflow, /-\s*universal/);
  assert.match(workflow, /-\s*individuales/);
  assert.match(workflow, /armeabi-v7a/);
  assert.match(workflow, /arm64-v8a/);
});

test('creative delete animations (ashes, burning paper, strange aura) run randomly in Rust with DataStore toggle', () => {
  const deleteFxRs = path.join(rootDir, 'android/rust/aura_core/src/delete_fx.rs');
  const rustLib = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/src/lib.rs'), 'utf8');
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const bridgeKt = fs.readFileSync(
    path.join(rootDir, 'android/core/rust/src/main/kotlin/com/auramusic/core/rust/AuraRustBridge.kt'),
    'utf8',
  );
  const reposKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );
  const settingsKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/settings/src/main/kotlin/com/auramusic/feature/settings/SettingsScreen.kt'),
    'utf8',
  );
  const libraryKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/library/src/main/kotlin/com/auramusic/feature/library/LibraryScreen.kt'),
    'utf8',
  );
  const playlistsKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/playlists/src/main/kotlin/com/auramusic/feature/playlists/PlaylistsScreen.kt'),
    'utf8',
  );
  const rootKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraAppRoot.kt'),
    'utf8',
  );

  assert.ok(fs.existsSync(deleteFxRs), 'Missing android/rust/aura_core/src/delete_fx.rs');
  const deleteFxCode = fs.readFileSync(deleteFxRs, 'utf8');
  assert.match(deleteFxCode, /DeleteEffectKind/);
  assert.match(deleteFxCode, /Ashes/);
  assert.match(deleteFxCode, /BurningPaper/);
  assert.match(deleteFxCode, /StrangeAura/);
  assert.match(rustLib, /mod delete_fx;/);
  assert.match(rustLib, /nativeRenderDeleteEffectFrame/);

  assert.match(modelsKt, /enum class DeleteAnimationEffect/);
  assert.match(modelsKt, /fun random\(\)/);
  assert.match(modelsKt, /fun interface DeleteEffectFrameRenderer/);
  assert.match(bridgeKt, /suspend fun renderDeleteEffectFrame/);

  assert.match(reposKt, /delete_animations_enabled/);
  assert.match(reposKt, /setDeleteAnimationsEnabled/);
  assert.match(settingsKt, /Animaciones al eliminar \(Rust\)/);
  assert.match(settingsKt, /onToggleDeleteAnimations/);

  assert.match(libraryKt, /DeleteAnimationEffect\.random\(\)/);
  assert.match(libraryKt, /DeleteEffectFrameRenderer/);
  assert.match(playlistsKt, /DeleteAnimationEffect\.random\(\)/);
  assert.match(playlistsKt, /DeleteEffectFrameRenderer/);
  assert.match(rootKt, /AuraRustBridge\.renderDeleteEffectFrame/);
});

test('yt-dlp downloads run in background foreground service (dataSync) with cancellation and ProGuard rules', () => {
  const serviceKt = path.join(
    rootDir,
    'android/core/downloader/src/main/kotlin/com/auramusic/core/downloader/AuraDownloadForegroundService.kt',
  );
  const downloaderManifest = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/AndroidManifest.xml'),
    'utf8',
  );
  const appManifest = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/AndroidManifest.xml'),
    'utf8',
  );
  const consumerRules = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/consumer-rules.pro'),
    'utf8',
  );
  const downloaderKt = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/kotlin/com/auramusic/core/downloader/YtDlpAudioDownloader.kt'),
    'utf8',
  );
  const pyScript = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/python/aura_ytdlp.py'),
    'utf8',
  );
  const appKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraApplication.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const libraryCoordinatorKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraLibraryCoordinator.kt'),
    'utf8',
  );
  const downloaderScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/downloader/src/main/kotlin/com/auramusic/feature/downloader/DownloaderScreen.kt'),
    'utf8',
  );

  assert.ok(fs.existsSync(serviceKt), 'Missing AuraDownloadForegroundService.kt');
  const serviceCode = fs.readFileSync(serviceKt, 'utf8');
  assert.match(serviceCode, /FOREGROUND_SERVICE_TYPE_DATA_SYNC/);
  assert.match(serviceCode, /interface AuraDownloadHost/);
  assert.match(serviceCode, /ACTION_CANCEL_DOWNLOAD/);

  assert.match(downloaderManifest, /AuraDownloadForegroundService/);
  assert.match(downloaderManifest, /foregroundServiceType="dataSync"/);
  assert.match(appManifest, /FOREGROUND_SERVICE_DATA_SYNC/);
  assert.match(appManifest, /WAKE_LOCK/);

  assert.match(consumerRules, /PyProgressBridge/);
  assert.match(consumerRules, /PyCancelBridge/);
  assert.match(downloaderKt, /startBackgroundConfirmedDownload/);
  assert.match(downloaderKt, /requestCancelDownload/);
  assert.match(pyScript, /DownloadCancelledError/);
  assert.match(pyScript, /cancel_check/);

  assert.match(appKt, /AuraDownloadHost/);
  assert.match(libraryCoordinatorKt, /startBackgroundConfirmedDownload/);
  assert.match(vmKt, /confirmPendingDownload/);
  assert.match(vmKt, /cancelActiveDownload/);
  assert.match(downloaderScreenKt, /onCancelDownload/);
  assert.match(downloaderScreenKt, /DownloadStatus\.CANCELLED/);
});

test('automatic artist playlists group tracks by artist and isolate playback to that artist', () => {
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const artistDetailKt = path.join(
    rootDir,
    'android/feature/playlists/src/main/kotlin/com/auramusic/feature/playlists/ArtistDetailScreen.kt',
  );
  const playlistsKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/playlists/src/main/kotlin/com/auramusic/feature/playlists/PlaylistsScreen.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const navGraphKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraNavGraph.kt'),
    'utf8',
  );
  const routesKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraRoutes.kt'),
    'utf8',
  );

  assert.match(modelsKt, /data class ArtistPlaylist/);
  assert.match(modelsKt, /object ArtistPlaylistBuilder/);
  assert.match(modelsKt, /fun buildFromTracks/);
  assert.ok(fs.existsSync(artistDetailKt), 'Missing ArtistDetailScreen.kt');
  const artistDetailCode = fs.readFileSync(artistDetailKt, 'utf8');
  assert.match(artistDetailCode, /onPlayTrack\(tracks, index\)/);
  assert.match(artistDetailCode, /onPlayShuffled\(tracks\)/);

  assert.match(playlistsKt, /artistPlaylists:\s*List<ArtistPlaylist>/);
  assert.match(playlistsKt, /Artistas \(\$\{artistPlaylists\.size\}\)/);
  assert.match(vmKt, /val artistPlaylists:\s*StateFlow<List<ArtistPlaylist>>/);
  assert.match(vmKt, /fun observeArtistPlaylist/);
  assert.match(routesKt, /ARTIST_DETAIL/);
  assert.match(navGraphKt, /ArtistDetailScreen\(/);
});

test('interactive playback queue supports play-next, append, reorder, remove, clear, and QueueScreen', () => {
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const controllerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlayerController.kt'),
    'utf8',
  );
  const queueScreenKt = path.join(
    rootDir,
    'android/feature/player/src/main/kotlin/com/auramusic/feature/player/QueueScreen.kt',
  );
  const playerScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/player/src/main/kotlin/com/auramusic/feature/player/PlayerScreen.kt'),
    'utf8',
  );
  const libraryScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/library/src/main/kotlin/com/auramusic/feature/library/LibraryScreen.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const navGraphKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraNavGraph.kt'),
    'utf8',
  );

  assert.match(modelsKt, /object PlaybackQueueHelper/);
  assert.match(modelsKt, /fun insertPlayNext/);
  assert.match(modelsKt, /fun appendToQueue/);
  assert.match(modelsKt, /fun moveItem/);
  assert.match(modelsKt, /fun removeItem/);
  assert.match(modelsKt, /fun clearUpcoming/);

  assert.match(controllerKt, /fun playNext\(/);
  assert.match(controllerKt, /fun addToQueue\(/);
  assert.match(controllerKt, /fun playQueueItemAt\(/);
  assert.match(controllerKt, /fun moveQueueItem\(/);
  assert.match(controllerKt, /fun removeFromQueue\(/);
  assert.match(controllerKt, /fun clearUpcomingQueue\(/);

  assert.ok(fs.existsSync(queueScreenKt), 'Missing QueueScreen.kt');
  assert.match(playerScreenKt, /onOpenQueue/);
  assert.match(libraryScreenKt, /Reproducir siguiente/);
  assert.match(libraryScreenKt, /Añadir a la cola/);
  assert.match(vmKt, /fun clearUpcomingQueue/);
  assert.match(navGraphKt, /AuraRoutes\.QUEUE/);
  assert.match(navGraphKt, /QueueScreen\(/);
});

test('Open-with ("Abrir con") ACTION_VIEW handles content/file URIs safely and opens PlayerScreen', () => {
  const manifest = fs.readFileSync(path.join(rootDir, 'android/app/src/main/AndroidManifest.xml'), 'utf8');
  const mainKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/MainActivity.kt'),
    'utf8',
  );
  const importerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/importer/src/main/kotlin/com/auramusic/core/importer/AudioImporter.kt'),
    'utf8',
  );
  const dbKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraDatabase.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const rootKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraAppRoot.kt'),
    'utf8',
  );

  assert.match(manifest, /android\.intent\.action\.VIEW/);
  assert.match(manifest, /android:mimeType="audio\/\*"/);
  assert.match(manifest, /android:scheme="content"/);
  assert.match(manifest, /android:scheme="file"/);

  assert.match(mainKt, /override fun onNewIntent\(intent:\s*Intent\)/);
  assert.match(mainKt, /Intent\.ACTION_VIEW/);
  assert.match(mainKt, /openExternalAudioUri/);

  assert.match(importerKt, /suspend fun importExternalAudioUri/);
  assert.match(importerKt, /opened_audio/);
  assert.match(dbKt, /fun findByUriOrFingerprint/);

  assert.match(vmKt, /fun openExternalAudioUri\(uri:\s*Uri\)/);
  assert.match(vmKt, /val openPlayerEvents:\s*Flow<Unit>/);
  assert.match(rootKt, /viewModel\.openPlayerEvents\.collect/);
  assert.match(rootKt, /AuraRoutes\.PLAYER/);
});

test('configurable Crossfade (seconds before end) and playback speed live exclusively in PlayerScreen 3-dot menu, service, and DataStore', () => {
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const reposKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );
  const serviceKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlaybackService.kt'),
    'utf8',
  );
  const tuningProviderKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlaybackTuningProvider.kt'),
    'utf8',
  );
  const crossfadeEngineKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraCrossfadeEngine.kt'),
    'utf8',
  );
  const controllerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlayerController.kt'),
    'utf8',
  );
  const appKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraApplication.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const playerScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/player/src/main/kotlin/com/auramusic/feature/player/PlayerScreen.kt'),
    'utf8',
  );
  const settingsScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/settings/src/main/kotlin/com/auramusic/feature/settings/SettingsScreen.kt'),
    'utf8',
  );

  assert.match(modelsKt, /data class PlaybackTuning/);
  assert.match(modelsKt, /object PlaybackTuningHelper/);
  assert.match(modelsKt, /fun computeEqualPowerGains/);
  assert.match(modelsKt, /fun shouldTriggerCrossfade/);

  assert.match(reposKt, /class PlaybackPreferencesRepository/);
  assert.match(reposKt, /playback_crossfade_enabled/);
  assert.match(reposKt, /playback_crossfade_seconds/);
  assert.match(reposKt, /playback_speed/);

  assert.match(tuningProviderKt, /interface AuraPlaybackTuningProvider/);
  assert.match(crossfadeEngineKt, /outgoingFadePlayer/);
  assert.match(crossfadeEngineKt, /launchOverlapCrossfade/);
  assert.match(crossfadeEngineKt, /playWhenReady/);
  assert.match(serviceKt, /onPlayWhenReadyChanged/);
  assert.match(serviceKt, /PlaybackParameters\(/);

  assert.match(controllerKt, /fun setPlaybackSpeed\(speed:\s*Float\)/);
  assert.match(appKt, /AuraPlaybackTuningProvider/);
  assert.match(vmKt, /val playbackTuning:\s*StateFlow<PlaybackTuning>/);
  assert.match(vmKt, /fun setCrossfadeEnabled/);
  assert.match(vmKt, /fun setCrossfadeSeconds/);
  assert.match(vmKt, /fun setPlaybackSpeed/);

  // PlayerScreen agrupa sus acciones en menú de 3 puntos y mantiene el encabezado en una línea
  assert.match(playerScreenKt, /Icons\.Rounded\.MoreVert/);
  assert.match(playerScreenKt, /DropdownMenu\(/);
  assert.match(playerScreenKt, /DropdownMenuItem\(/);
  assert.match(playerScreenKt, /PlaybackTuningBottomSheet/);
  assert.match(playerScreenKt, /Velocidad y Crossfade/);

  // Ajustes NO debe mostrar Crossfade ni Velocidad de reproducción
  assert.doesNotMatch(settingsScreenKt, /Crossfade entre canciones/);
  assert.doesNotMatch(settingsScreenKt, /Velocidad de reproducción/);
  assert.doesNotMatch(settingsScreenKt, /onToggleCrossfade/);
});

test('Android system media notification keeps symmetric transport controls, small icon, and opens PlayerScreen', () => {
  const iconXml = path.join(
    rootDir,
    'android/core/player/src/main/res/drawable/ic_aura_notification.xml',
  );
  const serviceKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlaybackService.kt'),
    'utf8',
  );
  const forwardingKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraSessionForwardingPlayer.kt'),
    'utf8',
  );
  const appKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraApplication.kt'),
    'utf8',
  );
  const mainKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/MainActivity.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const navGraphKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraNavGraph.kt'),
    'utf8',
  );

  assert.ok(fs.existsSync(iconXml), 'Missing ic_aura_notification.xml');
  assert.match(serviceKt, /DefaultMediaNotificationProvider/);
  assert.match(serviceKt, /setSmallIcon\(R\.drawable\.ic_aura_notification\)/);
  assert.match(forwardingKt, /class SessionControlForwardingPlayer/);
  assert.match(forwardingKt, /class ExtendedCommandsListener/);
  assert.doesNotMatch(forwardingKt, /\)\s*:\s*Player\.Listener\s+by\s+/);
  assert.match(forwardingKt, /Proxy\.newProxyInstance/);
  assert.match(forwardingKt, /COMMAND_SEEK_TO_NEXT_MEDIA_ITEM/);
  assert.match(forwardingKt, /COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM/);
  assert.match(serviceKt, /setSessionActivity\(pendingIntent\)/);

  assert.match(appKt, /EXTRA_OPEN_PLAYER/);
  assert.match(appKt, /createOpenPlayerIntent\(\)/);
  assert.match(mainKt, /EXTRA_OPEN_PLAYER/);
  assert.match(mainKt, /requestOpenPlayer\(\)/);
  assert.match(vmKt, /Channel<Unit>\(Channel\.BUFFERED\)/);
  assert.match(vmKt, /fun requestOpenPlayer\(\)/);

  // Al reproducir una pista descargada se carga la cola completa de la biblioteca para mantener navegación fluida
  assert.match(navGraphKt, /onPlayDownloadedTrack\s*=\s*\{\s*track\s*->/);
  assert.match(navGraphKt, /viewModel\.playQueue\(tracks,\s*idx\)/);
});

test('Disco Local con Aura SVG logo and adaptive/notification drawables are wired across app and services', () => {
  const masterSvg = path.join(rootDir, 'android/app/branding/aura_music_logo.svg');
  const monoSvg = path.join(rootDir, 'android/app/branding/aura_music_monochrome.svg');
  const bgXml = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/res/drawable/ic_launcher_background.xml'),
    'utf8',
  );
  const fgXml = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/res/drawable/ic_launcher_foreground.xml'),
    'utf8',
  );
  const monoXml = path.join(rootDir, 'android/app/src/main/res/drawable/ic_launcher_monochrome.xml');
  const brandXml = path.join(rootDir, 'android/app/src/main/res/drawable/ic_aura_brand_logo.xml');
  const launcherXml = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/res/mipmap-anydpi/ic_launcher.xml'),
    'utf8',
  );
  const launcherRoundXml = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/res/mipmap-anydpi/ic_launcher_round.xml'),
    'utf8',
  );
  const playerNotifXml = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/res/drawable/ic_aura_notification.xml'),
    'utf8',
  );
  const dlNotifXml = path.join(
    rootDir,
    'android/core/downloader/src/main/res/drawable/ic_aura_download_notification.xml',
  );
  const dlServiceKt = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/kotlin/com/auramusic/core/downloader/AuraDownloadForegroundService.kt'),
    'utf8',
  );
  const startupKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraStartupScreen.kt'),
    'utf8',
  );

  assert.ok(fs.existsSync(masterSvg), 'Missing android/app/branding/aura_music_logo.svg');
  assert.ok(fs.existsSync(monoSvg), 'Missing android/app/branding/aura_music_monochrome.svg');
  assert.ok(fs.existsSync(monoXml), 'Missing ic_launcher_monochrome.xml');
  assert.ok(fs.existsSync(brandXml), 'Missing ic_aura_brand_logo.xml');
  assert.ok(fs.existsSync(dlNotifXml), 'Missing ic_aura_download_notification.xml');

  assert.match(bgXml, /<gradient/);
  assert.match(fgXml, /<gradient/);
  assert.match(launcherXml, /<monochrome android:drawable="@drawable\/ic_launcher_monochrome"/);
  assert.match(launcherRoundXml, /<monochrome android:drawable="@drawable\/ic_launcher_monochrome"/);
  assert.match(playerNotifXml, /evenOdd/);
  assert.match(dlServiceKt, /R\.drawable\.ic_aura_download_notification/);
  assert.match(startupKt, /R\.drawable\.ic_aura_brand_logo/);
});

test('local track metadata editing updates Room, syncs MediaController items, and is exposed across library, playlists, artists, and player', () => {
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const modelsTestKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/test/kotlin/com/auramusic/core/model/AuraModelsTest.kt'),
    'utf8',
  );
  const dbKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraDatabase.kt'),
    'utf8',
  );
  const repoKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );
  const controllerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlayerController.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const rootKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraAppRoot.kt'),
    'utf8',
  );
  const dialogsKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraAppDialogs.kt'),
    'utf8',
  );
  const libraryKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/library/src/main/kotlin/com/auramusic/feature/library/LibraryScreen.kt'),
    'utf8',
  );
  const playlistsKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/playlists/src/main/kotlin/com/auramusic/feature/playlists/PlaylistsScreen.kt'),
    'utf8',
  );
  const artistDetailKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/playlists/src/main/kotlin/com/auramusic/feature/playlists/ArtistDetailScreen.kt'),
    'utf8',
  );
  const playerKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/player/src/main/kotlin/com/auramusic/feature/player/PlayerScreen.kt'),
    'utf8',
  );

  assert.match(modelsKt, /data class EditedTrackMetadata/);
  assert.match(modelsKt, /object TrackMetadataHelper/);
  assert.match(modelsKt, /fun normalizeOrNull/);
  assert.match(modelsTestKt, /TrackMetadataHelper\.normalizeOrNull/);

  assert.match(dbKt, /suspend fun updateMetadata\(/);
  assert.match(repoKt, /suspend fun updateTrackMetadata\(/);
  assert.match(controllerKt, /private fun syncMediaItemsMetadata\(/);
  assert.match(controllerKt, /player\.replaceMediaItem\(/);

  assert.match(vmKt, /fun updateTrackMetadata\(/);
  assert.match(dialogsKt, /private fun EditTrackMetadataDialog\(/);
  assert.match(rootKt, /trackPendingMetadataEdit/);

  for (const screenCode of [libraryKt, playlistsKt, artistDetailKt, playerKt]) {
    assert.match(screenCode, /onEditMetadata/);
    assert.match(screenCode, /Editar metadatos/);
  }
});

test('audio downloader supports preferred quality presets and real format/bitrate selection in preview', () => {
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const modelsTestKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/test/kotlin/com/auramusic/core/model/AuraModelsTest.kt'),
    'utf8',
  );
  const reposKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );
  const pyScript = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/python/aura_ytdlp.py'),
    'utf8',
  );
  const downloaderKt = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/kotlin/com/auramusic/core/downloader/YtDlpAudioDownloader.kt'),
    'utf8',
  );
  const downloaderScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/downloader/src/main/kotlin/com/auramusic/feature/downloader/DownloaderScreen.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const navGraphKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraNavGraph.kt'),
    'utf8',
  );

  assert.match(modelsKt, /enum class AudioDownloadQuality/);
  assert.match(modelsKt, /data class DownloadAudioFormatOption/);
  assert.match(modelsKt, /object DownloadFormatHelper/);
  assert.match(modelsTestKt, /DownloadFormatHelper\.pickDefaultOption/);

  assert.match(reposKt, /ytdlp_audio_quality/);
  assert.match(reposKt, /setAudioDownloadQuality/);

  assert.match(pyScript, /def _build_format_selector/);
  assert.match(pyScript, /def _extract_available_audio_formats/);
  assert.match(pyScript, /"available_formats":\s*available_formats/);
  assert.match(pyScript, /format_id:\s*str\s*\|\s*None\s*=\s*None/);

  assert.match(downloaderKt, /suspend fun setPreferredAudioQuality/);
  assert.match(downloaderKt, /fun selectPreviewAudioFormat/);
  assert.match(downloaderKt, /parseAvailableFormats/);

  assert.match(downloaderScreenKt, /Calidad de audio preferida/);
  assert.match(downloaderScreenKt, /Elige calidad del formato/);
  assert.match(downloaderScreenKt, /FormatOptionSelectorRow/);

  assert.match(vmKt, /fun setDownloaderAudioQuality/);
  assert.match(vmKt, /fun selectDownloaderPreviewFormat/);
  assert.match(navGraphKt, /onSelectQualityPreset\s*=\s*viewModel::setDownloaderAudioQuality/);
  assert.match(navGraphKt, /onSelectPreviewFormat\s*=\s*viewModel::selectDownloaderPreviewFormat/);
});

test('Rust stereo Reverb (Freeverb), Slowed, and Spatial 8D integrate modularly via :feature:effects', () => {
  const reverbRs = path.join(rootDir, 'android/rust/aura_core/src/reverb.rs');
  const spatial8dRs = path.join(rootDir, 'android/rust/aura_core/src/spatial_8d.rs');
  const eqRs = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/src/equalizer.rs'), 'utf8');
  const rustLib = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/src/lib.rs'), 'utf8');
  const bridgeKt = fs.readFileSync(
    path.join(rootDir, 'android/core/rust/src/main/kotlin/com/auramusic/core/rust/AuraRustBridge.kt'),
    'utf8',
  );
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const modelsTestKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/test/kotlin/com/auramusic/core/model/AuraModelsTest.kt'),
    'utf8',
  );
  const reposKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );
  const tuningProviderKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlaybackTuningProvider.kt'),
    'utf8',
  );
  const serviceKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlaybackService.kt'),
    'utf8',
  );
  const effectsGradle = fs.readFileSync(path.join(rootDir, 'android/feature/effects/build.gradle.kts'), 'utf8');
  const effectsScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/effects/src/main/kotlin/com/auramusic/feature/effects/AudioEffectsScreen.kt'),
    'utf8',
  );
  const playerScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/player/src/main/kotlin/com/auramusic/feature/player/PlayerScreen.kt'),
    'utf8',
  );
  const settingsScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/settings/src/main/kotlin/com/auramusic/feature/settings/SettingsScreen.kt'),
    'utf8',
  );
  const appKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraApplication.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const navGraphKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraNavGraph.kt'),
    'utf8',
  );
  const routesKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraRoutes.kt'),
    'utf8',
  );

  // Motor DSP en Rust (Freeverb estéreo + calidez analógica LPF + Audio Espacial 8D) sin fallbacks en Kotlin
  assert.ok(fs.existsSync(reverbRs), 'Missing android/rust/aura_core/src/reverb.rs');
  assert.ok(fs.existsSync(spatial8dRs), 'Missing android/rust/aura_core/src/spatial_8d.rs');
  const reverbCode = fs.readFileSync(reverbRs, 'utf8');
  const spatial8dCode = fs.readFileSync(spatial8dRs, 'utf8');
  assert.match(reverbCode, /struct AudioEffectsProcessor/);
  assert.match(reverbCode, /struct CombFilter/);
  assert.match(reverbCode, /struct AllpassFilter/);
  assert.match(reverbCode, /warmth_active/);
  assert.match(reverbCode, /spatial_8d:\s*Spatial8dProcessor/);
  assert.match(spatial8dCode, /struct Spatial8dProcessor/);
  assert.match(spatial8dCode, /struct FractionalDelayLine/);
  assert.match(eqRs, /effects:\s*AudioEffectsProcessor/);
  assert.match(rustLib, /mod reverb;/);
  assert.match(rustLib, /mod spatial_8d;/);
  assert.match(rustLib, /Java_com_auramusic_core_rust_AuraRustBridge_nativeSetAudioEffectsConfig/);
  assert.match(bridgeKt, /suspend fun setAudioEffectsConfig\(/);

  // Modelos, presets, DataStore y Media3 service
  assert.match(modelsKt, /data class AudioEffectsState/);
  assert.match(modelsKt, /val spatial8dEnabled:\s*Boolean/);
  assert.match(modelsKt, /data class AudioEffectPreset/);
  assert.match(modelsKt, /fun resolvePlaybackParameters\(/);
  assert.match(modelsKt, /fun spatial8dOrbitPeriodSeconds\(/);
  assert.match(modelsTestKt, /PlaybackTuningHelper\.resolvePlaybackParameters/);
  assert.match(modelsTestKt, /spatial_8d_slowed/);

  assert.match(reposKt, /class AudioEffectsPreferencesRepository/);
  assert.match(reposKt, /fx_reverb_enabled/);
  assert.match(reposKt, /fx_slowed_enabled/);
  assert.match(reposKt, /fx_spatial_8d_enabled/);

  assert.match(tuningProviderKt, /fun observeAudioEffects\(/);
  assert.match(serviceKt, /resolvePlaybackParameters\(currentTuning,\s*currentEffects\)/);
  assert.doesNotMatch(serviceKt, /android\.media\.audiofx\.PresetReverb|android\.media\.audiofx\.EnvironmentalReverb/);

  // Módulo :feature:effects desacoplado (solo depende de :core:model)
  assert.match(effectsGradle, /project\(":core:model"\)/);
  assert.doesNotMatch(effectsGradle, /project\(":core:(rust|player|database|importer|downloader)"\)/);
  assert.match(effectsScreenKt, /fun AudioEffectsScreen\(/);
  assert.match(effectsScreenKt, /Audio 8D \(Rust\)/);
  assert.match(effectsScreenKt, /Efecto Slowed/);
  assert.match(effectsScreenKt, /Reverb Estéreo \(Rust\)/);

  // Accesible desde menú de 3 puntos del reproductor y oculto en Ajustes
  assert.match(playerScreenKt, /onOpenAudioEffects/);
  assert.match(playerScreenKt, /8D, Reverb y Slowed/);
  assert.doesNotMatch(settingsScreenKt, /Reverb y Slowed|Audio 8D|onOpenAudioEffects/);

  // Conexión en :app
  assert.match(appKt, /audioEffectsPreferencesRepository/);
  assert.match(vmKt, /val audioEffects:\s*StateFlow<AudioEffectsState>/);
  assert.match(vmKt, /fun setSpatial8dEnabled/);
  assert.match(routesKt, /AUDIO_EFFECTS/);
  assert.match(navGraphKt, /AudioEffectsScreen\(/);
});

test('headphone output detection (AudioDeviceCallback without extra BT perms), Rust static Crossfeed, and wired/AVRCP media button tuning integrate cleanly', () => {
  const crossfeedRs = path.join(rootDir, 'android/rust/aura_core/src/crossfeed.rs');
  const reverbRs = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/src/reverb.rs'), 'utf8');
  const eqRs = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/src/equalizer.rs'), 'utf8');
  const rustLib = fs.readFileSync(path.join(rootDir, 'android/rust/aura_core/src/lib.rs'), 'utf8');
  const bridgeKt = fs.readFileSync(
    path.join(rootDir, 'android/core/rust/src/main/kotlin/com/auramusic/core/rust/AuraRustBridge.kt'),
    'utf8',
  );
  const manifestXml = fs.readFileSync(path.join(rootDir, 'android/app/src/main/AndroidManifest.xml'), 'utf8');
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const modelsTestKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/test/kotlin/com/auramusic/core/model/AuraModelsTest.kt'),
    'utf8',
  );
  const reposKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );
  const monitorKt = path.join(
    rootDir,
    'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraAudioOutputMonitor.kt',
  );
  const tuningProviderKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlaybackTuningProvider.kt'),
    'utf8',
  );
  const callbackKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraMediaSessionCallback.kt'),
    'utf8',
  );
  const forwardingKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraSessionForwardingPlayer.kt'),
    'utf8',
  );
  const controllerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlayerController.kt'),
    'utf8',
  );
  const effectsScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/effects/src/main/kotlin/com/auramusic/feature/effects/AudioEffectsScreen.kt'),
    'utf8',
  );
  const settingsScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/settings/src/main/kotlin/com/auramusic/feature/settings/SettingsScreen.kt'),
    'utf8',
  );
  const appKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraApplication.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const navGraphKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraNavGraph.kt'),
    'utf8',
  );

  // 1. Crossfeed binaural estático en Rust (sin fallbacks en Kotlin)
  assert.ok(fs.existsSync(crossfeedRs), 'Missing android/rust/aura_core/src/crossfeed.rs');
  const crossfeedCode = fs.readFileSync(crossfeedRs, 'utf8');
  assert.match(crossfeedCode, /struct StaticCrossfeedProcessor/);
  assert.match(crossfeedCode, /struct CrossfeedDelayLine/);
  assert.match(crossfeedCode, /lpf_l_to_r/);
  assert.match(reverbRs, /crossfeed:\s*StaticCrossfeedProcessor/);
  assert.match(reverbRs, /crossfeed_enabled:\s*bool/);
  assert.match(eqRs, /crossfeed_enabled:/);
  assert.match(rustLib, /mod crossfeed;/);
  assert.match(rustLib, /crossfeed_enabled:\s*jboolean/);
  assert.match(bridgeKt, /crossfeedEnabled:\s*Boolean/);

  // 2. Detección de salida con AudioDeviceCallback (minSdk 26, sin permisos Bluetooth extra)
  assert.ok(fs.existsSync(monitorKt), 'Missing AuraAudioOutputMonitor.kt');
  const monitorCode = fs.readFileSync(monitorKt, 'utf8');
  assert.match(monitorCode, /AudioDeviceCallback/);
  assert.match(monitorCode, /GET_DEVICES_OUTPUTS/);
  assert.doesNotMatch(manifestXml, /BLUETOOTH_CONNECT|BLUETOOTH_SCAN|BLUETOOTH_ADMIN/);

  // 3. Modelos puros y pruebas unitarias Kotlin
  assert.match(modelsKt, /enum class AudioOutputType/);
  assert.match(modelsKt, /data class AudioOutputRouteState/);
  assert.match(modelsKt, /data class HeadphonePreferences/);
  assert.match(modelsKt, /object HeadphoneRoutingHelper/);
  assert.match(modelsKt, /"headphone_crossfeed"/);
  assert.match(modelsTestKt, /HeadphoneRoutingHelper\.resolveEffectiveRustEffects/);
  assert.match(modelsTestKt, /HeadphoneRoutingHelper\.resolveMediaHookTapAction/);

  // 4. Persistencia DataStore
  assert.match(reposKt, /class HeadphonePreferencesRepository/);
  assert.match(reposKt, /fx_crossfeed_enabled/);
  assert.match(reposKt, /hp_seek_step_sec/);

  // 5. Servicio Media3, AVRCP y KEYCODE_HEADSETHOOK
  assert.match(tuningProviderKt, /fun observeHeadphonePreferences\(/);
  assert.match(tuningProviderKt, /fun observeAudioOutputRoute\(/);
  assert.match(callbackKt, /class AuraMediaSessionCallback/);
  assert.match(callbackKt, /KEYCODE_HEADSETHOOK/);
  assert.match(forwardingKt, /COMMAND_SEEK_FORWARD/);
  assert.match(forwardingKt, /COMMAND_SEEK_BACK/);
  assert.match(forwardingKt, /getSeekForwardIncrement/);
  assert.match(controllerKt, /fun updateHeadphoneControlTuning\(/);

  // 6. UI desacoplada en :feature:effects y :feature:settings
  assert.match(effectsScreenKt, /StaticCrossfeedCard/);
  assert.match(effectsScreenKt, /OutputRouteEffectsBanner/);
  assert.match(settingsScreenKt, /Auriculares y salida de audio/);
  assert.match(settingsScreenKt, /Botones multimedia \(Cable y Bluetooth AVRCP\)/);

  // 7. Conexión reactiva en :app
  assert.match(appKt, /audioOutputMonitor/);
  assert.match(appKt, /headphonePreferencesRepository/);
  assert.match(vmKt, /val audioOutputRoute:\s*StateFlow<AudioOutputRouteState>/);
  assert.match(vmKt, /fun setCrossfeedEnabled\(/);
  assert.match(vmKt, /fun setSeekStepSeconds\(/);
  assert.match(navGraphKt, /onSetCrossfeedEnabled\s*=\s*viewModel::setCrossfeedEnabled/);
  assert.match(navGraphKt, /onSetSeekStepSeconds\s*=\s*viewModel::setSeekStepSeconds/);
});

test('SettingsScreen acts as a clickable category hub navigating to dedicated sub-screens', () => {
  const settingsKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/settings/src/main/kotlin/com/auramusic/feature/settings/SettingsScreen.kt'),
    'utf8',
  );
  const routesKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraRoutes.kt'),
    'utf8',
  );
  const navGraphKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraNavGraph.kt'),
    'utf8',
  );

  // Hub de categorías en SettingsScreen
  assert.match(settingsKt, /onOpenAccentTheme:\s*\(\) -> Unit/);
  assert.match(settingsKt, /onOpenVisualEffects:\s*\(\) -> Unit/);
  assert.match(settingsKt, /onOpenHeadphoneSettings:\s*\(\) -> Unit/);
  assert.match(settingsKt, /onOpenMediaButtons:\s*\(\) -> Unit/);
  assert.match(settingsKt, /Colores dinámicos y animación al eliminar/);
  assert.match(settingsKt, /private fun SettingsCategoryCard\(/);

  // Subpantallas dedicadas con botón volver
  assert.match(settingsKt, /fun SettingsAccentThemeScreen\(/);
  assert.match(settingsKt, /fun SettingsVisualEffectsScreen\(/);
  assert.match(settingsKt, /fun SettingsHeadphonesScreen\(/);
  assert.match(settingsKt, /fun SettingsMediaButtonsScreen\(/);

  // Rutas de navegación en AuraRoutes / AuraNavGraph
  assert.match(routesKt, /SETTINGS_THEME\s*=\s*"settings\/theme"/);
  assert.match(routesKt, /SETTINGS_VISUAL_FX\s*=\s*"settings\/visual_fx"/);
  assert.match(routesKt, /SETTINGS_HEADPHONES\s*=\s*"settings\/headphones"/);
  assert.match(routesKt, /SETTINGS_MEDIA_BUTTONS\s*=\s*"settings\/media_buttons"/);
  assert.match(navGraphKt, /SettingsAccentThemeScreen\(/);
  assert.match(navGraphKt, /SettingsVisualEffectsScreen\(/);
  assert.match(navGraphKt, /SettingsHeadphonesScreen\(/);
  assert.match(navGraphKt, /SettingsMediaButtonsScreen\(/);
});

test('vocal speed/pitch control complements playback speed with DataStore persistence and PlayerScreen UI', () => {
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const modelsTestKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/test/kotlin/com/auramusic/core/model/AuraModelsTest.kt'),
    'utf8',
  );
  const reposKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );
  const controllerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlayerController.kt'),
    'utf8',
  );
  const playerKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/player/src/main/kotlin/com/auramusic/feature/player/PlayerScreen.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const playbackCoordKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraPlaybackCoordinator.kt'),
    'utf8',
  );
  const navGraphKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraNavGraph.kt'),
    'utf8',
  );

  assert.match(modelsKt, /val playbackPitch:\s*Float/);
  assert.match(modelsKt, /val linkPitchToSpeed:\s*Boolean/);
  assert.match(modelsKt, /val PITCH_PRESETS\s*=/);
  assert.match(modelsKt, /fun describeVoicePitch\(/);
  assert.match(modelsTestKt, /playback tuning helper resolves independent and linked vocal pitch accurately/);

  assert.match(reposKt, /playback_pitch/);
  assert.match(reposKt, /playback_link_pitch_speed/);
  assert.match(reposKt, /suspend fun setPlaybackPitch\(/);
  assert.match(reposKt, /suspend fun setLinkPitchToSpeed\(/);

  assert.match(controllerKt, /playbackPitch\s*=/);
  assert.match(playerKt, /Velocidad \/ Tono de voz/);
  assert.match(playerKt, /Vincular voz con velocidad/);
  assert.match(playerKt, /onSetPlaybackPitch/);
  assert.match(playerKt, /onSetLinkPitchToSpeed/);
  assert.match(playerKt, /onResetSpeedAndPitch/);

  assert.match(playbackCoordKt, /fun setPlaybackPitch\(/);
  assert.match(playbackCoordKt, /fun setLinkPitchToSpeed\(/);
  assert.match(playbackCoordKt, /fun resetPlaybackSpeedAndPitch\(/);
  assert.match(vmKt, /fun setPlaybackPitch\(/);
  assert.match(navGraphKt, /onSetPlaybackPitch\s*=\s*viewModel::setPlaybackPitch/);
});

test('sleep timer supports presets, custom minutes, end-of-track, end-of-queue, and background service execution', () => {
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const modelsTestKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/test/kotlin/com/auramusic/core/model/AuraModelsTest.kt'),
    'utf8',
  );
  const bridgeKtPath = path.join(
    rootDir,
    'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraSleepTimerBridge.kt',
  );
  const crossfadeEngineKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraCrossfadeEngine.kt'),
    'utf8',
  );
  const sleepTimerEngineKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraSleepTimerEngine.kt'),
    'utf8',
  );
  const callbackKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraMediaSessionCallback.kt'),
    'utf8',
  );
  const controllerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlayerController.kt'),
    'utf8',
  );
  const sleepSheetKtPath = path.join(
    rootDir,
    'android/feature/player/src/main/kotlin/com/auramusic/feature/player/SleepTimerBottomSheet.kt',
  );
  const playerKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/player/src/main/kotlin/com/auramusic/feature/player/PlayerScreen.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const navGraphKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraNavGraph.kt'),
    'utf8',
  );

  assert.match(modelsKt, /enum class SleepTimerMode/);
  assert.match(modelsKt, /END_OF_TRACK/);
  assert.match(modelsKt, /END_OF_QUEUE/);
  assert.match(modelsKt, /data class SleepTimerState/);
  assert.match(modelsKt, /object SleepTimerHelper/);
  assert.match(modelsTestKt, /sleep timer helper validates custom minutes/);

  assert.ok(fs.existsSync(bridgeKtPath), 'Missing AuraSleepTimerBridge.kt');
  assert.match(callbackKt, /AuraSleepTimerContract/);
  assert.match(crossfadeEngineKt, /shouldBypassCrossfadeForSleepTimer/);
  assert.match(sleepTimerEngineKt, /shouldPauseOnAutoTransition/);
  assert.match(callbackKt, /override fun onCustomCommand/);

  assert.match(controllerKt, /val sleepTimerState:\s*StateFlow<SleepTimerState>/);
  assert.match(controllerKt, /fun startSleepTimerMinutes\(/);
  assert.match(controllerKt, /fun setSleepTimerEndOfTrack\(/);
  assert.match(controllerKt, /fun setSleepTimerEndOfQueue\(/);

  assert.ok(fs.existsSync(sleepSheetKtPath), 'Missing SleepTimerBottomSheet.kt');
  const sleepSheetKt = fs.readFileSync(sleepSheetKtPath, 'utf8');
  assert.match(sleepSheetKt, /Duración preestablecida/);
  assert.match(sleepSheetKt, /Tiempo personalizado/);
  assert.match(sleepSheetKt, /Al terminar esta canción/);
  assert.match(sleepSheetKt, /Al terminar toda la música/);

  assert.match(playerKt, /SleepTimerBottomSheet\(/);
  assert.match(playerKt, /Icons\.Rounded\.Timer/);
  assert.match(vmKt, /val sleepTimerState:\s*StateFlow<SleepTimerState>/);
  assert.match(navGraphKt, /onStartSleepTimerMinutes\s*=\s*viewModel::startSleepTimerMinutes/);
});

test(':app and :core:player layers are cleanly modularized into dedicated coordinators, nav graph, and service engines', () => {
  const expectedFiles = [
    'android/app/src/main/kotlin/com/auramusic/app/AuraPlaybackCoordinator.kt',
    'android/app/src/main/kotlin/com/auramusic/app/AuraAudioTuningCoordinator.kt',
    'android/app/src/main/kotlin/com/auramusic/app/AuraLibraryCoordinator.kt',
    'android/app/src/main/kotlin/com/auramusic/app/AuraAppChrome.kt',
    'android/app/src/main/kotlin/com/auramusic/app/AuraRoutes.kt',
    'android/app/src/main/kotlin/com/auramusic/app/AuraNavGraph.kt',
    'android/app/src/main/kotlin/com/auramusic/app/AuraAppDialogs.kt',
    'android/app/src/main/kotlin/com/auramusic/app/AuraStartupScreen.kt',
    'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlaybackTuningProvider.kt',
    'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraCrossfadeEngine.kt',
    'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraSleepTimerEngine.kt',
    'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraSessionForwardingPlayer.kt',
    'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraMediaSessionCallback.kt',
  ];
  for (const rel of expectedFiles) {
    assert.ok(fs.existsSync(path.join(rootDir, rel)), `Missing modular file: ${rel}`);
  }
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const rootKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraAppRoot.kt'),
    'utf8',
  );
  const serviceKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlaybackService.kt'),
    'utf8',
  );
  assert.match(vmKt, /AuraPlaybackCoordinator/);
  assert.match(vmKt, /AuraAudioTuningCoordinator/);
  assert.match(vmKt, /AuraLibraryCoordinator/);
  assert.match(rootKt, /rememberAuraSafLaunchers/);
  assert.match(rootKt, /AuraBottomChrome\(/);
  assert.match(rootKt, /auraLibraryAndPlaylistsGraph\(/);
  assert.match(rootKt, /AuraTrackModalHost\(/);
  assert.match(serviceKt, /AuraCrossfadeEngine\(/);
  assert.match(serviceKt, /AuraSleepTimerEngine\(/);
  assert.match(serviceKt, /AuraMediaSessionCallback\(/);
});

test('RAM optimization: isolates :player and :downloader processes, bounds ExoPlayer/Coil caches, and trims memory in background', () => {
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const modelsTestKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/test/kotlin/com/auramusic/core/model/AuraModelsTest.kt'),
    'utf8',
  );
  const playerManifest = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/AndroidManifest.xml'),
    'utf8',
  );
  const downloaderManifest = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/AndroidManifest.xml'),
    'utf8',
  );
  const playerIpcKt = path.join(
    rootDir,
    'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlayerIpc.kt',
  );
  const downloaderIpcKt = path.join(
    rootDir,
    'android/core/downloader/src/main/kotlin/com/auramusic/core/downloader/AuraDownloaderIpc.kt',
  );
  const downloaderClientKt = path.join(
    rootDir,
    'android/core/downloader/src/main/kotlin/com/auramusic/core/downloader/AuraDownloaderRemoteClient.kt',
  );
  const playbackServiceKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlaybackService.kt'),
    'utf8',
  );
  const playerControllerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlayerController.kt'),
    'utf8',
  );
  const downloaderServiceKt = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/kotlin/com/auramusic/core/downloader/AuraDownloadForegroundService.kt'),
    'utf8',
  );
  const appKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraApplication.kt'),
    'utf8',
  );
  const libCoordKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraLibraryCoordinator.kt'),
    'utf8',
  );
  const mainKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/MainActivity.kt'),
    'utf8',
  );
  const coverThemeKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/player/src/main/kotlin/com/auramusic/feature/player/CoverDynamicTheme.kt'),
    'utf8',
  );
  const libraryScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/library/src/main/kotlin/com/auramusic/feature/library/LibraryScreen.kt'),
    'utf8',
  );
  const playlistsScreenKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/playlists/src/main/kotlin/com/auramusic/feature/playlists/PlaylistsScreen.kt'),
    'utf8',
  );
  const artistDetailKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/playlists/src/main/kotlin/com/auramusic/feature/playlists/ArtistDetailScreen.kt'),
    'utf8',
  );

  // Modelos puros y pruebas unitarias
  assert.match(modelsKt, /enum class AuraProcessRole/);
  assert.match(modelsKt, /object AuraProcessRoleResolver/);
  assert.match(modelsKt, /object MemoryTrimPolicy/);
  assert.match(modelsTestKt, /process role resolver/);
  assert.match(modelsTestKt, /memory trim policy/);

  // Aislamiento de procesos en manifests
  assert.match(playerManifest, /android:process=":player"/);
  assert.match(downloaderManifest, /android:process=":downloader"/);

  // Archivos IPC nuevos
  assert.ok(fs.existsSync(playerIpcKt), 'Missing AuraPlayerIpc.kt');
  assert.ok(fs.existsSync(downloaderIpcKt), 'Missing AuraDownloaderIpc.kt');
  assert.ok(fs.existsSync(downloaderClientKt), 'Missing AuraDownloaderRemoteClient.kt');

  // Reproductor ligero y sincronización Binder
  assert.match(playbackServiceKt, /DefaultLoadControl\.Builder/);
  assert.match(playbackServiceKt, /releaseOutgoingIfIdle/);
  assert.match(playbackServiceKt, /setSessionExtras\(/);
  assert.match(playerControllerKt, /MediaController\.Listener/);
  assert.match(playerControllerKt, /onExtrasChanged/);
  assert.match(playerControllerKt, /fun syncEqualizer\(/);
  assert.match(playerControllerKt, /fun trimMemoryIfDetached\(/);

  // Descargador aislado y liberación del proceso Python tras inactividad
  assert.match(downloaderServiceKt, /Process\.killProcess\(/);
  assert.match(downloaderServiceKt, /MSG_INSPECT_URL/);
  assert.doesNotMatch(appKt, /val ytDlpAudioDownloader/);
  assert.match(appKt, /remoteWorkerMode\s*=\s*true/);
  assert.match(libCoordKt, /AuraDownloaderRemoteClient/);
  assert.match(libCoordKt, /WhileSubscribed\(5_000L\)/);

  // Coil acotado, RGB_565, clearCoverColorCache y ciclo onStart/onStop/onTrimMemory
  assert.match(appKt, /SingletonImageLoader\.Factory/);
  assert.match(appKt, /allowRgb565\(true\)/);
  assert.match(appKt, /override fun onTrimMemory\(/);
  assert.match(appKt, /clearCoverColorCache\(\)/);
  assert.match(coverThemeKt, /fun clearCoverColorCache\(/);
  assert.match(mainKt, /viewModel\.onUiForeground\(\)/);
  assert.match(mainKt, /viewModel\.onUiBackground\(\)/);
  assert.match(libraryScreenKt, /\.size\(168\)/);
  assert.match(playlistsScreenKt, /\.size\(168\)/);
  assert.match(artistDetailKt, /\.size\(168\)/);
});

test('optional system folder (Aura Music) is disabled by default, migrates tracks/covers/json, and auto-discovers dropped audio', () => {
  const modelsKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/main/kotlin/com/auramusic/core/model/AuraModels.kt'),
    'utf8',
  );
  const modelsTestKt = fs.readFileSync(
    path.join(rootDir, 'android/core/model/src/test/kotlin/com/auramusic/core/model/AuraModelsTest.kt'),
    'utf8',
  );
  const manifestXml = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/AndroidManifest.xml'),
    'utf8',
  );
  const dbKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraDatabase.kt'),
    'utf8',
  );
  const reposKt = fs.readFileSync(
    path.join(rootDir, 'android/core/database/src/main/kotlin/com/auramusic/core/database/AuraRepositories.kt'),
    'utf8',
  );
  const systemManagerPath = path.join(
    rootDir,
    'android/core/importer/src/main/kotlin/com/auramusic/core/importer/AuraSystemStorageManager.kt',
  );
  const importerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/importer/src/main/kotlin/com/auramusic/core/importer/AudioImporter.kt'),
    'utf8',
  );
  const settingsKt = fs.readFileSync(
    path.join(rootDir, 'android/feature/settings/src/main/kotlin/com/auramusic/feature/settings/SettingsScreen.kt'),
    'utf8',
  );
  const appKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraApplication.kt'),
    'utf8',
  );
  const libCoordKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraLibraryCoordinator.kt'),
    'utf8',
  );
  const vmKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraViewModel.kt'),
    'utf8',
  );
  const chromeKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraAppChrome.kt'),
    'utf8',
  );
  const routesKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraRoutes.kt'),
    'utf8',
  );
  const navGraphKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraNavGraph.kt'),
    'utf8',
  );

  // 1. Modelos y pruebas unitarias en :core:model (desactivada por defecto)
  assert.match(modelsKt, /data class SystemFolderStorageState/);
  assert.match(modelsKt, /val isEnabled:\s*Boolean\s*=\s*false/);
  assert.match(modelsKt, /object SystemFolderHelper/);
  assert.match(modelsKt, /ROOT_FOLDER_NAME\s*=\s*"Aura Music"/);
  assert.match(modelsKt, /MUSIC_SUBFOLDER_NAME\s*=\s*"Música"/);
  assert.match(modelsKt, /COVERS_SUBFOLDER_NAME\s*=\s*"Carátulas"/);
  assert.match(modelsKt, /LIBRARY_JSON_FILE_NAME\s*=\s*"aura_biblioteca\.json"/);
  assert.match(modelsTestKt, /system folder helper sanitizes filenames and disables system folder by default/);

  // 2. Permisos en AndroidManifest (Android 8-15)
  assert.match(manifestXml, /android\.permission\.MANAGE_EXTERNAL_STORAGE/);
  assert.match(manifestXml, /android\.permission\.READ_EXTERNAL_STORAGE/);
  assert.match(manifestXml, /android\.permission\.WRITE_EXTERNAL_STORAGE/);
  assert.match(manifestXml, /android:requestLegacyExternalStorage="true"/);

  // 3. Persistencia Room + DataStore (default = false)
  assert.match(dbKt, /suspend fun updateStorageLocation\(/);
  assert.match(dbKt, /suspend fun getAllTracksOnce\(/);
  assert.match(reposKt, /class StoragePreferencesRepository/);
  assert.match(reposKt, /booleanPreferencesKey\("system_folder_enabled"\)/);
  assert.match(reposKt, /prefs\[systemFolderEnabledKey\]\s*\?:\s*false/);
  assert.match(reposKt, /suspend fun updateTrackStorageLocation\(/);
  assert.match(reposKt, /"Carátulas"/);

  // 4. Manager e importador en :core:importer
  assert.ok(fs.existsSync(systemManagerPath), 'Missing AuraSystemStorageManager.kt');
  const systemManagerKt = fs.readFileSync(systemManagerPath, 'utf8');
  assert.match(systemManagerKt, /suspend fun enableAndMigrate\(/);
  assert.match(systemManagerKt, /suspend fun syncOnAppEntryOrManual\(/);
  assert.match(systemManagerKt, /suspend fun syncJsonSnapshotsIfEnabled\(/);
  assert.match(systemManagerKt, /\.nomedia/);
  assert.match(systemManagerKt, /extractTrackFromLocalFile/);
  assert.match(systemManagerKt, /PLAYER_CONFIG_JSON_FILE_NAME/);
  assert.match(systemManagerKt, /ENGINE_VERSIONS_JSON_FILE_NAME/);
  assert.doesNotMatch(systemManagerKt, /PLAYER_CONFIG_JSON_NAME|ENGINE_VERSIONS_JSON_NAME/);
  assert.match(importerKt, /suspend fun extractTrackFromLocalFile\(/);
  assert.match(importerKt, /AuraSystemStorageManager\.getMusicDir\(\)/);
  assert.match(importerKt, /AuraSystemStorageManager\.getCoversDir\(\)/);

  // 5. Subpantalla dedicada y categoría en Ajustes
  assert.match(settingsKt, /Carpeta del sistema \(Aura Music\)/);
  assert.match(settingsKt, /onOpenSystemFolder:\s*\(\) -> Unit/);
  assert.match(settingsKt, /fun SettingsSystemFolderScreen\(/);
  assert.match(settingsKt, /PLAYER_CONFIG_JSON_FILE_NAME/);
  assert.doesNotMatch(settingsKt, /PLAYER_CONFIG_JSON_NAME/);
  assert.match(settingsKt, /Crear y usar carpeta Aura Music/);

  // 6. Conexión completa en :app (onStart/arranque, launcher de permisos y navegación)
  assert.match(appKt, /val storagePreferencesRepository:\s*StoragePreferencesRepository/);
  assert.match(appKt, /val systemStorageManager:\s*AuraSystemStorageManager/);
  assert.match(libCoordKt, /fun refreshSystemFolderAndAutoSync\(/);
  assert.match(libCoordKt, /fun setSystemFolderEnabled\(/);
  assert.match(vmKt, /val systemFolderState:\s*StateFlow<SystemFolderStorageState>/);
  assert.match(vmKt, /libraryCoordinator\.refreshSystemFolderAndAutoSync\(\)/);
  assert.match(chromeKt, /fun rememberAuraSystemStoragePermissionLauncher\(/);
  assert.match(routesKt, /SETTINGS_SYSTEM_FOLDER\s*=\s*"settings\/system_folder"/);
  assert.match(navGraphKt, /SettingsSystemFolderScreen\(/);
});

test('cross-module Kotlin API contracts stay aligned across player, downloader, app, and importer', () => {
  const callbackKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraMediaSessionCallback.kt'),
    'utf8',
  );
  const playbackServiceKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/AuraPlaybackService.kt'),
    'utf8',
  );
  const downloaderIpcKt = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/kotlin/com/auramusic/core/downloader/AuraDownloaderIpc.kt'),
    'utf8',
  );
  const ytDlpDownloaderKt = fs.readFileSync(
    path.join(rootDir, 'android/core/downloader/src/main/kotlin/com/auramusic/core/downloader/YtDlpAudioDownloader.kt'),
    'utf8',
  );
  const appKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraApplication.kt'),
    'utf8',
  );
  const systemManagerKt = fs.readFileSync(
    path.join(rootDir, 'android/core/importer/src/main/kotlin/com/auramusic/core/importer/AuraSystemStorageManager.kt'),
    'utf8',
  );

  // 1. AuraRustBridge.setEqualizerConfig recibe List<Float>, no FloatArray
  assert.match(playbackServiceKt, /AuraRustBridge\.setEqualizerConfig\(eq\.isEnabled,\s*eq\.bandGainsDb\)/);
  assert.doesNotMatch(playbackServiceKt, /setEqualizerConfig\([^)]*\.toFloatArray\(\)/);
  assert.match(callbackKt, /ACTION_SYNC_EQUALIZER/);

  // 2. Track usa addedAt (no addedAtEpochMs) como propiedad y parámetro nombrado
  assert.match(downloaderIpcKt, /track\.addedAt/);
  assert.match(downloaderIpcKt, /addedAt\s*=/);
  assert.doesNotMatch(downloaderIpcKt, /track\.addedAtEpochMs|addedAtEpochMs\s*=/);

  // 3. AudioDownloadQuality usa MAX / BALANCED / DATA_SAVER (sin BALANCED_128)
  assert.match(ytDlpDownloaderKt, /AudioDownloadQuality\.MAX/);
  assert.doesNotMatch(ytDlpDownloaderKt, /BALANCED_128/);

  // 4. MemoryTrimAction expone flags booleanos en vez de constantes enum
  assert.match(appKt, /action\.clearImageMemoryCache/);
  assert.match(appKt, /action\.clearDynamicColorCache/);
  assert.match(appKt, /action\.clearTransientControllerCaches/);
  assert.doesNotMatch(appKt, /MemoryTrimAction\.(NONE|TRIM_HALF|CLEAR_MEMORY_CACHE_AND_BITMAPS)/);

  // 5. AuraSystemStorageManager busca engine_versions.json dentro de py_updates primero
  assert.match(systemManagerKt, /File\(context\.filesDir,\s*"py_updates"\)/);

  // 6. Media3 1.11.1 usa las APIs vigentes sin deprecaciones en :core:player
  const eqProcessorKt = fs.readFileSync(
    path.join(rootDir, 'android/core/player/src/main/kotlin/com/auramusic/core/player/RustEqualizerAudioProcessor.kt'),
    'utf8',
  );
  assert.match(eqProcessorKt, /override fun onFlush\(streamMetadata:\s*StreamMetadata\)/);
  assert.doesNotMatch(eqProcessorKt, /override fun onFlush\(\)/);
  assert.match(callbackKt, /AcceptedResultBuilder\(session,\s*controller\)/);
  assert.match(playbackServiceKt, /setEnableAudioOutputPlaybackParameters\(enableAudioOutputPlaybackParameters\)/);
  assert.doesNotMatch(playbackServiceKt, /setEnableAudioTrackPlaybackParams\(/);
});

test('GitHub lint-and-audit workflow covers Kotlin, Rust, Python, outdated deps, and interactive HTML zip report', () => {
  const workflowPath = path.join(rootDir, '.github/workflows/lint-and-audit.yml');
  const depsScriptPath = path.join(rootDir, '.github/scripts/check_outdated_deps.py');
  const reportScriptPath = path.join(rootDir, '.github/scripts/generate_audit_report.py');

  assert.ok(fs.existsSync(workflowPath), 'Missing .github/workflows/lint-and-audit.yml');
  assert.ok(fs.existsSync(depsScriptPath), 'Missing .github/scripts/check_outdated_deps.py');
  assert.ok(fs.existsSync(reportScriptPath), 'Missing .github/scripts/generate_audit_report.py');

  const workflow = fs.readFileSync(workflowPath, 'utf8');
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /--continue/);
  assert.match(workflow, /lintDebug/);
  assert.match(workflow, /ktlint/);
  assert.match(workflow, /cargo clippy/);
  assert.match(workflow, /ruff check/);
  assert.match(workflow, /mypy/);
  assert.match(workflow, /check_outdated_deps\.py/);
  assert.match(workflow, /generate_audit_report\.py/);
  assert.match(workflow, /AuraMusic-Lint-Audit-Bundle\.zip/);
});



