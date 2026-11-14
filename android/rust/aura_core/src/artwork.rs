//! Generador procedural de carátulas en Rust mediante `tiny-skia`.
//!
//! Cuando una canción importada no incluye carátula embebida, este módulo renderiza
//! una ilustración cuadrada única de alta calidad a partir del identificador determinista
//! de la pista (título + artista + álbum). No utiliza IA ni red; todo se calcula localmente.

use rand::{Rng, SeedableRng};
use rand_chacha::ChaCha8Rng;
use sha2::{Digest, Sha256};
use tiny_skia::{
    BlendMode, FillRule, GradientStop, LinearGradient, Paint, PathBuilder, Pixmap, Point,
    RadialGradient, Rect, SpreadMode, Stroke, Transform,
};

use crate::palette::AuraPalette;

/// Estilos visuales procedurales que se seleccionan automáticamente según el hash de la canción.
#[derive(Debug, Clone, Copy)]
enum ArtworkStyle {
    AuraOrbs,
    PrismHorizon,
    ConcentricResonance,
    TopographicWaves,
    BauhausGeometry,
}

impl ArtworkStyle {
    fn from_byte(b: u8) -> Self {
        match b % 5 {
            0 => Self::AuraOrbs,
            1 => Self::PrismHorizon,
            2 => Self::ConcentricResonance,
            3 => Self::TopographicWaves,
            _ => Self::BauhausGeometry,
        }
    }
}

/// Calcula la huella digital SHA-256 hexadecimal de una pista para deduplicación y semillas.
pub fn compute_track_fingerprint(
    title: &str,
    artist: &str,
    duration_ms: i64,
    size_bytes: i64,
) -> String {
    let mut hasher = Sha256::new();
    hasher.update(title.trim().to_lowercase().as_bytes());
    hasher.update(b"|");
    hasher.update(artist.trim().to_lowercase().as_bytes());
    hasher.update(b"|");
    hasher.update(duration_ms.to_le_bytes());
    hasher.update(b"|");
    hasher.update(size_bytes.to_le_bytes());
    let digest = hasher.finalize();
    digest.iter().map(|b| format!("{b:02x}")).collect()
}

/// Genera un buffer RGBA8888 de tamaño `width * height * 4` con una carátula procedural cuadrada.
pub fn render_procedural_cover(
    seed_key: &str,
    width: u32,
    height: u32,
    accent_argb: Option<u32>,
) -> Option<Vec<u8>> {
    let w = width.clamp(64, 1024);
    let h = height.clamp(64, 1024);
    let mut pixmap = Pixmap::new(w, h)?;

    // Derivamos semilla criptográfica de 256 bits para reproducibilidad exacta.
    let mut hasher = Sha256::new();
    hasher.update(seed_key.as_bytes());
    let seed: [u8; 32] = hasher.finalize().into();

    let style = ArtworkStyle::from_byte(seed[0]);
    let mut rng = ChaCha8Rng::from_seed(seed);
    let palette = AuraPalette::from_rng(&mut rng, accent_argb);

    let wf = w as f32;
    let hf = h as f32;

    // 1. Fondo base con gradiente lineal profundo para dar volumen inmediato.
    draw_base_gradient(&mut pixmap, wf, hf, &palette, &mut rng);

    // 2. Composición vectorial según el estilo elegido para esta canción.
    match style {
        ArtworkStyle::AuraOrbs => draw_aura_orbs(&mut pixmap, wf, hf, &palette, &mut rng),
        ArtworkStyle::PrismHorizon => draw_prism_horizon(&mut pixmap, wf, hf, &palette, &mut rng),
        ArtworkStyle::ConcentricResonance => {
            draw_concentric_resonance(&mut pixmap, wf, hf, &palette, &mut rng)
        }
        ArtworkStyle::TopographicWaves => {
            draw_topographic_waves(&mut pixmap, wf, hf, &palette, &mut rng)
        }
        ArtworkStyle::BauhausGeometry => {
            draw_bauhaus_geometry(&mut pixmap, wf, hf, &palette, &mut rng)
        }
    }

    // 3. Viñeta suave y micro-grano determinista para eliminar bandas de color en pantallas OLED.
    apply_subtle_vignette_and_grain(&mut pixmap, w, h, seed[31]);

    Some(pixmap.take())
}

