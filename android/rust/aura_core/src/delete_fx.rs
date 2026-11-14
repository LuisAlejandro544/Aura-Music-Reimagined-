//! Motor de animaciones procedurales de eliminación en Rust puro para Aura Music.
//!
//! Procesa buffers RGBA8888 (premultiplicados, estilo `Bitmap.Config.ARGB_8888`) in-place
//! sin copias adicionales para renderizar 3 efectos creativos seleccionados aleatoriamente:
//! - `0` (**Cenizas**): desintegración progresiva en partículas que se dispersan con el viento.
//! - `1` (**Papel quemándose**): combustión orgánica con frente carbonizado, brasas y chispas.
//! - `2` (**Aura extraña**): vórtice espectral con filamentos luminosos que envuelven y absorben la pista.

use std::f32::consts::TAU;

/// Tipo de animación de eliminación ejecutada en Rust.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum DeleteEffectKind {
    Ashes = 0,
    BurningPaper = 1,
    StrangeAura = 2,
}

impl DeleteEffectKind {
    pub fn from_i32(raw: i32) -> Self {
        match raw {
            0 => Self::Ashes,
            1 => Self::BurningPaper,
            _ => Self::StrangeAura,
        }
    }
}

/// Renderiza un fotograma del efecto de eliminación desde `src_rgba` hacia `dst_rgba`.
///
/// Ambos slices deben tener longitud mínima `width * height * 4` bytes en formato RGBA8888
/// premultiplicado (el formato nativo de `android.graphics.Bitmap.Config.ARGB_8888`).
pub fn render_delete_effect_frame(
    src_rgba: &[u8],
    dst_rgba: &mut [u8],
    width: usize,
    height: usize,
    effect: DeleteEffectKind,
    progress: f32,
    seed: u32,
    accent_argb: u32,
) -> bool {
    if width == 0 || height == 0 {
        return false;
    }
    let required_bytes = match width.checked_mul(height).and_then(|px| px.checked_mul(4)) {
        Some(len) => len,
        None => return false,
    };
    if src_rgba.len() < required_bytes || dst_rgba.len() < required_bytes {
        return false;
    }

    let p = progress.clamp(0.0, 1.0);
    let src = &src_rgba[..required_bytes];
    let dst = &mut dst_rgba[..required_bytes];

    if p <= 0.0001 {
        dst.copy_from_slice(src);
        return true;
    }

    dst.fill(0);
    if p >= 0.999 {
        return true;
    }

    match effect {
        DeleteEffectKind::Ashes => render_ashes(src, dst, width, height, p, seed),
        DeleteEffectKind::BurningPaper => render_burning_paper(src, dst, width, height, p, seed),
        DeleteEffectKind::StrangeAura => {
            render_strange_aura(src, dst, width, height, p, seed, accent_argb)
        }
    }
    true
}

/// Efecto 0: Desintegración en cenizas (la tarjeta se quiebra en partículas que vuelan y se apagan).
fn render_ashes(
    src: &[u8],
    dst: &mut [u8],
    width: usize,
    height: usize,
    progress: f32,
    seed: u32,
) {
    let wf = width as f32;
    let hf = height as f32;
    // El frente avanza de derecha a izquierda con ligera inclinación diagonal.
    let front = 1.18 - progress * 1.48;
    let transition_band = 0.42;

    for y in 0..height {
        let ny = y as f32 / hf.max(1.0);
        for x in 0..width {
            let nx = x as f32 / wf.max(1.0);
            let idx = (y * width + x) * 4;
            let sa = src[idx + 3];
            if sa == 0 {
                continue;
            }

            let noise = value_noise_2d(nx * 14.0, ny * 5.0, seed);
            let fine = hash01(x as u32, y as u32, seed ^ 0x517cc1b7);
            let threshold = nx * 0.85 + ny * 0.15 + (noise - 0.5) * 0.24;

            if threshold < front {
                // Zona aún intacta de la tarjeta.
                dst[idx..idx + 4].copy_from_slice(&src[idx..idx + 4]);
            } else {
                let local_t = ((threshold - front) / transition_band).clamp(0.0, 1.0);
                if local_t >= 0.99 {
                    continue;
                }

                // Solo dispersa una densidad visual limpia para simular granos de ceniza.
                let grain_gate = hash01((x / 2) as u32, (y / 2) as u32, seed ^ 0x9e3779b9);
                if local_t > 0.18 && grain_gate < local_t * 0.48 {
                    continue;
                }

                let ease = local_t * local_t;
                let drift_x = (0.10 + fine * 0.26) * wf * ease
                    + (local_t * 8.0 + fine * TAU).sin() * 4.5 * local_t;
                let drift_y = (-0.36 - noise * 0.55) * hf * ease
                    + (local_t * 6.0 + noise * TAU).cos() * 3.0 * local_t;

                let tx = (x as f32 + drift_x).round() as isize;
                let ty = (y as f32 + drift_y).round() as isize;
                if tx < 0 || ty < 0 || tx >= width as isize || ty >= height as isize {
                    continue;
                }

                let fade = (1.0 - local_t).powf(1.25);
                let desat = (local_t * 1.35).clamp(0.0, 0.88);

                let sr = src[idx] as f32;
                let sg = src[idx + 1] as f32;
                let sb = src[idx + 2] as f32;
                let luma = 0.299 * sr + 0.587 * sg + 0.114 * sb;
                let ash_shade = luma * 0.65 + (sa as f32) * 0.12;

                let rr = ((sr * (1.0 - desat) + ash_shade * desat) * fade)
                    .round()
                    .clamp(0.0, 255.0) as u8;
                let rg = ((sg * (1.0 - desat) + ash_shade * desat) * fade)
                    .round()
                    .clamp(0.0, 255.0) as u8;
                let rb = ((sb * (1.0 - desat) + ash_shade * 1.04 * desat) * fade)
                    .round()
                    .clamp(0.0, 255.0) as u8;
                let ra = ((sa as f32) * fade).round().clamp(0.0, 255.0) as u8;

                let tidx = (ty as usize * width + tx as usize) * 4;
                blend_over_premul(&mut dst[tidx..tidx + 4], [rr.min(ra), rg.min(ra), rb.min(ra), ra]);
            }
        }
    }
}

