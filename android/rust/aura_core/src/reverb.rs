//! Motor DSP en Rust puro para efectos de Reverberación Estéreo (Freeverb)
//! y Calidez Analógica para el modo Slowed en Aura Music.
//!
//! Diseñado para ejecutarse en tiempo real sobre buffers directos PCM de Media3 ExoPlayer
//! sin asignaciones de memoria durante la reproducción y con protección contra subnormales
//! para mantener máximo rendimiento tanto en 32 bits (`armeabi-v7a`) como en 64 bits (`arm64-v8a`).

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

use crate::crossfeed::StaticCrossfeedProcessor;
use crate::spatial_8d::Spatial8dProcessor;

const COMB_TUNINGS_44100: [usize; 8] = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617];
const ALLPASS_TUNINGS_44100: [usize; 4] = [556, 441, 341, 225];
const STEREO_SPREAD_44100: usize = 23;
const FIXED_INPUT_GAIN: f64 = 0.015;
const ALLPASS_FEEDBACK: f64 = 0.5;
const MAX_CHANNELS: usize = 8;

/// Configuración compartida de efectos de audio entre Kotlin (DataStore/UI) y el hilo de audio Rust.
#[derive(Debug, Clone, Copy)]
pub struct AudioEffectsConfig {
    pub reverb_enabled: bool,
    pub reverb_room_size: f32,
    pub reverb_damping: f32,
    pub reverb_wet_mix: f32,
    pub reverb_width: f32,
    pub slowed_warmth_enabled: bool,
    pub slowed_warmth_amount: f32,
    pub spatial_8d_enabled: bool,
    pub spatial_8d_speed: f32,
    pub spatial_8d_depth: f32,
    pub crossfeed_enabled: bool,
    pub crossfeed_strength: f32,
    pub crossfeed_angle: f32,
}

impl Default for AudioEffectsConfig {
    fn default() -> Self {
        Self {
            reverb_enabled: false,
            reverb_room_size: 0.65,
            reverb_damping: 0.45,
            reverb_wet_mix: 0.32,
            reverb_width: 0.85,
            slowed_warmth_enabled: false,
            slowed_warmth_amount: 0.35,
            spatial_8d_enabled: false,
            spatial_8d_speed: 0.38,
            spatial_8d_depth: 0.80,
            crossfeed_enabled: false,
            crossfeed_strength: 0.45,
            crossfeed_angle: 0.50,
        }
    }
}

/// Filtro comb con realimentación amortiguada (Lowpass-Feedback Comb Filter).
#[derive(Debug, Clone)]
struct CombFilter {
    buffer: Vec<f32>,
    index: usize,
    filterstore: f64,
}

impl CombFilter {
    fn new(size: usize) -> Self {
        Self {
            buffer: vec![0.0; size.max(8)],
            index: 0,
            filterstore: 0.0,
        }
    }

    fn reset(&mut self) {
        self.buffer.fill(0.0);
        self.index = 0;
        self.filterstore = 0.0;
    }

    #[inline]
    fn process(&mut self, input: f64, feedback: f64, damp1: f64, damp2: f64) -> f64 {
        let output = self.buffer[self.index] as f64;
        self.filterstore = output * damp2 + self.filterstore * damp1;
        if self.filterstore.abs() < 1e-15 {
            self.filterstore = 0.0;
        }
        let next = input + self.filterstore * feedback;
        self.buffer[self.index] = (if next.abs() < 1e-15 { 0.0 } else { next }) as f32;
        self.index += 1;
        if self.index >= self.buffer.len() {
            self.index = 0;
        }
        output
    }
}

/// Filtro pasa-todo (Allpass) en serie para densificar las reflexiones tempranas.
#[derive(Debug, Clone)]
struct AllpassFilter {
    buffer: Vec<f32>,
    index: usize,
}

impl AllpassFilter {
    fn new(size: usize) -> Self {
        Self {
            buffer: vec![0.0; size.max(8)],
            index: 0,
        }
    }

