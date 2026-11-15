//! Motor DSP en Rust puro para Crossfeed Binaural Estático en auriculares.
//!
//! Simula la escucha natural de dos monitores de estudio frente al oyente
//! (entre 15° y 45°), reduciendo la fatiga auditiva en mezclas con paneo extremo:
//! 1. Micro-retardo interaural estático (ITD, ~0.18 ms a ~0.38 ms) entre canales cruzados.
//! 2. Filtro paso bajo de sombra craneal (ILD, ~650 Hz a ~1250 Hz) para cruzar
//!    principalmente graves y medios-bajos conservando intactos los agudos estéreo.
//! 3. Compensación de ganancia para mantener constante la presencia y evitar exceso de graves.
//!
//! Sin asignaciones en el hilo de reproducción y con limpieza de subnormales (< 1e-15).

use std::f64::consts::TAU;

/// Línea de retardo circular con interpolación lineal para el camino cruzado binaural.
#[derive(Debug, Clone)]
struct CrossfeedDelayLine {
    buffer: Vec<f32>,
    write_idx: usize,
}

impl CrossfeedDelayLine {
    fn new(capacity: usize) -> Self {
        Self {
            buffer: vec![0.0; capacity.max(8)],
            write_idx: 0,
        }
    }

    fn reset(&mut self) {
        self.buffer.fill(0.0);
        self.write_idx = 0;
    }

    #[inline]
    fn write_and_read(&mut self, input: f64, delay_samples: f64) -> f64 {
        let len = self.buffer.len();
        self.buffer[self.write_idx] = (if input.abs() < 1e-15 { 0.0 } else { input }) as f32;

        let max_delay = (len - 2) as f64;
        let clamped_delay = delay_samples.clamp(0.0, max_delay);
        let int_delay = clamped_delay.floor() as usize;
        let frac = clamped_delay - (int_delay as f64);

        let idx0 = (self.write_idx + len - int_delay) % len;
        let idx1 = (idx0 + len - 1) % len;

        let s0 = self.buffer[idx0] as f64;
        let s1 = self.buffer[idx1] as f64;
        let out = s0 + frac * (s1 - s0);

        self.write_idx += 1;
        if self.write_idx >= len {
            self.write_idx = 0;
        }
        out
    }
}

/// Procesador de Crossfeed Binaural Estático en precisión `f64` para auriculares.
#[derive(Debug)]
pub struct StaticCrossfeedProcessor {
    sample_rate: u32,
    channel_count: usize,
    enabled: bool,
    strength: f64,
    angle: f64,

    delay_samples: f64,
    shadow_alpha: f64,
    direct_gain: f64,
    cross_gain: f64,

    lpf_l_to_r: f64,
    lpf_r_to_l: f64,
    delay_l: CrossfeedDelayLine,
    delay_r: CrossfeedDelayLine,
}

impl StaticCrossfeedProcessor {
    pub fn new() -> Self {
        let cap = Self::calc_capacity(44_100);
        let mut proc = Self {
            sample_rate: 44_100,
            channel_count: 2,
            enabled: false,
            strength: 0.45,
            angle: 0.50,
            delay_samples: 12.0,
            shadow_alpha: 0.12,
            direct_gain: 1.0,
            cross_gain: 0.0,
            lpf_l_to_r: 0.0,
            lpf_r_to_l: 0.0,
            delay_l: CrossfeedDelayLine::new(cap),
            delay_r: CrossfeedDelayLine::new(cap),
        };
        proc.recalculate_coefficients();
        proc
    }

    fn calc_capacity(sample_rate: u32) -> usize {
        ((sample_rate.max(8_000) as f64) * 0.002).ceil() as usize + 8
    }

    pub fn configure(&mut self, sample_rate: u32, channel_count: usize) {
        let safe_rate = sample_rate.max(8_000);
        let safe_channels = channel_count.clamp(1, 8);
        let rate_changed = self.sample_rate != safe_rate;

        self.sample_rate = safe_rate;
        self.channel_count = safe_channels;

        if rate_changed {
            let cap = Self::calc_capacity(safe_rate);
            self.delay_l = CrossfeedDelayLine::new(cap);
            self.delay_r = CrossfeedDelayLine::new(cap);
            self.lpf_l_to_r = 0.0;
            self.lpf_r_to_l = 0.0;
        }
        self.recalculate_coefficients();
    }

    pub fn apply_config(&mut self, enabled: bool, strength: f32, angle: f32) {
        let safe_strength = if strength.is_finite() {
            strength.clamp(0.0, 1.0) as f64
        } else {
            0.45
        };
        let safe_angle = if angle.is_finite() {
            angle.clamp(0.0, 1.0) as f64
        } else {
            0.50
        };

        let was_active = self.is_active();
        self.enabled = enabled && safe_strength > 0.005;
        self.strength = safe_strength;
        self.angle = safe_angle;
        self.recalculate_coefficients();

        if was_active && !self.is_active() {
            self.reset_states();
        }
    }

