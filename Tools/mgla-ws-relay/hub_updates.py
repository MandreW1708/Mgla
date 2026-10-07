#!/usr/bin/env python3
"""In-app update metadata + admin page for Mgla Hub.

Update detection is driven by app_version (e.g. 12.10.6) and/or version_code
(build). mgla_version is for display in the client sheet only.
"""

from __future__ import annotations

import hashlib
import html
import os
import re
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Optional, Tuple
from urllib.parse import quote

UPDATES_DIR = Path(os.environ.get("MGLA_UPDATES_DIR", "/opt/mgla-ws-relay/updates"))
APK_NAME = "mgla-latest.apk"
MAX_APK_BYTES = int(os.environ.get("MGLA_UPDATES_MAX_BYTES", str(200 * 1024 * 1024)))
MAX_CHANGELOG = 12_000
VERSION_RE = re.compile(r"^[vV]?\d{1,4}(\.\d{1,4}){0,3}([a-zA-Z0-9_-]{0,16})?$")
CTRL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def normalize_mgla_version(raw: str) -> str:
    s = (raw or "").strip()
    if not s:
        return ""
    if not s.lower().startswith("v"):
        s = "v" + s
    return s


def parse_version_tuple(raw: str) -> Tuple[int, ...]:
    s = (raw or "").strip().lstrip("vV")
    if not s:
        return (0,)
    parts = []
    for chunk in s.split("."):
        m = re.match(r"^(\d+)", chunk)
        parts.append(int(m.group(1)) if m else 0)
    return tuple(parts) if parts else (0,)


def version_greater(a: str, b: str) -> bool:
    """True if a > b (semver-ish numeric parts)."""
    pa, pb = parse_version_tuple(a), parse_version_tuple(b)
    n = max(len(pa), len(pb))
    pa = pa + (0,) * (n - len(pa))
    pb = pb + (0,) * (n - len(pb))
    return pa > pb


def version_code_base(code: int) -> int:
    """Normalize to gradle APP_VERSION_CODE.

    Installed APKs use {@code APP_VERSION_CODE * 10 + abi} (7115 → 71151/71159).
    Admin form stores the base (7115). Full codes are >= 10000 in this project.
    """
    if code <= 0:
        return 0
    if code >= 10000:
        return code // 10
    return code


def version_code_newer(hub_code: int, client_code: int) -> bool:
    """True if hub build base is strictly greater than the installed APK base."""
    if hub_code <= 0 or client_code < 0:
        return False
    return version_code_base(hub_code) > version_code_base(client_code)


def validate_mgla_version(raw: str) -> str:
    s = normalize_mgla_version(raw)
    if not s or not VERSION_RE.match(s):
        raise ValueError("некорректная версия Mgla (пример: v1.5.1)")
    return s


def validate_app_version(raw: str) -> str:
    s = (raw or "").strip()
    if not s:
        return ""
    if not VERSION_RE.match(s if s.lower().startswith("v") else "v" + s):
        raise ValueError("некорректная версия приложения (пример: 12.10.6)")
    return s.lstrip("vV")


