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

  assert.ok(includes.length >= 11, 'Expected full multi-module Gradle structure including :feature:equalizer');
  assert.ok(includes.includes(':feature:equalizer'), 'Expected dedicated :feature:equalizer module');

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

test('GitHub Action apk Debug is manual-only, zero-cache, and uses Gradle 8.14.7', () => {
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
  assert.match(workflow, /8\.14\.7/);
  assert.match(workflow, /android-actions\/setup-android@v4/);
  assert.match(workflow, /platforms;android-35/);
  assert.doesNotMatch(workflow, /^\s*tools$/m);
  assert.match(wrapperProps, /gradle-8\.14\.7-bin\.zip/);
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
  const rootKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraAppRoot.kt'),
    'utf8',
  );
  const themeKt = fs.readFileSync(
    path.join(rootDir, 'android/app/src/main/kotlin/com/auramusic/app/AuraTheme.kt'),
    'utf8',
  );

  assert.match(vmKt, /val isAppReady: StateFlow<Boolean>/);
  assert.match(mainKt, /AuraStartupLoadingScreen/);
  assert.match(rootKt, /fun AuraStartupLoadingScreen\(\)/);
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
  assert.match(catalog, /material-kolor-material3/);
  assert.match(catalog, /material-kolor-palette/);
  assert.match(playerGradle, /libs\.androidx\.palette/);
  assert.match(playerGradle, /libs\.material\.kolor\.material3/);
  assert.match(playerGradle, /libs\.material\.kolor\.palette/);

  assert.match(coverThemeKt, /androidx\.palette\.graphics\.Palette/);
  assert.match(coverThemeKt, /com\.materialkolor\.material3\.rememberDynamicColorScheme/);
  assert.match(coverThemeKt, /com\.materialkolor\.palette\.rememberPaletteState/);
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

