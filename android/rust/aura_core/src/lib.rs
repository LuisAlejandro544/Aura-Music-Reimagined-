//! Puente JNI principal del motor nativo en Rust (`aura_core`) para Aura Music.
//!
//! Expone funciones seguras a Kotlin (`com.auramusic.core.rust.AuraRustBridge`) para:
//! - Validar la inicialización del motor Rust y reportar versión/arquitectura.
//! - Generar carátulas procedurales cuadradas en bytes RGBA8888 sin usar IA.
//! - Calcular huellas digitales SHA-256 de pistas importadas.

use jni::JNIEnv;
use jni::objects::{JByteArray, JByteBuffer, JClass, JFloatArray, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jfloat, jint, jlong, jstring};

mod artwork;
mod delete_fx;
mod equalizer;
pub mod js_runtime;
mod metadata;
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

/// Devuelve la versión del motor QuickJS-NG embebido en Rust (`rquickjs`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeGetQuickJsVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_android_logger();
    match env.new_string(js_runtime::QUICKJS_VERSION_STRING) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Evalúa un script JavaScript en QuickJS-NG desde Rust y devuelve la salida estándar o error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeEvalQuickJs(
    mut env: JNIEnv,
    _class: JClass,
    script: JString,
) -> jstring {
    init_android_logger();
    let script_str: String = match env.get_string(&script) {
        Ok(s) => s.into(),
        Err(err) => {
            let msg = format!("ERROR: No se pudo leer script JNI: {err:?}");
            return env.new_string(msg).map(JString::into_raw).unwrap_or(std::ptr::null_mut());
        }
    };

    let output = match js_runtime::eval_script_with_args(&script_str, &[]) {
        Ok(res) => res.stdout,
        Err(err) => format!("ERROR: {err}"),
    };

    match env.new_string(output) {
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

/// Extrae título, artista, álbum, duración y carátula embebida desde un file descriptor SAF en Rust.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeExtractAudioMetadata<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
) -> JByteArray<'local> {
    init_android_logger();

    let payload = match metadata::extract_metadata_from_fd(fd) {
        Ok(meta) => meta.to_jni_bytes(),
        Err(err) => {
            log::warn!("No se pudieron extraer metadatos de audio desde fd {fd}: {err}");
            metadata::AudioMetadataResult::error_jni_bytes()
        }
    };

    match env.byte_array_from_slice(&payload) {
        Ok(arr) => arr,
        Err(err) => {
            log::error!("Error serializando metadatos hacia JByteArray: {err:?}");
            JByteArray::default()
        }
    }
}

/// Actualiza en Rust el estado encendido/apagado y las ganancias (dB) de las 10 bandas del ecualizador.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeSetEqualizerConfig<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    enabled: jboolean,
    gains_db: JFloatArray<'local>,
) {
    init_android_logger();

    let len = env.get_array_length(&gains_db).unwrap_or(0).max(0) as usize;
    let copy_len = len.min(equalizer::BAND_COUNT);
    let mut bands = [0.0_f32; equalizer::BAND_COUNT];
    if copy_len > 0 {
        if let Err(err) = env.get_float_array_region(&gains_db, 0, &mut bands[..copy_len]) {
            log::error!("Error leyendo bandas del ecualizador desde JNI: {err:?}");
            return;
        }
    }

    equalizer::set_equalizer_config(enabled != JNI_FALSE, &bands);
}

/// Configura frecuencia de muestreo y número de canales en el procesador DSP del ecualizador.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeConfigureEqualizer(
    _env: JNIEnv,
    _class: JClass,
    sample_rate: jint,
    channel_count: jint,
) {
    init_android_logger();
    equalizer::configure_global_processor(
        sample_rate.max(8_000) as u32,
        channel_count.clamp(1, 8) as usize,
    );
}

/// Reinicia las líneas de retardo internas de los filtros biquad al hacer seek o flush.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeResetEqualizerState(
    _env: JNIEnv,
    _class: JClass,
) {
    equalizer::reset_global_processor();
}

/// Procesa in-place un DirectByteBuffer PCM de 16 bits little-endian sin copias adicionales.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeProcessEqualizerPcm16<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer: JByteBuffer<'local>,
    byte_length: jint,
) -> jboolean {
    process_direct_pcm_buffer(&env, &buffer, byte_length, false)
}

/// Procesa in-place un DirectByteBuffer PCM de 32 bits flotante little-endian sin copias adicionales.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeProcessEqualizerPcmFloat<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer: JByteBuffer<'local>,
    byte_length: jint,
) -> jboolean {
    process_direct_pcm_buffer(&env, &buffer, byte_length, true)
}

fn process_direct_pcm_buffer(
    env: &JNIEnv,
    buffer: &JByteBuffer,
    byte_length: jint,
    is_float: bool,
) -> jboolean {
    if byte_length <= 0 {
        return JNI_FALSE;
    }

    let Ok(ptr) = env.get_direct_buffer_address(buffer) else {
        return JNI_FALSE;
    };
    let Ok(capacity) = env.get_direct_buffer_capacity(buffer) else {
        return JNI_FALSE;
    };

    let len = byte_length as usize;
    if ptr.is_null() || len > capacity {
        return JNI_FALSE;
    }

    // SAFETY: `ptr` pertenece a un DirectByteBuffer válido y `len <= capacity` fue verificado.
    let slice = unsafe { std::slice::from_raw_parts_mut(ptr, len) };
    let applied = if is_float {
        equalizer::process_global_pcm_f32_le(slice)
    } else {
        equalizer::process_global_pcm16_le(slice)
    };

    if applied { JNI_TRUE } else { JNI_FALSE }
}

/// Renderiza en Rust un fotograma del efecto creativo de eliminación sobre DirectByteBuffers RGBA8888.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeRenderDeleteEffectFrame<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    source_buffer: JByteBuffer<'local>,
    output_buffer: JByteBuffer<'local>,
    width: jint,
    height: jint,
    effect_type: jint,
    progress: jfloat,
    seed: jint,
    accent_argb: jint,
) -> jboolean {
    if width <= 0 || height <= 0 {
        return JNI_FALSE;
    }
    let w = width as usize;
    let h = height as usize;
    let Some(required) = w.checked_mul(h).and_then(|px| px.checked_mul(4)) else {
        return JNI_FALSE;
    };

    let Ok(src_ptr) = env.get_direct_buffer_address(&source_buffer) else {
        return JNI_FALSE;
    };
    let Ok(src_cap) = env.get_direct_buffer_capacity(&source_buffer) else {
        return JNI_FALSE;
    };
    let Ok(dst_ptr) = env.get_direct_buffer_address(&output_buffer) else {
        return JNI_FALSE;
    };
    let Ok(dst_cap) = env.get_direct_buffer_capacity(&output_buffer) else {
        return JNI_FALSE;
    };

    if src_ptr.is_null() || dst_ptr.is_null() || src_cap < required || dst_cap < required {
        return JNI_FALSE;
    }

    // SAFETY: ambos son DirectByteBuffer no nulos con capacidad >= width * height * 4 verificada.
    let src_slice = unsafe { std::slice::from_raw_parts(src_ptr, required) };
    let dst_slice = unsafe { std::slice::from_raw_parts_mut(dst_ptr, required) };
    let ok = delete_fx::render_delete_effect_frame(
        src_slice,
        dst_slice,
        w,
        h,
        delete_fx::DeleteEffectKind::from_i32(effect_type),
        progress,
        seed as u32,
        accent_argb as u32,
    );

    if ok { JNI_TRUE } else { JNI_FALSE }
}