fn draw_base_gradient<R: Rng>(
    pixmap: &mut Pixmap,
    w: f32,
    h: f32,
    palette: &AuraPalette,
    rng: &mut R,
) {
    let angle = rng.random_range(0.0_f32..std::f32::consts::TAU);
    let dx = angle.cos() * w * 0.5;
    let dy = angle.sin() * h * 0.5;
    let cx = w * 0.5;
    let cy = h * 0.5;

    let stops = vec![
        GradientStop::new(0.0, AuraPalette::color_with_alpha(palette.bg_deep, 255)),
        GradientStop::new(0.55, AuraPalette::color_with_alpha(palette.bg_mid, 255)),
        GradientStop::new(1.0, AuraPalette::color_with_alpha(palette.bg_deep, 255)),
    ];

    if let Some(shader) = LinearGradient::new(
        Point::from_xy(cx - dx, cy - dy),
        Point::from_xy(cx + dx, cy + dy),
        stops,
        SpreadMode::Pad,
        Transform::identity(),
    ) && let Some(rect) = Rect::from_xywh(0.0, 0.0, w, h)
    {
        let paint = Paint {
            shader,
            anti_alias: true,
            force_hq_pipeline: true,
            ..Paint::default()
        };
        pixmap.fill_rect(rect, &paint, Transform::identity(), None);
    }
}

fn draw_radial_glow(
    pixmap: &mut Pixmap,
    cx: f32,
    cy: f32,
    radius: f32,
    rgb: [u8; 3],
    center_alpha: u8,
    blend_mode: BlendMode,
) {
    let stops = vec![
        GradientStop::new(0.0, AuraPalette::color_with_alpha(rgb, center_alpha)),
        GradientStop::new(0.45, AuraPalette::color_with_alpha(rgb, center_alpha / 2)),
        GradientStop::new(1.0, AuraPalette::color_with_alpha(rgb, 0)),
    ];

    if let Some(shader) = RadialGradient::new(
        Point::from_xy(cx, cy),
        0.0,
        Point::from_xy(cx, cy),
        radius,
        stops,
        SpreadMode::Pad,
        Transform::identity(),
    ) && let Some(path) = PathBuilder::from_circle(cx, cy, radius)
    {
        let paint = Paint {
            shader,
            blend_mode,
            anti_alias: true,
            force_hq_pipeline: true,
            ..Paint::default()
        };
        pixmap.fill_path(&path, &paint, FillRule::Winding, Transform::identity(), None);
    }
}

fn draw_aura_orbs<R: Rng>(
    pixmap: &mut Pixmap,
    w: f32,
    h: f32,
    palette: &AuraPalette,
    rng: &mut R,
) {
    let colors = [palette.glow_primary, palette.glow_accent, palette.highlight];
    for i in 0..4 {
        let cx = rng.random_range(0.15 * w..0.85 * w);
        let cy = rng.random_range(0.15 * h..0.85 * h);
        let radius = rng.random_range(0.30 * w..0.62 * w);
        let color = colors[i % colors.len()];
        let alpha = rng.random_range(130..205);
        draw_radial_glow(pixmap, cx, cy, radius, color, alpha, BlendMode::Screen);
    }

    // Anillo geométrico sutil descentrado que da identidad visual de carátula.
    let ring_cx = rng.random_range(0.32 * w..0.68 * w);
    let ring_cy = rng.random_range(0.32 * h..0.68 * h);
    let ring_r = rng.random_range(0.22 * w..0.36 * w);
    if let Some(ring_path) = PathBuilder::from_circle(ring_cx, ring_cy, ring_r) {
        let mut paint = Paint {
            anti_alias: true,
            blend_mode: BlendMode::Overlay,
            force_hq_pipeline: true,
            ..Paint::default()
        };
        paint.set_color(AuraPalette::color_with_alpha(palette.highlight, 145));
        let stroke = Stroke {
            width: w * 0.012,
            ..Stroke::default()
        };
        pixmap.stroke_path(&ring_path, &paint, &stroke, Transform::identity(), None);
    }
}

