//! Puente JNI principal del motor nativo en Rust (`aura_core`) para Aura Music.
//!
//! Expone funciones seguras a Kotlin (`com.auramusic.core.rust.AuraRustBridge`) para:
//! - Validar la inicialización del motor Rust y reportar versión/arquitectura.
//! - Generar carátulas procedurales cuadradas en bytes RGBA8888 sin usar IA.
//! - Calcular huellas digitales SHA-256 de pistas importadas.
//! - Extraer metadatos y carátulas embebidas con `lofty`.
//! - Procesar ecualizador de 10 bandas, efectos DSP y animaciones de eliminación.

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JByteBuffer, JClass, JFloatArray, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jfloat, jint, jlong};
use jni::{Env, EnvUnowned};

mod artwork;
mod crossfeed;
mod delete_fx;
mod equalizer;
#[cfg(feature = "js-runtime")]
pub mod js_runtime;
mod metadata;
mod palette;
mod reverb;
mod spatial_8d;

/// Versión reportada de QuickJS-NG compatible con la detección de `yt-dlp` en `libaura_qjs.so`.
pub const QUICKJS_VERSION_STRING: &str = "QuickJS-ng version 0.14.0";

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
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeGetEngineInfo<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    init_android_logger();
    unowned_env
        .with_env(|env| -> jni::errors::Result<JString<'local>> {
            let arch = std::env::consts::ARCH;
            let info = format!(
                "AuraCore Rust v{} (Edition 2024 • {})",
                env!("CARGO_PKG_VERSION"),
                arch
            );
            Ok(env.new_string(info).unwrap_or_default())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Devuelve la versión del motor QuickJS-NG (`libaura_qjs.so`) sin enlazar `rquickjs` dentro de `libaura_core.so`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeGetQuickJsVersion<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    init_android_logger();
    unowned_env
        .with_env(|env| -> jni::errors::Result<JString<'local>> {
            Ok(env
                .new_string(QUICKJS_VERSION_STRING)
                .unwrap_or_default())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Evalúa un script JavaScript en QuickJS-NG cuando `js-runtime` está compilado en la librería,
/// o indica que la ejecución JS se realiza mediante el binario dedicado `libaura_qjs.so`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeEvalQuickJs<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    script: JString<'local>,
) -> JString<'local> {
    init_android_logger();
    unowned_env
        .with_env(|env| -> jni::errors::Result<JString<'local>> {
            #[cfg(feature = "js-runtime")]
            {
                let script_str = match script.try_to_string(env) {
                    Ok(s) => s,
                    Err(err) => {
                        let msg = format!("ERROR: No se pudo leer script JNI: {err:?}");
                        return Ok(env.new_string(msg).unwrap_or_default());
                    }
                };

                let output = match js_runtime::eval_script_with_args(&script_str, &[]) {
                    Ok(res) => res.stdout,
                    Err(err) => format!("ERROR: {err}"),
                };

                Ok(env.new_string(output).unwrap_or_default())
            }

            #[cfg(not(feature = "js-runtime"))]
            {
                let _ = &script;
                let msg = "ERROR: QuickJS-NG se ejecuta mediante el binario dedicado libaura_qjs.so en esta compilación.";
                Ok(env.new_string(msg).unwrap_or_default())
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Genera una carátula procedural cuadrada en formato RGBA8888 directamente en Rust.
/// `accent_argb`: color ARGB del tema activo (0 indica sin preferencia).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeGenerateCoverRgba<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    seed_key: JString<'local>,
    width: jint,
    height: jint,
    accent_argb: jint,
) -> JByteArray<'local> {
    init_android_logger();
    unowned_env
        .with_env(|env| -> jni::errors::Result<JByteArray<'local>> {
            let seed_str = match seed_key.try_to_string(env) {
                Ok(s) => s,
                Err(err) => {
                    log::error!("Error leyendo seed_key desde JNI: {err:?}");
                    return Ok(JByteArray::default());
                }
            };

            let w = width.max(64) as u32;
            let h = height.max(64) as u32;
            let accent = if accent_argb == 0 {
                None
            } else {
                Some(accent_argb as u32)
            };

            let array = match artwork::render_procedural_cover(&seed_str, w, h, accent) {
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
            };
            Ok(array)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Calcula una huella digital determinista SHA-256 en Rust para evitar duplicados al importar.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeComputeTrackFingerprint<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    title: JString<'local>,
    artist: JString<'local>,
    duration_ms: jlong,
    size_bytes: jlong,
) -> JString<'local> {
    init_android_logger();
    unowned_env
        .with_env(|env| -> jni::errors::Result<JString<'local>> {
            let title_str = title.try_to_string(env).unwrap_or_default();
            let artist_str = artist.try_to_string(env).unwrap_or_default();

            let fp = artwork::compute_track_fingerprint(
                &title_str,
                &artist_str,
                duration_ms,
                size_bytes,
            );
            Ok(env.new_string(fp).unwrap_or_default())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Extrae título, artista, álbum, duración y carátula embebida desde un file descriptor SAF en Rust.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeExtractAudioMetadata<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    fd: jint,
) -> JByteArray<'local> {
    init_android_logger();
    unowned_env
        .with_env(|env| -> jni::errors::Result<JByteArray<'local>> {
            let payload = match metadata::extract_metadata_from_fd(fd) {
                Ok(meta) => meta.to_jni_bytes(),
                Err(err) => {
                    log::warn!("No se pudieron extraer metadatos de audio desde fd {fd}: {err}");
                    metadata::AudioMetadataResult::error_jni_bytes()
                }
            };

            let array = match env.byte_array_from_slice(&payload) {
                Ok(arr) => arr,
                Err(err) => {
                    log::error!("Error serializando metadatos hacia JByteArray: {err:?}");
                    JByteArray::default()
                }
            };
            Ok(array)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Actualiza en Rust el estado encendido/apagado y las ganancias (dB) de las 10 bandas del ecualizador.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeSetEqualizerConfig<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    enabled: jboolean,
    gains_db: JFloatArray<'local>,
) {
    init_android_logger();
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let len = gains_db.len(env).unwrap_or(0);
            let copy_len = len.min(equalizer::BAND_COUNT);
            let mut bands = [0.0_f32; equalizer::BAND_COUNT];
            if copy_len > 0
                && let Err(err) = gains_db.get_region(env, 0, &mut bands[..copy_len])
            {
                log::error!("Error leyendo bandas del ecualizador desde JNI: {err:?}");
                return Ok(());
            }

            equalizer::set_equalizer_config(enabled != JNI_FALSE, &bands);
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

/// Actualiza en Rust los parámetros de Reverberación estéreo, calidez analógica Slowed, Audio 8D y Crossfeed binaural.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeSetAudioEffectsConfig(
    _unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    reverb_enabled: jboolean,
    reverb_room_size: jfloat,
    reverb_damping: jfloat,
    reverb_wet_mix: jfloat,
    reverb_width: jfloat,
    slowed_warmth_enabled: jboolean,
    slowed_warmth_amount: jfloat,
    spatial_8d_enabled: jboolean,
    spatial_8d_speed: jfloat,
    spatial_8d_depth: jfloat,
    crossfeed_enabled: jboolean,
    crossfeed_strength: jfloat,
    crossfeed_angle: jfloat,
) {
    init_android_logger();
    reverb::set_audio_effects_config(reverb::AudioEffectsConfig {
        reverb_enabled: reverb_enabled != JNI_FALSE,
        reverb_room_size,
        reverb_damping,
        reverb_wet_mix,
        reverb_width,
        slowed_warmth_enabled: slowed_warmth_enabled != JNI_FALSE,
        slowed_warmth_amount,
        spatial_8d_enabled: spatial_8d_enabled != JNI_FALSE,
        spatial_8d_speed,
        spatial_8d_depth,
        crossfeed_enabled: crossfeed_enabled != JNI_FALSE,
        crossfeed_strength,
        crossfeed_angle,
    });
}

/// Configura frecuencia de muestreo y número de canales en el procesador DSP del ecualizador.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeConfigureEqualizer(
    _unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
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
    _unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
) {
    equalizer::reset_global_processor();
}

/// Procesa in-place un DirectByteBuffer PCM de 16 bits little-endian sin copias adicionales.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeProcessEqualizerPcm16<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    buffer: JByteBuffer<'local>,
    byte_length: jint,
) -> jboolean {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jboolean> {
            Ok(process_direct_pcm_buffer(env, &buffer, byte_length, false))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Procesa in-place un DirectByteBuffer PCM de 32 bits flotante little-endian sin copias adicionales.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeProcessEqualizerPcmFloat<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    buffer: JByteBuffer<'local>,
    byte_length: jint,
) -> jboolean {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jboolean> {
            Ok(process_direct_pcm_buffer(env, &buffer, byte_length, true))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn process_direct_pcm_buffer(
    env: &Env<'_>,
    buffer: &JByteBuffer<'_>,
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
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_auramusic_core_rust_AuraRustBridge_nativeRenderDeleteEffectFrame<
    'local,
>(
    mut unowned_env: EnvUnowned<'local>,
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
    unowned_env
        .with_env(|env| -> jni::errors::Result<jboolean> {
            if width <= 0 || height <= 0 {
                return Ok(JNI_FALSE);
            }
            let w = width as usize;
            let h = height as usize;
            let Some(required) = w.checked_mul(h).and_then(|px| px.checked_mul(4)) else {
                return Ok(JNI_FALSE);
            };

            let Ok(src_ptr) = env.get_direct_buffer_address(&source_buffer) else {
                return Ok(JNI_FALSE);
            };
            let Ok(src_cap) = env.get_direct_buffer_capacity(&source_buffer) else {
                return Ok(JNI_FALSE);
            };
            let Ok(dst_ptr) = env.get_direct_buffer_address(&output_buffer) else {
                return Ok(JNI_FALSE);
            };
            let Ok(dst_cap) = env.get_direct_buffer_capacity(&output_buffer) else {
                return Ok(JNI_FALSE);
            };

            if src_ptr.is_null() || dst_ptr.is_null() || src_cap < required || dst_cap < required {
                return Ok(JNI_FALSE);
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

            Ok(if ok { JNI_TRUE } else { JNI_FALSE })
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}
