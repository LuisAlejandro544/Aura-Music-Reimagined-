# Reglas ProGuard para :core:downloader (Chaquopy, Python 3.11 y puente yt-dlp).
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Runtime Java <-> Python de Chaquopy (invocado dinámicamente vía JNI desde CPython).
-keep class com.chaquo.python.** { *; }
-keep interface com.chaquo.python.** { *; }
-keepclassmembers class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**
-dontwarn org.python.**
-dontwarn java.lang.invoke.**

# Puentes y clases invocadas desde Python (progress_callback / cancel_check) y servicio IPC.
-keep class com.auramusic.core.downloader.YtDlpAudioDownloader { *; }
-keep class com.auramusic.core.downloader.YtDlpAudioDownloader$* { *; }
-keep class com.auramusic.core.downloader.YtDlpAudioDownloader$PyProgressBridge { *; }
-keep class com.auramusic.core.downloader.YtDlpAudioDownloader$PyCancelBridge { *; }
-keep interface com.auramusic.core.downloader.AuraDownloadHost { *; }
-keep class com.auramusic.core.downloader.AuraDownloadForegroundService { *; }
-keep class com.auramusic.core.downloader.AuraDownloaderIpc { *; }
-keep class com.auramusic.core.downloader.PendingDownloadedImport { *; }
-keepclassmembers class * {
    @com.chaquo.python.* <fields>;
    @com.chaquo.python.* <methods>;
}