/// Efecto 1: Papel quemándose lentamente con borde carbonizado, brasas incandescentes y chispas.
fn render_burning_paper(
    src: &[u8],
    dst: &mut [u8],
    width: usize,
    height: usize,
    progress: f32,
    seed: u32,
) {
    let wf = width as f32;
    let hf = height as f32;
    // La combustión avanza orgánicamente desde el borde inferior derecho hacia toda la tarjeta.
    let burn_front = progress * 1.36;

    for y in 0..height {
        let ny = y as f32 / hf.max(1.0);
        for x in 0..width {
            let nx = x as f32 / wf.max(1.0);
            let idx = (y * width + x) * 4;
            let sa = src[idx + 3];
            if sa == 0 {
                continue;
            }

            let fbm = fbm_2d(nx * 5.5, ny * 2.4, seed);
            let detail = value_noise_2d(nx * 18.0, ny * 9.0, seed ^ 0x3c6ef372);
            // Bias direccional + ruido orgánico para que arda como papel real.
            let burn_map = (1.0 - nx) * 0.42 + (1.0 - ny) * 0.16 + fbm * 0.34 + detail * 0.08;
            let diff = burn_front - burn_map;

            if diff <= -0.11 {
                dst[idx..idx + 4].copy_from_slice(&src[idx..idx + 4]);
            } else if diff <= 0.0 {
                // Papel tostado y carbonizado justo antes de la llama.
                let heat = ((diff + 0.11) / 0.11).clamp(0.0, 1.0);
                let darken = 1.0 - heat * 0.88;
                let warm_boost = heat * 26.0;
                let rr = ((src[idx] as f32 * darken) + warm_boost).clamp(0.0, sa as f32) as u8;
                let rg = ((src[idx + 1] as f32 * darken) + warm_boost * 0.35).clamp(0.0, sa as f32) as u8;
                let rb = (src[idx + 2] as f32 * darken * 0.65).clamp(0.0, sa as f32) as u8;
                dst[idx] = rr;
                dst[idx + 1] = rg;
                dst[idx + 2] = rb;
                dst[idx + 3] = sa;
            } else if diff <= 0.055 {
                // Línea de fuego / brasas incandescentes (naranja -> ámbar -> dorado).
                let flame_t = (diff / 0.055).clamp(0.0, 1.0);
                let flicker = 0.78
                    + 0.22
                        * (progress * 32.0 + detail * 14.0 + nx * 10.0)
                            .sin()
                            .abs();
                let core = (1.0 - (flame_t - 0.35).abs() * 2.1).clamp(0.0, 1.0);
                let alpha_f = (sa as f32 / 255.0) * (1.0 - flame_t * 0.25) * flicker;
                let alpha = (alpha_f * 255.0).round().clamp(0.0, 255.0) as u8;
                let r = ((255.0 * alpha_f).round().clamp(0.0, alpha as f32)) as u8;
                let g = (((95.0 + 145.0 * core) * alpha_f)
                    .round()
                    .clamp(0.0, alpha as f32)) as u8;
                let b = (((18.0 + 90.0 * core * core) * alpha_f)
                    .round()
                    .clamp(0.0, alpha as f32)) as u8;
                dst[idx] = r;
                dst[idx + 1] = g;
                dst[idx + 2] = b;
                dst[idx + 3] = alpha;
            } else if diff <= 0.12 {
                // Estela de humo oscuro ascendiendo detrás de la brasa.
                let smoke_t = ((diff - 0.055) / 0.065).clamp(0.0, 1.0);
                let smoke_alpha_f = (sa as f32 / 255.0) * (1.0 - smoke_t) * 0.38;
                let alpha = (smoke_alpha_f * 255.0).round().clamp(0.0, 255.0) as u8;
                let gray = (32.0 * smoke_alpha_f).round().clamp(0.0, alpha as f32) as u8;
                dst[idx] = gray;
                dst[idx + 1] = gray;
                dst[idx + 2] = (gray as f32 * 0.95) as u8;
                dst[idx + 3] = alpha;
            }
        }
    }

    // Añade chispas ascendentes brillantes en la zona recién quemada.
    let spark_count = 48usize;
    for s in 0..spark_count {
        let sx_norm = hash01(s as u32, 11, seed ^ 0x1337);
        let sy_norm = hash01(s as u32, 29, seed ^ 0x7331);
        let spark_life = ((progress * 1.8 - (1.0 - sx_norm) * 0.65) + sy_norm * 0.25).fract();
        if spark_life <= 0.0 || spark_life >= 0.95 {
            continue;
        }
        let px = ((sx_norm * wf + (progress * 16.0 + s as f32).sin() * 5.0).round()) as isize;
        let py = ((sy_norm * hf - spark_life * hf * 0.65).round()) as isize;
        if px <= 0 || py <= 0 || px >= (width as isize - 1) || py >= (height as isize - 1) {
            continue;
        }
        let sidx = (py as usize * width + px as usize) * 4;
        if src[sidx + 3] == 0 {
            continue;
        }
        let intensity = (1.0 - spark_life) * (1.0 - progress * 0.65);
        let a = (intensity * 235.0).round().clamp(0.0, 255.0) as u8;
        let g = (intensity * 195.0).round().clamp(0.0, a as f32) as u8;
        let b = (intensity * 65.0).round().clamp(0.0, a as f32) as u8;
        blend_over_premul(&mut dst[sidx..sidx + 4], [a, g, b, a]);
    }
}

