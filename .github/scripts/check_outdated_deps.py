#!/usr/bin/env python3
"""Comprueba dependencias desactualizadas en Gradle (libs.versions.toml), Gradle Wrapper,
Rust (Cargo.toml) y Python (yt-dlp / yt-dlp-ejs) consultando Maven, Gradle, Crates.io y PyPI.

Genera un JSON estructurado y un log legible para incluirlos en el reporte HTML de auditoría.
Nunca aborta el workflow si falla una consulta de red individual.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from typing import Optional

try:
    import tomllib  # Python 3.11+
except ImportError:  # pragma: no cover
    tomllib = None  # type: ignore[assignment]


USER_AGENT = "AuraMusic-CI-Audit/1.0 (https://github.com/LuisAlejandro544/Aura-Music-Reimagined-)"
UNSTABLE_MARKERS = ("alpha", "beta", "rc", "dev", "snapshot", "canary", "-m")


@dataclass
class DependencyCheckResult:
    ecosystem: str
    name: str
    current_version: str
    latest_version: Optional[str]
    status: str  # "up-to-date" | "patch-update" | "minor-update" | "major-update" | "update-available" | "check-failed"
    registry_url: str
    note: str = ""


def _http_get_text(url: str, timeout: int = 10) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="replace")


def _is_stable_version(version: str) -> bool:
    lower = version.lower()
    return not any(marker in lower for marker in UNSTABLE_MARKERS)


def _version_tuple(version: str) -> tuple[int, ...]:
    cleaned = re.split(r"[-+]", version.strip(), maxsplit=1)[0]
    parts = []
    for token in cleaned.split("."):
        digits = re.match(r"^(\d+)", token)
        if digits:
            parts.append(int(digits.group(1)))
        else:
            break
    return tuple(parts)


def _classify_update(current: str, latest: Optional[str]) -> str:
    if not latest:
        return "check-failed"
    cur_norm = current.strip().lstrip("^~>=<!v ")
    lat_norm = latest.strip().lstrip("v")
    if cur_norm == lat_norm:
        return "up-to-date"

    # Manejo especial para versiones de Compose BOM (YYYY.MM.DD) o CalVer (YYYY.MM.DD)
    cur_parts = _version_tuple(cur_norm)
    lat_parts = _version_tuple(lat_norm)
    if not cur_parts or not lat_parts:
        return "update-available"
    if cur_parts >= lat_parts:
        return "up-to-date"

    if len(cur_parts) >= 1 and len(lat_parts) >= 1 and lat_parts[0] > cur_parts[0]:
        return "major-update"
    if len(cur_parts) >= 2 and len(lat_parts) >= 2 and lat_parts[1] > cur_parts[1]:
        return "minor-update"
    return "patch-update"


def _parse_toml(path: str) -> dict:
    with open(path, "rb") as f:
        if tomllib is not None:
            return tomllib.load(f)
    raise RuntimeError("Python 3.11+ (tomllib) es requerido para parsear TOML.")


def _fetch_maven_latest(group: str, artifact: str) -> tuple[Optional[str], str]:
    group_path = group.replace(".", "/")
    urls = []
    if group.startswith(("androidx.", "com.android.", "com.google.android.")):
        urls.append(f"https://dl.google.com/dl/android/maven2/{group_path}/{artifact}/maven-metadata.xml")
        urls.append(f"https://repo1.maven.org/maven2/{group_path}/{artifact}/maven-metadata.xml")
    else:
        urls.append(f"https://repo1.maven.org/maven2/{group_path}/{artifact}/maven-metadata.xml")
        urls.append(f"https://dl.google.com/dl/android/maven2/{group_path}/{artifact}/maven-metadata.xml")

    for url in urls:
        try:
            xml_text = _http_get_text(url, timeout=10)
            root = ET.fromstring(xml_text)
            versions = [v.text.strip() for v in root.findall(".//versioning/versions/version") if v.text]
            stable = [v for v in versions if _is_stable_version(v)]
            release = root.findtext(".//versioning/release")
            if stable:
                return stable[-1], url.rsplit("/maven-metadata.xml", 1)[0]
            if release:
                return release.strip(), url.rsplit("/maven-metadata.xml", 1)[0]
            if versions:
                return versions[-1], url.rsplit("/maven-metadata.xml", 1)[0]
        except Exception:
            continue
    return None, urls[0]


def check_gradle_wrapper(root_dir: str) -> list[DependencyCheckResult]:
    props_path = os.path.join(root_dir, "android/gradle/wrapper/gradle-wrapper.properties")
    if not os.path.exists(props_path):
        return []
    with open(props_path, "r", encoding="utf-8") as f:
        text = f.read()
    m = re.search(r"gradle-([0-9]+\.[0-9]+(?:\.[0-9]+)?)-(?:bin|all)\.zip", text)
    if not m:
        return []
    current = m.group(1)
    latest = None
    note = ""
    try:
        data = json.loads(_http_get_text("https://services.gradle.org/versions/current", timeout=10))
        latest = str(data.get("version") or "").strip() or None
    except Exception as exc:
        note = f"No se pudo consultar Gradle Services: {exc}"
    return [
        DependencyCheckResult(
            ecosystem="Gradle",
            name="gradle-wrapper",
            current_version=current,
            latest_version=latest,
            status=_classify_update(current, latest),
            registry_url="https://gradle.org/releases/",
            note=note,
        )
    ]


def check_version_catalog(root_dir: str) -> list[DependencyCheckResult]:
    toml_path = os.path.join(root_dir, "android/gradle/libs.versions.toml")
    if not os.path.exists(toml_path):
        return []
    data = _parse_toml(toml_path)
    versions = data.get("versions", {})
    libraries = data.get("libraries", {})
    plugins = data.get("plugins", {})

    # Mapea cada version.ref a un artefacto representativo de Maven para consultar su última versión.
    ref_to_coord: dict[str, tuple[str, str, str]] = {}

    plugin_coords = {
        "com.android.application": ("com.android.tools.build", "gradle"),
        "com.android.library": ("com.android.tools.build", "gradle"),
        "org.jetbrains.kotlin.android": ("org.jetbrains.kotlin", "kotlin-gradle-plugin"),
        "com.google.devtools.ksp": ("com.google.devtools.ksp", "symbol-processing-gradle-plugin"),
        "com.chaquo.python": ("com.chaquo.python", "gradle"),
    }

    for alias, entry in plugins.items():
        if isinstance(entry, dict):
            pid = entry.get("id", "")
            vref = entry.get("version", {}).get("ref") if isinstance(entry.get("version"), dict) else entry.get("version.ref")
            if vref and pid in plugin_coords and vref not in ref_to_coord:
                g, a = plugin_coords[pid]
                ref_to_coord[vref] = (alias, g, a)

    for alias, entry in libraries.items():
        if not isinstance(entry, dict):
            continue
        vref = entry.get("version", {}).get("ref") if isinstance(entry.get("version"), dict) else entry.get("version.ref")
        if not vref or vref in ref_to_coord:
            continue
        group = entry.get("group")
        name = entry.get("name")
        module = entry.get("module")
        if module and ":" in module:
            group, name = module.split(":", 1)
        if group and name:
            ref_to_coord[vref] = (alias, group, name)

    results: list[DependencyCheckResult] = []
    for vref, current in versions.items():
        if not isinstance(current, str):
            continue
        coord = ref_to_coord.get(vref)
        if not coord:
            continue
        alias, group, artifact = coord
        latest, reg_url = _fetch_maven_latest(group, artifact)
        note = ""
        if vref == "ksp" and latest and not latest.startswith(str(versions.get("kotlin", ""))):
            note = "KSP debe mantenerse alineado con la versión mayor/menor de Kotlin."
        results.append(
            DependencyCheckResult(
                ecosystem="Android / Maven",
                name=f"{group}:{artifact} ({vref})",
                current_version=current,
                latest_version=latest,
                status=_classify_update(current, latest),
                registry_url=reg_url,
                note=note,
            )
        )
    return results


def check_rust_crates(root_dir: str) -> list[DependencyCheckResult]:
    cargo_path = os.path.join(root_dir, "android/rust/aura_core/Cargo.toml")
    if not os.path.exists(cargo_path):
        return []
    data = _parse_toml(cargo_path)
    deps = data.get("dependencies", {})
    results: list[DependencyCheckResult] = []

    for crate_name, spec in deps.items():
        if isinstance(spec, str):
            current = spec
        elif isinstance(spec, dict):
            current = str(spec.get("version") or "").strip()
        else:
            continue
        if not current:
            continue

        latest = None
        note = ""
        url = f"https://crates.io/api/v1/crates/{crate_name}"
        try:
            payload = json.loads(_http_get_text(url, timeout=10))
            crate_info = payload.get("crate", {})
            latest = str(
                crate_info.get("max_stable_version")
                or crate_info.get("max_version")
                or crate_info.get("newest_version")
                or ""
            ).strip() or None
        except Exception as exc:
            note = f"Error consultando crates.io: {exc}"

        results.append(
            DependencyCheckResult(
                ecosystem="Rust (crates.io)",
                name=crate_name,
                current_version=current,
                latest_version=latest,
                status=_classify_update(current, latest),
                registry_url=f"https://crates.io/crates/{crate_name}",
                note=note,
            )
        )
    return results


def check_python_packages(root_dir: str) -> list[DependencyCheckResult]:
    dl_gradle_path = os.path.join(root_dir, "android/core/downloader/build.gradle.kts")
    if not os.path.exists(dl_gradle_path):
        return []
    with open(dl_gradle_path, "r", encoding="utf-8") as f:
        gradle_text = f.read()

    pkgs = re.findall(r'install\(\s*"([^"]+)"\s*\)', gradle_text)
    results: list[DependencyCheckResult] = []
    for raw_pkg in pkgs:
        pkg_name = raw_pkg.split("==")[0].split(">=")[0].strip()
        pinned = raw_pkg.split("==")[1].strip() if "==" in raw_pkg else "latest (en compilación)"
        latest = None
        note = "Sin versión fija en build.gradle.kts; la app también auto-actualiza desde PyPI en runtime."
        try:
            payload = json.loads(_http_get_text(f"https://pypi.org/pypi/{pkg_name}/json", timeout=10))
            latest = str(payload.get("info", {}).get("version") or "").strip() or None
        except Exception as exc:
            note = f"Error consultando PyPI: {exc}"

        status = "up-to-date" if "==" not in raw_pkg and latest else _classify_update(pinned, latest)
        results.append(
            DependencyCheckResult(
                ecosystem="Python (PyPI)",
                name=pkg_name,
                current_version=pinned,
                latest_version=latest,
                status=status,
                registry_url=f"https://pypi.org/project/{pkg_name}/",
                note=note,
            )
        )
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description="Audita dependencias viejas en Gradle, Rust y Python.")
    parser.add_argument("--root", default=".", help="Raíz del repositorio")
    parser.add_argument("--output-json", required=True, help="Ruta de salida JSON")
    parser.add_argument("--output-log", required=True, help="Ruta de salida de log legible")
    args = parser.parse_args()

    results: list[DependencyCheckResult] = []
    results.extend(check_gradle_wrapper(args.root))
    results.extend(check_version_catalog(args.root))
    results.extend(check_rust_crates(args.root))
    results.extend(check_python_packages(args.root))

    outdated_count = sum(
        1 for r in results if r.status in ("patch-update", "minor-update", "major-update", "update-available")
    )

    payload = {
        "total": len(results),
        "outdated_count": outdated_count,
        "dependencies": [asdict(r) for r in results],
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.output_json)), exist_ok=True)
    with open(args.output_json, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)

    lines = [
        f"=== Auditoría de dependencias ({len(results)} analizadas, {outdated_count} con actualización disponible) ===",
        "",
    ]
    for item in results:
        tag = {
            "up-to-date": "OK",
            "patch-update": "PATCH",
            "minor-update": "MINOR",
            "major-update": "MAJOR",
            "update-available": "UPDATE",
            "check-failed": "WARN",
        }.get(item.status, item.status.upper())
        latest_str = item.latest_version or "?"
        note_str = f" | {item.note}" if item.note else ""
        lines.append(f"[{tag}] [{item.ecosystem}] {item.name}: {item.current_version} -> {latest_str}{note_str}")

    os.makedirs(os.path.dirname(os.path.abspath(args.output_log)), exist_ok=True)
    with open(args.output_log, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print(f"Checked {len(results)} dependencies ({outdated_count} outdated).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
