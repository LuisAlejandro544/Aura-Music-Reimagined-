// Módulo de reproducción de audio basado en AndroidX Media3 ExoPlayer y MediaSessionService.
// Garantiza reproducción estable en segundo plano, controles de notificación del sistema
// y sincronización reactiva con la interfaz mediante StateFlow y Coroutines.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.auramusic.core.player"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:rust"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    // Mantenida únicamente en Debug para futuras vistas Media3; se ignora por completo en el APK Release.
    debugImplementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    testImplementation(libs.junit)
}
