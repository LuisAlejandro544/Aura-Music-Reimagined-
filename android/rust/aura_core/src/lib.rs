//! Puente JNI principal del motor nativo en Rust (`aura_core`) para Aura Music.
//!
//! Expone funciones seguras a Kotlin (`com.auramusic.core.rust.AuraRustBridge`) para:
//! - Validar la inicialización del motor Rust y reportar versión/arquitectura.
//! - Generar carátulas procedurales cuadradas en bytes RGBA8888 sin usar IA.
//! - Calcular huellas digitales SHA-256 de pistas importadas.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong, jstring};

mod artwork;
mod palette;

/// Inicializa el logger nativo de Android la primera vez que se invoca el puente.
fn init_android_logger() {
    static INIT: std::sync::Once = std::sync::Once::new();
    INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("AuraRustCore"),
        );
    });
}

/// Devuelve información del motor nativo Rust y la arquitectura ABI compilada.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeGetEngineInfo(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_android_logger();
    let arch = std::env::consts::ARCH;
    let info = format!(
        "AuraCore Rust v{} (Edition 2024 • {})",
        env!("CARGO_PKG_VERSION"),
        arch
    );
    match env.new_string(info) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Genera una carátula procedural cuadrada en formato RGBA8888 directamente en Rust.
/// `accent_argb`: color ARGB del tema activo (0 indica sin preferencia).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeGenerateCoverRgba<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    seed_key: JString<'local>,
    width: jint,
    height: jint,
    accent_argb: jint,
) -> JByteArray<'local> {
    init_android_logger();

    let seed_str: String = match env.get_string(&seed_key) {
        Ok(s) => s.into(),
        Err(err) => {
            log::error!("Error leyendo seed_key desde JNI: {err:?}");
            return JByteArray::default();
        }
    };

    let w = width.max(64) as u32;
    let h = height.max(64) as u32;
    let accent = if accent_argb == 0 {
        None
    } else {
        Some(accent_argb as u32)
    };

    match artwork::render_procedural_cover(&seed_str, w, h, accent) {
        Some(bytes) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr,
            Err(err) => {
                log::error!("Error creando JByteArray para carátula: {err:?}");
                JByteArray::default()
            }
        },
        None => {
            log::error!("render_procedural_cover devolvió None");
            JByteArray::default()
        }
    }
}

/// Calcula una huella digital determinista SHA-256 en Rust para evitar duplicados al importar.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeComputeTrackFingerprint(
    mut env: JNIEnv,
    _class: JClass,
    title: JString,
    artist: JString,
    duration_ms: jlong,
    size_bytes: jlong,
) -> jstring {
    init_android_logger();

    let title_str: String = env
        .get_string(&title)
        .map(Into::into)
        .unwrap_or_default();
    let artist_str: String = env
        .get_string(&artist)
        .map(Into::into)
        .unwrap_or_default();

    let fp = artwork::compute_track_fingerprint(&title_str, &artist_str, duration_ms, size_bytes);
    match env.new_string(fp) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
