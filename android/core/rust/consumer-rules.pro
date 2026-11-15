# Reglas ProGuard para conservar la clase puente JNI, sus métodos nativos y modelos asociados en Release.
-keep class com.auramusic.core.rust.AuraRustBridge {
    *;
    native <methods>;
}
-keep class com.auramusic.core.rust.RustAudioMetadata { *; }
-keep class com.auramusic.core.rust.AuraMetadataCodec { *; }
