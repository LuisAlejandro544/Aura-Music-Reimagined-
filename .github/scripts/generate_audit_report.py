"""Genera un reporte HTML interactivo y autocontenido + paquete .zip de artefactos
con todos los errores crudos, warnings, resultados de lint (Kotlin/Android, Rust, Python,
Integridad) y dependencias desactualizadas de Aura Music.
"""

from __future__ import annotations

import argparse
import glob
import html
import json
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from typing import Any


@dataclass
class DiagnosticItem:
    id: str
    category: str  # kotlin | rust | python | integrity | dependencies
    severity: str  # error | warning | info
    tool: str
    file: str
    line: str
    message: str
    raw_line: str


@dataclass
class ToolLogSection:
    id: str
    title: str
    category: str
    status: str  # passed | failed | skipped
    exit_code: int | None
    error_count: int
    warning_count: int
    raw_text: str


LOG_SPECS = [
    ("integrity-and-web.log", "Integridad estructural y entorno Web", "integrity"),
    ("python-lint.log", "Python (py_compile, ruff, mypy)", "python"),
    ("rust-lint-and-test.log", "Rust (cargo fmt, check, clippy, test, ndk)", "rust"),
    ("kotlin-ktlint.log", "Kotlin Style & Lint (ktlint)", "kotlin"),
    ("gradle-lint.log", "Android Lint (./gradlew lintDebug --continue)", "kotlin"),
    (
        "gradle-compile-and-test.log",
        "Compilación APK & Unit Tests (./gradlew --continue)",
        "kotlin",
    ),
    ("room-schema-diff.log", "Verificación de esquemas Room (schemas/)", "integrity"),
    (
        "outdated-deps.log",
        "Dependencias desactualizadas (Maven / Crates.io / PyPI)",
        "dependencies",
    ),
]


def _read_text(path: str) -> str:
    if not os.path.exists(path):
        return ""
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def _parse_diagnostics_from_log(
    tool_id: str,
    title: str,
    category: str,
    text: str,
) -> list[DiagnosticItem]:
    items: list[DiagnosticItem] = []
    seen: set[tuple[str, str, str]] = set()
    lines = text.splitlines()

    for idx, line in enumerate(lines):
        stripped = line.strip()
        if not stripped:
            continue

        severity: str | None = None
        file_ref = ""
        line_ref = ""
        msg = stripped

        # Kotlin compiler: e: file:///.../Foo.kt:12:5 message | w: ...
        m_kt = re.match(
            r"^(e|w):\s+(?:file://)?(.+?):(\d+)(?::\d+)?\s+(.*)$",
            stripped,
        )
        # ktlint / ruff / mypy: path/file.py:12:5: E501 message
        m_std = re.match(
            r"^([^\s:]+\.(?:kt|kts|py|rs|ts|tsx)):(\d+)(?::\d+)?:\s+(.+)$",
            stripped,
        )
        # Rust cargo / clippy: error[E0308]: ... or warning: ...
        m_rs = re.match(r"^(error(?:\[[^\]]+\])?|warning):\s+(.+)$", stripped)

        if m_kt:
            severity = "error" if m_kt.group(1) == "e" else "warning"
            file_ref, line_ref, msg = m_kt.group(2), m_kt.group(3), m_kt.group(4)
        elif m_std:
            file_ref, line_ref, msg = m_std.group(1), m_std.group(2), m_std.group(3)
            lower_msg = msg.lower()
            is_err = (
                "error" in lower_msg or "syntaxerror" in lower_msg or "fail" in lower_msg
            )
            severity = "error" if is_err else "warning"
        elif m_rs:
            kind, msg = m_rs.group(1), m_rs.group(2)
            if "generated" in msg and "warning" in msg:
                continue
            severity = "error" if kind.startswith("error") else "warning"
            if idx + 1 < len(lines):
                m_loc = re.match(r"^\s*-->\s+(.+?):(\d+):\d+", lines[idx + 1])
                if m_loc:
                    file_ref, line_ref = m_loc.group(1), m_loc.group(2)
        elif any(
            token in stripped
            for token in (
                "FAILURE:",
                "* What went wrong:",
                "Execution failed for task",
                "not ok ",
                "FAIL ",
                "SyntaxError:",
                "[ERROR]",
            )
        ):
            severity = "error"
        elif (
            stripped.startswith(("w: ", "[WARN]", "WARNING:"))
            or "[WARN]" in stripped
            or "WARNING:" in stripped
        ):
            severity = "warning"

        if severity is not None:
            key = (severity, file_ref, msg)
            if key in seen:
                continue
            seen.add(key)
            items.append(
                DiagnosticItem(
                    id=f"{tool_id}-{len(items) + 1}",
                    category=category,
                    severity=severity,
                    tool=title,
                    file=file_ref or "-",
                    line=line_ref or "-",
                    message=msg,
                    raw_line=stripped,
                )
            )
    return items


