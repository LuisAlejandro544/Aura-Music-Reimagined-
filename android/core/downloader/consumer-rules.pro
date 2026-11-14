# Reglas ProGuard para :core:downloader (Chaquopy y puente yt-dlp).
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**
-keep class com.auramusic.core.downloader.YtDlpAudioDownloader$PyProgressBridge { *; }
-keep class com.auramusic.core.downloader.YtDlpAudioDownloader$PyCancelBridge { *; }