/// Efecto 2: Aura extraña (vórtice espectral y filamentos de energía que envuelven y absorben la canción).
fn render_strange_aura(
    src: &[u8],
    dst: &mut [u8],
    width: usize,
    height: usize,
    progress: f32,
    seed: u32,
    accent_argb: u32,
) {
    let wf = width as f32;
    let hf = height as f32;
    let cx = wf * 0.5;
    let cy = hf * 0.5;
    let max_radius = (cx * cx + cy * cy).sqrt().max(1.0);

    // Extrae el color acento de Aura Music y lo mezcla con un matiz etéreo violeta/cian.
    let ac_r = ((accent_argb >> 16) & 0xff) as f32;
    let ac_g = ((accent_argb >> 8) & 0xff) as f32;
    let ac_b = (accent_argb & 0xff) as f32;
    let aura_r = (ac_r * 0.65 + 168.0 * 0.35).clamp(40.0, 255.0);
    let aura_g = (ac_g * 0.55 + 105.0 * 0.45).clamp(40.0, 255.0);
    let aura_b = (ac_b * 0.60 + 255.0 * 0.40).clamp(90.0, 255.0);

    let pull = progress.powf(1.35);
    let card_fade = (1.0 - progress.powf(1.65)).clamp(0.0, 1.0);

    // 1. Deforma y succiona los píxeles reales de la canción hacia el vórtice del aura.
    for y in 0..height {
        for x in 0..width {
            let idx = (y * width + x) * 4;
            let sa = src[idx + 3];
            if sa == 0 {
                continue;
            }

            let dx = x as f32 - cx;
            let dy = y as f32 - cy;
            let dist = (dx * dx + dy * dy).sqrt();
            let norm_dist = (dist / max_radius).clamp(0.0, 1.0);

            let swirl = pull * (2.8 * (1.15 - norm_dist * 0.65));
            let cos_a = swirl.cos();
            let sin_a = swirl.sin();
            let shrink = (1.0 - pull * 0.92 * (0.45 + 0.55 * (1.0 - norm_dist))).max(0.02);

            let rx = (dx * cos_a - dy * sin_a) * shrink;
            let ry = (dx * sin_a + dy * cos_a) * shrink - pull * hf * 0.08 * (1.0 - norm_dist);

            let nx = x as f32 / wf.max(1.0);
            let ny = y as f32 / hf.max(1.0);
            let wisp = value_noise_2d(nx * 7.0 + progress * 2.5, ny * 4.0, seed);
            let tx = (cx + rx + (wisp - 0.5) * 10.0 * progress).round() as isize;
            let ty = (cy + ry + (wisp - 0.5) * 6.0 * progress).round() as isize;
            if tx < 0 || ty < 0 || tx >= width as isize || ty >= height as isize {
                continue;
            }

            let edge_tint = (pull * (0.35 + norm_dist * 0.65)).clamp(0.0, 0.85);
            let alpha_f = (sa as f32 / 255.0) * card_fade * (0.72 + 0.28 * wisp);
            let out_a = (alpha_f * 255.0).round().clamp(0.0, 255.0) as u8;
            if out_a == 0 {
                continue;
            }

            let sr = src[idx] as f32;
            let sg = src[idx + 1] as f32;
            let sb = src[idx + 2] as f32;
            let rr = ((sr * (1.0 - edge_tint) + aura_r * alpha_f * edge_tint) * card_fade)
                .round()
                .clamp(0.0, out_a as f32) as u8;
            let rg = ((sg * (1.0 - edge_tint) + aura_g * alpha_f * edge_tint) * card_fade)
                .round()
                .clamp(0.0, out_a as f32) as u8;
            let rb = ((sb * (1.0 - edge_tint) + aura_b * alpha_f * edge_tint) * card_fade)
                .round()
                .clamp(0.0, out_a as f32) as u8;

            let tidx = (ty as usize * width + tx as usize) * 4;
            blend_over_premul(&mut dst[tidx..tidx + 4], [rr, rg, rb, out_a]);
        }
    }

    // 2. Superpone tentáculos y halos de aura espectral envolviendo la tarjeta.
    let envelope = (progress * std::f32::consts::PI).sin().clamp(0.0, 1.0);
    if envelope > 0.01 {
        for y in 0..height {
            let ny = y as f32 / hf.max(1.0);
            for x in 0..width {
                let nx = x as f32 / wf.max(1.0);
                let dx = (x as f32 - cx) / wf.max(1.0);
                let dy = (y as f32 - cy) / hf.max(1.0);
                let radius = (dx * dx + dy * dy * 2.4).sqrt();
                let angle = dy.atan2(dx);

                let wave1 = (angle * 3.0 + radius * 14.0 - progress * 11.0).sin();
                let wave2 = (angle * 5.0 - radius * 10.0 + progress * 8.0).cos();
                let n = fbm_2d(nx * 5.0 + progress * 1.4, ny * 3.0 - progress * 1.1, seed ^ 0x99aabbcc);

                let ribbon = ((wave1 * 0.55 + wave2 * 0.45) * 0.5 + 0.5).powf(2.6);
                let ring = (1.0 - (radius - (0.48 - progress * 0.42)).abs() * 3.2).clamp(0.0, 1.0);
                let glow = ((ribbon * 0.72 + ring * 0.55) * n * envelope).clamp(0.0, 1.0);

                if glow < 0.10 {
                    continue;
                }

                let idx = (y * width + x) * 4;
                let aura_a = (glow * 210.0).round().clamp(0.0, 255.0) as u8;
                let af = aura_a as f32 / 255.0;
                // Alterna entre el color acento y un destello cian espectral en los filamentos.
                let cyan_mix = (wave2 * 0.5 + 0.5).clamp(0.0, 1.0) * 0.45;
                let r = ((aura_r * (1.0 - cyan_mix) + 95.0 * cyan_mix) * af)
                    .round()
                    .clamp(0.0, aura_a as f32) as u8;
                let g = ((aura_g * (1.0 - cyan_mix) + 235.0 * cyan_mix) * af)
                    .round()
                    .clamp(0.0, aura_a as f32) as u8;
                let b = ((aura_b * (1.0 - cyan_mix) + 255.0 * cyan_mix) * af)
                    .round()
                    .clamp(0.0, aura_a as f32) as u8;
                blend_over_premul(&mut dst[idx..idx + 4], [r, g, b, aura_a]);
            }
        }
    }
}

