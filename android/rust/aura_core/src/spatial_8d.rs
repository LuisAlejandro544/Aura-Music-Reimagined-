//! Motor DSP en Rust puro para Audio Espacial 8D psicoacústico en tiempo real.
//!
//! Combina cuatro técnicas complementarias pensadas especialmente para audífonos:
//! 1. Enfoque estéreo controlado (cross-feed) para no perder instrumentos paneados a un extremo.
//! 2. Ancla de graves centrada (~135 Hz) para conservar fuerza y cuerpo en el bajo.
//! 3. Retardo interaural de tiempo (ITD, hasta ~0.65 ms con interpolación lineal) que simula
//!    la diferencia de llegada entre ambos oídos al girar la fuente 360°.
//! 4. Sombra de la cabeza (ILD + filtro paso bajo dinámico de 1 polo) para diferenciar
//!    el oído cercano del lejano y dar profundidad delante/detrás.
//!
//! Sin asignaciones en el hilo de reproducción y con protección contra subnormales (< 1e-15),
//! apto tanto para arquitecturas de 32 bits (`armeabi-v7a`) como de 64 bits (`arm64-v8a`).

use std::f64::consts::{PI, TAU};

const MAX_ITD_SECONDS: f64 = 0.00065;
const MIN_ORBIT_HZ: f64 = 0.05;
const MAX_ORBIT_HZ: f64 = 0.35;
const BASS_ANCHOR_HZ: f64 = 135.0;

/// Línea de retardo circular con interpolación lineal para micro-retardos ITD sin clicks.
#[derive(Debug, Clone)]
struct FractionalDelayLine {
    buffer: Vec<f32>,
    write_idx: usize,
}

impl FractionalDelayLine {
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

/// Procesador espacial 8D continuo por muestras en precisión `f64`.
#[derive(Debug)]
pub struct Spatial8dProcessor {
    sample_rate: u32,
    channel_count: usize,
    enabled: bool,
    speed: f64,
    depth: f64,

    phase_rad: f64,
    phase_step: f64,
    max_itd_samples: f64,
    bass_alpha: f64,

    bass_l_state: f64,
    bass_r_state: f64,
    shadow_l_state: f64,
    shadow_r_state: f64,
    delay_left: FractionalDelayLine,
    delay_right: FractionalDelayLine,
}

impl Spatial8dProcessor {
    pub fn new() -> Self {
        let capacity = Self::calc_delay_capacity(44_100);
        let mut proc = Self {
            sample_rate: 44_100,
            channel_count: 2,
            enabled: false,
            speed: 0.38,
            depth: 0.80,
            phase_rad: 0.0,
            phase_step: 0.0,
            max_itd_samples: 44_100.0 * MAX_ITD_SECONDS,
            bass_alpha: 0.02,
            bass_l_state: 0.0,
            bass_r_state: 0.0,
            shadow_l_state: 0.0,
            shadow_r_state: 0.0,
            delay_left: FractionalDelayLine::new(capacity),
            delay_right: FractionalDelayLine::new(capacity),
        };
        proc.recalculate_coefficients();
        proc
    }

    fn calc_delay_capacity(sample_rate: u32) -> usize {
        ((sample_rate.max(8_000) as f64) * 0.0025).ceil() as usize + 8
    }

    pub fn configure(&mut self, sample_rate: u32, channel_count: usize) {
        let safe_rate = sample_rate.max(8_000);
        let safe_channels = channel_count.clamp(1, 8);
        let rate_changed = self.sample_rate != safe_rate;

        self.sample_rate = safe_rate;
        self.channel_count = safe_channels;

        if rate_changed {
            let cap = Self::calc_delay_capacity(safe_rate);
            self.delay_left = FractionalDelayLine::new(cap);
            self.delay_right = FractionalDelayLine::new(cap);
            self.bass_l_state = 0.0;
            self.bass_r_state = 0.0;
            self.shadow_l_state = 0.0;
            self.shadow_r_state = 0.0;
        }
        self.recalculate_coefficients();
    }

    pub fn apply_config(&mut self, enabled: bool, speed: f32, depth: f32) {
        let safe_speed = if speed.is_finite() {
            speed.clamp(0.0, 1.0) as f64
        } else {
            0.38
        };
        let safe_depth = if depth.is_finite() {
            depth.clamp(0.0, 1.0) as f64
        } else {
            0.80
        };

        let was_active = self.is_active();
        self.enabled = enabled && safe_depth > 0.005;
        self.speed = safe_speed;
        self.depth = safe_depth;
        self.recalculate_coefficients();

        // Al desactivar se limpian estados para evitar colas residuales si se reactiva más tarde.
        if was_active && !self.is_active() {
            self.reset_states();
        }
    }

