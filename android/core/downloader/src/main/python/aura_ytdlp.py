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
import urllib.request
import zipfile
from typing import Callable, Optional


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
    """Coloca las carpetas de actualizaciones locales al frente de sys.path."""
    if not updates_dir:
        return
    os.makedirs(updates_dir, exist_ok=True)
    # Inserta primero yt_dlp_ejs y luego yt_dlp en orden inverso para prioridad 0/1.
    for pkg_name in reversed(PYPI_PACKAGES):
        pkg_root = _updates_pkg_dir(updates_dir, pkg_name)
        if os.path.isdir(pkg_root):
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
            with open(meta_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}
    return {}


def _get_active_yt_dlp_version(updates_dir: str) -> str:
    ensure_updates_in_sys_path(updates_dir)
    try:
        import yt_dlp.version as ytdlp_ver

        return str(getattr(ytdlp_ver, "__version__", "desconocida"))
    except Exception:
        return "no disponible"


def _get_active_ejs_version(updates_dir: str) -> str:
    ensure_updates_in_sys_path(updates_dir)
    try:
        import yt_dlp_ejs._version as ejs_ver

        return str(getattr(ejs_ver, "__version__", "desconocida"))
    except Exception:
        try:
            import yt_dlp_ejs

            return str(getattr(yt_dlp_ejs, "__version__", "instalada"))
        except Exception:
            return "no disponible"


def get_engine_versions(updates_dir: str) -> str:
    """Devuelve un JSON con las versiones activas de Python, yt-dlp y yt-dlp-ejs."""
    ensure_updates_in_sys_path(updates_dir)
    ytdlp_meta = _read_installed_meta(updates_dir, "yt-dlp")
    ejs_meta = _read_installed_meta(updates_dir, "yt-dlp-ejs")
    payload = {
        "python_version": f"Python {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
        "yt_dlp_version": _get_active_yt_dlp_version(updates_dir),
        "yt_dlp_ejs_version": _get_active_ejs_version(updates_dir),
        "yt_dlp_updated_at": ytdlp_meta.get("updated_at"),
        "yt_dlp_ejs_updated_at": ejs_meta.get("updated_at"),
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
        if entry.get("packagetype") == "bdist_wheel" and str(entry.get("filename", "")).endswith("py3-none-any.whl"):
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


def _download_and_install_wheel(updates_dir: str, pkg_name: str, version: str, wheel_url: str, expected_sha256: str) -> None:
    """Descarga el wheel .whl, valida SHA-256 y lo extrae atómicamente en updates_dir."""
    os.makedirs(updates_dir, exist_ok=True)
    target_dir = _updates_pkg_dir(updates_dir, pkg_name)
    module_prefix = PACKAGE_DIR_MAP[pkg_name][1]
    staging_dir = os.path.join(updates_dir, f".staging_{module_prefix}_{int(time.time() * 1000)}")
    backup_dir = os.path.join(updates_dir, f".backup_{module_prefix}_{int(time.time() * 1000)}")

    with tempfile.NamedTemporaryFile(suffix=".whl", delete=False) as tmp_file:
        tmp_wheel_path = tmp_file.name

    try:
        req = urllib.request.Request(
            wheel_url,
            headers={"User-Agent": "AuraMusic/1.0 (Android)"},
        )
        hasher = hashlib.sha256()
        with urllib.request.urlopen(req, timeout=45) as resp, open(tmp_wheel_path, "wb") as out_f:
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                hasher.update(chunk)
                out_f.write(chunk)

        actual_sha256 = hasher.hexdigest().lower()
        if expected_sha256 and actual_sha256 != expected_sha256:
            raise RuntimeError(
                f"Checksum SHA-256 inválido para {pkg_name} {version}: esperado {expected_sha256}, recibido {actual_sha256}"
            )

        os.makedirs(staging_dir, exist_ok=True)
        with zipfile.ZipFile(tmp_wheel_path, "r") as zf:
            for member in zf.infolist():
                # Solo extrae el paquete principal (yt_dlp/ o yt_dlp_ejs/) y su dist-info.
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
            except OSError:
                pass


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
        except Exception as exc:
            errors.append(f"{pkg_name}: {exc}")

    # Recarga módulos en memoria para aplicar las nuevas versiones sin reiniciar la app.
    _purge_loaded_modules(("yt_dlp", "yt_dlp_ejs"))
    ensure_updates_in_sys_path(updates_dir)

    payload = json.loads(get_engine_versions(updates_dir))
    payload["ok"] = len(errors) == 0
    if errors:
        payload["message"] = "Actualización parcial: " + " | ".join(updated_notes + errors)
    else:
        payload["message"] = " • ".join(updated_notes)
    return json.dumps(payload)


def download_audio(
    url: str,
    output_dir: str,
    updates_dir: str,
    qjs_binary_path: Optional[str],
    progress_callback: Optional[Callable[[str], None]] = None,
) -> str:
    """Descarga solo el flujo de audio compatible con ExoPlayer usando yt-dlp + yt-dlp-ejs + QuickJS-NG."""
    ensure_updates_in_sys_path(updates_dir)
    os.makedirs(output_dir, exist_ok=True)

    import yt_dlp

    last_emit_ts = [0.0]

    def emit(stage: str, percent: float = 0.0, downloaded: int = 0, total: int = 0, title: Optional[str] = None) -> None:
        if progress_callback is None:
            return
        now = time.monotonic()
        if stage == "downloading" and (now - last_emit_ts[0]) < 0.25 and percent < 99.5:
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
                        "title": title,
                    }
                )
            )
        except Exception:
            pass

    captured_title = [None]

    def progress_hook(status_dict: dict) -> None:
        status = status_dict.get("status")
        info = status_dict.get("info_dict") or {}
        title = info.get("title") or captured_title[0]
        if title:
            captured_title[0] = title

        if status == "downloading":
            downloaded = int(status_dict.get("downloaded_bytes") or 0)
            total = int(status_dict.get("total_bytes") or status_dict.get("total_bytes_estimate") or 0)
            percent = (downloaded * 100.0 / total) if total > 0 else 0.0
            emit("downloading", percent=percent, downloaded=downloaded, total=total, title=title)
        elif status == "finished":
            total = int(status_dict.get("total_bytes") or status_dict.get("downloaded_bytes") or 0)
            emit("importing", percent=100.0, downloaded=total, total=total, title=title)

    ydl_opts: dict = {
        # Prioriza audio puro nativo para Media3 ExoPlayer sin depender de FFmpeg (evita LGPL/GPL).
        "format": "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/best",
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

    emit("resolving", percent=0.0)

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
        if info is None:
            raise RuntimeError("yt-dlp no devolvió información de la pista.")

        if "entries" in info and info["entries"]:
            info = next((item for item in info["entries"] if item is not None), info)

        prepared_path = ydl.prepare_filename(info)
        requested = info.get("requested_downloads") or []
        audio_path = prepared_path
        if requested and isinstance(requested, list):
            filepath = requested[0].get("filepath")
            if filepath and os.path.exists(filepath):
                audio_path = filepath

        if not os.path.exists(audio_path):
            video_id = str(info.get("id") or "")
            for fname in os.listdir(output_dir):
                if video_id and fname.startswith(f"{video_id}.") and not fname.endswith((".webp", ".jpg", ".jpeg", ".png", ".part", ".ytdl")):
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
        artist = info.get("artist") or info.get("creator") or info.get("uploader") or info.get("channel") or "YouTube"
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