def _parse_android_lint_xmls(
    android_dir: str,
    copy_dest_dir: str,
) -> tuple[list[DiagnosticItem], int]:
    items: list[DiagnosticItem] = []
    copied_reports = 0
    seen: set[tuple[str, str, str, str]] = set()

    if not os.path.isdir(android_dir):
        return items, copied_reports

    os.makedirs(copy_dest_dir, exist_ok=True)
    pattern = os.path.join(android_dir, "**", "build", "reports", "lint-results-*.*")
    for report_path in sorted(glob.glob(pattern, recursive=True)):
        rel = os.path.relpath(report_path, android_dir).replace(os.sep, "__")
        shutil.copy2(report_path, os.path.join(copy_dest_dir, rel))
        copied_reports += 1

        if not report_path.endswith(".xml"):
            continue
        try:
            tree = ET.parse(report_path)
            root = tree.getroot()
        except (ET.ParseError, OSError) as exc:
            sys.stderr.write(
                f"Advertencia leyendo reporte XML {report_path}: {exc}\n"
            )
            continue

        for issue in root.findall("issue"):
            sev_raw = (issue.attrib.get("severity") or "Warning").lower()
            if sev_raw in ("fatal", "error"):
                severity = "error"
            elif sev_raw == "warning":
                severity = "warning"
            else:
                severity = "info"

            issue_id = issue.attrib.get("id", "LintIssue")
            summary = issue.attrib.get("message", "")
            loc = issue.find("location")
            file_ref = loc.attrib.get("file", "-") if loc is not None else "-"
            line_ref = loc.attrib.get("line", "-") if loc is not None else "-"
            key = (issue_id, severity, file_ref, line_ref)
            if key in seen:
                continue
            seen.add(key)

            items.append(
                DiagnosticItem(
                    id=f"lint-{len(items) + 1}",
                    category="kotlin",
                    severity=severity,
                    tool=f"Android Lint ({issue_id})",
                    file=file_ref,
                    line=line_ref,
                    message=f"[{issue_id}] {summary}",
                    raw_line=(
                        f"[{sev_raw.upper()}] {issue_id} at "
                        f"{file_ref}:{line_ref} — {summary}"
                    ),
                )
            )

    return items, copied_reports


