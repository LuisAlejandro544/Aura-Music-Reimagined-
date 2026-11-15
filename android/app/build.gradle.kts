// Módulo principal de aplicación (:app) para Aura Music.
// Ensambla los módulos core y feature, configura soporte explícito para arquitecturas
// de 32 y 64 bits (armeabi-v7a, arm64-v8a, x86_64) y genera un APK universal apto para
// distribución directa en Uptodown o repositorios de terceros.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.auramusic.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.auramusic.app"
        // minSdk 26 (Android 8.0): elegido deliberadamente para contar con canales de notificación
        // nativos requeridos por Media3 MediaSessionService, iconos adaptativos y mejor ABI Rust.
        minSdk = 26
        targetSdk = 36
        versionCode = (project.findProperty("auraVersionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("auraVersionName") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "1.0.0"

        // Soporte completo para dispositivos de 32 bits (armeabi-v7a) y 64 bits (arm64-v8a, x86_64),
        // configurable mediante `-PauraAbis=...` para compilar APK universal o individuales.
        val targetAbis = (project.findProperty("auraAbis") as? String)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        ndk {
            abiFilters += targetAbis
        }
    }

    val releaseKeystorePath = System.getenv("AURA_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("auraKeystorePath") as? String)?.takeIf { it.isNotBlank() }
    val releaseKeystorePassword = System.getenv("AURA_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("auraKeystorePassword") as? String)?.takeIf { it.isNotBlank() }
    val releaseKeyAlias = System.getenv("AURA_KEY_ALIAS")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("auraKeyAlias") as? String)?.takeIf { it.isNotBlank() }
    val releaseKeyPassword = System.getenv("AURA_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("auraKeyPassword") as? String)?.takeIf { it.isNotBlank() }

    val hasReleaseSigning = releaseKeystorePath != null &&
        file(releaseKeystorePath).exists() &&
        releaseKeystorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

    val requireReleaseSigning = (project.findProperty("auraRequireReleaseSigning") as? String)
        ?.equals("true", ignoreCase = true) == true
    if (requireReleaseSigning && !hasReleaseSigning) {
        error(
            "Firma Release requerida: configura AURA_KEYSTORE_PATH, AURA_KEYSTORE_PASSWORD, " +
                "AURA_KEY_ALIAS y AURA_KEY_PASSWORD antes de compilar Release.",
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Usa firma Release oficial cuando están presentes los secretos/credenciales;
            // conserva debug como respaldo únicamente para pruebas locales sin keystore.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // La distribución universal/individual por ABI móvil (-PauraAbis) se gestiona intencionadamente para teléfonos 32/64-bit.
        disable.add("ChromeOsAbiSupport")
    }

    packaging {
        jniLibs {
            // Extrae libaura_qjs.so y librerías nativas a nativeLibraryDir para ejecución SELinux W^X.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:rust"))
    implementation(project(":core:database"))
    implementation(project(":core:importer"))
    implementation(project(":core:downloader"))
    implementation(project(":core:player"))
    implementation(project(":feature:library"))
    implementation(project(":feature:playlists"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:equalizer"))
    implementation(project(":feature:effects"))
    implementation(project(":feature:downloader"))
    implementation(project(":feature:onboarding"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
