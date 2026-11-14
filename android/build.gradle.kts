// Archivo raíz de Gradle para Aura Music.
// Declara los plugins compartidos sin aplicarlos en la raíz, permitiendo que cada módulo
// aplique únicamente lo que necesita.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.chaquopy) apply false
}
