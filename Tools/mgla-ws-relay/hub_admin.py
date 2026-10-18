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
    ("ai_enabled", "Включение AI"),
    ("ai_summary", "Краткая Сводка"),
    ("ai_retell", "Пересказ сообщений"),
    ("ai_editor", "AI-редактор"),
    ("ai_transcribe_enabled", "Включить расшифровку"),
    ("ai_chat_dna", "Главные темы Chat DNA"),
    ("ai_memory", "Живая память"),
    ("ws_enabled", "Авто-обход"),
    ("dpi_enabled", "Обход через ByeDPI"),
    ("spy_ghost_mode", "Режим призрака"),
    ("spy_last_online", "Последний онлайн"),
    ("spy_save_deleted_messages", "Сохранение удаленных"),
    ("hidden_chats", "Скрытые чаты"),
    ("chat_time_seconds", "Время с секундами"),
    ("hide_keyboard_on_scroll", "Скрывать клавиатуру при прокрутке"),
    ("comma_after_mention", "Запятая после упоминания"),
    ("text_anim_enabled", "Анимация текста"),
    ("proxy_in_header", "Прокси в шапке"),
    ("downloads_in_header", "Загрузки в шапке"),
    ("haptic_enabled", "Виброотклик"),
    ("mgla_popup_notifications_enabled", "Всплывающие уведомления"),
    ("audio_autopause", "Автопауза"),
    ("edited_icon_enabled", "Значок вместо «изменено»"),
)

FEATURE_ICONS = {
    "ai_enabled": "✨",
    "ai_summary": "📝",
    "ai_retell": "🔁",
    "ai_editor": "✏️",
    "ai_transcribe_enabled": "🎙️",
    "ai_chat_dna": "🧬",
    "ai_memory": "🧠",
    "ws_enabled": "🛡️",
    "dpi_enabled": "🌐",
    "spy_ghost_mode": "👻",
    "spy_last_online": "👀",
    "spy_save_deleted_messages": "🗑️",
    "hidden_chats": "🙈",
    "chat_time_seconds": "⏱️",
    "hide_keyboard_on_scroll": "⌨️",
    "comma_after_mention": "@",
    "text_anim_enabled": "✨",
    "proxy_in_header": "🔗",
    "downloads_in_header": "⬇️",
    "haptic_enabled": "📳",
    "mgla_popup_notifications_enabled": "🔔",
    "audio_autopause": "⏸️",
    "edited_icon_enabled": "✏️",
}

FEATURE_IDS = frozenset(f for f, _ in FEATURE_CATALOG)
FEATURE_LABELS = {f: label for f, label in FEATURE_CATALOG}

DEFAULT_OPENROUTER_MODELS = (
    "nvidia/nemotron-3-super-120b-a12b:free",
    "nvidia/nemotron-3-ultra-550b-a55b:free",
    "nvidia/nemotron-3.5-lightning:free",
    "nex-agi/nex-n2.5-pro:free",
    "google/gemma-4-31b-it:free",
)

# Stable plan ids for future subscription / entitlements (client + billing).
PLAN_FREE = "free"
PLAN_PREMIUM = "premium"
PLAN_PREMIUM_PLUS = "premium_plus"
PLAN_PREMIUM_ULTRA = "premium_ultra"

# (plan_id, UI title, short description). Keys live only on mglabot env — never in git.
PLAN_CATALOG: Sequence[Tuple[str, str, str]] = (
    (
        PLAN_FREE,
        "Free",
        "Бесплатно для всех. Ключ: OPENROUTER_API_KEY на mglabot.",
    ),
    (
        PLAN_PREMIUM,
        "Premium",
        "Будущий план подписки без отдельных нейросетей — те же модели, что у Free "
        "(и тот же OPENROUTER_API_KEY). Список моделей здесь не редактируется.",
    ),
    (
        PLAN_PREMIUM_PLUS,
        "Premium+",
        "Будущий план подписки. Ключ: OPENROUTER_API_KEY_PREMIUM_PLUS на mglabot.",
    ),
    (
        PLAN_PREMIUM_ULTRA,
        "Premium Ultra",
        "Будущий план подписки. Ключ: OPENROUTER_API_KEY_PREMIUM_ULTRA на mglabot.",
    ),
)

