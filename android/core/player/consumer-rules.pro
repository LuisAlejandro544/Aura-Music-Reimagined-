# Reglas ProGuard para :core:player.
# Conserva la firma de Player.Listener / Commands para el Proxy dinámico de MediaSession
# y las interfaces/contratos de sintonización en proceso :player.
-keep interface androidx.media3.common.Player$Listener { *; }
-keepclassmembers interface androidx.media3.common.Player$Listener {
    void onAvailableCommandsChanged(androidx.media3.common.Player$Commands);
}
-keep class androidx.media3.common.Player$Commands { *; }
-keep interface com.auramusic.core.player.AuraPlaybackTuningProvider { *; }
-keep class com.auramusic.core.player.AuraSleepTimerContract { *; }
-keep class com.auramusic.core.player.AuraPlayerIpcContract { *; }
