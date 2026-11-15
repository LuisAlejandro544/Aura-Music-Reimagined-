// Módulo puente JNI entre Kotlin y el motor nativo Rust (`aura_core`).
// IMPORTANTE: La compilación de Gradle ejecuta `cargo ndk` obligatoriamente antes de ensamblar
// las librerías nativas, compilando binarios .so para arquitecturas de 32 y 64 bits
// (armeabi-v7a, arm64-v8a y x86_64) sin omitir Rust ni usar fallbacks en Kotlin.

import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

val rustCrateDir = rootProject.file("rust/aura_core")
val qjsStagingDir = layout.buildDirectory.dir("rust-qjs-jniLibs")
val qjsNdkTempOutDir = layout.buildDirectory.dir("rust-qjs-ndk-out")
val jniLibsOutputDir = layout.projectDirectory.dir("src/main/jniLibs")

val targetAbis: List<String> = (project.findProperty("auraAbis") as? String)
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: listOf("armeabi-v7a", "arm64-v8a", "x86_64")

val abiToRustTriple = mapOf(
    "armeabi-v7a" to "armv7-linux-androideabi",
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)

fun configureRustNdkEnvironment(execTask: Exec, localPropertiesFile: File) {
    // Detecta el NDK en tiempo de ejecución desde ANDROID_NDK_HOME, ANDROID_HOME o local.properties
    // para evitar errores durante la fase de configuración de Gradle.
    val envNdk = System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }
    val sdkPath = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
        ?: localPropertiesFile.takeIf { it.exists() }?.let { propsFile ->
            Properties().apply { propsFile.inputStream().use(::load) }.getProperty("sdk.dir")
        }

    val resolvedNdk = envNdk ?: sdkPath?.let { sdk ->
        File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.absolutePath
    }

    if (resolvedNdk != null) {
        execTask.environment("ANDROID_NDK_HOME", resolvedNdk)
        val prebuiltDir = File(resolvedNdk, "toolchains/llvm/prebuilt")
        val hostDir = prebuiltDir.listFiles()?.firstOrNull { it.isDirectory }
        if (hostDir != null) {
            val sysroot = File(hostDir, "sysroot").absolutePath
            fun hasLibClang(dir: File): Boolean =
                dir.isDirectory && (dir.listFiles()?.any { f ->
                    f.name.startsWith("libclang") &&
                        (f.name.contains(".so") || f.name.endsWith(".dylib") || f.name.endsWith(".dll"))
                } == true)

            val existingLibClang = System.getenv("LIBCLANG_PATH")?.takeIf { it.isNotBlank() }
            if (existingLibClang == null) {
                val systemLlvmDirs = File("/usr/lib").listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("llvm-") }
                    ?.sortedByDescending { it.name }
                    ?.map { File(it, "lib") }
                    .orEmpty()
                val candidates = listOf(
                    File(hostDir, "lib64"),
                    File(hostDir, "lib"),
                ) + systemLlvmDirs + listOf(
                    File("/usr/lib/x86_64-linux-gnu"),
                    File("/usr/lib/aarch64-linux-gnu"),
                    File("/usr/lib64"),
                    File("/usr/lib"),
                )
                val resolvedLibClang = candidates.firstOrNull(::hasLibClang)
                if (resolvedLibClang != null) {
                    execTask.environment("LIBCLANG_PATH", resolvedLibClang.absolutePath)
                }
            }
            execTask.environment(
                "BINDGEN_EXTRA_CLANG_ARGS_armv7_linux_androideabi",
                "--target=armv7a-linux-androideabi26 --sysroot=$sysroot",
            )
            execTask.environment(
                "BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android",
                "--target=aarch64-linux-android26 --sysroot=$sysroot",
            )
            execTask.environment(
                "BINDGEN_EXTRA_CLANG_ARGS_x86_64_linux_android",
                "--target=x86_64-linux-android26 --sysroot=$sysroot",
            )
        }
    }
}

val localPropertiesFile = rootProject.file("local.properties")

