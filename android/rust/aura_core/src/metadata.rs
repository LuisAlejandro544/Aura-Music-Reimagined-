//! Lector nativo de metadatos de audio y carátulas embebidas en Rust mediante `lofty`.
//!
//! Lee etiquetas (ID3v1/ID3v2, Vorbis Comments, FLAC, MP4/M4A, RIFF INFO, Opus, AIFF),
//! duración exacta de la pista y carátula embebida directamente desde un descriptor de
//! archivo (`fd`) entregado por Storage Access Framework (SAF) en Android o desde cualquier
//! flujo `Read + Seek`, evitando copias completas en memoria y eliminando el uso de
//! `MediaMetadataRetriever` en Kotlin.

use std::io::{BufReader, Read, Seek, SeekFrom};
use std::mem::ManuallyDrop;

use lofty::picture::PictureType;
use lofty::prelude::*;
use lofty::probe::Probe;

/// Resultado estructurado de metadatos extraídos de un archivo de audio local.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AudioMetadataResult {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub duration_ms: i64,
    pub cover_bytes: Option<Vec<u8>>,
}

impl AudioMetadataResult {
    /// Serializa el resultado a un buffer binario compacto big-endian para cruzar JNI
    /// en una sola llamada sin reflexión ni sobrecarga de Base64.
    ///
    /// Formato:
    /// - `status`: i32 BE (`1` = éxito, `0` = error)
    /// - `duration_ms`: i64 BE
    /// - `title`: i32 BE longitud (`-1` si es `None`) + bytes UTF-8
    /// - `artist`: i32 BE longitud (`-1` si es `None`) + bytes UTF-8
    /// - `album`: i32 BE longitud (`-1` si es `None`) + bytes UTF-8
    /// - `cover_bytes`: i32 BE longitud (`0` si no hay carátula) + bytes de imagen
    pub fn to_jni_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(128);
        out.extend_from_slice(&1_i32.to_be_bytes());
        out.extend_from_slice(&self.duration_ms.max(0).to_be_bytes());
        write_opt_str(&mut out, self.title.as_deref());
        write_opt_str(&mut out, self.artist.as_deref());
        write_opt_str(&mut out, self.album.as_deref());

        match &self.cover_bytes {
            Some(bytes) if !bytes.is_empty() => {
                let len = i32::try_from(bytes.len()).unwrap_or(0);
                out.extend_from_slice(&len.to_be_bytes());
                if len > 0 {
                    out.extend_from_slice(&bytes[..len as usize]);
                }
            }
            _ => out.extend_from_slice(&0_i32.to_be_bytes()),
        }

        out
    }

    /// Genera un buffer de error con `status = 0` para indicar fallo controlado a Kotlin.
    pub fn error_jni_bytes() -> Vec<u8> {
        0_i32.to_be_bytes().to_vec()
    }
}

fn write_opt_str(out: &mut Vec<u8>, value: Option<&str>) {
    match value.map(str::trim).filter(|s| !s.is_empty()) {
        Some(text) => {
            let bytes = text.as_bytes();
            let len = i32::try_from(bytes.len()).unwrap_or(-1);
            out.extend_from_slice(&len.to_be_bytes());
            if len >= 0 {
                out.extend_from_slice(&bytes[..len as usize]);
            }
        }
        None => out.extend_from_slice(&(-1_i32).to_be_bytes()),
    }
}

/// Extrae metadatos de audio desde cualquier lector `Read + Seek` usando `lofty`.
pub fn extract_metadata_from_reader<R: Read + Seek>(reader: R) -> Result<AudioMetadataResult, String> {
    let buf_reader = BufReader::new(reader);
    let tagged_file = Probe::new(buf_reader)
        .guess_file_type()
        .map_err(|err| format!("No se pudo detectar el formato de audio con lofty: {err}"))?
        .read()
        .map_err(|err| format!("No se pudo leer el archivo de audio con lofty: {err}"))?;

    let duration_ms = i64::try_from(tagged_file.properties().duration().as_millis())
        .unwrap_or(0)
        .max(0);

    let mut title: Option<String> = None;
    let mut artist: Option<String> = None;
    let mut album: Option<String> = None;
    let mut cover_bytes: Option<Vec<u8>> = None;

    // Prioriza la etiqueta primaria y luego recorre el resto por si existen tags parciales.
    let mut ordered_tags = Vec::new();
    if let Some(primary) = tagged_file.primary_tag() {
        ordered_tags.push(primary);
    }
    for tag in tagged_file.tags() {
        if ordered_tags
            .first()
            .is_none_or(|first| !std::ptr::eq(*first, tag))
        {
            ordered_tags.push(tag);
        }
    }

    for tag in ordered_tags {
        if title.is_none() {
            title = tag
                .title()
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty());
        }
        if artist.is_none() {
            artist = tag
                .artist()
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty());
        }
        if album.is_none() {
            album = tag
                .album()
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty());
        }
        if cover_bytes.is_none() && !tag.pictures().is_empty() {
            let preferred = tag
                .pictures()
                .iter()
                .find(|pic| pic.pic_type() == PictureType::CoverFront && !pic.data().is_empty())
                .or_else(|| tag.pictures().iter().find(|pic| !pic.data().is_empty()));

            if let Some(pic) = preferred {
                cover_bytes = Some(pic.data().to_vec());
            }
        }
    }

    Ok(AudioMetadataResult {
        title,
        artist,
        album,
        duration_ms,
        cover_bytes,
    })
}