def _render_html(payload: dict[str, Any]) -> str:
    safe_json = json.dumps(payload, ensure_ascii=False).replace("</", "<\\/")
    meta = payload["meta"]
    summary = payload["summary"]

    return f"""<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Aura Music — Reporte de Lint y Auditoría</title>
  <style>
    :root {{
      --bg: #0c0d14;
      --surface: #141724;
      --surface-2: #1c2033;
      --border: #2b314d;
      --text: #eef1ff;
      --muted: #9aa4c7;
      --accent: #7c6bff;
      --err: #ff5f6d;
      --err-bg: rgba(255, 95, 109, 0.14);
      --warn: #f7b955;
      --warn-bg: rgba(247, 185, 85, 0.14);
      --ok: #3ad29f;
      --ok-bg: rgba(58, 210, 159, 0.14);
      --info: #5bc0ff;
      --info-bg: rgba(91, 192, 255, 0.14);
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
      background: var(--bg);
      color: var(--text);
      line-height: 1.5;
    }}
    .wrap {{
      max-width: 1200px;
      margin: 0 auto;
      padding: 16px;
    }}
    header {{
      background: linear-gradient(135deg, #1a1834, #121523);
      border: 1px solid var(--border);
      border-radius: 16px;
      padding: 20px;
      margin-bottom: 16px;
    }}
    h1 {{
      margin: 0 0 6px;
      font-size: 1.4rem;
    }}
    .meta {{
      color: var(--muted);
      font-size: 0.85rem;
      display: flex;
      flex-wrap: wrap;
      gap: 10px 18px;
    }}
    .cards {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: 12px;
      margin-bottom: 16px;
    }}
    .card {{
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 14px 16px;
    }}
    .card-label {{
      font-size: 0.8rem;
      color: var(--muted);
    }}
    .card-val {{
      font-size: 1.7rem;
      font-weight: 700;
      margin-top: 4px;
    }}
    .c-err {{ color: var(--err); }}
    .c-warn {{ color: var(--warn); }}
    .c-info {{ color: var(--info); }}
    .c-ok {{ color: var(--ok); }}

    .tabs {{
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 14px;
    }}
    .tab-btn {{
      border: 1px solid var(--border);
      background: var(--surface);
      color: var(--text);
      padding: 9px 14px;
      border-radius: 10px;
      cursor: pointer;
      font-weight: 600;
      font-size: 0.9rem;
    }}
    .tab-btn.active {{
      background: var(--accent);
      border-color: var(--accent);
      color: #fff;
    }}

    .toolbar {{
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      margin-bottom: 14px;
      align-items: center;
    }}
    input[type="search"], select {{
      background: var(--surface);
      border: 1px solid var(--border);
      color: var(--text);
      padding: 9px 12px;
      border-radius: 10px;
      font-size: 0.9rem;
    }}
    input[type="search"] {{ flex: 1 1 240px; }}

    .panel {{
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 14px;
      overflow: hidden;
      margin-bottom: 14px;
    }}
    .diag-item {{
      padding: 12px 14px;
      border-bottom: 1px solid var(--border);
    }}
    .diag-item:last-child {{ border-bottom: none; }}
    .diag-head {{
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
      margin-bottom: 6px;
      font-size: 0.82rem;
    }}
    .badge {{
      display: inline-block;
      padding: 2px 8px;
      border-radius: 6px;
      font-weight: 700;
      font-size: 0.75rem;
      text-transform: uppercase;
    }}
    .b-error {{ background: var(--err-bg); color: var(--err); }}
    .b-warning {{ background: var(--warn-bg); color: var(--warn); }}
    .b-info {{ background: var(--info-bg); color: var(--info); }}
    .b-ok {{ background: var(--ok-bg); color: var(--ok); }}
    .diag-file {{
      color: var(--muted);
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      word-break: break-all;
    }}
    .diag-raw {{
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 0.82rem;
      background: var(--surface-2);
      padding: 8px 10px;
      border-radius: 8px;
      white-space: pre-wrap;
      word-break: break-word;
      margin: 0;
    }}

    .table-wrap {{
      overflow-x: auto;
    }}
    table {{
      width: 100%;
      border-collapse: collapse;
      font-size: 0.88rem;
    }}
    th, td {{
      padding: 10px 12px;
      border-bottom: 1px solid var(--border);
      text-align: left;
    }}
    th {{
      background: var(--surface-2);
      color: var(--muted);
      font-size: 0.78rem;
      text-transform: uppercase;
    }}

    details.log-box {{
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 14px;
      margin-bottom: 12px;
      overflow: hidden;
    }}
    details.log-box summary {{
      padding: 14px 16px;
      cursor: pointer;
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 10px;
      font-weight: 600;
      list-style: none;
    }}
    .log-actions {{
      display: flex;
      align-items: center;
      gap: 8px;
    }}
    .copy-btn {{
      background: var(--surface-2);
      color: var(--text);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 5px 10px;
      font-size: 0.78rem;
      cursor: pointer;
    }}
    .copy-btn:hover {{ border-color: var(--accent); }}
    pre.raw-log {{
      margin: 0;
      padding: 14px;
      background: #090a10;
      border-top: 1px solid var(--border);
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 0.78rem;
      overflow-x: auto;
      max-height: 520px;
      overflow-y: auto;
      white-space: pre-wrap;
      word-break: break-word;
    }}
    .hl-err {{ color: #ff7b86; display: block; }}
    .hl-warn {{ color: #ffd17a; display: block; }}
    .hl-ok {{ color: #5de8b8; display: block; }}
    .empty {{
      padding: 28px;
      text-align: center;
      color: var(--muted);
    }}
    .hidden {{ display: none !important; }}
  </style>
</head>
<body>
  <div class="wrap">
    <header>
      <h1>Aura Music — Auditoría &amp; Lint Multi-Lenguaje</h1>
      <div class="meta">
        <span><strong>Commit:</strong> {html.escape(meta['sha'][:12])}</span>
        <span><strong>Rama:</strong> {html.escape(meta['branch'])}</span>
        <span><strong>Generado (UTC):</strong> {html.escape(meta['generated_at'])}</span>
        <span><strong>Reportes Android Lint XML/HTML adjuntos:</strong> {summary['lint_report_files']}</span>
      </div>
    </header>

    <section class="cards">
      <div class="card">
        <div class="card-label">Errores crudos</div>
        <div class="card-val c-err">{summary['total_errors']}</div>
      </div>
      <div class="card">
        <div class="card-label">Warnings</div>
        <div class="card-val c-warn">{summary['total_warnings']}</div>
      </div>
      <div class="card">
        <div class="card-label">Dependencias viejas</div>
        <div class="card-val c-info">{summary['outdated_dependencies']}</div>
      </div>
      <div class="card">
        <div class="card-label">Etapas sin fallo</div>
        <div class="card-val c-ok">{summary['passed_sections']} / {summary['total_sections']}</div>
      </div>
    </section>

    <nav class="tabs" id="main-tabs">
      <button class="tab-btn active" data-tab="diagnostics">Diagnósticos ({len(payload['diagnostics'])})</button>
      <button class="tab-btn" data-tab="dependencies">Dependencias viejas ({summary['outdated_dependencies']} / {len(payload['dependencies'])})</button>
      <button class="tab-btn" data-tab="rawlogs">Logs crudos completos ({len(payload['sections'])})</button>
    </nav>

    <section id="tab-diagnostics">
      <div class="toolbar">
        <input type="search" id="diag-search" placeholder="Buscar error, archivo, regla, mensaje..." />
        <select id="diag-category">
          <option value="all">Todas las áreas</option>
          <option value="kotlin">Kotlin / Android</option>
          <option value="rust">Rust</option>
          <option value="python">Python</option>
          <option value="integrity">Integridad / Room / Web</option>
        </select>
        <select id="diag-severity">
          <option value="all">Todas las severidades</option>
          <option value="error">Solo Errores</option>
          <option value="warning">Solo Warnings</option>
          <option value="info">Info</option>
        </select>
      </div>
      <div class="panel" id="diag-list"></div>
    </section>

    <section id="tab-dependencies" class="hidden">
      <div class="toolbar">
        <input type="search" id="dep-search" placeholder="Filtrar librería, crate o paquete..." />
        <select id="dep-status">
          <option value="all">Todas</option>
          <option value="outdated">Solo desactualizadas</option>
          <option value="up-to-date">Al día</option>
          <option value="check-failed">No verificadas</option>
        </select>
      </div>
      <div class="panel table-wrap">
        <table>
          <thead>
            <tr>
              <th>Ecosistema</th>
              <th>Dependencia</th>
              <th>Actual</th>
              <th>Última estable</th>
              <th>Estado</th>
              <th>Notas</th>
            </tr>
          </thead>
          <tbody id="dep-tbody"></tbody>
        </table>
      </div>
    </section>

    <section id="tab-rawlogs" class="hidden">
      <div class="toolbar">
        <input type="search" id="log-search" placeholder="Filtrar texto dentro de los logs crudos..." />
      </div>
      <div id="rawlogs-container"></div>
    </section>
  </div>

  <script id="audit-data" type="application/json">{safe_json}</script>
  <script>
    const DATA = JSON.parse(document.getElementById('audit-data').textContent);

    function esc(s) {{
      return String(s ?? '').replace(/[&<>"']/g, c => ({{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}}[c]));
    }}

    // Tabs
    document.querySelectorAll('#main-tabs .tab-btn').forEach(btn => {{
      btn.addEventListener('click', () => {{
        document.querySelectorAll('#main-tabs .tab-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        const target = btn.dataset.tab;
        ['diagnostics', 'dependencies', 'rawlogs'].forEach(t => {{
          document.getElementById('tab-' + t).classList.toggle('hidden', t !== target);
        }});
      }});
    }});

    // Diagnostics renderer
    function renderDiagnostics() {{
      const q = document.getElementById('diag-search').value.toLowerCase().trim();
      const cat = document.getElementById('diag-category').value;
      const sev = document.getElementById('diag-severity').value;
      const filtered = DATA.diagnostics.filter(d => {{
        if (cat !== 'all' && d.category !== cat) return false;
        if (sev !== 'all' && d.severity !== sev) return false;
        if (q) {{
          const hay = (d.tool + ' ' + d.file + ' ' + d.message + ' ' + d.raw_line).toLowerCase();
          if (!hay.includes(q)) return false;
        }}
        return true;
      }});
      const box = document.getElementById('diag-list');
      if (!filtered.length) {{
        box.innerHTML = '<div class="empty">No hay diagnósticos que coincidan con este filtro.</div>';
        return;
      }}
      box.innerHTML = filtered.slice(0, 600).map(d => {{
        const badgeCls = d.severity === 'error' ? 'b-error' : (d.severity === 'warning' ? 'b-warning' : 'b-info');
        const loc = d.file !== '-' ? (d.line !== '-' ? d.file + ':' + d.line : d.file) : '';
        return `
          <div class="diag-item">
            <div class="diag-head">
              <span class="badge ${{badgeCls}}">${{esc(d.severity)}}</span>
              <span class="badge b-info">${{esc(d.category)}}</span>
              <strong>${{esc(d.tool)}}</strong>
              ${{loc ? `<span class="diag-file">${{esc(loc)}}</span>` : ''}}
            </div>
            <pre class="diag-raw">${{esc(d.raw_line)}}</pre>
          </div>
        `;
      }}).join('') + (filtered.length > 600 ? `<div class="empty">Mostrando 600 de ${{filtered.length}} diagnósticos (usa el buscador o revisa los logs crudos).</div>` : '');
    }}

    // Dependencies renderer
    function renderDependencies() {{
      const q = document.getElementById('dep-search').value.toLowerCase().trim();
      const st = document.getElementById('dep-status').value;
      const filtered = DATA.dependencies.filter(dep => {{
        const isOutdated = ['patch-update','minor-update','major-update','update-available'].includes(dep.status);
        if (st === 'outdated' && !isOutdated) return false;
        if (st === 'up-to-date' && dep.status !== 'up-to-date') return false;
        if (st === 'check-failed' && dep.status !== 'check-failed') return false;
        if (q && !(dep.name + ' ' + dep.ecosystem + ' ' + dep.note).toLowerCase().includes(q)) return false;
        return true;
      }});
      const tbody = document.getElementById('dep-tbody');
      if (!filtered.length) {{
        tbody.innerHTML = '<tr><td colspan="6" class="empty">Sin dependencias para este filtro.</td></tr>';
        return;
      }}
      tbody.innerHTML = filtered.map(dep => {{
        let badge = 'b-ok';
        if (dep.status === 'major-update') badge = 'b-error';
        else if (dep.status === 'minor-update' || dep.status === 'patch-update' || dep.status === 'update-available') badge = 'b-warning';
        else if (dep.status === 'check-failed') badge = 'b-info';
        return `
          <tr>
            <td>${{esc(dep.ecosystem)}}</td>
            <td><a href="${{esc(dep.registry_url)}}" target="_blank" rel="noopener" style="color:#b8b1ff">${{esc(dep.name)}}</a></td>
            <td><code>${{esc(dep.current_version)}}</code></td>
            <td><code>${{esc(dep.latest_version || 'N/D')}}</code></td>
            <td><span class="badge ${{badge}}">${{esc(dep.status)}}</span></td>
            <td style="color:var(--muted)">${{esc(dep.note)}}</td>
          </tr>
        `;
      }}).join('');
    }}

    // Raw logs renderer
    function highlightLog(text) {{
      return text.split('\\n').map(line => {{
        const low = line.toLowerCase();
        const escaped = esc(line);
        if (low.includes('error') || low.startsWith('e: ') || low.includes('failure:') || low.startsWith('not ok ')) {{
          return `<span class="hl-err">${{escaped}}</span>`;
        }}
        if (low.includes('warning') || low.startsWith('w: ') || low.includes('[warn]')) {{
          return `<span class="hl-warn">${{escaped}}</span>`;
        }}
        if (low.startsWith('ok ') || low.includes('build successful') || low.includes('[ok]')) {{
          return `<span class="hl-ok">${{escaped}}</span>`;
        }}
        return escaped + '\\n';
      }}).join('');
    }}

    function renderRawLogs() {{
      const q = document.getElementById('log-search').value.toLowerCase().trim();
      const box = document.getElementById('rawlogs-container');
      const sections = DATA.sections.filter(s => !q || s.title.toLowerCase().includes(q) || s.raw_text.toLowerCase().includes(q));
      box.innerHTML = sections.map(sec => {{
        const statusCls = sec.status === 'passed' ? 'b-ok' : (sec.status === 'failed' ? 'b-error' : 'b-info');
        const openAttr = sec.status === 'failed' || sec.error_count > 0 ? 'open' : '';
        return `
          <details class="log-box" ${{openAttr}}>
            <summary>
              <span>${{esc(sec.title)}}</span>
              <span class="log-actions">
                <span class="badge ${{statusCls}}">${{esc(sec.status)}}${{sec.exit_code !== null ? ' (exit ' + sec.exit_code + ')' : ''}}</span>
                ${{sec.error_count ? `<span class="badge b-error">${{sec.error_count}} err</span>` : ''}}
                ${{sec.warning_count ? `<span class="badge b-warning">${{sec.warning_count}} warn</span>` : ''}}
                <button type="button" class="copy-btn" data-copy-id="${{esc(sec.id)}}">Copiar log</button>
              </span>
            </summary>
            <pre class="raw-log">${{highlightLog(sec.raw_text || '(Sin salida registrada)')}}</pre>
          </details>
        `;
      }}).join('');

      box.querySelectorAll('.copy-btn').forEach(btn => {{
        btn.addEventListener('click', (e) => {{
          e.preventDefault();
          e.stopPropagation();
          const sec = DATA.sections.find(s => s.id === btn.dataset.copyId);
          if (!sec) return;
          navigator.clipboard?.writeText(sec.raw_text).then(() => {{
            btn.textContent = '¡Copiado!';
            setTimeout(() => btn.textContent = 'Copiar log', 1500);
          }});
        }});
      }});
    }}

    ['diag-search', 'diag-category', 'diag-severity'].forEach(id => {{
      document.getElementById(id).addEventListener('input', renderDiagnostics);
      document.getElementById(id).addEventListener('change', renderDiagnostics);
    }});
    ['dep-search', 'dep-status'].forEach(id => {{
      document.getElementById(id).addEventListener('input', renderDependencies);
      document.getElementById(id).addEventListener('change', renderDependencies);
    }});
    document.getElementById('log-search').addEventListener('input', renderRawLogs);

    renderDiagnostics();
    renderDependencies();
    renderRawLogs();
  </script>
</body>
</html>
"""


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Genera index.html interactivo y ZIP de auditoría."
    )
    parser.add_argument("--logs-dir", required=True)
    parser.add_argument("--deps-json", required=True)
    parser.add_argument("--android-dir", default="android")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--zip-path", required=True)
    parser.add_argument("--exit-codes-json", default="")
    args = parser.parse_args()

    exit_codes: dict[str, int] = {}
    if args.exit_codes_json and os.path.exists(args.exit_codes_json):
        with open(args.exit_codes_json, encoding="utf-8") as f:
            exit_codes = json.load(f)

    deps_payload: dict[str, Any] = {"dependencies": [], "outdated_count": 0}
    if os.path.exists(args.deps_json):
        with open(args.deps_json, encoding="utf-8") as f:
            deps_payload = json.load(f)

    os.makedirs(args.output_dir, exist_ok=True)
    raw_dest = os.path.join(args.output_dir, "raw-logs")
    os.makedirs(raw_dest, exist_ok=True)

    diagnostics: list[DiagnosticItem] = []
    sections: list[ToolLogSection] = []

    for filename, title, category in LOG_SPECS:
        log_path = os.path.join(args.logs_dir, filename)
        raw_text = _read_text(log_path)
        if os.path.exists(log_path):
            shutil.copy2(log_path, os.path.join(raw_dest, filename))

        tool_id = filename.replace(".log", "")
        parsed = _parse_diagnostics_from_log(tool_id, title, category, raw_text)
        diagnostics.extend(parsed)

        code = exit_codes.get(tool_id)
        if not os.path.exists(log_path) and code is None:
            status = "skipped"
        elif code is not None and code != 0:
            status = "failed"
        else:
            status = "passed"

        sections.append(
            ToolLogSection(
                id=tool_id,
                title=title,
                category=category,
                status=status,
                exit_code=code,
                error_count=sum(1 for d in parsed if d.severity == "error"),
                warning_count=sum(1 for d in parsed if d.severity == "warning"),
                raw_text=raw_text,
            )
        )

    lint_dest = os.path.join(args.output_dir, "android-lint-reports")
    lint_items, copied_lint_reports = _parse_android_lint_xmls(
        args.android_dir,
        lint_dest,
    )
    diagnostics.extend(lint_items)

    # Copia esquemas Room generados por KSP si existieran para inspección directa
    schemas_src = os.path.join(args.android_dir, "core/database/schemas")
    if os.path.isdir(schemas_src):
        shutil.copytree(
            schemas_src,
            os.path.join(args.output_dir, "room-schemas"),
            dirs_exist_ok=True,
        )

    if os.path.exists(args.deps_json):
        shutil.copy2(
            args.deps_json,
            os.path.join(args.output_dir, "outdated-deps.json"),
        )

    total_errors = sum(1 for d in diagnostics if d.severity == "error")
    total_warnings = sum(1 for d in diagnostics if d.severity == "warning")
    passed_sections = sum(1 for s in sections if s.status == "passed")
    raw_outdated = deps_payload.get("outdated_count", 0)
    outdated_dependencies = (
        raw_outdated if isinstance(raw_outdated, int) else int(str(raw_outdated))
    )

    payload: dict[str, Any] = {
        "meta": {
            "sha": os.environ.get("GITHUB_SHA", "local"),
            "branch": os.environ.get("GITHUB_REF_NAME", "local"),
            "generated_at": datetime.now(UTC).strftime("%Y-%m-%d %H:%M:%S UTC"),
        },
        "summary": {
            "total_errors": total_errors,
            "total_warnings": total_warnings,
            "outdated_dependencies": outdated_dependencies,
            "passed_sections": passed_sections,
            "total_sections": len(sections),
            "lint_report_files": copied_lint_reports,
        },
        "diagnostics": [asdict(d) for d in diagnostics],
        "dependencies": deps_payload.get("dependencies", []),
        "sections": [asdict(s) for s in sections],
    }

    with open(os.path.join(args.output_dir, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(payload["summary"], f, indent=2, ensure_ascii=False)

    html_content = _render_html(payload)
    with open(os.path.join(args.output_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write(html_content)

    os.makedirs(os.path.dirname(os.path.abspath(args.zip_path)), exist_ok=True)
    with zipfile.ZipFile(args.zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for root, _, files in os.walk(args.output_dir):
            for fname in sorted(files):
                full_path = os.path.join(root, fname)
                rel_path = os.path.relpath(full_path, args.output_dir)
                zf.write(full_path, arcname=rel_path)

    print(
        f"Generated {args.zip_path} with {total_errors} errors, "
        f"{total_warnings} warnings."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
