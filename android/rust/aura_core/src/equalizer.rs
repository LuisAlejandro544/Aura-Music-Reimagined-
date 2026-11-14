//! Motor DSP de ecualización gráfica de 10 bandas en Rust puro para Aura Music.
//!
//! Implementa 10 filtros IIR biquad tipo Peaking EQ (frecuencias ISO estándar de
//! 31.25 Hz a 16 kHz) calculados en precisión `f64` mediante Direct Form II Transposed
//! y limitación suave asintótica para evitar distorsión digital al realzar frecuencias.
//! Se conecta en tiempo real con Media3 ExoPlayer vía JNI sobre buffers directos PCM.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

/// Número fijo de bandas del ecualizador gráfico.
pub const BAND_COUNT: usize = 10;

/// Frecuencias centrales ISO estándar (Hz) de las 10 bandas.
pub const BAND_FREQUENCIES_HZ: [f64; BAND_COUNT] = [
    31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
];

/// Factor Q estándar para bandas separadas 1 octava (1.4142).
const OCTAVE_BAND_Q: f64 = 1.4142135623730951;
const MIN_GAIN_DB: f32 = -12.0;
const MAX_GAIN_DB: f32 = 12.0;

/// Configuración compartida entre la interfaz (Kotlin) y el hilo de audio (ExoPlayer).
#[derive(Debug, Clone)]
struct EqualizerConfig {
    enabled: bool,
    gains_db: [f32; BAND_COUNT],
}

impl Default for EqualizerConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            gains_db: [0.0; BAND_COUNT],
        }
    }
}

/// Coeficientes normalizados de un filtro biquad IIR.
#[derive(Debug, Clone, Copy)]
struct BiquadCoeffs {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
    active: bool,
}

impl BiquadCoeffs {
    const UNITY: Self = Self {
        b0: 1.0,
        b1: 0.0,
        b2: 0.0,
        a1: 0.0,
        a2: 0.0,
        active: false,
    };

    /// Calcula coeficientes Peaking EQ (Audio EQ Cookbook) para una banda y frecuencia de muestreo.
    fn peaking(center_hz: f64, sample_rate: f64, gain_db: f64) -> Self {
        if gain_db.abs() < 0.01 || sample_rate <= 0.0 {
            return Self::UNITY;
        }

        // Evita inestabilidad cerca de Nyquist en pistas con sample rate bajo (ej. 22.05 / 32 kHz).
        let fc = center_hz.clamp(10.0, sample_rate * 0.45);
        let a = 10.0_f64.powf(gain_db / 40.0);
        let omega = 2.0 * std::f64::consts::PI * fc / sample_rate;
        let sin_w = omega.sin();
        let cos_w = omega.cos();
        let alpha = sin_w / (2.0 * OCTAVE_BAND_Q);

        let b0 = 1.0 + alpha * a;
        let b1 = -2.0 * cos_w;
        let b2 = 1.0 - alpha * a;
        let a0 = 1.0 + alpha / a;
        let a1 = -2.0 * cos_w;
        let a2 = 1.0 - alpha / a;

        Self {
            b0: b0 / a0,
            b1: b1 / a0,
            b2: b2 / a0,
            a1: a1 / a0,
            a2: a2 / a0,
            active: true,
        }
    }
}

/// Estado de retardo por canal y banda (Direct Form II Transposed).
#[derive(Debug, Clone, Copy, Default)]
struct BiquadState {
    z1: f64,
    z2: f64,
}

impl BiquadState {
    #[inline]
    fn process(&mut self, x: f64, c: &BiquadCoeffs) -> f64 {
        let y = c.b0 * x + self.z1;
        self.z1 = c.b1 * x - c.a1 * y + self.z2;
        self.z2 = c.b2 * x - c.a2 * y;
        y
    }
}

/// Procesador DSP de 10 bandas que mantiene los coeficientes y líneas de retardo en memoria nativa.
#[derive(Debug)]
pub struct EqualizerProcessor {
    sample_rate: u32,
    channel_count: usize,
    enabled: bool,
    has_active_band: bool,
    applied_version: u64,
    coeffs: [BiquadCoeffs; BAND_COUNT],
    // Dimensionado como `channel_count * BAND_COUNT`.
    states: Vec<BiquadState>,
}

