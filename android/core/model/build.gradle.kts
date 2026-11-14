// Módulo puro de modelos de dominio para Aura Music.
// No depende de Android SDK para mantener las reglas de negocio desacopladas y rápidas de probar.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
