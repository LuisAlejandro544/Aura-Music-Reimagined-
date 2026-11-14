# Reglas ProGuard para conservar la clase puente JNI y sus métodos nativos en compilaciones Release.
-keep class com.auramusic.core.rust.AuraRustBridge {
    native <methods>;
}