    pub fn reset_states(&mut self) {
        self.delay_l.reset();
        self.delay_r.reset();
        self.lpf_l_to_r = 0.0;
        self.lpf_r_to_l = 0.0;
    }

    #[inline]
    pub fn is_active(&self) -> bool {
        self.enabled && self.strength > 0.005
    }

    fn recalculate_coefficients(&mut self) {
        let sr = self.sample_rate.max(8_000) as f64;
        // Ángulo virtual 0.0 (15° estrecho) -> 1.0 (45° amplio):
        // Mayor ángulo implica mayor distancia interaural (0.18 ms -> 0.38 ms) y sombra craneal más marcada.
        let itd_seconds = 0.00018 + self.angle * 0.00020;
        self.delay_samples = sr * itd_seconds;

        let cutoff_hz = (1220.0 - self.angle * 540.0).clamp(550.0, sr * 0.40);
        self.shadow_alpha = (1.0 - (-TAU * cutoff_hz / sr).exp()).clamp(0.01, 0.95);

        // Ganancia cruzada moderada (hasta ~0.36) con leve atenuación directa para evitar suma de graves.
        self.cross_gain = self.strength * 0.36;
        self.direct_gain = 1.0 - self.cross_gain * 0.42;
    }

    /// Procesa un frame estéreo/multicanal in-place aplicando crossfeed binaural estático.
    #[inline]
    pub fn process_frame(&mut self, samples: &mut [f64]) {
        if !self.is_active() || samples.len() < 2 {
            return;
        }

        let in_l = samples[0];
        let in_r = samples[1];

        let delayed_l = self.delay_l.write_and_read(in_l, self.delay_samples);
        let delayed_r = self.delay_r.write_and_read(in_r, self.delay_samples);

        let a = self.shadow_alpha;
        self.lpf_l_to_r += a * (delayed_l - self.lpf_l_to_r);
        self.lpf_r_to_l += a * (delayed_r - self.lpf_r_to_l);

        if self.lpf_l_to_r.abs() < 1e-15 {
            self.lpf_l_to_r = 0.0;
        }
        if self.lpf_r_to_l.abs() < 1e-15 {
            self.lpf_r_to_l = 0.0;
        }

        samples[0] = in_l * self.direct_gain + self.lpf_r_to_l * self.cross_gain;
        samples[1] = in_r * self.direct_gain + self.lpf_l_to_r * self.cross_gain;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f64::consts::PI;

    #[test]
    fn bypasses_when_disabled_or_zero_strength() {
        let mut proc = StaticCrossfeedProcessor::new();
        proc.configure(48_000, 2);
        proc.apply_config(false, 0.6, 0.5);
        assert!(!proc.is_active());

        let original = [0.45_f64, -0.35_f64];
        let mut frame = original;
        proc.process_frame(&mut frame);
        assert_eq!(frame, original);

        proc.apply_config(true, 0.0, 0.5);
        assert!(!proc.is_active());
    }

    #[test]
    fn transfers_more_low_frequencies_than_high_frequencies_to_opposite_ear() {
        let mut proc_low = StaticCrossfeedProcessor::new();
        proc_low.configure(48_000, 2);
        proc_low.apply_config(true, 0.85, 0.55);

        let mut low_cross_energy = 0.0_f64;
        for i in 0..4_096 {
            let x = (2.0 * PI * 140.0 * (i as f64) / 48_000.0).sin() * 0.5;
            let mut frame = [x, 0.0];
            proc_low.process_frame(&mut frame);
            if i >= 1_024 {
                low_cross_energy += frame[1] * frame[1];
            }
        }

        let mut proc_high = StaticCrossfeedProcessor::new();
        proc_high.configure(48_000, 2);
        proc_high.apply_config(true, 0.85, 0.55);

        let mut high_cross_energy = 0.0_f64;
        for i in 0..4_096 {
            let x = (2.0 * PI * 8_500.0 * (i as f64) / 48_000.0).sin() * 0.5;
            let mut frame = [x, 0.0];
            proc_high.process_frame(&mut frame);
            if i >= 1_024 {
                high_cross_energy += frame[1] * frame[1];
            }
        }

        assert!(
            low_cross_energy > high_cross_energy * 8.0 && low_cross_energy > 1.0,
            "La sombra craneal debe cruzar graves mucho más que agudos: low={low_cross_energy}, high={high_cross_energy}"
        );
    }

    #[test]
    fn mono_and_reset_remain_finite() {
        let mut proc = StaticCrossfeedProcessor::new();
        proc.configure(44_100, 1);
        proc.apply_config(true, 0.7, 0.5);
        let mut mono = [0.33_f64];
        proc.process_frame(&mut mono);
        assert_eq!(mono[0], 0.33);

        proc.configure(44_100, 2);
        proc.reset_states();
        let mut stereo = [0.5_f64, -0.5_f64];
        proc.process_frame(&mut stereo);
        assert!(stereo[0].is_finite() && stereo[1].is_finite());
    }
}
