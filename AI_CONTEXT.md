# AI Context — Aura Music

Este archivo resume las decisiones de arquitectura, restricciones técnicas y reglas de producto que cualquier modelo de IA debe respetar al trabajar en **Aura Music**.

## 1. Contexto de doble entorno (Web + Móvil)
- **Web (`src/App.tsx`)**: Debe mostrar **únicamente** una pantalla limpia con `"Hello World"` para mantener el entorno de previsualización web compilando sin errores. No añadir interfaz musical ni paneles adicionales en la web salvo petición explícita.
- **Móvil (`android/`)**: Aquí reside la aplicación real en **Kotlin + Jetpack Compose + Rust (Edition 2024)**.

## 2. Reglas de arquitectura y compilación Android
- **Versiones objetivo**: `minSdk = 26` (Android 8.0), `targetSdk = 35`, `compileSdk = 35`, Gradle `8.14.7`, AGP `8.9.1`, Kotlin `2.1.20`.
- **Arquitecturas obligatorias**: Soporte completo para **32 bits (`armeabi-v7a`)** y **64 bits (`arm64-v8a`, `x86_64`)**.
- **Distribución**: La app está pensada para instalarse vía APK directo o **Uptodown** (no Google Play). No introducir dependencias ligadas a servicios exclusivos de Google Play.
- **Rust sin fallbacks**: Toda funcionalidad delegada a Rust (lectura de metadatos y carátulas embebidas vía `lofty` desde descriptor SAF, generación de carátulas procedurales, fingerprinting SHA-256 y el ecualizador DSP de 10 bandas en `aura_core::equalizer` sobre Media3 `DirectByteBuffer`) debe compilarse en `gradlew` vía `cargo-ndk` (`:core:rust:buildRustAndroidLibs`) y ejecutarse en `libaura_core.so`. Nunca reemplazar lógica Rust con fallbacks en Kotlin (como `MediaMetadataRetriever` o `android.media.audiofx.Equalizer`).
- **Migraciones Room seguras**: `AuraDatabase` debe mantener `exportSchema = true` (esquemas exportados vía KSP en `android/core/database/schemas`) y registrar toda evolución de esquema en `AuraMigrations.ALL_MIGRATIONS` sin usar `fallbackToDestructiveMigration`.
- **Licencias**: Usar exclusivamente librerías y crates con licencias permisivas (**Apache-2.0, MIT, BSD**). Prohibido agregar dependencias GPL/LGPL/AGPL/CC-BY que obliguen a liberar código fuente o mostrar créditos obligatorios.
- **Propiedad intelectual**: Prohibido nombrar archivos, paquetes o identificadores con marcas registradas de terceros.

## 3. Reglas de producto y UX
- **Importación explícita y eficiente**: Nunca escanear automáticamente todo el almacenamiento del dispositivo. Solo importar lo que el usuario elija (individual, tanda o carpeta completa con SAF), usando consulta única de identidades en Room y concurrencia acotada para procesar lotes rápido sin agotar descriptores.
- **Carátulas**: Al extraer portadas embebidas, detectar el formato real por magic bytes (sin forzar `.jpg`). Las carátulas personalizadas elegidas por el usuario desde su galería deben convertirse en segundo plano a **WebP sin pérdida a máxima compresión** (`WEBP_LOSSLESS` / calidad `100`) eliminando portadas huérfanas anteriores.
- **Reproductor sobrio**: Carátula cuadrada normal con bordes suavizados; evitar diseños experimentales confusos (vinilos giratorios, formas raras, etc.).
- **Diseño no minimalista en exceso**: Usar superficies oscuras con matices tonales del color elegido, tarjetas con jerarquía visual clara, iconos descriptivos y separación en pantallas dedicadas (incluyendo pantalla propia para el Ecualizador) + popups/bottom sheets cuando corresponda.
- **Concurrencia**: Usar coroutines (`Dispatchers.IO` para Room/SAF/archivos, `Dispatchers.Default` para JNI/Rust/compresión WebP) sin bloquear el hilo principal.
- **Comentarios**: Todo archivo de código debe explicar claramente qué hace su lógica.
