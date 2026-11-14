# Reglas ProGuard para entidades y DAOs de Room en Aura Music.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