class UpdateStore:
    def __init__(self, db: sqlite3.Connection, lock: threading.Lock):
        self._db = db
        self._lock = lock
        UPDATES_DIR.mkdir(parents=True, exist_ok=True)
        with self._lock, self._db:
            self._db.execute(
                """CREATE TABLE IF NOT EXISTS app_update (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    mgla_version TEXT NOT NULL DEFAULT '',
                    app_version TEXT NOT NULL DEFAULT '',
                    version_code INTEGER NOT NULL DEFAULT 0,
                    changelog TEXT NOT NULL DEFAULT '',
                    file_name TEXT NOT NULL DEFAULT '',
                    file_sha256 TEXT NOT NULL DEFAULT '',
                    file_size INTEGER NOT NULL DEFAULT 0,
                    mandatory INTEGER NOT NULL DEFAULT 0,
                    published INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT NOT NULL DEFAULT ''
                )"""
            )
            row = self._db.execute("SELECT id FROM app_update WHERE id = 1").fetchone()
            if not row:
                self._db.execute(
                    "INSERT INTO app_update(id, updated_at) VALUES (1, ?)",
                    (_now(),),
                )

    def get(self) -> Dict[str, Any]:
        with self._lock:
            row = self._db.execute(
                """SELECT mgla_version, app_version, version_code, changelog,
                          file_name, file_sha256, file_size, mandatory, published, updated_at
                   FROM app_update WHERE id = 1"""
            ).fetchone()
        if not row:
            return {
                "mgla_version": "",
                "app_version": "",
                "version_code": 0,
                "changelog": "",
                "file_name": "",
                "file_sha256": "",
                "file_size": 0,
                "mandatory": False,
                "published": False,
                "updated_at": "",
                "has_file": False,
            }
        (
            mgla_version,
            app_version,
            version_code,
            changelog,
            file_name,
            file_sha256,
            file_size,
            mandatory,
            published,
            updated_at,
        ) = row
        path = UPDATES_DIR / APK_NAME
        has_file = bool(file_name) and path.is_file() and path.stat().st_size > 0
        return {
            "mgla_version": mgla_version or "",
            "app_version": app_version or "",
            "version_code": int(version_code or 0),
            "changelog": changelog or "",
            "file_name": file_name or "",
            "file_sha256": file_sha256 or "",
            "file_size": int(file_size or 0),
            "mandatory": False,
            "published": bool(published),
            "updated_at": updated_at or "",
            "has_file": has_file,
        }

    def save_meta(
        self,
        *,
        mgla_version: str,
        app_version: str,
        version_code: int,
        changelog: str,
        published: bool,
        file_meta: Optional[Dict[str, Any]] = None,
        **_ignored,
    ) -> Dict[str, Any]:
        mgla_version = validate_mgla_version(mgla_version)
        app_version = validate_app_version(app_version)
        if not app_version:
            raise ValueError("укажите версию приложения (например 12.10.6)")
        if version_code <= 0 or version_code > 2_000_000_000:
            raise ValueError("укажите код сборки (versionCode > 0)")
        changelog = (changelog or "").strip()
        if len(changelog) > MAX_CHANGELOG:
            raise ValueError(f"changelog слишком длинный (max {MAX_CHANGELOG})")
        if CTRL_RE.search(changelog):
            # allow newlines/tabs already stripped of other controls below
            changelog = "".join(ch for ch in changelog if ch == "\n" or ch == "\t" or ord(ch) >= 32)

        if file_meta is None:
            with self._lock:
                row = self._db.execute(
                    "SELECT file_name, file_sha256, file_size FROM app_update WHERE id = 1"
                ).fetchone()
            file_name, file_sha256, file_size = row if row else ("", "", 0)
        else:
            file_name = file_meta["file_name"]
            file_sha256 = file_meta["file_sha256"]
            file_size = file_meta["file_size"]

        if published and not (file_name and (UPDATES_DIR / APK_NAME).is_file()):
            raise ValueError("нельзя опубликовать без загруженного APK")

        with self._lock, self._db:
            self._db.execute(
                """UPDATE app_update SET
                     mgla_version=?, app_version=?, version_code=?, changelog=?,
                     file_name=?, file_sha256=?, file_size=?,
                     mandatory=?, published=?, updated_at=?
                   WHERE id = 1""",
                (
                    mgla_version,
                    app_version,
                    int(version_code),
                    changelog,
                    file_name or "",
                    file_sha256 or "",
                    int(file_size or 0),
                    0,  # mandatory removed — updates are always dismissible
                    1 if published else 0,
                    _now(),
                ),
            )
        return self.get()

    def save_apk_bytes(self, data: bytes, original_name: str = "") -> Dict[str, Any]:
        if not data:
            raise ValueError("пустой файл")
        if len(data) > MAX_APK_BYTES:
            raise ValueError(f"APK слишком большой (max {MAX_APK_BYTES // (1024 * 1024)} МБ)")
        if data[:2] != b"PK":
            raise ValueError("это не APK/ZIP (ожидался PK header)")
        UPDATES_DIR.mkdir(parents=True, exist_ok=True)
        dest = UPDATES_DIR / APK_NAME
        tmp = UPDATES_DIR / (APK_NAME + ".tmp")
        sha = hashlib.sha256(data).hexdigest()
        tmp.write_bytes(data)
        tmp.replace(dest)
        name = (original_name or APK_NAME).strip() or APK_NAME
        name = re.sub(r"[^\w.\-]+", "_", name)[:120]
        return {
            "file_name": name,
            "file_sha256": sha,
            "file_size": len(data),
        }

    def apk_path(self) -> Optional[Path]:
        path = UPDATES_DIR / APK_NAME
        return path if path.is_file() and path.stat().st_size > 0 else None

    def check_for_client(
        self,
        *,
        client_mgla: str,
        client_app: str,
        client_code: int,
    ) -> Dict[str, Any]:
        meta = self.get()
        if not meta["published"] or not meta["has_file"]:
            return {"v": 1, "update": False}
        # Need at least one of the primary indicators set on the release.
        if not meta["app_version"] and not (meta["version_code"] > 0):
            return {"v": 1, "update": False}

        # OR, not AND: same app_version + higher version_code → update;
        # higher app_version + same version_code → update; both higher → update.
        # mgla_version is display-only and never decides.
        code_newer = version_code_newer(int(meta["version_code"] or 0), int(client_code or 0))
        app_newer = bool(
            meta["app_version"]
            and version_greater(meta["app_version"], client_app or "0")
        )
        if not (code_newer or app_newer):
            return {"v": 1, "update": False}

        return {
            "v": 1,
            "update": True,
            "mgla_version": meta["mgla_version"],
            "app_version": meta["app_version"],
            "version_code": meta["version_code"],
            "changelog": meta["changelog"],
            "file_size": meta["file_size"],
            "file_sha256": meta["file_sha256"],
            "mandatory": False,
            # relative paths — client prefixes hub or relay host
            "apk_path": "/mgla-updates/v1/apk",
        }