/// Extrae metadatos directamente desde el file descriptor (`fd`) de Android sin cerrar
/// el descriptor original (el propietario sigue siendo `ParcelFileDescriptor` en Kotlin).
#[cfg(unix)]
pub fn extract_metadata_from_fd(raw_fd: i32) -> Result<AudioMetadataResult, String> {
    use std::os::unix::io::FromRawFd;

    if raw_fd < 0 {
        return Err(format!("Descriptor de archivo inválido: {raw_fd}"));
    }

    // `ManuallyDrop` impide que Rust cierre el `fd` al salir de ámbito, evitando double-close
    // con `ParcelFileDescriptor.use {}` en Kotlin.
    let mut file = ManuallyDrop::new(unsafe { std::fs::File::from_raw_fd(raw_fd) });
    file.seek(SeekFrom::Start(0))
        .map_err(|err| format!("No se pudo posicionar el descriptor al inicio: {err}"))?;

    extract_metadata_from_reader(&mut *file)
}

#[cfg(not(unix))]
pub fn extract_metadata_from_fd(_raw_fd: i32) -> Result<AudioMetadataResult, String> {
    Err("extract_metadata_from_fd solo está soportado en plataformas Unix/Android".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn build_sample_wav_with_info(title: &str, artist: &str, album: &str) -> Vec<u8> {
        fn push_info_subchunk(dst: &mut Vec<u8>, id: &[u8; 4], text: &str) {
            let mut data = text.as_bytes().to_vec();
            data.push(0); // terminador NUL requerido en RIFF INFO
            let unpadded_len = data.len() as u32;
            if data.len() % 2 != 0 {
                data.push(0);
            }
            dst.extend_from_slice(id);
            dst.extend_from_slice(&unpadded_len.to_le_bytes());
            dst.extend_from_slice(&data);
        }

        let mut info_payload = Vec::new();
        info_payload.extend_from_slice(b"INFO");
        push_info_subchunk(&mut info_payload, b"INAM", title);
        push_info_subchunk(&mut info_payload, b"IART", artist);
        push_info_subchunk(&mut info_payload, b"IPRD", album);

        // 1 segundo de audio PCM estéreo a 44100 Hz / 16 bits = 176_400 bytes
        let pcm_len: u32 = 176_400;
        let mut riff_body = Vec::with_capacity(4 + 24 + 8 + pcm_len as usize + 8 + info_payload.len());
        riff_body.extend_from_slice(b"WAVE");

        // Chunk fmt
        riff_body.extend_from_slice(b"fmt ");
        riff_body.extend_from_slice(&16_u32.to_le_bytes());
        riff_body.extend_from_slice(&1_u16.to_le_bytes()); // PCM
        riff_body.extend_from_slice(&2_u16.to_le_bytes()); // canales
        riff_body.extend_from_slice(&44_100_u32.to_le_bytes()); // sample rate
        riff_body.extend_from_slice(&176_400_u32.to_le_bytes()); // byte rate
        riff_body.extend_from_slice(&4_u16.to_le_bytes()); // block align
        riff_body.extend_from_slice(&16_u16.to_le_bytes()); // bits por muestra

        // Chunk data
        riff_body.extend_from_slice(b"data");
        riff_body.extend_from_slice(&pcm_len.to_le_bytes());
        riff_body.resize(riff_body.len() + pcm_len as usize, 0);

        // Chunk LIST INFO
        riff_body.extend_from_slice(b"LIST");
        riff_body.extend_from_slice(&(info_payload.len() as u32).to_le_bytes());
        riff_body.extend_from_slice(&info_payload);

        let mut wav = Vec::with_capacity(8 + riff_body.len());
        wav.extend_from_slice(b"RIFF");
        wav.extend_from_slice(&(riff_body.len() as u32).to_le_bytes());
        wav.extend_from_slice(&riff_body);
        wav
    }

    #[test]
    fn extracts_tags_and_duration_from_wav_stream() {
        let wav = build_sample_wav_with_info("Aura Signal", "Synth Lab", "Nocturne EP");
        let meta = extract_metadata_from_reader(Cursor::new(wav))
            .expect("lofty debería parsear el WAV sintético");

        assert_eq!(meta.title.as_deref(), Some("Aura Signal"));
        assert_eq!(meta.artist.as_deref(), Some("Synth Lab"));
        assert_eq!(meta.album.as_deref(), Some("Nocturne EP"));
        assert_eq!(meta.duration_ms, 1000);
        assert!(meta.cover_bytes.is_none());
    }

    #[test]
    fn serializes_metadata_to_compact_jni_binary_format() {
        let sample = AudioMetadataResult {
            title: Some("Track".to_string()),
            artist: None,
            album: Some("Album".to_string()),
            duration_ms: 42_500,
            cover_bytes: Some(vec![0xFF, 0xD8, 0xFF]),
        };

        let bytes = sample.to_jni_bytes();
        assert_eq!(&bytes[0..4], &1_i32.to_be_bytes());
        assert_eq!(&bytes[4..12], &42_500_i64.to_be_bytes());
        assert_eq!(&bytes[12..16], &5_i32.to_be_bytes());
        assert_eq!(&bytes[16..21], b"Track");
        assert_eq!(&bytes[21..25], &(-1_i32).to_be_bytes());
        assert_eq!(&bytes[25..29], &5_i32.to_be_bytes());
        assert_eq!(&bytes[29..34], b"Album");
        assert_eq!(&bytes[34..38], &3_i32.to_be_bytes());
        assert_eq!(&bytes[38..41], &[0xFF, 0xD8, 0xFF]);
    }

    #[test]
    fn rejects_invalid_or_empty_audio_stream_cleanly() {
        let result = extract_metadata_from_reader(Cursor::new(b"not-an-audio-file".to_vec()));
        assert!(result.is_err());
        assert_eq!(AudioMetadataResult::error_jni_bytes(), 0_i32.to_be_bytes());
    }
}