    fn reset(&mut self) {
        self.buffer.fill(0.0);
        self.index = 0;
    }

    #[inline]
    fn process(&mut self, input: f64) -> f64 {
        let buf_out = self.buffer[self.index] as f64;
        let output = -input + buf_out;
        let next = input + buf_out * ALLPASS_FEEDBACK;
        self.buffer[self.index] = (if next.abs() < 1e-15 { 0.0 } else { next }) as f32;
        self.index += 1;
        if self.index >= self.buffer.len() {
            self.index = 0;
        }
        output
    }
}

/// Banco Freeverb de 8 combs paralelos + 4 allpass en serie para un canal.
#[derive(Debug, Clone)]
struct ReverbChannelBank {
    combs: Vec<CombFilter>,
    allpasses: Vec<AllpassFilter>,
}

impl ReverbChannelBank {
    fn new(sample_rate: u32, stereo_offset_44100: usize) -> Self {
        let ratio = (sample_rate as f64 / 44_100.0).clamp(0.18, 4.5);
        let combs = COMB_TUNINGS_44100
            .iter()
            .map(|&len| {
                let scaled = (((len + stereo_offset_44100) as f64) * ratio).round() as usize;
                CombFilter::new(scaled)
            })
            .collect();

        let allpasses = ALLPASS_TUNINGS_44100
            .iter()
            .map(|&len| {
                let scaled = (((len + stereo_offset_44100) as f64) * ratio).round() as usize;
                AllpassFilter::new(scaled)
            })
            .collect();

        Self { combs, allpasses }
    }

    fn reset(&mut self) {
        for c in &mut self.combs {
            c.reset();
        }
        for a in &mut self.allpasses {
            a.reset();
        }
    }

    #[inline]
    fn process(&mut self, input: f64, feedback: f64, damp1: f64, damp2: f64) -> f64 {
        let mut out = 0.0;
        for comb in &mut self.combs {
            out += comb.process(input, feedback, damp1, damp2);
        }
        for allpass in &mut self.allpasses {
            out = allpass.process(out);
        }
        out
    }
}

/// Procesador DSP de Reverberación Estéreo y Calidez Analógica Slowed.
#[derive(Debug)]
pub struct AudioEffectsProcessor {
    sample_rate: u32,
    channel_count: usize,
    applied_version: u64,

    reverb_enabled: bool,
    reverb_feedback: f64,
    reverb_damp1: f64,
    reverb_damp2: f64,
    reverb_dry_gain: f64,
    reverb_wet1: f64,
    reverb_wet2: f64,
    reverb_mono_wet: f64,

    warmth_active: bool,
    warmth_alpha: f64,
    warmth_states: [f64; MAX_CHANNELS],

    bank_left: ReverbChannelBank,
    bank_right: ReverbChannelBank,
    crossfeed: StaticCrossfeedProcessor,
    spatial_8d: Spatial8dProcessor,
}

impl AudioEffectsProcessor {
    pub fn new() -> Self {
        let mut proc = Self {
            sample_rate: 44_100,
            channel_count: 2,
            applied_version: 0,
            reverb_enabled: false,
            reverb_feedback: 0.84,
            reverb_damp1: 0.35,
            reverb_damp2: 0.65,
            reverb_dry_gain: 1.0,
            reverb_wet1: 0.0,
            reverb_wet2: 0.0,
            reverb_mono_wet: 0.0,
            warmth_active: false,
            warmth_alpha: 1.0,
            warmth_states: [0.0; MAX_CHANNELS],
            bank_left: ReverbChannelBank::new(44_100, 0),
            bank_right: ReverbChannelBank::new(44_100, STEREO_SPREAD_44100),
            crossfeed: StaticCrossfeedProcessor::new(),
            spatial_8d: Spatial8dProcessor::new(),
        };
        proc.sync_config_if_needed();
        proc
    }