# Plans that own a model list in SQLite / push payload.
# Premium has no own list — it aliases Free (see PLAN_MODELS_ALIAS).
PLANS_WITH_MODEL_LISTS: Sequence[str] = (
    PLAN_FREE,
    PLAN_PREMIUM_PLUS,
    PLAN_PREMIUM_ULTRA,
)

# plan_id → which plan's model list it uses (no separate storage).
PLAN_MODELS_ALIAS = {
    PLAN_PREMIUM: PLAN_FREE,
}

# Rank for multi-account: highest plan wins.
PLAN_RANK = {
    PLAN_FREE: 0,
    PLAN_PREMIUM: 1,
    PLAN_PREMIUM_PLUS: 2,
    PLAN_PREMIUM_ULTRA: 3,
}
PLAN_IDS_ALL = (PLAN_FREE, PLAN_PREMIUM, PLAN_PREMIUM_PLUS, PLAN_PREMIUM_ULTRA)


def normalize_plan(plan_id: str | None) -> str:
    if isinstance(plan_id, str) and plan_id.strip() in PLAN_RANK:
        return plan_id.strip()
    return PLAN_FREE


def best_plan(plans) -> str:
    best = PLAN_FREE
    best_rank = 0
    for plan in plans or ():
        pid = normalize_plan(plan)
        rank = PLAN_RANK.get(pid, 0)
        if rank > best_rank:
            best = pid
            best_rank = rank
    return best

# SQLite ai_config keys per plan that stores its own list.
# Free keeps legacy key for backwards compatibility.
AI_CONFIG_MODEL_KEYS = {
    PLAN_FREE: "openrouter_models",
    PLAN_PREMIUM_PLUS: "openrouter_models_premium_plus",
    PLAN_PREMIUM_ULTRA: "openrouter_models_premium_ultra",
}

MODEL_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_./:-]{0,127}$")
MAX_MODELS = 40


def parse_models_text(text: str, *, allow_empty: bool = False) -> List[str]:
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
    if not out and not allow_empty:
        raise ValueError("at least one model required")
    return out


def validate_plans_disjoint(plans: dict) -> None:
    """Raise if the same model id appears in more than one subscription plan."""
    owner: dict = {}
    for plan_id, models in plans.items():
        for model in models or []:
            prev = owner.get(model)
            if prev and prev != plan_id:
                raise ValueError(
                    f"модель «{model}» уже в плане «{prev}» — списки планов не должны пересекаться"
                )
            owner[model] = plan_id


def models_to_text(models: Iterable[str]) -> str:
    return "\n".join(models)


def label_feature(feature_id: str) -> str:
    return FEATURE_LABELS.get(feature_id, feature_id.replace("_", " "))


