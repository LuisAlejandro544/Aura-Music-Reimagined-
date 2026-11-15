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
        versionCode = 2
        versionName = "0.1.0"

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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Firma con keystore de depuración si no existe keystore propio, facilitando
            // pruebas directas en teléfono; en producción el usuario puede configurar su keystore.
            signingConfig = signingConfigs.getByName("debug")
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