    /// Reasigna líneas de retardo únicamente cuando cambia la frecuencia de muestreo o canales.
    pub fn configure(&mut self, sample_rate: u32, channel_count: usize) {
        let safe_rate = sample_rate.max(8_000);
        let safe_channels = channel_count.clamp(1, MAX_CHANNELS);
        let rate_changed = self.sample_rate != safe_rate;

        self.sample_rate = safe_rate;
        self.channel_count = safe_channels;

        if rate_changed {
            self.bank_left = ReverbChannelBank::new(safe_rate, 0);
            self.bank_right = ReverbChannelBank::new(safe_rate, STEREO_SPREAD_44100);
            self.warmth_states = [0.0; MAX_CHANNELS];
            self.applied_version = 0;
        }
        self.crossfeed.configure(safe_rate, safe_channels);
        self.spatial_8d.configure(safe_rate, safe_channels);
        self.sync_config_if_needed();
    }

    /// Limpia colas de reverb, estados del filtro cálido, crossfeed y fase/retardos 8D al hacer seek o cambiar de pista.
    pub fn reset_states(&mut self) {
        self.bank_left.reset();
        self.bank_right.reset();
        self.warmth_states = [0.0; MAX_CHANNELS];
        self.crossfeed.reset_states();
        self.spatial_8d.reset_states();
    }

    pub fn sync_config_if_needed(&mut self) {
        let current_version = EFFECTS_CONFIG_VERSION.load(Ordering::Acquire);
        if self.applied_version == current_version {
            return;
        }

        let cfg = shared_effects_config()
            .lock()
            .map(|guard| *guard)
            .unwrap_or_default();

        self.apply_config(cfg);
        self.applied_version = current_version;
    }

    pub fn apply_config(&mut self, cfg: AudioEffectsConfig) {
        let room = clamp_unit(cfg.reverb_room_size) as f64;
        let damp = clamp_unit(cfg.reverb_damping) as f64;
        let wet = clamp_unit(cfg.reverb_wet_mix) as f64;
        let width = clamp_unit(cfg.reverb_width) as f64;

        self.reverb_enabled = cfg.reverb_enabled && wet > 0.005;
        // Mantiene la realimentación estrictamente por debajo de 1.0 para estabilidad incondicional.
        self.reverb_feedback = 0.70 + room * 0.255;
        self.reverb_damp1 = 0.05 + damp * 0.70;
        self.reverb_damp2 = 1.0 - self.reverb_damp1;

        self.reverb_dry_gain = 1.0 - wet * 0.42;
        let wet_scale = wet * 2.35;
        self.reverb_mono_wet = wet_scale;
        self.reverb_wet1 = wet_scale * (width * 0.5 + 0.5);
        self.reverb_wet2 = wet_scale * ((1.0 - width) * 0.5);

        let warmth = clamp_unit(cfg.slowed_warmth_amount) as f64;
        self.warmth_active = cfg.slowed_warmth_enabled && warmth > 0.01;
        if self.warmth_active {
            // Barre la frecuencia de corte desde 15.5 kHz hasta 3.2 kHz para un sonido cálido tipo cinta.
            let cutoff_hz = (15_500.0 - warmth * 12_300.0).clamp(2_400.0, self.sample_rate as f64 * 0.45);
            let alpha = 1.0 - (-2.0 * std::f64::consts::PI * cutoff_hz / (self.sample_rate as f64)).exp();
            self.warmth_alpha = alpha.clamp(0.02, 1.0);
        } else {
            self.warmth_alpha = 1.0;
        }

        self.crossfeed.apply_config(
            cfg.crossfeed_enabled,
            cfg.crossfeed_strength,
            cfg.crossfeed_angle,
        );

        self.spatial_8d.apply_config(
            cfg.spatial_8d_enabled,
            cfg.spatial_8d_speed,
            cfg.spatial_8d_depth,
        );
    }

    #[inline]
    pub fn is_active(&self) -> bool {
        self.reverb_enabled
            || self.warmth_active
            || self.crossfeed.is_active()
            || self.spatial_8d.is_active()
    }