def render_admin_html(
    *,
    global_disabled: dict,
    denies: Sequence[Tuple[int, str, str]],
    models: Sequence[str] | None = None,
    models_by_plan: dict | None = None,
    subscriptions: Sequence[Tuple[int, str, str]] | None = None,
    flash_ok: str = "",
    flash_err: str = "",
    bot_url: str = "",
) -> str:
    """Build the /admin control page."""
    if models_by_plan is None:
        models_by_plan = {
            PLAN_FREE: list(models or DEFAULT_OPENROUTER_MODELS),
            PLAN_PREMIUM_PLUS: [],
            PLAN_PREMIUM_ULTRA: [],
        }
    if subscriptions is None:
        subscriptions = []
    rows = []
    for fid, label in FEATURE_CATALOG:
        checked = " checked" if global_disabled.get(fid) else ""
        ico = FEATURE_ICONS.get(fid, "🔹")
        rows.append(
            "<tr>"
            f"<td class='name'><span class='fico'>{ico}</span> {html.escape(label)}</td>"
            f"<td><code>{html.escape(fid)}</code></td>"
            f"<td class='num'><label><input type='checkbox' name='kill_{html.escape(fid)}' value='1'{checked}> "
            "🛑 выключить у всех</label></td>"
            "</tr>"
        )

    options = [
        f"<option value='{html.escape(fid)}'>{FEATURE_ICONS.get(fid, '🔹')} {html.escape(label)}</option>"
        for fid, label in FEATURE_CATALOG
    ]

    deny_rows = []
    for tg_id, feature, created in denies:
        ico = FEATURE_ICONS.get(feature, "🔹")
        deny_rows.append(
            "<tr>"
            f"<td class='num'>{tg_id}</td>"
            f"<td class='name'><span class='fico'>{ico}</span> {html.escape(label_feature(feature))} "
            f"(<code>{html.escape(feature)}</code>)</td>"
            f"<td class='num'>{html.escape(created or '')}</td>"
            "<td>"
            f"<form method='post' action='/admin' class='inline'>"
            "<input type='hidden' name='action' value='deny_remove'>"
            f"<input type='hidden' name='tg_id' value='{tg_id}'>"
            f"<input type='hidden' name='feature' value='{html.escape(feature)}'>"
            "<button type='submit' class='btn danger'>✅ Снять</button>"
            "</form>"
            "</td>"
            "</tr>"
        )
    if not deny_rows:
        deny_rows.append("<tr><td colspan='4' class='empty'>Пока нет персональных запретов.</td></tr>")

    plan_options = "".join(
        f"<option value='{html.escape(pid)}'>{html.escape(title)}</option>"
        for pid, title, _ in PLAN_CATALOG
        if pid != PLAN_FREE
    )
    sub_rows = []
    for tg_id, plan, updated in subscriptions:
        title = next((t for pid, t, _ in PLAN_CATALOG if pid == plan), plan)
        sub_rows.append(
            "<tr>"
            f"<td class='num'>{tg_id}</td>"
            f"<td class='name'>{html.escape(title)} (<code>{html.escape(plan)}</code>)</td>"
            f"<td class='num'>{html.escape(updated or '')}</td>"
            "<td>"
            f"<form method='post' action='/admin' class='inline'>"
            "<input type='hidden' name='action' value='subscription_remove'>"
            f"<input type='hidden' name='tg_id' value='{tg_id}'>"
            "<button type='submit' class='btn danger'>Снять</button>"
            "</form>"
            "</td>"
            "</tr>"
        )
    if not sub_rows:
        sub_rows.append(
            "<tr><td colspan='4' class='empty'>Подписчиков пока нет — добавьте Telegram ID ниже.</td></tr>"
        )

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
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex,nofollow">
<title>Mgla Hub — управление</title>
<style>
:root {{
  --bg0:#050304; --bg1:#100a0c; --card:#1a1014; --line:#3d2a32;
  --text:#f3e9ec; --muted:#b09aa2; --accent:#8b1e3f; --accent-hi:#c23b5f;
  --accent2:#9a6b78; --danger:#b33a4a; --radius:16px;
}}
* {{ box-sizing:border-box; }}
html {{ -webkit-text-size-adjust:100%; text-size-adjust:100%; }}
body {{
  margin:0; color:var(--text); max-width:100vw; overflow-x:hidden;
  font:15px/1.45 "Segoe UI", "SF Pro Display", system-ui, sans-serif;
  background:
    radial-gradient(900px 480px at 8% -8%, rgba(139,30,63,.28), transparent 55%),
    radial-gradient(700px 420px at 100% 0%, rgba(90,58,68,.22), transparent 50%),
    linear-gradient(180deg, var(--bg1), var(--bg0) 42%, #030203);
  min-height:100vh;
}}
.wrap {{ width:100%; max-width:960px; margin:0 auto; padding:24px 16px 64px; }}
.hero {{ display:flex; flex-wrap:wrap; gap:14px; align-items:flex-end; justify-content:space-between; margin-bottom:18px; }}
.brand {{ display:flex; gap:12px; align-items:center; min-width:0; }}
.logo {{
  width:48px; height:48px; border-radius:14px; flex:0 0 auto;
  background:linear-gradient(145deg, #c23b5f, #6e152f);
  display:grid; place-items:center; font-weight:800; color:#f8eef1; font-size:18px;
}}
.brand h1 {{ margin:0; font-size:24px; letter-spacing:-.02em; }}
.brand p {{ margin:2px 0 0; color:var(--muted); font-size:13px; }}
.nav {{
  display:flex; flex-wrap:nowrap; gap:6px; overflow-x:auto; -webkit-overflow-scrolling:touch;
  max-width:100%; scrollbar-width:none;
}}
.nav::-webkit-scrollbar {{ display:none; }}
.nav a {{
  color:var(--muted); text-decoration:none; padding:8px 11px; border-radius:999px;
  border:1px solid var(--line); background:rgba(255,255,255,.03); white-space:nowrap; flex:0 0 auto;
  font-size:13px;
}}
.nav a.on {{ color:#fff; background:var(--accent); border-color:var(--accent-hi); font-weight:650; }}
.card {{
  background:var(--card); border:1px solid var(--line); border-radius:var(--radius);
  padding:16px; margin-top:14px; overflow:hidden;
}}
.card h2 {{ margin:0 0 4px; font-size:16px; }}
.sub {{ color:var(--muted); font-size:13px; margin:0 0 14px; }}
.table-wrap {{ width:100%; overflow-x:auto; -webkit-overflow-scrolling:touch; }}
table {{ width:100%; border-collapse:collapse; min-width:0; }}
th {{
  text-align:left; color:var(--muted); font-weight:600; font-size:11px;
  text-transform:uppercase; letter-spacing:.04em;
  border-bottom:1px solid var(--line); padding:8px 6px;
}}
td {{ padding:8px 6px; border-bottom:1px solid var(--line); vertical-align:middle; font-size:13px; }}
tr:last-child td {{ border-bottom:0; }}
td.num {{ white-space:nowrap; font-variant-numeric:tabular-nums; }}
td.name {{ word-break:break-word; overflow-wrap:anywhere; }}
.fico {{ display:inline-block; width:1.4em; text-align:center; }}
code {{ font-size:12px; color:#e0d0d6; word-break:break-all; }}
.empty {{ color:var(--muted); padding:10px 0; }}
.flash {{
  border-radius:14px; padding:12px 14px; margin-bottom:14px; border:1px solid var(--line);
}}
.flash.ok {{ background:rgba(139,30,63,.18); border-color:rgba(194,59,95,.4); }}
.flash.err {{ background:rgba(179,58,74,.16); border-color:rgba(179,58,74,.45); }}
.row {{ display:flex; flex-wrap:wrap; gap:10px; align-items:flex-end; margin-top:12px; }}
label.field {{ display:flex; flex-direction:column; gap:4px; color:var(--muted); font-size:12px; flex:1 1 160px; }}
input[type=text], input[type=number], select, textarea {{
  background:#120c0e; color:var(--text); border:1px solid var(--line);
  border-radius:10px; padding:9px 11px; font:inherit; width:100%; min-width:0;
}}
textarea {{ width:100%; min-height:160px; font-family:ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size:13px; }}
.btn {{
  appearance:none; border:0; border-radius:10px; padding:11px 14px; font:inherit; font-weight:650;
  cursor:pointer; background:var(--accent); color:#fff;
}}
.btn.secondary {{ background:#2a1c22; color:var(--text); }}
.btn.danger {{ background:var(--danger); color:#fff; }}
form.inline {{ display:inline; margin:0; }}
.footer {{ margin-top:28px; color:var(--muted); font-size:12.5px; text-align:center; }}
@media (max-width:720px) {{
  .wrap {{ padding:14px 10px 48px; }}
  .brand h1 {{ font-size:20px; }}
  .card {{ padding:12px; }}
  .row {{ flex-direction:column; align-items:stretch; }}
  .btn {{ width:100%; }}
  table {{ font-size:12px; }}
  th, td {{ padding:7px 5px; }}
}}
</style>
</head>
<body>
<div class="wrap">
  <div class="hero">
    <div class="brand">
      <div class="logo">M</div>
      <div>
        <h1>Mgla Hub</h1>
        <p>🎛️ Управление доступом и моделями OpenRouter</p>
      </div>
    </div>
    <div class="nav">
      <a href="/">📊 Статистика</a>
      <a class="on" href="/admin">🚩 Флаги</a>
      <a href="/admin/updates">📦 Обновления</a>
    </div>
  </div>
  {flash}

  <div class="card">
    <h2>🚩 Feature flags — глобально</h2>
    <p class="sub">Kill-switch выключает функцию у всех клиентов после следующего опроса хаба.</p>
    <form method="post" action="/admin">
      <input type="hidden" name="action" value="save_globals">
      <div class="table-wrap">
      <table>
        <thead><tr><th>Функция</th><th>id</th><th></th></tr></thead>
        <tbody>
          {"".join(rows)}
        </tbody>
      </table>
      </div>
      <div class="row"><button class="btn" type="submit">💾 Сохранить kill-switch</button></div>
    </form>
  </div>

  <div class="card">
    <h2>👤 Feature flags — запрет по Telegram ID</h2>
    <p class="sub">После сохранения функция станет недоступна этому пользователю и выключится, если была включена.</p>
    <form method="post" action="/admin">
      <input type="hidden" name="action" value="deny_add">
      <div class="row">
        <label class="field">🆔 Telegram ID
          <input type="number" name="tg_id" required min="1" step="1" placeholder="123456789">
        </label>
        <label class="field">🧩 Функция
          <select name="feature">{"".join(options)}</select>
        </label>
        <button class="btn" type="submit">🚫 Запретить</button>
      </div>
    </form>
    <div class="table-wrap" style="margin-top:16px">
    <table>
      <thead><tr><th>tg_id</th><th>Функция</th><th>Когда</th><th></th></tr></thead>
      <tbody>
        {"".join(deny_rows)}
      </tbody>
    </table>
    </div>
  </div>

  <div class="card">
    <h2>💎 Подписки Mgla Premium</h2>
    <p class="sub">План привязан к Telegram ID аккаунта (не ко всему клиенту). В ответе
      <code>POST /features</code> — объект <code>plans</code> (tg_id → plan) и сводный
      <code>plan</code> (лучший среди запрошенных). Без записи — Free, вкладка ИИ скрыта.</p>
    <form method="post" action="/admin">
      <input type="hidden" name="action" value="subscription_set">
      <div class="row">
        <label class="field">🆔 Telegram ID
          <input type="number" name="tg_id" required min="1" step="1" placeholder="123456789">
        </label>
        <label class="field">📦 План
          <select name="plan">{plan_options}</select>
        </label>
        <button class="btn" type="submit">💾 Выдать план</button>
      </div>
    </form>
    <div class="table-wrap" style="margin-top:16px">
    <table>
      <thead><tr><th>tg_id</th><th>План</th><th>Когда</th><th></th></tr></thead>
      <tbody>
        {"".join(sub_rows)}
      </tbody>
    </table>
    </div>
  </div>

  <div class="card">
    <h2>🤖 OpenRouter — модели по планам</h2>
    <p class="sub">Отдельные списки: Free / Premium+ / Premium Ultra (без пересечений).
      План <code>premium</code> своих нейросетей не имеет — те же модели, что Free.
      Источник правды на хабе; при сохранении пушится на mglabot (<code>{bot_hint}</code>).
      API-ключи только в env на mglabot. Plan id: <code>free</code>, <code>premium</code>,
      <code>premium_plus</code>, <code>premium_ultra</code>.</p>
    <form method="post" action="/admin">
      <input type="hidden" name="action" value="save_models">
      {_render_plan_textareas(models_by_plan)}
      <div class="row">
        <button class="btn" type="submit">💾 Сохранить все планы и пушить на mglabot</button>
      </div>
    </form>
    <form method="post" action="/admin" style="margin-top:10px">
      <input type="hidden" name="action" value="push_models">
      <button class="btn secondary" type="submit">🔁 Повторить push на mglabot</button>
    </form>
  </div>

  <p class="footer">🔐 Только Basic Auth. Клиентский токен сюда не подходит.</p>
</div>
</body>
</html>
"""


def _render_plan_textareas(models_by_plan: dict) -> str:
    blocks = []
    for plan_id, title, desc in PLAN_CATALOG:
        alias = PLAN_MODELS_ALIAS.get(plan_id)
        if alias:
            alias_title = next((t for pid, t, _ in PLAN_CATALOG if pid == alias), alias)
            blocks.append(
                f"""
      <div class="plan-block" style="margin:14px 0 18px;padding:12px;border:1px dashed var(--line);border-radius:12px;background:rgba(0,0,0,.12)">
        <h3 style="margin:0 0 4px;font-size:15px">{html.escape(title)}
          <code style="font-size:11px;font-weight:500;margin-left:6px">{html.escape(plan_id)}</code>
        </h3>
        <p class="sub" style="margin:0">{html.escape(desc)}</p>
        <p class="sub" style="margin:8px 0 0">🔗 Модели = список плана <strong>{html.escape(alias_title)}</strong>
          (<code>{html.escape(alias)}</code>). Отдельный textarea не нужен.</p>
      </div>"""
            )
            continue
        models = models_by_plan.get(plan_id) or []
        required = " required" if plan_id == PLAN_FREE else ""
        hint = "обязателен ≥1 модель" if plan_id == PLAN_FREE else "можно пустым до запуска подписки"
        blocks.append(
            f"""
      <div class="plan-block" style="margin:14px 0 18px;padding:12px;border:1px solid var(--line);border-radius:12px;background:rgba(0,0,0,.18)">
        <h3 style="margin:0 0 4px;font-size:15px">{html.escape(title)}
          <code style="font-size:11px;font-weight:500;margin-left:6px">{html.escape(plan_id)}</code>
        </h3>
        <p class="sub" style="margin:0 0 8px">{html.escape(desc)} ({hint})</p>
        <label class="field" style="width:100%;flex:1 1 100%">📋 Модели (одна на строку, порядок = fallback)
          <textarea name="models_{html.escape(plan_id)}" spellcheck="false"{required}>{html.escape(models_to_text(models))}</textarea>
        </label>
      </div>"""
        )
    return "".join(blocks)


def flash_redirect(ok: str = "", err: str = "") -> str:
    parts = []
    if ok:
        parts.append("ok=" + quote(ok, safe=""))
    if err:
        parts.append("err=" + quote(err, safe=""))
    return "/admin" + (("?" + "&".join(parts)) if parts else "")


def models_from_json(raw: str, *, allow_empty: bool = True) -> List[str]:
    data = json.loads(raw)
    if not isinstance(data, list):
        raise ValueError("models must be a JSON array")
    if not data:
        if allow_empty:
            return []
        raise ValueError("at least one model required")
    return parse_models_text("\n".join(str(x) for x in data), allow_empty=allow_empty)