impl EqualizerProcessor {
    pub fn new() -> Self {
        let mut processor = Self {
            sample_rate: 44_100,
            channel_count: 2,
            enabled: false,
            has_active_band: false,
            applied_version: 0,
            coeffs: [BiquadCoeffs::UNITY; BAND_COUNT],
            states: vec![BiquadState::default(); 2 * BAND_COUNT],
        };
        processor.sync_config_if_needed();
        processor
    }

    /// Reconfigura frecuencia de muestreo y canales cuando ExoPlayer abre una pista nueva.
    pub fn configure(&mut self, sample_rate: u32, channel_count: usize) {
        let safe_rate = sample_rate.max(8_000);
        let safe_channels = channel_count.clamp(1, 8);
        let format_changed = self.sample_rate != safe_rate || self.channel_count != safe_channels;

        self.sample_rate = safe_rate;
        self.channel_count = safe_channels;

        if format_changed {
            self.states
                .resize(self.channel_count * BAND_COUNT, BiquadState::default());
            self.reset_states();
            self.applied_version = 0; // Fuerza recalcular coeficientes para el nuevo sample_rate.
        }
        self.sync_config_if_needed();
    }

    /// Limpia las líneas de retardo al hacer seek o reiniciar la reproducción.
    pub fn reset_states(&mut self) {
        for s in &mut self.states {
            *s = BiquadState::default();
        }
    }

    fn sync_config_if_needed(&mut self) {
        let current_version = CONFIG_VERSION.load(Ordering::Acquire);
        if self.applied_version == current_version {
            return;
        }

        let cfg = shared_config()
            .lock()
            .map(|guard| guard.clone())
            .unwrap_or_default();

        self.enabled = cfg.enabled;
        let mut any_active = false;
        let sr = self.sample_rate as f64;
        for (i, freq) in BAND_FREQUENCIES_HZ.iter().enumerate() {
            let coeff = BiquadCoeffs::peaking(*freq, sr, cfg.gains_db[i] as f64);
            if coeff.active {
                any_active = true;
            }
            self.coeffs[i] = coeff;
        }
        self.has_active_band = any_active;
        self.applied_version = current_version;
    }

    /// Procesa un buffer PCM de 16 bits con signo little-endian in-place.
    /// Devuelve `true` si aplicó ecualización y `false` si operó en bypass directo.
    pub fn process_pcm16_le(&mut self, bytes: &mut [u8]) -> bool {
        self.sync_config_if_needed();
        if !self.enabled || !self.has_active_band || self.channel_count == 0 {
            return false;
        }

        let ch_count = self.channel_count;
        let frame_bytes = ch_count * 2;
        let usable_bytes = (bytes.len() / frame_bytes) * frame_bytes;
        if usable_bytes == 0 {
            return false;
        }

        for frame in bytes[..usable_bytes].chunks_exact_mut(frame_bytes) {
            for ch in 0..ch_count {
                let offset = ch * 2;
                let raw = i16::from_le_bytes([frame[offset], frame[offset + 1]]);
                let mut sample = (raw as f64) / 32768.0;

                let state_base = ch * BAND_COUNT;
                for band in 0..BAND_COUNT {
                    let c = &self.coeffs[band];
                    if c.active {
                        sample = self.states[state_base + band].process(sample, c);
                    }
                }

                let limited = soft_limit(sample);
                let out_i16 = (limited * 32767.0).round().clamp(-32768.0, 32767.0) as i16;
                let out_bytes = out_i16.to_le_bytes();
                frame[offset] = out_bytes[0];
                frame[offset + 1] = out_bytes[1];
            }
        }

        true
    }