fn draw_prism_horizon<R: Rng>(
    pixmap: &mut Pixmap,
    w: f32,
    h: f32,
    palette: &AuraPalette,
    rng: &mut R,
) {
    draw_radial_glow(
        pixmap,
        w * rng.random_range(0.25..0.75),
        h * rng.random_range(0.25..0.75),
        w * 0.65,
        palette.glow_primary,
        170,
        BlendMode::Screen,
    );

    let colors = [
        palette.glow_primary,
        palette.glow_accent,
        palette.highlight,
        palette.bg_mid,
    ];
    for i in 0..5 {
        let mut pb = PathBuilder::new();
        let x1 = rng.random_range(-0.15 * w..0.55 * w);
        let y1 = rng.random_range(-0.15 * h..0.85 * h);
        let x2 = x1 + rng.random_range(0.35 * w..0.95 * w);
        let y2 = rng.random_range(-0.10 * h..1.10 * h);
        let x3 = rng.random_range(0.10 * w..1.15 * w);
        let y3 = y1 + rng.random_range(0.25 * h..0.90 * h);

        pb.move_to(x1, y1);
        pb.line_to(x2, y2);
        pb.line_to(x3, y3);
        pb.close();

        if let Some(path) = pb.finish() {
            let stops = vec![
                GradientStop::new(0.0, AuraPalette::color_with_alpha(colors[i % colors.len()], 155)),
                GradientStop::new(1.0, AuraPalette::color_with_alpha(colors[(i + 1) % colors.len()], 25)),
            ];
            if let Some(shader) = LinearGradient::new(
                Point::from_xy(x1, y1),
                Point::from_xy(x3, y3),
                stops,
                SpreadMode::Pad,
                Transform::identity(),
            ) {
                let paint = Paint {
                    shader,
                    blend_mode: if i % 2 == 0 {
                        BlendMode::Screen
                    } else {
                        BlendMode::SoftLight
                    },
                    anti_alias: true,
                    force_hq_pipeline: true,
                    ..Paint::default()
                };
                pixmap.fill_path(&path, &paint, FillRule::Winding, Transform::identity(), None);
            }
        }
    }
}

fn draw_concentric_resonance<R: Rng>(
    pixmap: &mut Pixmap,
    w: f32,
    h: f32,
    palette: &AuraPalette,
    rng: &mut R,
) {
    let cx = w * rng.random_range(0.45..0.55);
    let cy = h * rng.random_range(0.45..0.55);

    draw_radial_glow(pixmap, cx, cy, w * 0.62, palette.glow_accent, 190, BlendMode::Screen);
    draw_radial_glow(pixmap, cx, cy, w * 0.34, palette.glow_primary, 150, BlendMode::Screen);

    let ring_count = rng.random_range(5..8);
    for i in 0..ring_count {
        let t = i as f32 / ring_count as f32;
        let radius = w * (0.11 + t * 0.38);
        let offset_x = rng.random_range(-0.008 * w..0.008 * w);
        let offset_y = rng.random_range(-0.008 * h..0.008 * h);
        if let Some(path) = PathBuilder::from_circle(cx + offset_x, cy + offset_y, radius) {
            let color = if i % 2 == 0 {
                palette.highlight
            } else {
                palette.glow_primary
            };
            let mut paint = Paint {
                anti_alias: true,
                blend_mode: BlendMode::Screen,
                force_hq_pipeline: true,
                ..Paint::default()
            };
            let alpha = ((1.0 - t * 0.55) * 200.0) as u8;
            paint.set_color(AuraPalette::color_with_alpha(color, alpha));
            let stroke = Stroke {
                width: w * rng.random_range(0.010..0.020),
                ..Stroke::default()
            };
            pixmap.stroke_path(&path, &paint, &stroke, Transform::identity(), None);
        }
    }

    // Disco central luminoso nítido.
    if let Some(core) = PathBuilder::from_circle(cx, cy, w * 0.068) {
        let mut paint = Paint {
            anti_alias: true,
            blend_mode: BlendMode::Screen,
            force_hq_pipeline: true,
            ..Paint::default()
        };
        paint.set_color(AuraPalette::color_with_alpha(palette.highlight, 235));
        pixmap.fill_path(&core, &paint, FillRule::Winding, Transform::identity(), None);
    }
}