def parse_multipart(content_type: str, body: bytes) -> Tuple[Dict[str, str], Dict[str, Tuple[str, bytes]]]:
    """Minimal multipart/form-data parser → (fields, files[name]=(filename, data))."""
    m = re.search(r"boundary=([^;]+)", content_type or "", re.I)
    if not m:
        raise ValueError("no multipart boundary")
    boundary = m.group(1).strip().strip('"').encode()
    if not boundary:
        raise ValueError("empty boundary")
    fields: Dict[str, str] = {}
    files: Dict[str, Tuple[str, bytes]] = {}
    parts = body.split(b"--" + boundary)
    for part in parts:
        if not part or part in (b"--", b"--\r\n", b"\r\n"):
            continue
        if part.startswith(b"--"):
            continue
        if part.startswith(b"\r\n"):
            part = part[2:]
        if part.endswith(b"\r\n"):
            part = part[:-2]
        header_blob, sep, data = part.partition(b"\r\n\r\n")
        if not sep:
            continue
        headers = header_blob.decode("utf-8", errors="replace")
        disp = ""
        for line in headers.split("\r\n"):
            if line.lower().startswith("content-disposition:"):
                disp = line.split(":", 1)[1].strip()
        name_m = re.search(r'name="([^"]+)"', disp)
        if not name_m:
            continue
        name = name_m.group(1)
        file_m = re.search(r'filename="([^"]*)"', disp)
        if file_m is not None:
            filename = file_m.group(1) or "upload.bin"
            files[name] = (filename, data)
        else:
            fields[name] = data.decode("utf-8", errors="replace")
    return fields, files


def flash_redirect(ok: str = "", err: str = "") -> str:
    parts = []
    if ok:
        parts.append("ok=" + quote(ok, safe=""))
    if err:
        parts.append("err=" + quote(err, safe=""))
    return "/admin/updates" + (("?" + "&".join(parts)) if parts else "")


def render_updates_html(meta: Dict[str, Any], flash_ok: str = "", flash_err: str = "") -> str:
    flash = ""
    if flash_ok:
        flash += f"<div class='flash ok'>{html.escape(flash_ok)}</div>"
    if flash_err:
        flash += f"<div class='flash err'>{html.escape(flash_err)}</div>"

    file_line = "файл ещё не загружен"
    if meta.get("has_file"):
        mb = (meta.get("file_size") or 0) / (1024 * 1024)
        file_line = (
            f"{html.escape(meta.get('file_name') or APK_NAME)} · "
            f"{mb:.1f} МБ · sha256 <code>{html.escape((meta.get('file_sha256') or '')[:16])}…</code>"
        )

    published = " checked" if meta.get("published") else ""
    status = "опубликовано" if meta.get("published") and meta.get("has_file") else "черновик"

    return f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>Mgla Hub — обновления</title>