    /// Procesa un buffer PCM flotante de 32 bits little-endian in-place.
    /// Devuelve `true` si aplicó ecualización y `false` si operó en bypass directo.
    pub fn process_pcm_f32_le(&mut self, bytes: &mut [u8]) -> bool {
        self.sync_config_if_needed();
        if !self.enabled || !self.has_active_band || self.channel_count == 0 {
            return false;
        }

        let ch_count = self.channel_count;
        let frame_bytes = ch_count * 4;
        let usable_bytes = (bytes.len() / frame_bytes) * frame_bytes;
        if usable_bytes == 0 {
            return false;
        }

        for frame in bytes[..usable_bytes].chunks_exact_mut(frame_bytes) {
            for ch in 0..ch_count {
                let offset = ch * 4;
                let raw = f32::from_le_bytes([
                    frame[offset],
                    frame[offset + 1],
                    frame[offset + 2],
                    frame[offset + 3],
                ]);
                let mut sample = if raw.is_finite() { raw as f64 } else { 0.0 };

                let state_base = ch * BAND_COUNT;
                for band in 0..BAND_COUNT {
                    let c = &self.coeffs[band];
                    if c.active {
                        sample = self.states[state_base + band].process(sample, c);
                    }
                }

                let out_f32 = soft_limit(sample) as f32;
                let out_bytes = out_f32.to_le_bytes();
                frame[offset..offset + 4].copy_from_slice(&out_bytes);
            }
        }

        true
    }
}

/// Limitador suave transparente por debajo de 0.92 para proteger contra clipping al realzar bandas.
#[inline]
fn soft_limit(x: f64) -> f64 {
    let ax = x.abs();
    if ax <= 0.92 {
        x
    } else {
        let excess = (ax - 0.92) / 0.08;
        let compressed = 0.92 + 0.08 * (excess / (1.0 + excess));
        x.signum() * compressed.min(1.0)
    }
}

static CONFIG_VERSION: AtomicU64 = AtomicU64::new(1);
static SHARED_CONFIG: OnceLock<Mutex<EqualizerConfig>> = OnceLock::new();
static GLOBAL_PROCESSOR: OnceLock<Mutex<EqualizerProcessor>> = OnceLock::new();

fn shared_config() -> &'static Mutex<EqualizerConfig> {
    SHARED_CONFIG.get_or_init(|| Mutex::new(EqualizerConfig::default()))
}

fn global_processor() -> &'static Mutex<EqualizerProcessor> {
    GLOBAL_PROCESSOR.get_or_init(|| Mutex::new(EqualizerProcessor::new()))
}

/// Actualiza la configuración global del ecualizador (llamado desde Kotlin al mover sliders o presets).
pub fn set_equalizer_config(enabled: bool, gains_db: &[f32]) {
    let mut clamped = [0.0_f32; BAND_COUNT];
    for (i, dst) in clamped.iter_mut().enumerate() {
        let raw = gains_db.get(i).copied().unwrap_or(0.0);
        *dst = if raw.is_finite() {
            raw.clamp(MIN_GAIN_DB, MAX_GAIN_DB)
        } else {
            0.0
        };
    }

    if let Ok(mut guard) = shared_config().lock() {
        guard.enabled = enabled;
        guard.gains_db = clamped;
    }
    CONFIG_VERSION.fetch_add(1, Ordering::Release);
}

/// Configura frecuencia de muestreo y canales en el procesador global de reproducción.
pub fn configure_global_processor(sample_rate: u32, channel_count: usize) {
    if let Ok(mut guard) = global_processor().lock() {
        guard.configure(sample_rate, channel_count);
    }
}

/// Reinicia las líneas de retardo del procesador global de reproducción.
pub fn reset_global_processor() {
    if let Ok(mut guard) = global_processor().lock() {
        guard.reset_states();
    }
}

/// Procesa un bloque PCM 16-bit LE usando el procesador global de reproducción.
pub fn process_global_pcm16_le(bytes: &mut [u8]) -> bool {
    match global_processor().lock() {
        Ok(mut guard) => guard.process_pcm16_le(bytes),
        Err(_) => false,
    }
}

