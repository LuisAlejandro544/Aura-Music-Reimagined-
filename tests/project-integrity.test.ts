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

  assert.ok(includes.length >= 10, 'Expected full multi-module Gradle structure');

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

