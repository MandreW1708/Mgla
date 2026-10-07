#!/usr/bin/env python3
"""Admin panel for Mgla Hub: feature flags + OpenRouter model list.

Used by stats_server.py. Stdlib only. HTML matches the stats dashboard look.
"""

from __future__ import annotations

import html
import json
import re
from typing import Iterable, List, Sequence, Tuple
from urllib.parse import quote

# (feature_id, Russian label). feature_id matches mgla_config keys where possible.
FEATURE_CATALOG: Sequence[Tuple[str, str]] = (
    ("ai_enabled", "ИИ (мастер)"),
    ("ai_summary", "Краткая сводка"),
    ("ai_retell", "Пересказ сообщений"),
    ("ai_editor", "AI-редактор"),
    ("ai_transcribe_enabled", "Расшифровка голосовых"),
    ("ai_chat_dna", "Chat DNA"),
    ("ws_enabled", "Обход через WebSocket"),
    ("dpi_enabled", "ByeDPI"),
    ("spy_ghost_mode", "Режим призрака"),
    ("spy_last_online", "Последний онлайн"),
    ("spy_save_deleted_messages", "Сохранение удалённых"),
    ("hidden_chats", "Скрытые чаты"),
    ("chat_time_seconds", "Время сообщений с секундами"),
    ("hide_keyboard_on_scroll", "Скрывать клавиатуру при прокрутке"),
    ("comma_after_mention", "Запятая после упоминания"),
    ("text_anim_enabled", "Анимация ввода текста"),
    ("proxy_in_header", "Прокси в шапке"),
    ("downloads_in_header", "Загрузки в шапке"),
    ("haptic_enabled", "Хаптика"),
    ("mgla_popup_notifications_enabled", "Всплывающие уведомления"),
    ("audio_autopause", "Автопауза аудио"),
    ("edited_icon_enabled", "Иконка «изменено»"),
)

FEATURE_IDS = frozenset(f for f, _ in FEATURE_CATALOG)
FEATURE_LABELS = {f: label for f, label in FEATURE_CATALOG}

DEFAULT_OPENROUTER_MODELS = (
    "nvidia/nemotron-3-super-120b-a12b:free",
    "nvidia/nemotron-3-ultra-550b-a55b:free",
    "nvidia/nemotron-3.5-lightning:free",
    "nex-agi/nex-n2.5-pro:free",
    "google/gemma-4-31b-it:free",
)

MODEL_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_./:-]{0,127}$")
MAX_MODELS = 40


def parse_models_text(text: str) -> List[str]:
    """Parse one-model-per-line textarea into a validated list."""
    if not isinstance(text, str):
        raise ValueError("models text required")
    out: List[str] = []
    seen = set()
    for line in text.splitlines():
        model = line.strip()
        if not model or model.startswith("#"):
            continue
        if not MODEL_RE.match(model):
            raise ValueError(f"bad model id: {model[:40]}")
        if model in seen:
            continue
        seen.add(model)
        out.append(model)
        if len(out) > MAX_MODELS:
            raise ValueError(f"too many models (max {MAX_MODELS})")
    if not out:
        raise ValueError("at least one model required")
    return out


def models_to_text(models: Iterable[str]) -> str:
    return "\n".join(models)


def label_feature(feature_id: str) -> str:
    return FEATURE_LABELS.get(feature_id, feature_id.replace("_", " "))


