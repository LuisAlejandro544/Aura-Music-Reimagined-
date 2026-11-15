"""Motor ligero en Python 3.11 para descargar audio con yt-dlp + yt-dlp-ejs + QuickJS-NG
y auto-actualizar ambos paquetes desde PyPI sin recompilar el APK.

Todas las operaciones de red y extracción corren en hilos en segundo plano invocados desde Kotlin.
"""

from __future__ import annotations

import hashlib
import importlib
import json
import os
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from collections.abc import Callable

PYPI_PACKAGES = ("yt-dlp", "yt-dlp-ejs")
PACKAGE_DIR_MAP = {
    "yt-dlp": ("yt_dlp", "yt_dlp"),
    "yt-dlp-ejs": ("yt_dlp_ejs", "yt_dlp_ejs"),
}


def _updates_pkg_dir(updates_dir: str, pkg_name: str) -> str:
    folder_name = PACKAGE_DIR_MAP[pkg_name][0]
    return os.path.join(updates_dir, folder_name)


def _meta_file_path(updates_dir: str, pkg_name: str) -> str:
    folder_name = PACKAGE_DIR_MAP[pkg_name][0]
    return os.path.join(updates_dir, f"{folder_name}_meta.json")


def ensure_updates_in_sys_path(updates_dir: str) -> None:
    """Coloca las carpetas válidas de actualizaciones locales al frente de sys.path."""
    if not updates_dir:
        return
    os.makedirs(updates_dir, exist_ok=True)
    # Inserta primero yt_dlp_ejs y luego yt_dlp en orden inverso para prioridad 0/1,
    # comprobando __init__.py para evitar que una carpeta vacía tape el paquete del APK.
    for pkg_name in reversed(PYPI_PACKAGES):
        pkg_root = _updates_pkg_dir(updates_dir, pkg_name)
        module_prefix = PACKAGE_DIR_MAP[pkg_name][1]
        init_file = os.path.join(pkg_root, module_prefix, "__init__.py")
        if os.path.isfile(init_file):
            if pkg_root in sys.path:
                sys.path.remove(pkg_root)
            sys.path.insert(0, pkg_root)


def _purge_loaded_modules(prefixes: tuple[str, ...]) -> None:
    to_remove = [
        mod_name
        for mod_name in list(sys.modules.keys())
        if any(mod_name == p or mod_name.startswith(f"{p}.") for p in prefixes)
    ]
    for mod_name in to_remove:
        sys.modules.pop(mod_name, None)
    importlib.invalidate_caches()


def _read_installed_meta(updates_dir: str, pkg_name: str) -> dict:
    meta_path = _meta_file_path(updates_dir, pkg_name)
    if os.path.isfile(meta_path):
        try:
            with open(meta_path, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, json.JSONDecodeError, ValueError):
            return {}
    return {}


def _probe_yt_dlp_version(updates_dir: str) -> tuple[str, str | None]:
    ensure_updates_in_sys_path(updates_dir)
    try:
        import yt_dlp.version as ytdlp_ver

        return str(getattr(ytdlp_ver, "__version__", "desconocida")), None
    except Exception as exc:  # noqa: BLE001 - Diagnóstico visible en UI móvil
        return "no disponible", f"{type(exc).__name__}: {exc}"


def _probe_ejs_version(updates_dir: str) -> tuple[str, str | None]:
    ensure_updates_in_sys_path(updates_dir)
    try:
        import yt_dlp_ejs._version as ejs_ver

        return str(getattr(ejs_ver, "__version__", "desconocida")), None
    except (ImportError, AttributeError):
        try:
            import yt_dlp_ejs

            return str(getattr(yt_dlp_ejs, "__version__", "instalada")), None
        except Exception as exc:  # noqa: BLE001 - Diagnóstico visible en UI móvil
            return "no disponible", f"{type(exc).__name__}: {exc}"


def _get_active_yt_dlp_version(updates_dir: str) -> str:
    ver, _ = _probe_yt_dlp_version(updates_dir)
    return ver


def _get_active_ejs_version(updates_dir: str) -> str:
    ver, _ = _probe_ejs_version(updates_dir)
    return ver