/// Composición Porter-Duff Source-Over para píxeles premultiplicados RGBA.
#[inline]
fn blend_over_premul(dst: &mut [u8], src: [u8; 4]) {
    let sa = src[3] as u32;
    if sa == 0 {
        return;
    }
    if sa == 255 || dst[3] == 0 {
        dst[0] = src[0];
        dst[1] = src[1];
        dst[2] = src[2];
        dst[3] = src[3];
        return;
    }
    let inv_sa = 255 - sa;
    dst[0] = (src[0] as u32 + ((dst[0] as u32 * inv_sa) / 255)).min(255) as u8;
    dst[1] = (src[1] as u32 + ((dst[1] as u32 * inv_sa) / 255)).min(255) as u8;
    dst[2] = (src[2] as u32 + ((dst[2] as u32 * inv_sa) / 255)).min(255) as u8;
    dst[3] = (sa + ((dst[3] as u32 * inv_sa) / 255)).min(255) as u8;
}

#[inline]
fn hash01(x: u32, y: u32, seed: u32) -> f32 {
    let mut h = x.wrapping_mul(374761393) ^ y.wrapping_mul(668265263) ^ seed.wrapping_mul(1442695041);
    h = (h ^ (h >> 13)).wrapping_mul(1274126177);
    h ^= h >> 16;
    (h & 0x00ff_ffff) as f32 / 16_777_215.0
}