def render_admin_html(
    *,
    global_disabled: dict,
    denies: Sequence[Tuple[int, str, str]],
    models: Sequence[str],
    flash_ok: str = "",
    flash_err: str = "",
    bot_url: str = "",
) -> str:
    """Build the /admin control page."""
    rows = []
    for fid, label in FEATURE_CATALOG:
        checked = " checked" if global_disabled.get(fid) else ""
        rows.append(
            "<tr>"
            f"<td class='name'>{html.escape(label)}</td>"
            f"<td><code>{html.escape(fid)}</code></td>"
            f"<td class='num'><label><input type='checkbox' name='kill_{html.escape(fid)}' value='1'{checked}> "
            "выключить у всех</label></td>"
            "</tr>"
        )

    options = [
        f"<option value='{html.escape(fid)}'>{html.escape(label)}</option>"
        for fid, label in FEATURE_CATALOG
    ]

    deny_rows = []
    for tg_id, feature, created in denies:
        deny_rows.append(
            "<tr>"
            f"<td class='num'>{tg_id}</td>"
            f"<td class='name'>{html.escape(label_feature(feature))} "
            f"(<code>{html.escape(feature)}</code>)</td>"
            f"<td class='num'>{html.escape(created or '')}</td>"
            "<td>"
            f"<form method='post' action='/admin' class='inline'>"
            "<input type='hidden' name='action' value='deny_remove'>"
            f"<input type='hidden' name='tg_id' value='{tg_id}'>"
            f"<input type='hidden' name='feature' value='{html.escape(feature)}'>"
            "<button type='submit' class='btn danger'>Снять</button>"
            "</form>"
            "</td>"
            "</tr>"
        )
    if not deny_rows:
        deny_rows.append("<tr><td colspan='4' class='empty'>Пока нет персональных запретов.</td></tr>")

    flash = ""
    if flash_ok:
        flash += f"<div class='flash ok'>{html.escape(flash_ok)}</div>"
    if flash_err:
        flash += f"<div class='flash err'>{html.escape(flash_err)}</div>"

    bot_hint = html.escape(bot_url) if bot_url else "не задан (MGLA_BOT_AI_URL)"

    return f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>Mgla Hub — управление</title>
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
.wrap {{ max-width:960px; margin:0 auto; padding:28px 18px 72px; }}
.hero {{ display:flex; flex-wrap:wrap; gap:14px; align-items:flex-end; justify-content:space-between; margin-bottom:18px; }}
.brand {{ display:flex; gap:14px; align-items:center; }}
.logo {{
  width:52px; height:52px; border-radius:16px;
  background:linear-gradient(145deg, #f0b45a, #c47a22);
  display:grid; place-items:center; font-weight:800; color:#1a1208; font-size:20px;
}}
.brand h1 {{ margin:0; font-size:26px; letter-spacing:-.02em; }}
.brand p {{ margin:2px 0 0; color:var(--muted); font-size:14px; }}
.nav a {{
  color:var(--muted); text-decoration:none; padding:8px 12px; border-radius:999px;
  border:1px solid var(--line); background:rgba(255,255,255,.02); margin-left:6px;
}}
.nav a.on {{ color:#1a1208; background:var(--accent); border-color:var(--accent); font-weight:650; }}
.card {{
  background:var(--card); border:1px solid var(--line); border-radius:var(--radius);
  padding:18px; margin-top:16px;
}}
.card h2 {{ margin:0 0 4px; font-size:17px; }}
.sub {{ color:var(--muted); font-size:13px; margin:0 0 14px; }}
table {{ width:100%; border-collapse:collapse; }}
th {{
  text-align:left; color:var(--muted); font-weight:600; font-size:12px;
  text-transform:uppercase; letter-spacing:.04em;
  border-bottom:1px solid var(--line); padding:8px;
}}
td {{ padding:9px 8px; border-bottom:1px solid var(--line); vertical-align:middle; }}
tr:last-child td {{ border-bottom:0; }}
td.num {{ white-space:nowrap; font-variant-numeric:tabular-nums; }}
code {{ font-size:12px; color:#c9d2e0; }}
.empty {{ color:var(--muted); padding:10px 0; }}
.flash {{
  border-radius:14px; padding:12px 14px; margin-bottom:14px; border:1px solid var(--line);
}}
.flash.ok {{ background:rgba(62,207,142,.12); border-color:rgba(62,207,142,.35); }}
.flash.err {{ background:rgba(232,93,93,.12); border-color:rgba(232,93,93,.4); }}
.row {{ display:flex; flex-wrap:wrap; gap:10px; align-items:flex-end; margin-top:12px; }}
label.field {{ display:flex; flex-direction:column; gap:4px; color:var(--muted); font-size:12px; }}
input[type=text], input[type=number], select, textarea {{
  background:#10141a; color:var(--text); border:1px solid var(--line);
  border-radius:10px; padding:9px 11px; font:inherit; min-width:160px;
}}
textarea {{ width:100%; min-height:160px; font-family:ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size:13px; }}
.btn {{
  appearance:none; border:0; border-radius:10px; padding:9px 14px; font:inherit; font-weight:650;
  cursor:pointer; background:var(--accent); color:#1a1208;
}}
.btn.secondary {{ background:#2a313d; color:var(--text); }}
.btn.danger {{ background:var(--danger); color:#fff; }}
form.inline {{ display:inline; margin:0; }}
.footer {{ margin-top:28px; color:var(--muted); font-size:12.5px; text-align:center; }}
</style>
</head>
<body>
<div class="wrap">
  <div class="hero">
    <div class="brand">
      <div class="logo">M</div>
      <div>
        <h1>Mgla Hub</h1>
        <p>Управление доступом и моделями OpenRouter</p>
      </div>
    </div>
    <div class="nav">
      <a href="/">Статистика</a>
      <a class="on" href="/admin">Флаги</a>
      <a href="/admin/updates">Обновления</a>
    </div>
  </div>
  {flash}

  <div class="card">
    <h2>Feature flags — глобально</h2>
    <p class="sub">Kill-switch выключает функцию у всех клиентов после следующего опроса хаба.</p>
    <form method="post" action="/admin">
      <input type="hidden" name="action" value="save_globals">
      <table>
        <thead><tr><th>Функция</th><th>id</th><th></th></tr></thead>
        <tbody>
          {"".join(rows)}
        </tbody>
      </table>
      <div class="row"><button class="btn" type="submit">Сохранить kill-switch</button></div>
    </form>
  </div>

  <div class="card">
    <h2>Feature flags — запрет по Telegram ID</h2>
    <p class="sub">После сохранения функция станет недоступна этому пользователю и выключится, если была включена.</p>
    <form method="post" action="/admin">
      <input type="hidden" name="action" value="deny_add">
      <div class="row">
        <label class="field">Telegram ID
          <input type="number" name="tg_id" required min="1" step="1" placeholder="123456789">
        </label>
        <label class="field">Функция
          <select name="feature">{"".join(options)}</select>
        </label>
        <button class="btn" type="submit">Запретить</button>
      </div>
    </form>
    <table style="margin-top:16px">
      <thead><tr><th>tg_id</th><th>Функция</th><th>Когда</th><th></th></tr></thead>
      <tbody>
        {"".join(deny_rows)}
      </tbody>
    </table>
  </div>

  <div class="card">
    <h2>OpenRouter — модели (fallback по порядку)</h2>
    <p class="sub">Источник правды на хабе. При сохранении список пушится на mglabot
      (<code>{bot_hint}</code>). Ключ API на хабе не хранится.</p>
    <form method="post" action="/admin">
      <input type="hidden" name="action" value="save_models">
      <label class="field" style="width:100%">Модели (одна на строку)
        <textarea name="models" spellcheck="false">{html.escape(models_to_text(models))}</textarea>
      </label>
      <div class="row">
        <button class="btn" type="submit">Сохранить и пушить на mglabot</button>
      </div>
    </form>
    <form method="post" action="/admin" style="margin-top:10px">
      <input type="hidden" name="action" value="push_models">
      <button class="btn secondary" type="submit">Повторить push на mglabot</button>
    </form>
  </div>

  <p class="footer">Только Basic Auth. Клиентский токен сюда не подходит.</p>
</div>
</body>
</html>
"""


def flash_redirect(ok: str = "", err: str = "") -> str:
    parts = []
    if ok:
        parts.append("ok=" + quote(ok, safe=""))
    if err:
        parts.append("err=" + quote(err, safe=""))
    return "/admin" + (("?" + "&".join(parts)) if parts else "")


def models_from_json(raw: str) -> List[str]:
    data = json.loads(raw)
    if not isinstance(data, list):
        raise ValueError("models must be a JSON array")
    return parse_models_text("\n".join(str(x) for x in data))