def get_engine_versions(updates_dir: str) -> str:
    """Devuelve un JSON con las versiones activas de Python, yt-dlp y yt-dlp-ejs."""
    ensure_updates_in_sys_path(updates_dir)
    ytdlp_meta = _read_installed_meta(updates_dir, "yt-dlp")
    ejs_meta = _read_installed_meta(updates_dir, "yt-dlp-ejs")
    yt_dlp_ver, yt_dlp_err = _probe_yt_dlp_version(updates_dir)
    ejs_ver, ejs_err = _probe_ejs_version(updates_dir)
    py_ver = (
        f"Python {sys.version_info.major}."
        f"{sys.version_info.minor}.{sys.version_info.micro}"
    )
    payload = {
        "python_version": py_ver,
        "yt_dlp_version": yt_dlp_ver,
        "yt_dlp_ejs_version": ejs_ver,
        "yt_dlp_updated_at": ytdlp_meta.get("updated_at"),
        "yt_dlp_ejs_updated_at": ejs_meta.get("updated_at"),
        "yt_dlp_error": yt_dlp_err,
        "yt_dlp_ejs_error": ejs_err,
    }
    return json.dumps(payload)


def _fetch_pypi_latest_wheel(pkg_name: str) -> tuple[str, str, str]:
    """Consulta la API oficial de PyPI y devuelve (version, wheel_url, sha256)."""
    api_url = f"https://pypi.org/pypi/{pkg_name}/json"
    req = urllib.request.Request(
        api_url,
        headers={"User-Agent": "AuraMusic/1.0 (Android)"},
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        data = json.loads(resp.read().decode("utf-8"))

    info = data.get("info", {})
    version = str(info.get("version", "")).strip()
    urls = data.get("urls", [])

    wheel_entry = None
    for entry in urls:
        fname = str(entry.get("filename", ""))
        if entry.get("packagetype") == "bdist_wheel" and fname.endswith(
            "py3-none-any.whl"
        ):
            wheel_entry = entry
            break
    if wheel_entry is None:
        for entry in urls:
            if entry.get("packagetype") == "bdist_wheel":
                wheel_entry = entry
                break

    if not wheel_entry or not wheel_entry.get("url"):
        raise RuntimeError(f"No se encontró wheel compatible en PyPI para {pkg_name}")

    wheel_url = str(wheel_entry["url"])
    sha256 = str(wheel_entry.get("digests", {}).get("sha256", "")).strip().lower()
    return version, wheel_url, sha256


def _download_and_install_wheel(
    updates_dir: str,
    pkg_name: str,
    version: str,
    wheel_url: str,
    expected_sha256: str,
) -> None:
    """Descarga el wheel .whl, valida SHA-256 y lo extrae atómicamente en updates_dir."""
    os.makedirs(updates_dir, exist_ok=True)
    target_dir = _updates_pkg_dir(updates_dir, pkg_name)
    module_prefix = PACKAGE_DIR_MAP[pkg_name][1]
    stamp = int(time.time() * 1000)
    staging_dir = os.path.join(updates_dir, f".staging_{module_prefix}_{stamp}")
    backup_dir = os.path.join(updates_dir, f".backup_{module_prefix}_{stamp}")

    with tempfile.NamedTemporaryFile(suffix=".whl", delete=False) as tmp_file:
        tmp_wheel_path = tmp_file.name

    try:
        req = urllib.request.Request(
            wheel_url,
            headers={"User-Agent": "AuraMusic/1.0 (Android)"},
        )
        hasher = hashlib.sha256()
        with (
            urllib.request.urlopen(req, timeout=45) as resp,
            open(tmp_wheel_path, "wb") as out_f,
        ):
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                hasher.update(chunk)
                out_f.write(chunk)

        actual_sha256 = hasher.hexdigest().lower()
        if expected_sha256 and actual_sha256 != expected_sha256:
            raise RuntimeError(
                f"Checksum SHA-256 inválido para {pkg_name} {version}: "
                f"esperado {expected_sha256}, recibido {actual_sha256}"
            )

        os.makedirs(staging_dir, exist_ok=True)
        with zipfile.ZipFile(tmp_wheel_path, "r") as zf:
            for member in zf.infolist():
                # Solo extrae el paquete principal (yt_dlp/ o yt_dlp_ejs/) y dist-info.
                if member.is_dir():
                    continue
                norm_name = member.filename.replace("\\", "/").lstrip("/")
                if ".." in norm_name.split("/"):
                    continue
                if norm_name.startswith(f"{module_prefix}/") or ".dist-info/" in norm_name:
                    dest_path = os.path.join(staging_dir, norm_name)
                    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
                    with zf.open(member, "r") as src_f, open(dest_path, "wb") as dst_f:
                        shutil.copyfileobj(src_f, dst_f)

        if os.path.exists(target_dir):
            os.replace(target_dir, backup_dir)
        os.replace(staging_dir, target_dir)

        meta_path = _meta_file_path(updates_dir, pkg_name)
        with open(meta_path, "w", encoding="utf-8") as mf:
            json.dump(
                {
                    "version": version,
                    "updated_at": time.strftime("%Y-%m-%d %H:%M"),
                    "sha256": actual_sha256,
                },
                mf,
            )

        if os.path.exists(backup_dir):
            shutil.rmtree(backup_dir, ignore_errors=True)
    except Exception:
        if os.path.exists(staging_dir):
            shutil.rmtree(staging_dir, ignore_errors=True)
        if not os.path.exists(target_dir) and os.path.exists(backup_dir):
            os.replace(backup_dir, target_dir)
        raise
    finally:
        if os.path.exists(tmp_wheel_path):
            try:
                os.remove(tmp_wheel_path)
            except OSError as exc:
                sys.stderr.write(f"No se pudo borrar temporal {tmp_wheel_path}: {exc}\n")


def update_packages(updates_dir: str) -> str:
    """Comprueba y actualiza independientemente yt-dlp y yt-dlp-ejs desde PyPI."""
    ensure_updates_in_sys_path(updates_dir)
    current_versions = {
        "yt-dlp": _get_active_yt_dlp_version(updates_dir),
        "yt-dlp-ejs": _get_active_ejs_version(updates_dir),
    }

    updated_notes = []
    errors = []

    for pkg_name in PYPI_PACKAGES:
        try:
            remote_version, wheel_url, sha256 = _fetch_pypi_latest_wheel(pkg_name)
            local_version = current_versions[pkg_name]
            if remote_version and remote_version != local_version:
                _download_and_install_wheel(
                    updates_dir=updates_dir,
                    pkg_name=pkg_name,
                    version=remote_version,
                    wheel_url=wheel_url,
                    expected_sha256=sha256,
                )
                updated_notes.append(f"{pkg_name}: {local_version} → {remote_version}")
            else:
                updated_notes.append(f"{pkg_name}: al día ({local_version})")
        except (
            urllib.error.URLError,
            urllib.error.HTTPError,
            TimeoutError,
            OSError,
            RuntimeError,
            ValueError,
            zipfile.BadZipFile,
        ) as exc:
            errors.append(f"{pkg_name}: {exc}")

    # Recarga módulos en memoria para aplicar las nuevas versiones sin reiniciar.
    _purge_loaded_modules(("yt_dlp", "yt_dlp_ejs"))
    ensure_updates_in_sys_path(updates_dir)

    payload = json.loads(get_engine_versions(updates_dir))
    payload["ok"] = len(errors) == 0
    if errors:
        joined = " | ".join(updated_notes + errors)
        payload["message"] = f"Actualización parcial: {joined}"
    else:
        payload["message"] = " • ".join(updated_notes)
    return json.dumps(payload)


_EXOPLAYER_AUDIO_EXTS = {
    "m4a",
    "mp4",
    "webm",
    "opus",
    "ogg",
    "mp3",
    "aac",
    "flac",
    "wav",
}


def _humanize_codec(acodec: str | None) -> str:
    raw = str(acodec or "").strip().lower()
    if not raw or raw == "none":
        return ""
    if raw.startswith("mp4a"):
        return "AAC"
    if "opus" in raw:
        return "Opus"
    if "vorbis" in raw:
        return "Vorbis"
    if "flac" in raw:
        return "FLAC"
    if "mp3" in raw or raw == "mpga":
        return "MP3"
    return raw.split(".")[0].upper()


def _build_format_selector(
    quality_key: str | None,
    format_id: str | None = None,
) -> str:
    """Construye selector de formato compatible con Media3 ExoPlayer sin FFmpeg."""
    fallback = "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/best"
    clean_id = str(format_id or "").strip()
    if clean_id and all(ch.isalnum() or ch in ("-", "_", ".", "+") for ch in clean_id):
        return f"{clean_id}/{fallback}"

    key = str(quality_key or "max").strip().lower()
    if key == "data_saver":
        return f"worstaudio[abr>=48]/bestaudio[abr<=96]/worstaudio/{fallback}"
    if key == "balanced":
        return (
            "bestaudio[abr<=160][ext=m4a]/bestaudio[abr<=160][ext=webm]/"
            f"bestaudio[abr<=160]/{fallback}"
        )
    return fallback


def _extract_available_audio_formats(info: dict) -> list[dict]:
    """Extrae y ordena flujos de solo audio disponibles en el enlace (M4A/Opus/etc.)."""
    formats = info.get("formats") or []
    audio_only = [
        f
        for f in formats
        if f.get("format_id")
        and f.get("acodec") not in (None, "none")
        and f.get("vcodec") in (None, "none")
        and str(f.get("protocol") or "").lower() != "mhtml"
    ]

    if not audio_only:
        requested = info.get("requested_formats") or []
        if requested and isinstance(requested, list):
            audio_only = [
                f
                for f in requested
                if f.get("format_id") and f.get("acodec") not in (None, "none")
            ]
        elif info.get("format_id") and info.get("acodec") not in (None, "none"):
            audio_only = [info]

    candidates_by_signature: dict[
        tuple[str, str, int],
        tuple[tuple[int, int, int, int], dict],
    ] = {}
    for fmt in audio_only:
        ext_raw = str(fmt.get("ext") or info.get("ext") or "audio").strip().lower()
        if ext_raw not in _EXOPLAYER_AUDIO_EXTS and len(audio_only) > 1:
            continue

        ext_upper = ext_raw.upper()
        codec_label = _humanize_codec(fmt.get("acodec") or info.get("acodec"))
        raw_abr = fmt.get("abr") or fmt.get("tbr") or 0
        try:
            abr_kbps = max(0, round(float(raw_abr)))
        except (TypeError, ValueError):
            abr_kbps = 0

        try:
            size_bytes = max(
                0,
                int(fmt.get("filesize") or fmt.get("filesize_approx") or 0),
            )
        except (TypeError, ValueError):
            size_bytes = 0

        format_note = str(fmt.get("format_note") or "").lower()
        fmt_id_lower = str(fmt.get("format_id") or "").lower()
        is_drc = "drc" in format_note or "-drc" in fmt_id_lower
        raw_lang_pref = str(fmt.get("language_preference") or "")
        lang_pref = (
            int(fmt.get("language_preference") or 0)
            if raw_lang_pref.lstrip("-").isdigit()
            else 0
        )

        label = f"{ext_upper} ({codec_label})" if codec_label else ext_upper
        if abr_kbps > 0:
            label = f"{label} • {abr_kbps} kbps"

        option = {
            "format_id": str(fmt.get("format_id")),
            "extension": ext_raw,
            "codec": codec_label,
            "bitrate_kbps": abr_kbps,
            "estimated_size_bytes": size_bytes,
            "label": label,
        }
        rank = (
            1 if not is_drc else 0,
            lang_pref,
            1 if size_bytes > 0 else 0,
            size_bytes,
        )
        sig = (ext_upper, codec_label, abr_kbps)
        existing = candidates_by_signature.get(sig)
        if existing is None or rank > existing[0]:
            candidates_by_signature[sig] = (rank, option)

    options = [pair[1] for pair in candidates_by_signature.values()]
    options.sort(
        key=lambda o: (
            o["bitrate_kbps"],
            1 if o["extension"] == "m4a" else 0,
            o["estimated_size_bytes"],
        ),
        reverse=True,
    )
    return options[:8]


def _pick_default_format_option(
    options: list[dict],
    quality_key: str | None,
) -> dict | None:
    if not options:
        return None
    key = str(quality_key or "max").strip().lower()
    if key == "data_saver":
        eligible = [o for o in options if o["bitrate_kbps"] >= 48]
        if eligible:
            return eligible[-1]
        positive = [o for o in options if o["bitrate_kbps"] > 0]
        return positive[-1] if positive else options[-1]
    if key == "balanced":
        eligible = [o for o in options if 0 < o["bitrate_kbps"] <= 160]
        if eligible:
            return eligible[0]
        positive = [o for o in options if o["bitrate_kbps"] > 0]
        return positive[-1] if positive else options[0]
    return options[0]


def _resolve_audio_size_and_format(info: dict) -> tuple[int, str]:
    """Calcula peso estimado en bytes y etiqueta legible de formato/bitrate."""
    selected = None
    requested = info.get("requested_formats") or []
    if requested and isinstance(requested, list):
        selected = requested[0]
    elif info.get("acodec") not in (None, "none"):
        selected = info

    if selected is None:
        formats = info.get("formats") or []
        audio_only = [
            f
            for f in formats
            if f.get("acodec") not in (None, "none")
            and f.get("vcodec") in (None, "none")
        ]
        if audio_only:
            selected = audio_only[-1]
        elif formats:
            selected = formats[-1]
        else:
            selected = info

    size_bytes = int(
        selected.get("filesize")
        or selected.get("filesize_approx")
        or info.get("filesize")
        or info.get("filesize_approx")
        or 0
    )
    ext = str(selected.get("ext") or info.get("ext") or "audio").upper()
    codec = _humanize_codec(selected.get("acodec") or info.get("acodec"))
    abr = selected.get("abr") or info.get("abr")
    base = f"{ext} ({codec})" if codec else ext
    if abr:
        try:
            format_label = f"{base} • {round(float(abr))} kbps"
        except (TypeError, ValueError):
            format_label = base
    else:
        format_label = base
    return max(0, size_bytes), format_label


def _detect_thumb_ext(raw_bytes: bytes) -> str:
    if raw_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if raw_bytes.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if len(raw_bytes) >= 12 and raw_bytes[:4] == b"RIFF" and raw_bytes[8:12] == b"WEBP":
        return "webp"
    return "jpg"


def _download_preview_thumbnail(
    thumbnail_url: str | None,
    cache_dir: str,
    video_id: str,
) -> str | None:
    """Descarga miniatura de vista previa a disco local para mostrarla con Coil."""
    if not thumbnail_url or not cache_dir:
        return None
    try:
        os.makedirs(cache_dir, exist_ok=True)
        req = urllib.request.Request(
            thumbnail_url,
            headers={"User-Agent": "AuraMusic/1.0 (Android)"},
        )
        with urllib.request.urlopen(req, timeout=12) as resp:
            data = resp.read(4 * 1024 * 1024)
        if not data:
            return None
        ext = _detect_thumb_ext(data)
        safe_id = "".join(
            c if c.isalnum() or c in ("-", "_") else "_"
            for c in (video_id or "preview")
        )[:48]
        stamp = int(time.time() * 1000)
        out_path = os.path.join(cache_dir, f"thumb_{safe_id}_{stamp}.{ext}")
        with open(out_path, "wb") as f:
            f.write(data)
        return out_path
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        return None


def inspect_audio_preview(
    url: str,
    cache_dir: str,
    updates_dir: str,
    qjs_binary_path: str | None,
    quality_key: str = "max",
) -> str:
    """Obtiene metadatos, duración, formatos disponibles, peso estimado y miniatura."""
    ensure_updates_in_sys_path(updates_dir)
    import yt_dlp

    ydl_opts: dict = {
        "format": _build_format_selector(quality_key),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": False,
        "skip_download": True,
        "socket_timeout": 20,
    }

    if qjs_binary_path and os.path.exists(qjs_binary_path):
        ydl_opts["js_runtimes"] = {
            "quickjs": {
                "path": qjs_binary_path,
            }
        }

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=False)
        if info is None:
            raise RuntimeError("No se pudo obtener información del enlace.")

        entries = info.get("entries")
        if entries:
            info = next((item for item in entries if item is not None), info)

        video_id = str(info.get("id") or "")
        title = info.get("track") or info.get("title") or "Audio sin título"
        artist = (
            info.get("artist")
            or info.get("creator")
            or info.get("uploader")
            or info.get("channel")
            or info.get("extractor_key")
            or "Descarga web"
        )
        duration_sec = info.get("duration") or 0
        duration_ms = int(float(duration_sec) * 1000) if duration_sec else 0

        available_formats = _extract_available_audio_formats(info)
        default_option = _pick_default_format_option(available_formats, quality_key)
        if default_option is not None:
            selected_format_id = default_option["format_id"]
            estimated_size_bytes = default_option["estimated_size_bytes"]
            format_label = default_option["label"]
        else:
            selected_format_id = None
            estimated_size_bytes, format_label = _resolve_audio_size_and_format(info)

        thumb_url = info.get("thumbnail")
        if not thumb_url:
            thumbs = info.get("thumbnails") or []
            if thumbs and isinstance(thumbs, list):
                thumb_url = thumbs[-1].get("url")
        local_thumb_path = _download_preview_thumbnail(thumb_url, cache_dir, video_id)

        return json.dumps(
            {
                "video_id": video_id,
                "title": str(title),
                "artist": str(artist),
                "duration_ms": duration_ms,
                "estimated_size_bytes": estimated_size_bytes,
                "audio_format_label": format_label,
                "selected_format_id": selected_format_id,
                "available_formats": available_formats,
                "thumbnail_path": local_thumb_path,
                "webpage_url": str(info.get("webpage_url") or url),
            }
        )