/// Procesa un bloque PCM 32-bit float LE usando el procesador global de reproducción.
pub fn process_global_pcm_f32_le(bytes: &mut [u8]) -> bool {
    match global_processor().lock() {
        Ok(mut guard) => guard.process_pcm_f32_le(bytes),
        Err(_) => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn generate_sine_pcm16(freq_hz: f64, sample_rate: u32, frames: usize, amplitude: f64) -> Vec<u8> {
        let mut out = Vec::with_capacity(frames * 4);
        for i in 0..frames {
            let t = i as f64 / sample_rate as f64;
            let val = (2.0 * std::f64::consts::PI * freq_hz * t).sin() * amplitude;
            let s = (val * 32767.0).round().clamp(-32768.0, 32767.0) as i16;
            let bytes = s.to_le_bytes();
            // Estéreo L + R
            out.extend_from_slice(&bytes);
            out.extend_from_slice(&bytes);
        }
        out
    }

    fn rms_pcm16(bytes: &[u8]) -> f64 {
        let mut sum_sq = 0.0_f64;
        let mut count = 0usize;
        for chunk in bytes.chunks_exact(2) {
            let s = i16::from_le_bytes([chunk[0], chunk[1]]) as f64 / 32768.0;
            sum_sq += s * s;
            count += 1;
        }
        (sum_sq / count.max(1) as f64).sqrt()
    }

    #[test]
    fn bypasses_when_disabled_or_flat() {
        let mut proc = EqualizerProcessor::new();
        proc.configure(48_000, 2);

        set_equalizer_config(false, &[12.0; BAND_COUNT]);
        let original = generate_sine_pcm16(125.0, 48_000, 1024, 0.25);
        let mut buf = original.clone();
        assert!(!proc.process_pcm16_le(&mut buf));
        assert_eq!(buf, original);

        set_equalizer_config(true, &[0.0; BAND_COUNT]);
        let mut buf2 = original.clone();
        assert!(!proc.process_pcm16_le(&mut buf2));
        assert_eq!(buf2, original);
    }

    #[test]
    fn boosts_target_band_and_attenuates_when_negative_gain() {
        let mut proc = EqualizerProcessor::new();
        proc.configure(48_000, 2);

        let input = generate_sine_pcm16(125.0, 48_000, 4096, 0.15);
        let base_rms = rms_pcm16(&input);

        // Realza +10 dB en banda 2 (125 Hz)
        let mut boost_gains = [0.0_f32; BAND_COUNT];
        boost_gains[2] = 10.0;
        set_equalizer_config(true, &boost_gains);

        let mut boosted = input.clone();
        assert!(proc.process_pcm16_le(&mut boosted));
        let boosted_rms = rms_pcm16(&boosted);
        assert!(
            boosted_rms > base_rms * 2.0,
            "Se esperaba aumento fuerte de RMS en 125 Hz: base={base_rms}, boosted={boosted_rms}"
        );

        // Atenúa -10 dB en banda 2 (125 Hz)
        proc.reset_states();
        let mut cut_gains = [0.0_f32; BAND_COUNT];
        cut_gains[2] = -10.0;
        set_equalizer_config(true, &cut_gains);

        let mut cut = input.clone();
        assert!(proc.process_pcm16_le(&mut cut));
        let cut_rms = rms_pcm16(&cut);
        assert!(
            cut_rms < base_rms * 0.5,
            "Se esperaba atenuación de RMS en 125 Hz: base={base_rms}, cut={cut_rms}"
        );

        // Restablece configuración global al final para no contaminar otros tests.
        set_equalizer_config(false, &[0.0; BAND_COUNT]);
    }

    #[test]
    fn processes_f32_pcm_without_nans_or_clipping() {
        let mut proc = EqualizerProcessor::new();
        proc.configure(44_100, 2);
        set_equalizer_config(true, &[12.0; BAND_COUNT]);

        let mut bytes = Vec::with_capacity(512 * 8);
        for i in 0..512 {
            let v = ((i as f32) * 0.1).sin() * 0.9;
            bytes.extend_from_slice(&v.to_le_bytes());
            bytes.extend_from_slice(&v.to_le_bytes());
        }

        assert!(proc.process_pcm_f32_le(&mut bytes));
        for chunk in bytes.chunks_exact(4) {
            let sample = f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
            assert!(sample.is_finite());
            assert!(sample.abs() <= 1.0);
        }

        set_equalizer_config(false, &[0.0; BAND_COUNT]);
    }
}
