// Módulo de descarga de audio con Python 3.11 ligero (Chaquopy MIT), yt-dlp, yt-dlp-ejs
// y ejecución JS sobre QuickJS-NG compilado en Rust (32 y 64 bits).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.auramusic.core.downloader"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        val targetAbis = (project.findProperty("auraAbis") as? String)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        ndk {
            abiFilters += targetAbis
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable.add("ChromeOsAbiSupport")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        // Python 3.11 mantiene soporte oficial de Chaquopy para 32 bits (armeabi-v7a) y 64 bits.
        version = "3.11"
        buildPython("python3.11")

        // Compila todo a bytecode .pyc para reducir peso y acelerar arranque.
        pyc {
            src = true
            pip = true
            stdlib = true
        }

        // Instalación minimalista: sin extras opcionales pesados (mutagen, pycryptodomex, etc.)
        // ni FFmpeg; ExoPlayer reproduce m4a/opus directo y Rust (lofty) gestiona metadatos.
        pip {
            options("--no-deps")
            install("yt-dlp")
            install("yt-dlp-ejs")
        }

        // yt_dlp_ejs necesita leer sus assets .js (core.min.js / lib.min.js) desde disco.
        extractPackages("yt_dlp_ejs")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:rust"))
    implementation(project(":core:importer"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