<style>
:root {{
  --bg0:#0b0d10; --bg1:#12161c; --card:#171c24; --line:#2a313d;
  --text:#eef1f6; --muted:#9aa3b2; --accent:#e8a54b; --accent2:#3ecf8e;
  --danger:#e85d5d; --radius:18px;
}}
* {{ box-sizing:border-box; }}
body {{
  margin:0; color:var(--text);
  font:15px/1.5 "Segoe UI", "SF Pro Display", system-ui, sans-serif;
  background:
    radial-gradient(1200px 600px at 10% -10%, rgba(232,165,75,.16), transparent 55%),
    radial-gradient(900px 500px at 100% 0%, rgba(91,141,239,.12), transparent 50%),
    linear-gradient(180deg, var(--bg1), var(--bg0) 40%, #090b0e);
  min-height:100vh;
}}
html {{ -webkit-text-size-adjust:100%; }}
.wrap {{ max-width:820px; margin:0 auto; padding:28px 18px 72px; }}
.hero {{ display:flex; flex-wrap:wrap; gap:14px; align-items:flex-end; justify-content:space-between; margin-bottom:18px; }}
.brand {{ display:flex; gap:14px; align-items:center; min-width:0; }}
.logo {{
  width:52px; height:52px; border-radius:16px; flex:0 0 auto;
  background:linear-gradient(145deg, #f0b45a, #c47a22);
  display:grid; place-items:center; font-weight:800; color:#1a1208; font-size:20px;
}}
.brand h1 {{ margin:0; font-size:26px; letter-spacing:-.02em; }}
.brand p {{ margin:2px 0 0; color:var(--muted); font-size:14px; }}
.nav {{
  display:flex; flex-wrap:nowrap; gap:8px; overflow-x:auto; -webkit-overflow-scrolling:touch; max-width:100%;
}}
.nav a {{
  color:var(--muted); text-decoration:none; padding:8px 12px; border-radius:999px;
  border:1px solid var(--line); background:rgba(255,255,255,.02); white-space:nowrap; flex:0 0 auto;
}}
.nav a.on {{ color:#1a1208; background:var(--accent); border-color:var(--accent); font-weight:650; }}
.card {{
  background:var(--card); border:1px solid var(--line); border-radius:var(--radius);
  padding:18px; margin-top:16px;
}}
.card h2 {{ margin:0 0 4px; font-size:17px; }}
.sub {{ color:var(--muted); font-size:13px; margin:0 0 14px; }}
.flash {{
  border-radius:14px; padding:12px 14px; margin-bottom:14px; border:1px solid var(--line);
}}
.flash.ok {{ background:rgba(62,207,142,.12); border-color:rgba(62,207,142,.35); }}
.flash.err {{ background:rgba(232,93,93,.12); border-color:rgba(232,93,93,.4); }}
.grid {{ display:grid; grid-template-columns:1fr 1fr; gap:12px; }}
label.field {{ display:flex; flex-direction:column; gap:4px; color:var(--muted); font-size:12px; }}
input[type=text], input[type=number], textarea {{
  background:#10141a; color:var(--text); border:1px solid var(--line);
  border-radius:10px; padding:9px 11px; font:inherit; width:100%;
}}
textarea {{ min-height:180px; font-family:ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size:13px; resize:vertical; }}
.checks {{ display:flex; flex-wrap:wrap; gap:16px; margin:12px 0 4px; color:var(--muted); font-size:14px; }}
.btn {{
  appearance:none; border:0; border-radius:10px; padding:11px 16px; font:inherit; font-weight:650;
  cursor:pointer; background:var(--accent); color:#1a1208;
}}
.drop {{
  margin-top:8px; border:1.5px dashed var(--line); border-radius:14px; padding:28px 18px;
  text-align:center; color:var(--muted); background:rgba(255,255,255,.02); transition:.15s;
}}
.drop.over {{ border-color:var(--accent); background:rgba(232,165,75,.08); color:var(--text); }}
.drop strong {{ color:var(--text); }}
.fileinfo {{ margin-top:10px; font-size:13px; color:var(--muted); }}
.pill {{
  display:inline-block; padding:3px 10px; border-radius:999px; font-size:12px; font-weight:650;
  background:rgba(62,207,142,.15); color:var(--accent2); border:1px solid rgba(62,207,142,.35);
}}
.pill.draft {{ background:rgba(154,163,178,.12); color:var(--muted); border-color:var(--line); }}
.footer {{ margin-top:28px; color:var(--muted); font-size:12.5px; text-align:center; }}
code {{ font-size:12px; color:#c9d2e0; word-break:break-all; }}
@media (max-width:720px) {{
  .wrap {{ padding:18px 12px 56px; }}
  .brand h1 {{ font-size:22px; }}
  .grid {{ grid-template-columns:1fr; }}
  .card {{ padding:14px; }}
  .btn {{ width:100%; }}
  .drop {{ padding:22px 14px; }}
}}
</style>
</head>
<body>
<div class="wrap">
  <div class="hero">
    <div class="brand">
      <div class="logo">M</div>
      <div>
        <h1>📦 Обновления Mgla</h1>
        <p>Окно обновления по <b>версии приложения</b> и/или <b>коду сборки</b>. Версия Mgla — только для отображения</p>
      </div>
    </div>
    <div class="nav">
      <a href="/">📊 Статистика</a>
      <a href="/admin">🚩 Флаги</a>
      <a class="on" href="/admin/updates">📦 Обновления</a>
    </div>
  </div>
  {flash}

  <div class="card">
    <h2>🚀 Релиз <span class="pill{' draft' if status=='черновик' else ''}">{status}</span></h2>
    <p class="sub">Заполните три версии, список изменений и перетащите APK. Старый файл заменится.</p>
    <form id="upd" method="post" action="/admin/updates" enctype="multipart/form-data">
      <input type="hidden" name="action" value="save_update">
      <div class="grid">
        <label class="field">🏷️ Версия Mgla (подпись в окне у клиента)
          <input type="text" name="mgla_version" required placeholder="v1.5.1"
            value="{html.escape(meta.get('mgla_version') or '')}">
        </label>
        <label class="field">📱 Версия приложения — главный критерий
          <input type="text" name="app_version" required placeholder="12.10.6"
            value="{html.escape(meta.get('app_version') or '')}">
        </label>
        <label class="field">🔢 Код сборки (из gradle, например 7114)
          <input type="number" name="version_code" required min="1" step="1" placeholder="7114"
            value="{html.escape(str(meta['version_code']) if meta.get('version_code') else '')}">
          <span style="font-size:11px;color:var(--muted);margin-top:2px">На телефоне код = это×10+abi (71129…). Хватит базового числа из gradle.properties</span>
        </label>
        <label class="field">🕒 Обновлено
          <input type="text" disabled value="{html.escape(meta.get('updated_at') or '—')}">
        </label>
      </div>
      <label class="field" style="margin-top:12px">📝 Список изменений
        <textarea name="changelog" placeholder="- Исправления&#10;- Новые функции">{html.escape(meta.get('changelog') or '')}</textarea>
      </label>
      <div class="checks">
        <label><input type="checkbox" name="published" value="1"{published}> 📢 Опубликовать (клиенты увидят)</label>
      </div>
      <div class="drop" id="drop">
        <strong>📦 Перетащите APK сюда</strong> — загрузится на сервер сразу<br>
        <span style="font-size:12px">Один файл · заменяет предыдущий · до {MAX_APK_BYTES // (1024*1024)} МБ</span>
        <input type="file" name="apk" id="apk" accept=".apk,application/vnd.android.package-archive" style="display:none">
        <div class="bar" id="bar"><i id="barfill"></i></div>
        <div class="fileinfo" id="picked">Сейчас на сервере: {file_line}</div>
        <div class="upload-ok" id="uploadok" hidden>✓ Файл скопирован на сервер</div>
      </div>
      <div style="margin-top:16px">
        <button class="btn" type="submit" id="savebtn">💾 Сохранить метаданные</button>
      </div>
    </form>
  </div>

  <p class="footer">Достаточно одного: выше <b>версия приложения</b> <em>или</em> выше
    <b>код сборки</b> (можно оба). Версия может не меняться — хватит роста кода сборки.
    APK грузится сразу при выборе; кнопка сохраняет версии/changelog/публикацию.</p>
</div>
<style>
.bar {{ display:none; height:8px; margin:14px auto 0; max-width:320px; background:#10141a; border:1px solid var(--line); border-radius:999px; overflow:hidden; }}
.bar.on {{ display:block; }}
.bar i {{ display:block; height:100%; width:0; background:linear-gradient(90deg,#f0b45a,#c47a22); transition:width .1s linear; }}
.fileinfo.ok {{ color:var(--accent2); }}
.fileinfo.err {{ color:var(--danger); }}
.fileinfo.busy {{ color:var(--accent); }}
.upload-ok {{
  display:none; margin-top:12px; padding:10px 12px; border-radius:12px;
  background:rgba(62,207,142,.14); border:1px solid rgba(62,207,142,.4);
  color:var(--accent2); font-weight:700; font-size:14px;
}}
.upload-ok[hidden] {{ display:none !important; }}
.upload-ok.show {{ display:block; }}
</style>
<script>
(function(){{
  const drop = document.getElementById('drop');
  const input = document.getElementById('apk');
  const picked = document.getElementById('picked');
  const bar = document.getElementById('bar');
  const barfill = document.getElementById('barfill');
  const uploadOk = document.getElementById('uploadok');
  let uploading = false;

  function setInfo(text, cls) {{
    picked.textContent = text;
    picked.className = 'fileinfo' + (cls ? (' ' + cls) : '');
  }}

  function uploadFile(f) {{
    if (!f || uploading) return;
    if (!/\\.apk$/i.test(f.name) && f.type !== 'application/vnd.android.package-archive') {{
      setInfo('Нужен файл .apk', 'err');
      uploadOk.hidden = true;
      uploadOk.classList.remove('show');
      return;
    }}
    uploading = true;
    uploadOk.hidden = true;
    uploadOk.classList.remove('show');
    bar.classList.add('on');
    barfill.style.width = '0%';
    setInfo('Загрузка на сервер: ' + f.name + ' (0%)', 'busy');
    const fd = new FormData();
    fd.append('action', 'upload_apk');
    fd.append('apk', f, f.name);
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/admin/updates');
    xhr.upload.onprogress = (e) => {{
      if (!e.lengthComputable) return;
      const p = Math.round(e.loaded * 100 / e.total);
      barfill.style.width = p + '%';
      setInfo('Загрузка на сервер: ' + f.name + ' (' + p + '%)', 'busy');
    }};
    xhr.onload = () => {{
      uploading = false;
      bar.classList.remove('on');
      let data = null;
      try {{ data = JSON.parse(xhr.responseText); }} catch (err) {{}}
      if (xhr.status >= 200 && xhr.status < 300 && data && data.ok) {{
        const mb = (data.file_size / 1048576).toFixed(1);
        setInfo('На сервере: ' + data.file_name + ' · ' + mb + ' МБ · sha256 ' + (data.file_sha256 || '').slice(0,16) + '…', 'ok');
        uploadOk.hidden = false;
        uploadOk.classList.add('show');
        input.value = '';
      }} else {{
        const err = (data && data.error) ? data.error : ('HTTP ' + xhr.status);
        setInfo('Ошибка загрузки: ' + err, 'err');
        uploadOk.hidden = true;
        uploadOk.classList.remove('show');
      }}
    }};
    xhr.onerror = () => {{
      uploading = false;
      bar.classList.remove('on');
      setInfo('Ошибка сети при загрузке APK', 'err');
      uploadOk.hidden = true;
      uploadOk.classList.remove('show');
    }};
    xhr.send(fd);
  }}

  drop.addEventListener('click', (e) => {{
    if (e.target === input) return;
    input.click();
  }});
  input.addEventListener('change', () => {{
    const f = input.files && input.files[0];
    if (f) uploadFile(f);
  }});
  ['dragenter','dragover'].forEach(ev => drop.addEventListener(ev, e => {{
    e.preventDefault(); drop.classList.add('over');
  }}));
  ['dragleave','drop'].forEach(ev => drop.addEventListener(ev, e => {{
    e.preventDefault(); drop.classList.remove('over');
  }}));
  drop.addEventListener('drop', e => {{
    const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if (f) uploadFile(f);
  }});
}})();
</script>
</body>
</html>
"""