    /// Procesa un frame multicanal completo en precisión `f64` en el orden óptimo:
    /// Calidez analógica LPF -> Crossfeed binaural -> Espacializador 8D -> Reverberación estéreo Freeverb.
    #[inline]
    pub fn process_frame(&mut self, samples: &mut [f64]) {
        let ch_count = samples.len().min(self.channel_count).min(MAX_CHANNELS);
        if ch_count == 0 || !self.is_active() {
            return;
        }

        if self.warmth_active {
            let a = self.warmth_alpha;
            for ch in 0..ch_count {
                let prev = self.warmth_states[ch];
                let filtered = prev + a * (samples[ch] - prev);
                self.warmth_states[ch] = if filtered.abs() < 1e-15 { 0.0 } else { filtered };
                samples[ch] = filtered;
            }
        }

        if self.crossfeed.is_active() {
            self.crossfeed.process_frame(&mut samples[..ch_count]);
        }

        if self.spatial_8d.is_active() {
            self.spatial_8d.process_frame(&mut samples[..ch_count]);
        }

        if self.reverb_enabled {
            if ch_count == 1 {
                let dry = samples[0];
                let mono_in = dry * FIXED_INPUT_GAIN;
                let out_l = self.bank_left.process(
                    mono_in,
                    self.reverb_feedback,
                    self.reverb_damp1,
                    self.reverb_damp2,
                );
                samples[0] = dry * self.reverb_dry_gain + out_l * self.reverb_mono_wet;
            } else {
                let dry_l = samples[0];
                let dry_r = samples[1];
                let mono_in = (dry_l + dry_r) * FIXED_INPUT_GAIN;
                let out_l = self.bank_left.process(
                    mono_in,
                    self.reverb_feedback,
                    self.reverb_damp1,
                    self.reverb_damp2,
                );
                let out_r = self.bank_right.process(
                    mono_in,
                    self.reverb_feedback,
                    self.reverb_damp1,
                    self.reverb_damp2,
                );
                samples[0] = dry_l * self.reverb_dry_gain + out_l * self.reverb_wet1 + out_r * self.reverb_wet2;
                samples[1] = dry_r * self.reverb_dry_gain + out_r * self.reverb_wet1 + out_l * self.reverb_wet2;
            }
        }
    }
}

#[inline]
fn clamp_unit(v: f32) -> f32 {
    if v.is_finite() {
        v.clamp(0.0, 1.0)
    } else {
        0.0
    }
}

static EFFECTS_CONFIG_VERSION: AtomicU64 = AtomicU64::new(1);
static SHARED_EFFECTS_CONFIG: OnceLock<Mutex<AudioEffectsConfig>> = OnceLock::new();

fn shared_effects_config() -> &'static Mutex<AudioEffectsConfig> {
    SHARED_EFFECTS_CONFIG.get_or_init(|| Mutex::new(AudioEffectsConfig::default()))
}

