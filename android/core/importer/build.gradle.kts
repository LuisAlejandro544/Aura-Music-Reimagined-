// Módulo importador manual de música de Aura Music.
// No escanea el teléfono automáticamente: procesa únicamente archivos individuales,
// selecciones múltiples o carpetas completas elegidas por el usuario vía Storage Access Framework.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.auramusic.core.importer"
    compileSdk = 36

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
    implementation(project(":core:database"))
    implementation(project(":core:rust"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