// Compila únicamente el binario `aura_qjs` con la feature `js-runtime` (QuickJS-NG)
// en una etapa aislada para evitar duplicar QuickJS-NG dentro de `libaura_core.so`.
val buildRustQuickJsBin by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compila el binario QuickJS-NG (aura_qjs -> libaura_qjs.so) con feature js-runtime."
    workingDir = rustCrateDir

    doFirst {
        val stagingRoot = qjsStagingDir.get().asFile
        stagingRoot.deleteRecursively()
        stagingRoot.mkdirs()
        qjsNdkTempOutDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        configureRustNdkEnvironment(this as Exec, localPropertiesFile)
    }

    val ndkCommand = buildList {
        add("cargo")
        add("ndk")
        for (abi in targetAbis) {
            add("-t")
            add(abi)
        }
        add("--platform")
        add("26")
        add("-o")
        add(qjsNdkTempOutDir.get().asFile.absolutePath)
        add("build")
        add("--release")
        add("--features")
        add("js-runtime")
        add("--bin")
        add("aura_qjs")
    }
    commandLine(ndkCommand)

    doLast {
        val stagingRoot = qjsStagingDir.get().asFile
        for (abi in targetAbis) {
            val triple = abiToRustTriple[abi] ?: error("ABI Rust no soportada: $abi")
            val srcBin = File(rustCrateDir, "target/$triple/release/aura_qjs")
            if (!srcBin.exists()) {
                error("Falta el binario compilado aura_qjs para $abi en ${srcBin.absolutePath}")
            }
            val dstDir = File(stagingRoot, abi).apply { mkdirs() }
            val dstFile = File(dstDir, "libaura_qjs.so")
            srcBin.copyTo(dstFile, overwrite = true)
            dstFile.setExecutable(true, false)
        }
    }

    inputs.property("targetAbis", targetAbis.joinToString(","))
    inputs.dir(File(rustCrateDir, "src"))
    inputs.file(File(rustCrateDir, "Cargo.toml"))
    inputs.file(File(rustCrateDir, ".cargo/config.toml"))
    outputs.dir(qjsStagingDir)
}

val buildRustAndroidLibs by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compila libaura_core.so sin QuickJS-NG (--no-default-features) y empaqueta libaura_qjs.so."
    dependsOn(buildRustQuickJsBin)
    workingDir = rustCrateDir

    doFirst {
        // Limpia salidas nativas previas para evitar mezclar ABIs entre compilaciones individuales secuenciales.
        jniLibsOutputDir.asFile.deleteRecursively()
        jniLibsOutputDir.asFile.mkdirs()
        configureRustNdkEnvironment(this as Exec, localPropertiesFile)
    }

    val ndkCommand = buildList {
        add("cargo")
        add("ndk")
        for (abi in targetAbis) {
            add("-t")
            add(abi)
        }
        add("--platform")
        add("26")
        add("-o")
        add(jniLibsOutputDir.asFile.absolutePath)
        add("build")
        add("--release")
        add("--no-default-features")
        add("--lib")
    }
    commandLine(ndkCommand)

    doLast {
        // Copia el binario ejecutable `libaura_qjs.so` preparado por `buildRustQuickJsBin` dentro de jniLibs
        // para que Android lo extraiga en `nativeLibraryDir` cumpliendo SELinux W^X.
        val stagingRoot = qjsStagingDir.get().asFile
        for (abi in targetAbis) {
            val stagedQjs = File(stagingRoot, "$abi/libaura_qjs.so")
            if (!stagedQjs.exists()) {
                error("Falta el binario preparado libaura_qjs.so para $abi en ${stagedQjs.absolutePath}")
            }
            val dstDir = File(jniLibsOutputDir.asFile, abi).apply { mkdirs() }
            val dstFile = File(dstDir, "libaura_qjs.so")
            stagedQjs.copyTo(dstFile, overwrite = true)
            dstFile.setExecutable(true, false)
        }
    }

    inputs.property("targetAbis", targetAbis.joinToString(","))
    inputs.dir(File(rustCrateDir, "src"))
    inputs.file(File(rustCrateDir, "Cargo.toml"))
    inputs.file(File(rustCrateDir, ".cargo/config.toml"))
    inputs.dir(qjsStagingDir)
    outputs.dir(jniLibsOutputDir)
}

android {
    namespace = "com.auramusic.core.rust"
    compileSdk = 37

    // minSdk 26 (Android 8.0): habilita canales de notificación nativos para Media3,
    // iconos adaptativos, java.time sin desugaring pesado y compatibilidad moderna con NDK/Rust.
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(jniLibsOutputDir)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") {
    dependsOn(buildRustAndroidLibs)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