fn draw_topographic_waves<R: Rng>(
    pixmap: &mut Pixmap,
    w: f32,
    h: f32,
    palette: &AuraPalette,
    rng: &mut R,
) {
    draw_radial_glow(
        pixmap,
        w * 0.5,
        h * 0.3,
        w * 0.65,
        palette.glow_primary,
        150,
        BlendMode::Screen,
    );

    let wave_count = 7;
    let base_amp = h * rng.random_range(0.06..0.13);
    for i in 0..wave_count {
        let t = i as f32 / wave_count as f32;
        let y_base = h * (0.20 + t * 0.72);
        let phase = rng.random_range(0.0..std::f32::consts::TAU);
        let mut pb = PathBuilder::new();
        pb.move_to(0.0, h);
        pb.line_to(0.0, y_base);

        let steps = 16;
        for s in 1..=steps {
            let px = (s as f32 / steps as f32) * w;
            let wave = (px / w * std::f32::consts::TAU * 1.5 + phase).sin() * base_amp;
            let wave2 = (px / w * std::f32::consts::TAU * 3.0 - phase * 0.5).cos() * (base_amp * 0.35);
            pb.line_to(px, y_base + wave + wave2);
        }
        pb.line_to(w, h);
        pb.close();

        if let Some(path) = pb.finish() {
            let color = match i % 3 {
                0 => palette.glow_primary,
                1 => palette.glow_accent,
                _ => palette.bg_mid,
            };
            let stops = vec![
                GradientStop::new(0.0, AuraPalette::color_with_alpha(color, 130)),
                GradientStop::new(1.0, AuraPalette::color_with_alpha(palette.bg_deep, 175)),
            ];
            if let Some(shader) = LinearGradient::new(
                Point::from_xy(0.0, y_base - base_amp),
                Point::from_xy(0.0, h),
                stops,
                SpreadMode::Pad,
                Transform::identity(),
            ) {
                let paint = Paint {
                    shader,
                    blend_mode: BlendMode::Screen,
                    anti_alias: true,
                    force_hq_pipeline: true,
                    ..Paint::default()
                };
                pixmap.fill_path(&path, &paint, FillRule::Winding, Transform::identity(), None);
            }
        }
    }
}

fn draw_bauhaus_geometry<R: Rng>(
    pixmap: &mut Pixmap,
    w: f32,
    h: f32,
    palette: &AuraPalette,
    rng: &mut R,
) {
    draw_radial_glow(
        pixmap,
        w * 0.72,
        h * 0.28,
        w * 0.55,
        palette.glow_accent,
        155,
        BlendMode::Screen,
    );

    // Bloques equilibrados asimétricos con círculos y rectángulos redondeados.
    let shapes = [palette.glow_primary, palette.glow_accent, palette.highlight];
    for i in 0..4 {
        let rx = rng.random_range(0.08 * w..0.55 * w);
        let ry = rng.random_range(0.08 * h..0.55 * h);
        let rw = rng.random_range(0.22 * w..0.46 * w);
        let rh = rng.random_range(0.22 * h..0.46 * h);
        if let Some(rect) = Rect::from_xywh(rx, ry, rw, rh) {
            let mut paint = Paint {
                anti_alias: true,
                blend_mode: if i % 2 == 0 {
                    BlendMode::Screen
                } else {
                    BlendMode::Overlay
                },
                force_hq_pipeline: true,
                ..Paint::default()
            };
            paint.set_color(AuraPalette::color_with_alpha(shapes[i % shapes.len()], 135));
            pixmap.fill_rect(rect, &paint, Transform::identity(), None);
        }
    }

    let c_x = w * rng.random_range(0.35..0.65);
    let c_y = h * rng.random_range(0.35..0.65);
    if let Some(circle) = PathBuilder::from_circle(c_x, c_y, w * 0.24) {
        let stops = vec![
            GradientStop::new(0.0, AuraPalette::color_with_alpha(palette.highlight, 210)),
            GradientStop::new(1.0, AuraPalette::color_with_alpha(palette.glow_primary, 95)),
        ];
        if let Some(shader) = LinearGradient::new(
            Point::from_xy(c_x - w * 0.24, c_y - h * 0.24),
            Point::from_xy(c_x + w * 0.24, c_y + h * 0.24),
            stops,
            SpreadMode::Pad,
            Transform::identity(),
        ) {
            let paint = Paint {
                shader,
                blend_mode: BlendMode::Screen,
                anti_alias: true,
                force_hq_pipeline: true,
                ..Paint::default()
            };
            pixmap.fill_path(&circle, &paint, FillRule::Winding, Transform::identity(), None);
        }
    }
}