fn value_noise_2d(x: f32, y: f32, seed: u32) -> f32 {
    let x0 = x.floor();
    let y0 = y.floor();
    let fx = x - x0;
    let fy = y - y0;
    let ux = fx * fx * (3.0 - 2.0 * fx);
    let uy = fy * fy * (3.0 - 2.0 * fy);

    let ix0 = x0 as i32 as u32;
    let iy0 = y0 as i32 as u32;
    let n00 = hash01(ix0, iy0, seed);
    let n10 = hash01(ix0.wrapping_add(1), iy0, seed);
    let n01 = hash01(ix0, iy0.wrapping_add(1), seed);
    let n11 = hash01(ix0.wrapping_add(1), iy0.wrapping_add(1), seed);

    let nx0 = n00 + (n10 - n00) * ux;
    let nx1 = n01 + (n11 - n01) * ux;
    nx0 + (nx1 - nx0) * uy
}

fn fbm_2d(x: f32, y: f32, seed: u32) -> f32 {
    let v1 = value_noise_2d(x, y, seed);
    let v2 = value_noise_2d(x * 2.03 + 11.3, y * 2.03 + 5.7, seed ^ 0x5555_aaaa);
    let v3 = value_noise_2d(x * 4.07 + 23.1, y * 4.07 + 17.9, seed ^ 0xaaaa_5555);
    v1 * 0.56 + v2 * 0.30 + v3 * 0.14
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_card_rgba(w: usize, h: usize) -> Vec<u8> {
        let mut buf = vec![0u8; w * h * 4];
        for y in 0..h {
            for x in 0..w {
                let idx = (y * w + x) * 4;
                buf[idx] = ((x * 3) % 240) as u8;
                buf[idx + 1] = ((y * 5) % 240) as u8;
                buf[idx + 2] = 210;
                buf[idx + 3] = 255;
            }
        }
        buf
    }

    #[test]
    fn preserves_source_at_zero_and_clears_at_one_for_all_effects() {
        let w = 64;
        let h = 24;
        let src = sample_card_rgba(w, h);
        let mut dst = vec![0u8; src.len()];

        for effect in [
            DeleteEffectKind::Ashes,
            DeleteEffectKind::BurningPaper,
            DeleteEffectKind::StrangeAura,
        ] {
            assert!(render_delete_effect_frame(
                &src,
                &mut dst,
                w,
                h,
                effect,
                0.0,
                42,
                0xFFA78BFA
            ));
            assert_eq!(dst, src, "progress=0.0 debe preservar el frame original");

            assert!(render_delete_effect_frame(
                &src,
                &mut dst,
                w,
                h,
                effect,
                0.5,
                42,
                0xFFA78BFA
            ));
            assert_ne!(dst, src, "progress=0.5 debe transformar el buffer en {effect:?}");
            // Verifica que todo píxel mantenga alfa premultiplicado válido (R, G, B <= A).
            for px in dst.chunks_exact(4) {
                assert!(px[0] <= px[3] && px[1] <= px[3] && px[2] <= px[3]);
            }

            assert!(render_delete_effect_frame(
                &src,
                &mut dst,
                w,
                h,
                effect,
                1.0,
                42,
                0xFFA78BFA
            ));
            assert!(
                dst.iter().all(|&b| b == 0),
                "progress=1.0 debe disolver completamente en {effect:?}"
            );
        }
    }
}
