# Reglas ProGuard para :core:player.
# Conserva la firma de onAvailableCommandsChanged para el Proxy dinámico de MediaSession.
-keepclassmembers interface androidx.media3.common.Player$Listener {
    void onAvailableCommandsChanged(androidx.media3.common.Player$Commands);
}