class DownloadCancelledError(RuntimeError):
    """Excepción controlada lanzada cuando el usuario cancela la descarga."""


def download_audio(
    url: str,
    output_dir: str,
    updates_dir: str,
    qjs_binary_path: str | None,
    progress_callback: Callable[[str], None] | None = None,
    cancel_check: Callable[[], bool] | None = None,
    quality_key: str = "max",
    format_id: str | None = None,
) -> str:
    """Descarga flujo de audio compatible con ExoPlayer usando yt-dlp y QuickJS-NG."""
    ensure_updates_in_sys_path(updates_dir)
    os.makedirs(output_dir, exist_ok=True)

    import yt_dlp

    last_emit_ts = [0.0]
    last_speed_ts = [time.monotonic()]
    last_speed_bytes = [0]
    smoothed_speed = [0.0]

    def check_cancelled() -> None:
        if cancel_check is not None:
            try:
                if bool(cancel_check()):
                    raise DownloadCancelledError("Descarga cancelada por el usuario.")
            except DownloadCancelledError:
                raise
            except (RuntimeError, TypeError, ValueError) as exc:
                sys.stderr.write(f"Advertencia comprobando cancelación: {exc}\n")

    def emit(
        stage: str,
        percent: float = 0.0,
        downloaded: int = 0,
        total: int = 0,
        speed_bps: int = 0,
        title: str | None = None,
    ) -> None:
        if progress_callback is None:
            return
        now = time.monotonic()
        if (
            stage == "downloading"
            and (now - last_emit_ts[0]) < 0.25
            and percent < 99.5
        ):
            return
        last_emit_ts[0] = now
        try:
            progress_callback(
                json.dumps(
                    {
                        "stage": stage,
                        "percent": float(percent),
                        "downloaded": int(downloaded),
                        "total": int(total),
                        "speed_bps": int(max(0, speed_bps)),
                        "title": title,
                    }
                )
            )
        except (RuntimeError, TypeError, ValueError) as exc:
            sys.stderr.write(f"Advertencia emitiendo progreso: {exc}\n")

    captured_title = [None]

    def progress_hook(status_dict: dict) -> None:
        check_cancelled()
        status = status_dict.get("status")
        info = status_dict.get("info_dict") or {}
        title = info.get("title") or captured_title[0]
        if title:
            captured_title[0] = title

        if status == "downloading":
            downloaded = int(status_dict.get("downloaded_bytes") or 0)
            total = int(
                status_dict.get("total_bytes")
                or status_dict.get("total_bytes_estimate")
                or 0
            )
            percent = (downloaded * 100.0 / total) if total > 0 else 0.0

            raw_speed = status_dict.get("speed")
            now = time.monotonic()
            dt = now - last_speed_ts[0]
            if raw_speed is not None and float(raw_speed) > 0:
                inst_speed = float(raw_speed)
                smoothed_speed[0] = (
                    inst_speed
                    if smoothed_speed[0] <= 0
                    else (smoothed_speed[0] * 0.5 + inst_speed * 0.5)
                )
                last_speed_ts[0] = now
                last_speed_bytes[0] = downloaded
            elif dt >= 0.35 and downloaded >= last_speed_bytes[0]:
                inst_speed = (downloaded - last_speed_bytes[0]) / dt
                smoothed_speed[0] = (
                    inst_speed
                    if smoothed_speed[0] <= 0
                    else (smoothed_speed[0] * 0.6 + inst_speed * 0.4)
                )
                last_speed_ts[0] = now
                last_speed_bytes[0] = downloaded

            emit(
                "downloading",
                percent=percent,
                downloaded=downloaded,
                total=total,
                speed_bps=int(smoothed_speed[0]),
                title=title,
            )
        elif status == "finished":
            total = int(
                status_dict.get("total_bytes")
                or status_dict.get("downloaded_bytes")
                or 0
            )
            emit(
                "importing",
                percent=100.0,
                downloaded=total,
                total=total,
                speed_bps=0,
                title=title,
            )

    ydl_opts: dict = {
        "format": _build_format_selector(quality_key, format_id),
        "noplaylist": True,
        "outtmpl": os.path.join(output_dir, "%(id)s.%(ext)s"),
        "writethumbnail": True,
        "quiet": True,
        "no_warnings": False,
        "noprogress": True,
        "retries": 3,
        "fragment_retries": 3,
        "socket_timeout": 25,
        "progress_hooks": [progress_hook],
    }

    if qjs_binary_path and os.path.exists(qjs_binary_path):
        ydl_opts["js_runtimes"] = {
            "quickjs": {
                "path": qjs_binary_path,
            }
        }

    check_cancelled()
    emit("resolving", percent=0.0)

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
        check_cancelled()
        if info is None:
            raise RuntimeError("yt-dlp no devolvió información de la pista.")

        entries = info.get("entries")
        if entries:
            info = next((item for item in entries if item is not None), info)

        prepared_path = ydl.prepare_filename(info)
        requested = info.get("requested_downloads") or []
        audio_path = prepared_path
        if requested and isinstance(requested, list):
            filepath = requested[0].get("filepath")
            if filepath and os.path.exists(filepath):
                audio_path = filepath

        skip_exts = (".webp", ".jpg", ".jpeg", ".png", ".part", ".ytdl")
        if not os.path.exists(audio_path):
            video_id = str(info.get("id") or "")
            for fname in os.listdir(output_dir):
                if (
                    video_id
                    and fname.startswith(f"{video_id}.")
                    and not fname.endswith(skip_exts)
                ):
                    audio_path = os.path.join(output_dir, fname)
                    break

        if not os.path.exists(audio_path):
            raise RuntimeError("No se encontró el archivo de audio descargado.")

        # Busca la miniatura descargada asociada al mismo video_id.
        thumbnail_path = None
        video_id = str(info.get("id") or "")
        stem = os.path.splitext(audio_path)[0]
        for ext in (".webp", ".jpg", ".jpeg", ".png"):
            cand = f"{stem}{ext}"
            if os.path.exists(cand):
                thumbnail_path = cand
                break
            if video_id:
                cand_id = os.path.join(output_dir, f"{video_id}{ext}")
                if os.path.exists(cand_id):
                    thumbnail_path = cand_id
                    break

        duration_sec = info.get("duration") or 0
        duration_ms = int(float(duration_sec) * 1000) if duration_sec else 0
        artist = (
            info.get("artist")
            or info.get("creator")
            or info.get("uploader")
            or info.get("channel")
            or info.get("extractor_key")
            or "Descarga web"
        )
        album = info.get("album") or "Descargas Aura"
        title = info.get("track") or info.get("title") or os.path.basename(audio_path)

        return json.dumps(
            {
                "audio_path": audio_path,
                "thumbnail_path": thumbnail_path,
                "title": str(title),
                "artist": str(artist),
                "album": str(album),
                "duration_ms": duration_ms,
                "webpage_url": str(info.get("webpage_url") or url),
            }
        )