    pub fn reset_states(&mut self) {
        self.delay_left.reset();
        self.delay_right.reset();
        self.bass_l_state = 0.0;
        self.bass_r_state = 0.0;
        self.shadow_l_state = 0.0;
        self.shadow_r_state = 0.0;
        self.phase_rad = 0.0;
    }

    #[inline]
    pub fn is_active(&self) -> bool {
        self.enabled && self.depth > 0.005
    }

    fn recalculate_coefficients(&mut self) {
        let sr = self.sample_rate.max(8_000) as f64;
        let orbit_hz = MIN_ORBIT_HZ + self.speed * (MAX_ORBIT_HZ - MIN_ORBIT_HZ);
        self.phase_step = TAU * orbit_hz / sr;
        self.max_itd_samples = sr * MAX_ITD_SECONDS;
        self.bass_alpha = (1.0 - (-TAU * BASS_ANCHOR_HZ / sr).exp()).clamp(0.002, 0.25);
    }

    /// Procesa un frame multicanal in-place aplicando la rotación psicoacústica 8D.
    #[inline]
    pub fn process_frame(&mut self, samples: &mut [f64]) {
        if !self.is_active() || samples.is_empty() {
            return;
        }

        // En salida mono mantenemos bypass limpio porque no existe separación interaural L/R.
        if samples.len() < 2 {
            self.advance_phase();
            return;
        }

        let in_l = samples[0];
        let in_r = samples[1];

        // 1. Separación suave de graves (<135 Hz) para mantener el bajo centrado y con pegada.
        let ba = self.bass_alpha;
        self.bass_l_state += ba * (in_l - self.bass_l_state);
        self.bass_r_state += ba * (in_r - self.bass_r_state);
        if self.bass_l_state.abs() < 1e-15 {
            self.bass_l_state = 0.0;
        }
        if self.bass_r_state.abs() < 1e-15 {
            self.bass_r_state = 0.0;
        }
        let bass_center = (self.bass_l_state + self.bass_r_state) * 0.5;
        let high_l = in_l - self.bass_l_state;
        let high_r = in_r - self.bass_r_state;

        // 2. Cross-feed controlado para evitar perder instrumentos grabados solo en L o solo en R.
        let high_mid = (high_l + high_r) * 0.5;
        let focus = 0.58 * self.depth;
        let focused_l = high_l + focus * (high_mid - high_l);
        let focused_r = high_r + focus * (high_mid - high_r);

        // 3. Coordenadas polares de órbita continua (-1 = izquierda, +1 = derecha; +1 = frente, -1 = detrás).
        let azimuth = self.phase_rad.sin();
        let front_back = self.phase_rad.cos();
        self.advance_phase();

        // 4. Ganancias equal-power suavizadas por profundidad + ligera atenuación trasera.
        let pan_norm = (azimuth * self.depth * 0.88).clamp(-0.92, 0.92);
        let angle = (pan_norm + 1.0) * 0.25 * PI; // [0, pi/2]
        let rear_factor = if front_back < 0.0 {
            1.0 + front_back * 0.14 * self.depth
        } else {
            1.0
        };
        let gain_l = angle.cos() * rear_factor * 1.18;
        let gain_r = angle.sin() * rear_factor * 1.18;

        // 5. ITD (Interaural Time Difference): el oído opuesto recibe el audio hasta ~0.65 ms después.
        let delay_l = if azimuth > 0.0 {
            azimuth * self.depth * self.max_itd_samples
        } else {
            0.0
        };
        let delay_r = if azimuth < 0.0 {
            (-azimuth) * self.depth * self.max_itd_samples
        } else {
            0.0
        };

        let delayed_l = self.delay_left.write_and_read(focused_l, delay_l);
        let delayed_r = self.delay_right.write_and_read(focused_r, delay_r);

        // 6. Sombra de la cabeza (Head Shadow LPF): el oído lejano y la zona trasera pierden brillo agudo.
        let sr = self.sample_rate as f64;
        let rear_occ = ((1.0 - front_back) * 0.5) * 0.25;
        let occ_l = ((azimuth.max(0.0) * 0.78 + rear_occ) * self.depth).clamp(0.0, 1.0);
        let occ_r = (((-azimuth).max(0.0) * 0.78 + rear_occ) * self.depth).clamp(0.0, 1.0);

        let alpha_l = Self::shadow_alpha(sr, occ_l);
        let alpha_r = Self::shadow_alpha(sr, occ_r);

        self.shadow_l_state += alpha_l * (delayed_l - self.shadow_l_state);
        self.shadow_r_state += alpha_r * (delayed_r - self.shadow_r_state);
        if self.shadow_l_state.abs() < 1e-15 {
            self.shadow_l_state = 0.0;
        }
        if self.shadow_r_state.abs() < 1e-15 {
            self.shadow_r_state = 0.0;
        }

        let wet_l = bass_center + self.shadow_l_state * gain_l;
        let wet_r = bass_center + self.shadow_r_state * gain_r;

        // Mezcla gradual según profundidad para conservar nitidez sin saltos de nivel.
        samples[0] = in_l + self.depth * (wet_l - in_l);
        samples[1] = in_r + self.depth * (wet_r - in_r);
    }