fn apply_subtle_vignette_and_grain(pixmap: &mut Pixmap, width: u32, height: u32, seed_byte: u8) {
    let w = width as f32;
    let h = height as f32;
    let half_w = w * 0.5;
    let half_h = h * 0.5;
    let max_dist_sq = half_w * half_w + half_h * half_h;

    let data = pixmap.data_mut();
    let mut state: u32 = (seed_byte as u32).wrapping_mul(0x9E37_79B9) ^ 0xA5A5_5A5A;

    for y in 0..height {
        let dy = y as f32 - half_h;
        for x in 0..width {
            let dx = x as f32 - half_w;
            let dist_ratio = (dx * dx + dy * dy) / max_dist_sq;
            let vignette = (1.0 - dist_ratio * 0.14).clamp(0.86, 1.0);

            // PRNG rápido Xorshift32 para dithering sutil sin costo.
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            let grain = ((state & 0x07) as i16) - 3;

            let idx = ((y * width + x) * 4) as usize;
            for c in 0..3 {
                let v = (data[idx + c] as f32 * vignette) as i16 + grain;
                data[idx + c] = v.clamp(0, 255) as u8;
            }
            // Canal alfa siempre opaco para compatibilidad directa con Bitmap.Config.ARGB_8888.
            data[idx + 3] = 255;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn procedural_cover_renders_exact_rgba_dimensions_and_is_deterministic() {
        let a = render_procedural_cover("Midnight City|Artist|Album", 256, 256, Some(0xFF8B5CF6))
            .expect("cover should render");
        let b = render_procedural_cover("Midnight City|Artist|Album", 256, 256, Some(0xFF8B5CF6))
            .expect("cover should render");

        assert_eq!(a.len(), 256 * 256 * 4);
        assert_eq!(a, b);

        // Verifica que el canal alfa sea 255 en todos los píxeles y exista variación cromática real.
        let mut min_r = 255u8;
        let mut max_r = 0u8;
        for chunk in a.chunks_exact(4) {
            assert_eq!(chunk[3], 255);
            min_r = min_r.min(chunk[0]);
            max_r = max_r.max(chunk[0]);
        }
        assert!(max_r - min_r > 20, "Cover should not be flat monochrome");
    }

    #[test]
    fn different_tracks_produce_different_covers() {
        let a = render_procedural_cover("Song One|Artist A|Album 1", 128, 128, None).unwrap();
        let b = render_procedural_cover("Song Two|Artist B|Album 2", 128, 128, None).unwrap();
        assert_ne!(a, b);
    }

    #[test]
    fn all_artwork_styles_render_without_panics() {
        for i in 0..25 {
            let key = format!("Track {i}|Artist {i}|Album {i}");
            let accent = if i % 2 == 0 { Some(0xFFA78BFA) } else { None };
            let bytes = render_procedural_cover(&key, 128, 128, accent)
                .expect("every seed should render cleanly");
            assert_eq!(bytes.len(), 128 * 128 * 4);
        }
    }

    #[test]
    fn fingerprint_is_stable_sha256_hex() {
        let fp1 = compute_track_fingerprint("  Aura ", "Artist", 180_000, 4_200_000);
        let fp2 = compute_track_fingerprint("aura", "artist", 180_000, 4_200_000);
        assert_eq!(fp1.len(), 64);
        assert_eq!(fp1, fp2);
    }
}
