//! Módulo de teoría de color armónica en Rust para carátulas procedurales.
//!
//! Convierte hashes deterministas en paletas cromáticas ricas y equilibradas
//! (primario profundo, secundario luminoso, acento cálido/frío y sombra de contraste),
//! evitando tonos apagados o combinaciones genéricas.

use rand::Rng;
use tiny_skia::Color;

/// Paleta cromática de 4 colores usada para componer cada carátula procedural.
#[derive(Debug, Clone, Copy)]
pub struct AuraPalette {
    pub bg_deep: [u8; 3],
    pub bg_mid: [u8; 3],
    pub glow_primary: [u8; 3],
    pub glow_accent: [u8; 3],
    pub highlight: [u8; 3],
}

impl AuraPalette {
    /// Genera una paleta armónica a partir de un generador pseudoaleatorio determinista
    /// y un color de acento opcional (ARGB) proveniente del tema del usuario.
    pub fn from_rng<R: Rng>(rng: &mut R, accent_argb: Option<u32>) -> Self {
        // Si hay acento del tema, desplazamos ligeramente su matiz para mantener variedad
        // entre canciones sin perder cohesión visual.
        let base_hue = match accent_argb {
            Some(argb) if (argb >> 24) > 0 => {
                let r = ((argb >> 16) & 0xFF) as f32 / 255.0;
                let g = ((argb >> 8) & 0xFF) as f32 / 255.0;
                let b = (argb & 0xFF) as f32 / 255.0;
                let extracted = rgb_to_hue(r, g, b);
                let jitter = rng.random_range(-55.0_f32..55.0_f32);
                (extracted + jitter).rem_euclid(360.0)
            }
            _ => rng.random_range(0.0_f32..360.0_f32),
        };

        // Esquema análogo / tríada dividida para profundidad visual.
        let secondary_hue = (base_hue + rng.random_range(28.0_f32..68.0_f32)).rem_euclid(360.0);
        let accent_hue = (base_hue + rng.random_range(135.0_f32..205.0_f32)).rem_euclid(360.0);

        let bg_deep = hsl_to_rgb(
            base_hue,
            rng.random_range(0.45_f32..0.72_f32),
            rng.random_range(0.07_f32..0.13_f32),
        );
        let bg_mid = hsl_to_rgb(
            secondary_hue,
            rng.random_range(0.50_f32..0.78_f32),
            rng.random_range(0.16_f32..0.25_f32),
        );
        let glow_primary = hsl_to_rgb(
            base_hue,
            rng.random_range(0.72_f32..0.94_f32),
            rng.random_range(0.46_f32..0.60_f32),
        );
        let glow_accent = hsl_to_rgb(
            accent_hue,
            rng.random_range(0.75_f32..0.95_f32),
            rng.random_range(0.52_f32..0.66_f32),
        );
        let highlight = hsl_to_rgb(
            secondary_hue,
            rng.random_range(0.80_f32..0.98_f32),
            rng.random_range(0.72_f32..0.86_f32),
        );

        Self {
            bg_deep,
            bg_mid,
            glow_primary,
            glow_accent,
            highlight,
        }
    }

    pub fn color_with_alpha(rgb: [u8; 3], alpha: u8) -> Color {
        Color::from_rgba8(rgb[0], rgb[1], rgb[2], alpha)
    }
}

/// Convierte HSL (matiz 0..360, saturación 0..1, luminosidad 0..1) a RGB [0..255].
pub fn hsl_to_rgb(h: f32, s: f32, l: f32) -> [u8; 3] {
    let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
    let h_prime = h / 60.0;
    let x = c * (1.0 - ((h_prime % 2.0) - 1.0).abs());
    let (r1, g1, b1) = if h_prime < 1.0 {
        (c, x, 0.0)
    } else if h_prime < 2.0 {
        (x, c, 0.0)
    } else if h_prime < 3.0 {
        (0.0, c, x)
    } else if h_prime < 4.0 {
        (0.0, x, c)
    } else if h_prime < 5.0 {
        (x, 0.0, c)
    } else {
        (c, 0.0, x)
    };
    let m = l - c / 2.0;
    [
        ((r1 + m).clamp(0.0, 1.0) * 255.0).round() as u8,
        ((g1 + m).clamp(0.0, 1.0) * 255.0).round() as u8,
        ((b1 + m).clamp(0.0, 1.0) * 255.0).round() as u8,
    ]
}

fn rgb_to_hue(r: f32, g: f32, b: f32) -> f32 {
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let delta = max - min;
    if delta < 1e-5 {
        return 260.0;
    }
    let hue = if (max - r).abs() < 1e-5 {
        60.0 * (((g - b) / delta).rem_euclid(6.0))
    } else if (max - g).abs() < 1e-5 {
        60.0 * (((b - r) / delta) + 2.0)
    } else {
        60.0 * (((r - g) / delta) + 4.0)
    };
    hue.rem_euclid(360.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rand::SeedableRng;
    use rand_chacha::ChaCha8Rng;

    #[test]
    fn palette_generation_is_deterministic() {
        let mut rng_a = ChaCha8Rng::from_seed([7u8; 32]);
        let mut rng_b = ChaCha8Rng::from_seed([7u8; 32]);
        let p1 = AuraPalette::from_rng(&mut rng_a, Some(0xFF7C4DFF));
        let p2 = AuraPalette::from_rng(&mut rng_b, Some(0xFF7C4DFF));
        assert_eq!(p1.bg_deep, p2.bg_deep);
        assert_eq!(p1.glow_accent, p2.glow_accent);
    }
}