    #[inline]
    fn advance_phase(&mut self) {
        self.phase_rad += self.phase_step;
        if self.phase_rad >= TAU {
            self.phase_rad -= TAU;
        }
    }

    #[inline]
    fn shadow_alpha(sample_rate: f64, occlusion: f64) -> f64 {
        if occlusion <= 0.005 {
            return 1.0;
        }
        let cutoff_hz = (17_500.0 - occlusion * 14_100.0).clamp(2_800.0, sample_rate * 0.45);
        (1.0 - (-TAU * cutoff_hz / sample_rate).exp()).clamp(0.05, 1.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bypasses_when_disabled_or_zero_depth() {
        let mut proc = Spatial8dProcessor::new();
        proc.configure(48_000, 2);
        proc.apply_config(false, 0.5, 0.9);
        assert!(!proc.is_active());

        let original = [0.35_f64, -0.25_f64];
        let mut frame = original;
        proc.process_frame(&mut frame);
        assert_eq!(frame, original);

        proc.apply_config(true, 0.5, 0.0);
        assert!(!proc.is_active());
    }

    #[test]
    fn rotates_energy_between_left_and_right_without_losing_asymmetric_instrument() {
        let mut proc = Spatial8dProcessor::new();
        proc.configure(44_100, 2);
        proc.apply_config(true, 1.0, 0.90); // 0.35 Hz -> ~2.85 s por órbita completa

        // Entrada totalmente asimétrica (guitarra solo en L, R en silencio) para comprobar cross-feed.
        let quarter_orbit_samples = (44_100.0 * 0.72) as usize;
        let mut energy_l_q1 = 0.0_f64;
        let mut energy_r_q1 = 0.0_f64;
        let mut energy_l_q3 = 0.0_f64;
        let mut energy_r_q3 = 0.0_f64;

        for i in 0..(quarter_orbit_samples * 4) {
            let tone = (2.0 * PI * 880.0 * (i as f64) / 44_100.0).sin() * 0.4;
            let mut frame = [tone, 0.0];
            proc.process_frame(&mut frame);
            assert!(frame[0].is_finite() && frame[1].is_finite());

            // Primer cuarto: azimuth > 0 -> fuente se mueve hacia R
            if i > 10_000 && i < quarter_orbit_samples {
                energy_l_q1 += frame[0] * frame[0];
                energy_r_q1 += frame[1] * frame[1];
            }
            // Tercer cuarto: azimuth < 0 -> fuente se mueve hacia L
            if i > quarter_orbit_samples * 2 + 10_000 && i < quarter_orbit_samples * 3 {
                energy_l_q3 += frame[0] * frame[0];
                energy_r_q3 += frame[1] * frame[1];
            }
        }

        assert!(
            energy_r_q1 > energy_l_q1 * 1.3 && energy_r_q1 > 1.0,
            "Cuando la órbita gira a la derecha, R debe dominar sin perder el instrumento de L: L={energy_l_q1}, R={energy_r_q1}"
        );
        assert!(
            energy_l_q3 > energy_r_q3 * 1.3 && energy_l_q3 > 1.0,
            "Cuando la órbita gira a la izquierda, L debe dominar: L={energy_l_q3}, R={energy_r_q3}"
        );
    }

    #[test]
    fn mono_and_reset_remain_finite() {
        let mut proc = Spatial8dProcessor::new();
        proc.configure(48_000, 1);
        proc.apply_config(true, 0.6, 0.85);
        let mut mono = [0.25_f64];
        proc.process_frame(&mut mono);
        assert_eq!(mono[0], 0.25);

        proc.configure(48_000, 2);
        proc.reset_states();
        let mut stereo = [0.4_f64, -0.4_f64];
        proc.process_frame(&mut stereo);
        assert!(stereo[0].is_finite() && stereo[1].is_finite());
    }
}