/// Actualiza la configuración global de Reverb, Calidez Slowed y Audio 8D desde Kotlin.
pub fn set_audio_effects_config(config: AudioEffectsConfig) {
    let sanitized = AudioEffectsConfig {
        reverb_enabled: config.reverb_enabled,
        reverb_room_size: clamp_unit(config.reverb_room_size),
        reverb_damping: clamp_unit(config.reverb_damping),
        reverb_wet_mix: clamp_unit(config.reverb_wet_mix),
        reverb_width: clamp_unit(config.reverb_width),
        slowed_warmth_enabled: config.slowed_warmth_enabled,
        slowed_warmth_amount: clamp_unit(config.slowed_warmth_amount),
        spatial_8d_enabled: config.spatial_8d_enabled,
        spatial_8d_speed: clamp_unit(config.spatial_8d_speed),
        spatial_8d_depth: clamp_unit(config.spatial_8d_depth),
        crossfeed_enabled: config.crossfeed_enabled,
        crossfeed_strength: clamp_unit(config.crossfeed_strength),
        crossfeed_angle: clamp_unit(config.crossfeed_angle),
    };

    if let Ok(mut guard) = shared_effects_config().lock() {
        *guard = sanitized;
    }
    EFFECTS_CONFIG_VERSION.fetch_add(1, Ordering::Release);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bypasses_when_reverb_and_warmth_disabled() {
        let mut proc = AudioEffectsProcessor::new();
        proc.configure(48_000, 2);
        proc.apply_config(AudioEffectsConfig::default());

        assert!(!proc.is_active());
        let original = [0.42_f64, -0.31_f64];
        let mut frame = original;
        proc.process_frame(&mut frame);
        assert_eq!(frame, original);
    }

    #[test]
    fn produces_decaying_reverb_tail_after_impulse() {
        let mut proc = AudioEffectsProcessor::new();
        proc.configure(44_100, 2);
        proc.apply_config(AudioEffectsConfig {
            reverb_enabled: true,
            reverb_room_size: 0.78,
            reverb_damping: 0.40,
            reverb_wet_mix: 0.50,
            reverb_width: 0.90,
            ..AudioEffectsConfig::default()
        });

        // Envía un pulso corto y luego silencio para observar la cola de reverberación.
        let mut tail_energy = 0.0_f64;
        for i in 0..8_000 {
            let x = if i < 64 { 0.8 } else { 0.0 };
            let mut frame = [x, x];
            proc.process_frame(&mut frame);
            assert!(frame[0].is_finite() && frame[1].is_finite());
            if i >= 1_500 {
                tail_energy += frame[0].abs() + frame[1].abs();
            }
        }

        assert!(
            tail_energy > 1.0,
            "Se esperaba una cola de reverberación audible tras el pulso: tail_energy={tail_energy}"
        );
    }

    #[test]
    fn stereo_width_creates_spatial_decorrelation_between_channels() {
        let mut proc = AudioEffectsProcessor::new();
        proc.configure(44_100, 2);
        proc.apply_config(AudioEffectsConfig {
            reverb_enabled: true,
            reverb_room_size: 0.70,
            reverb_damping: 0.30,
            reverb_wet_mix: 0.65,
            reverb_width: 1.0,
            ..AudioEffectsConfig::default()
        });

        let mut diff_sum = 0.0_f64;
        for i in 0..6_000 {
            let s = ((i as f64) * 0.05).sin() * 0.4;
            let mut frame = [s, s];
            proc.process_frame(&mut frame);
            if i > 1_200 {
                diff_sum += (frame[0] - frame[1]).abs();
            }
        }

        assert!(
            diff_sum > 0.5,
            "Se esperaba decorrelación estéreo entre L y R: diff_sum={diff_sum}"
        );
    }

    #[test]
    fn warmth_lowpass_attenuates_high_frequencies() {
        let mut proc = AudioEffectsProcessor::new();
        proc.configure(48_000, 2);
        proc.apply_config(AudioEffectsConfig {
            reverb_enabled: false,
            slowed_warmth_enabled: true,
            slowed_warmth_amount: 0.90,
            ..AudioEffectsConfig::default()
        });

        // Señal de alta frecuencia (~14 kHz a 48 kHz)
        let mut out_rms = 0.0_f64;
        let mut in_rms = 0.0_f64;
        let frames = 2_048;
        for i in 0..frames {
            let t = i as f64 / 48_000.0;
            let x = (2.0 * std::f64::consts::PI * 14_000.0 * t).sin() * 0.5;
            in_rms += x * x;
            let mut frame = [x, x];
            proc.process_frame(&mut frame);
            out_rms += frame[0] * frame[0];
        }
        let in_level = (in_rms / frames as f64).sqrt();
        let out_level = (out_rms / frames as f64).sqrt();
        assert!(
            out_level < in_level * 0.5,
            "La calidez analógica debe atenuar frecuencias altas: in={in_level}, out={out_level}"
        );
    }
}
