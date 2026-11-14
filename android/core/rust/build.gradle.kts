// Módulo puente JNI entre Kotlin y el motor nativo Rust (`aura_core`).
// IMPORTANTE: La compilación de Gradle ejecuta `cargo ndk` obligatoriamente antes de ensamblar
// las librerías nativas, compilando binarios .so para arquitecturas de 32 y 64 bits
// (armeabi-v7a, arm64-v8a y x86_64) sin omitir Rust ni usar fallbacks en Kotlin.

import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val rustCrateDir = rootProject.file("rust/aura_core")
val jniLibsOutputDir = layout.projectDirectory.dir("src/main/jniLibs")

val buildRustAndroidLibs by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compila aura_core (libaura_core.so) y aura_qjs (libaura_qjs.so) en Rust para 32 y 64 bits usando cargo-ndk."
    workingDir = rustCrateDir

    doFirst {
        // Detecta el NDK en tiempo de ejecución desde ANDROID_NDK_HOME, ANDROID_HOME o local.properties
        // para evitar errores durante la fase de configuración de Gradle.
        val envNdk = System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }
        val sdkPath = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
            ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
            ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { propsFile ->
                Properties().apply { propsFile.inputStream().use(::load) }.getProperty("sdk.dir")
            }

        val resolvedNdk = envNdk ?: sdkPath?.let { sdk ->
            File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.absolutePath
        }

        if (resolvedNdk != null) {
            environment("ANDROID_NDK_HOME", resolvedNdk)
            val prebuiltDir = File(resolvedNdk, "toolchains/llvm/prebuilt")
            val hostDir = prebuiltDir.listFiles()?.firstOrNull { it.isDirectory }
            if (hostDir != null) {
                val sysroot = File(hostDir, "sysroot").absolutePath
                val ndkLibDir = listOf(File(hostDir, "lib64"), File(hostDir, "lib"))
                    .firstOrNull { it.exists() }
                if (ndkLibDir != null && System.getenv("LIBCLANG_PATH").isNullOrBlank()) {
                    environment("LIBCLANG_PATH", ndkLibDir.absolutePath)
                }
                environment(
                    "BINDGEN_EXTRA_CLANG_ARGS_armv7_linux_androideabi",
                    "--target=armv7a-linux-androideabi26 --sysroot=$sysroot",
                )
                environment(
                    "BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android",
                    "--target=aarch64-linux-android26 --sysroot=$sysroot",
                )
                environment(
                    "BINDGEN_EXTRA_CLANG_ARGS_x86_64_linux_android",
                    "--target=x86_64-linux-android26 --sysroot=$sysroot",
                )
            }
        }
    }

    commandLine(
        "cargo",
        "ndk",
        "-t", "armeabi-v7a",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "--platform", "26",
        "-o", jniLibsOutputDir.asFile.absolutePath,
        "build",
        "--release",
        "--lib",
        "--bin",
        "aura_qjs",
    )

    doLast {
        // Copia el binario ejecutable `aura_qjs` como `libaura_qjs.so` dentro de cada ABI en jniLibs
        // para que Android lo empaquete y extraiga en `nativeLibraryDir` cumpliendo SELinux W^X.
        val abiToTriple = mapOf(
            "armeabi-v7a" to "armv7-linux-androideabi",
            "arm64-v8a" to "aarch64-linux-android",
            "x86_64" to "x86_64-linux-android",
        )
        for ((abi, triple) in abiToTriple) {
            val srcBin = File(rustCrateDir, "target/$triple/release/aura_qjs")
            if (!srcBin.exists()) {
                error("Falta el binario compilado aura_qjs para $abi en ${srcBin.absolutePath}")
            }
            val dstDir = File(jniLibsOutputDir.asFile, abi).apply { mkdirs() }
            val dstFile = File(dstDir, "libaura_qjs.so")
            srcBin.copyTo(dstFile, overwrite = true)
            dstFile.setExecutable(true, false)
        }
    }

    inputs.dir(File(rustCrateDir, "src"))
    inputs.file(File(rustCrateDir, "Cargo.toml"))
    inputs.file(File(rustCrateDir, ".cargo/config.toml"))
    outputs.dir(jniLibsOutputDir)
}

android {
    namespace = "com.auramusic.core.rust"
    compileSdk = 35

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

    kotlinOptions {
        jvmTarget = "17"
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
