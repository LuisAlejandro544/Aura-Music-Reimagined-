# Reglas ProGuard / R8 para el módulo principal (:app) de Aura Music en compilaciones Release.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Conserva Application y sus contratos multi-proceso (:player y :downloader).
-keep class com.auramusic.app.AuraApplication { *; }

# Evita warnings innecesarios de dependencias JVM opcionales al minificar con R8.
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.slf4j.**
-dontwarn java.lang.invoke.**
