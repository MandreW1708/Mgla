#!/usr/bin/env python3
"""Отчёт Mgla Hub — статистика простым языком.

    python3 stats_report.py
    python3 stats_report.py --days 7
    python3 stats_report.py --html report.html
    python3 stats_report.py --telegram
"""

import argparse
import html
import os
import sqlite3
import urllib.request
import uuid
from datetime import date, datetime, timedelta, timezone

DB_PATH = os.environ.get("MGLA_STATS_DB", "/opt/mgla-ws-relay/stats.db")

# --- Словарь: технические имена → понятный русский ---

# Labels match on-screen titles from MglaSettingsSearch / settings screens.
SETTING_LABELS = {
    "proxy_in_header": "Прокси в шапке",
    "downloads_in_header": "Загрузки в шапке",
    "haptic_enabled": "Виброотклик",
    "spy_last_online": "Последний онлайн",
    "spy_save_deleted_messages": "Сохранение удаленных",
    "spy_ghost_mode": "Режим призрака",
    "audio_autopause": "Автопауза",
    "chat_time_seconds": "Время с секундами",
    "sticker_time_hidden": "Убрать время на стикерах",
    "hide_keyboard_on_scroll": "Скрывать клавиатуру при прокрутке",
    "comma_after_mention": "Запятая после упоминания",
    "mgla_popup_notifications_enabled": "Всплывающие уведомления",
    "edited_icon_enabled": "Значок вместо «изменено»",
    "text_anim_enabled": "Анимация текста",
    "ws_enabled": "Авто-обход",
    "dpi_enabled": "Обход через ByeDPI",
    "ai_enabled": "Включение AI",
    "ai_summary": "Краткая Сводка",
    "ai_retell": "Пересказ сообщений",
    "ai_editor": "AI-редактор",
    "ai_chat_dna": "Главные темы Chat DNA",
    "transcribe_enabled": "Включить расшифровку",
    "ai_transcribe_enabled": "Включить расшифровку",
    "camera_x_60fps": "60 FPS",
}

SETTING_ICONS = {
    "ai_enabled": "✨",
    "ai_summary": "📝",
    "ai_retell": "🔁",
    "ai_editor": "✏️",
    "ai_chat_dna": "🧬",
    "transcribe_enabled": "🎙️",
    "ai_transcribe_enabled": "🎙️",
    "ws_enabled": "🛡️",
    "dpi_enabled": "🌐",
    "spy_ghost_mode": "👻",
    "spy_last_online": "👀",
    "spy_save_deleted_messages": "🗑️",
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
    "sticker_time_hidden": "😶",
    "camera_api": "📷",
    "camera_x_60fps": "🎬",
}

COUNTER_LABELS = {
    "app_foreground": "Открыли приложение",
    "ai:summary": "Сделали краткую сводку",
    "ai:retell": "Пересказали сообщения",
    "ai:reply": "Попросили ИИ ответить",
    "ai:voice_summary": "Сводка голосового",
    "ai:translate": "Перевод расшифровки",
    "ai:quota_exhausted": "Упёрлись в дневной лимит ИИ",
    "copy_photo:viewer": "Скопировали фото из просмотрщика",
    "copy_photo:chat": "Скопировали фото из чата",
    "hidden_chats:open": "Открыли скрытые чаты",
    "hidden_chats:hide": "Скрыли чат",
    "hidden_chats:unhide": "Вернули чат из скрытых",
}

def pct(part, whole):
    return f"{100.0 * part / whole:.0f}%" if whole else "—"


def label_setting(key):
    return SETTING_LABELS.get(key)


def label_counter(name):
    if name in COUNTER_LABELS:
        return COUNTER_LABELS[name]
    if name.startswith("ai:request:"):
        return f"Запрос к ИИ ({name.split(':', 2)[-1]})"
    if name.startswith("ai:error:"):
        return f"Ошибка ИИ ({name.split(':', 2)[-1]})"
    if name.startswith("ai:editor:"):
        return f"AI-редактор: действие {name.split(':', 2)[-1]}"
    if name.startswith("screen:"):
        return None  # collection removed; ignore legacy rows
    if name.startswith("set:"):
        body = name[4:]
        if "=" in body:
            k, v = body.split("=", 1)
            pretty = label_setting(k)
            if not pretty:
                return None
            if v in ("true", "1"):
                return f"Включили «{pretty}»"
            if v in ("false", "0"):
                return f"Выключили «{pretty}»"
            return f"Изменили «{pretty}» → {v}"
        return None
    if name.startswith("search_open:"):
        return f"Перешли из поиска настроек (№{name.split(':', 1)[-1]})"
    if name.startswith("deeplink:"):
        return f"Открыли по ссылке: {name.split(':', 1)[-1]}"
    return name


class Report:
    def __init__(self, db, days, top):
        self.db = db
        self.top = top
        self.today = datetime.now(timezone.utc).date()
        self.all_time = days == 0
        if self.all_time:
            earliest = self.one("SELECT MIN(first_seen) FROM installs")
            if earliest:
                try:
                    start = date.fromisoformat(str(earliest)[:10])
                except ValueError:
                    start = self.today
            else:
                start = self.today
            self.since = start.isoformat()
            self.days = max(1, (self.today - start).days + 1)
            self.period_label = "за всё время"
        else:
            self.days = days
            start = self.today - timedelta(days=days - 1)
            self.since = start.isoformat()
            self.period_label = f"за {days} дн."
        self.active = self.active_between(start, self.today)

    def q(self, sql, params=()):
        return self.db.execute(sql, params).fetchall()

    def one(self, sql, params=()):
        return self.db.execute(sql, params).fetchone()[0] or 0

    def active_between(self, start, end):
        return self.one(
            "SELECT COUNT(DISTINCT iid) FROM counters WHERE day BETWEEN ? AND ?",
            (start.isoformat(), end.isoformat()))

    def counters(self, prefix, limit=None):
        rows = self.q(
            """SELECT name, COUNT(DISTINCT iid), SUM(count) FROM counters
               WHERE day >= ? AND name LIKE ? GROUP BY name ORDER BY 2 DESC, 3 DESC LIMIT ?""",
            (self.since, prefix + "%", limit or self.top))
        return [(n, u, c) for n, u, c in rows]

    def insight_lines(self, total, new, dau, wau, mau, ai_users, quota_users, misses_top):
        lines = []
        if total == 0:
            lines.append("Пока нет данных: клиенты ещё не успели отправить статистику.")
            return lines
        lines.append(f"Всего в базе {total} установок. За выбранный период впервые появились {new}.")
        if mau:
            lines.append(
                f"За месяц заходили {mau} человек, за неделю — {wau}, сегодня уже отметились {dau}.")
        if ai_users:
            share = pct(ai_users, self.active)
            line = f"ИИ пользуются {ai_users} человек ({share} из активных за период)."
            if quota_users:
                line += f" Дневной лимит исчерпали {quota_users} — сигнал, что лимит тесный."
            lines.append(line)
        else:
            lines.append("Запросы к ИИ за период почти не фиксировались.")
        if misses_top:
            q, n = misses_top[0]
            lines.append(f"Чаще всего ищут в настройках и не находят: «{q}» ({n} раз) — подсказка, чего не хватает.")
        lines.append("Цифры за сегодня могут быть неполными: приложение шлёт данные примерно раз в 6 часов.")
        return lines

    def build(self):
        today = self.today
        sections = []

        total = self.one("SELECT COUNT(*) FROM installs")
        new = self.one("SELECT COUNT(*) FROM installs WHERE first_seen >= ?", (self.since,))
        dau = self.active_between(today, today)
        dau_y = self.active_between(today - timedelta(days=1), today - timedelta(days=1))
        wau = self.active_between(today - timedelta(days=6), today)
        mau = self.active_between(today - timedelta(days=29), today)

        cohort_start = (today - timedelta(days=14)).isoformat()
        cohort_end = (today - timedelta(days=8)).isoformat()
        cohort = self.one(
            "SELECT COUNT(*) FROM installs WHERE first_seen BETWEEN ? AND ?",
            (cohort_start, cohort_end))
        retained = self.one(
            """SELECT COUNT(DISTINCT c.iid) FROM counters c JOIN installs i ON i.iid = c.iid
               WHERE i.first_seen BETWEEN ? AND ? AND c.day >= ?""",
            (cohort_start, cohort_end, (today - timedelta(days=6)).isoformat()))

        ai_users = self.one(
            "SELECT COUNT(DISTINCT iid) FROM counters WHERE day >= ? AND name LIKE 'ai:request:%'",
            (self.since,))
        quota_users = self.one(
            "SELECT COUNT(DISTINCT iid) FROM counters WHERE day >= ? AND name = 'ai:quota_exhausted'",
            (self.since,))
        misses_top = self.q(
            "SELECT query, SUM(count) FROM search_miss WHERE day >= ? GROUP BY query "
            "ORDER BY 2 DESC LIMIT 1", (self.since,))

        sections.append({
            "kind": "insights",
            "title": "📌 Коротко: что происходит",
            "lines": self.insight_lines(total, new, dau, wau, mau, ai_users, quota_users, misses_top),
        })

        kpi_rows = [
            ("📦 Всего установок", total, "Сколько раз поставили Mgla (анонимно)"),
        ]
        if self.all_time:
            kpi_rows.append(
                ("👥 Когда-либо активны", self.active, "Хоть раз прислали счётчики за всё время"))
        else:
            kpi_rows.append(
                (f"🆕 Новых {self.period_label}", new, "Первый раз прислали статистику"))
        kpi_rows += [
            ("☀️ Сегодня", dau, "Хоть раз что-то сделали сегодня"),
            ("🌙 Вчера", dau_y, "Были активны вчера"),
            ("📅 За 7 дней", wau, "Уникальные установки за неделю"),
            ("🗓️ За 30 дней", mau, "Уникальные установки за месяц"),
            ("↩️ Возвращаемость", pct(retained, cohort),
             "Из тех, кто пришёл 8–14 дней назад, сколько ещё заходят"),
        ]
        sections.append({
            "kind": "kpis",
            "title": "👥 Сколько людей",
            "kv": kpi_rows,
        })

        for column, title, hint in (
            ("ver", "🏷️ Версии Mgla", "Какая сборка у людей"),
            ("man", "🏭 Производители", None),
            ("model", "📱 Модели", None),
            ("sdk", "🤖 Android (SDK)", "Чем выше — тем новее Android"),
            ("lang", "🌍 Язык системы", None),
        ):
            rows = self.q(
                f"SELECT COALESCE({column}, '?'), COUNT(*) FROM installs WHERE last_seen >= ? "
                f"GROUP BY 1 ORDER BY 2 DESC LIMIT ?", (self.since, self.top))
            s = sum(r[1] for r in rows)
            sections.append({
                "kind": "table",
                "title": title,
                "subtitle": hint,
                "headers": ["Значение", "Установок", "Доля"],
                "rows": [(v, n, pct(n, s)) for v, n in rows],
                "bar": 1,
                "group": "devices",
            })

        chart_days = min(self.days, 120) if self.all_time else self.days
        active_by_day = dict(self.q(
            "SELECT day, COUNT(DISTINCT iid) FROM counters WHERE day >= ? GROUP BY day",
            ((today - timedelta(days=chart_days - 1)).isoformat(),)))
        new_by_day = dict(self.q(
            "SELECT first_seen, COUNT(*) FROM installs WHERE first_seen >= ? GROUP BY first_seen",
            ((today - timedelta(days=chart_days - 1)).isoformat(),)))
        chart = []
        for i in range(chart_days):
            d = (today - timedelta(days=chart_days - 1 - i)).isoformat()
            chart.append((d, active_by_day.get(d, 0), new_by_day.get(d, 0)))
        chart_note = (
            f"За последние {chart_days} дн. (весь период длиннее)."
            if self.all_time and chart_days < self.days else
            "Синие столбцы — сколько людей пользовались приложением. Зелёная линия — новые установки."
        )
        sections.append({
            "kind": "chart",
            "title": "📈 Активность по дням",
            "subtitle": chart_note,
            "chart": chart,
        })

        quota_events = self.one(
            "SELECT SUM(count) FROM counters WHERE day >= ? AND name = 'ai:quota_exhausted'",
            (self.since,))
        sections.append(self.counter_section(
            "ai:", "✨ Искусственный интеллект",
            f"ИИ пользуются {ai_users} человек ({pct(ai_users, self.active)} активных). "
            f"В лимит упёрлись {quota_users} человек, всего {quota_events or 0} раз."))

        usage_rows = []
        seen = set()
        for prefix in ("app_foreground", "copy_photo:", "hidden_chats:"):
            if prefix.endswith(":"):
                items = self.counters(prefix)
            else:
                items = [
                    (n, u, c) for n, u, c in self.q(
                        """SELECT name, COUNT(DISTINCT iid), SUM(count) FROM counters
                           WHERE day >= ? AND name = ? GROUP BY name""",
                        (self.since, prefix))
                ]
            for n, u, c in items:
                label = label_counter(n)
                if label in seen:
                    continue
                seen.add(label)
                usage_rows.append((label, u, pct(u, self.active), c))
        sections.append({
            "kind": "table",
            "title": "🧭 Что делают в приложении",
            "subtitle": "Самые заметные действия за период (не настройки).",
            "headers": ["Действие", "Сколько человек", "Доля активных", "Сколько раз"],
            "rows": usage_rows[: self.top],
            "bar": 1,
            "bar_max": self.active or 1,
        })

        sections.append(self.counter_section(
            "set:", "⚙️ Что меняют в настройках",
            "Каждую строку читайте как «столько человек сделали это действие»."))

        rows = self.q(
            """SELECT s.key, s.value, COUNT(*) FROM snapshot s JOIN installs i ON i.iid = s.iid
               WHERE i.last_seen >= ? GROUP BY s.key, s.value""", (self.since,))
        by_key = {}
        for key, value, n in rows:
            by_key.setdefault(key, {})[value] = n
        feature_items = []
        for key, values in by_key.items():
            pretty = label_setting(key)
            if not pretty or not (set(values) <= {"true", "false"}):
                continue
            n = sum(values.values())
            on = values.get("true", 0)
            off = values.get("false", 0)
            share = (100.0 * on / n) if n else 0.0
            feature_items.append({
                "key": key,
                "name": pretty,
                "icon": SETTING_ICONS.get(key, "🔹"),
                "on": on,
                "off": off,
                "total": n,
                "pct": f"{share:.0f}%",
                "share": share,
            })
        feature_items.sort(key=lambda r: (-r["share"], -r["on"], r["name"]))
        sections.append({
            "kind": "features",
            "title": "🧩 Какие функции сейчас включены",
            "subtitle": (
                "Карточки настроек у активных за период. "
                "Зелёная полоска — сколько человек держат опцию включённой, "
                "серая — выключенной. Процент — доля включённых."
            ),
            "items": feature_items,
        })

        miss_rows = self.q(
            "SELECT query, SUM(count) FROM search_miss WHERE day >= ? GROUP BY query "
            "ORDER BY 2 DESC LIMIT ?", (self.since, self.top))
        sections.append({
            "kind": "table",
            "title": "🔎 Ищут в настройках — и не находят",
            "subtitle": "Это готовый список идей: чего люди ждут, а чего ещё нет.",
            "headers": ["Что искали", "Сколько раз"],
            "rows": miss_rows,
            "bar": 1,
        })
        return sections

    def counter_section(self, prefix, title, note=None):
        rows = []
        for n, u, c in self.counters(prefix):
            label = label_counter(n)
            if not label:
                continue
            rows.append((label, u, pct(u, self.active), c))
        return {
            "kind": "table",
            "title": title,
            "subtitle": note,
            "headers": ["Что произошло", "Сколько человек", "Доля активных", "Сколько раз"],
            "rows": rows,
            "bar": 1,
            "bar_max": self.active or 1,
        }


def render_text(sections, days, since):
    period = "за всё время" if days == 0 else f"за {days} дн."
    out = [f"Mgla Hub — статистика {period} (с {since})"]
    for s in sections:
        out += ["", "=" * 72, s["title"], "=" * 72]
        if s.get("kind") == "insights":
            out += [f"  • {line}" for line in s["lines"]]
        elif s.get("kind") == "kpis":
            for k, v, hint in s["kv"]:
                out.append(f"  {k}: {v}" + (f"  ({hint})" if hint else ""))
        elif s.get("kind") == "features":
            for it in s.get("items") or []:
                out.append(
                    f"  {it['icon']} {it['name']}: вкл. {it['on']} / выкл. {it['off']} "
                    f"({it['pct']} из {it['total']})"
                )
        elif "chart" in s:
            out += [f"  {d}  активны: {a:<6} новых: {n}" for d, a, n in s["chart"]]
        else:
            rows, headers = s.get("rows") or [], s.get("headers") or []
            if not rows:
                out.append("  (нет данных)")
            else:
                widths = [max(len(str(h)), *(len(str(r[i])) for r in rows)) for i, h in enumerate(headers)]
                out.append("  " + "  ".join(str(h).ljust(w) for h, w in zip(headers, widths)))
                out += ["  " + "  ".join(str(v).ljust(w) for v, w in zip(r, widths)) for r in rows]
        if s.get("subtitle"):
            out.append(f"  * {s['subtitle']}")
    return "\n".join(out)


CSS = """
:root {
  --bg0:#050304;
  --bg1:#100a0c;
  --card:#1a1014;
  --card2:#22161a;
  --line:#3d2a32;
  --text:#f3e9ec;
  --muted:#b09aa2;
  --accent:#8b1e3f;
  --accent-hi:#c23b5f;
  --accent2:#9a6b78;
  --accent3:#5a3a44;
  --ok:#7d9e8a;
  --danger:#b33a4a;
  --radius:16px;
  --shadow:0 16px 40px rgba(0,0,0,.45);
}
* { box-sizing:border-box; }
html {
  scroll-behavior:smooth;
  -webkit-text-size-adjust:100%;
  text-size-adjust:100%;
}
body {
  margin:0; color:var(--text);
  font:15px/1.45 "Segoe UI", "SF Pro Display", system-ui, sans-serif;
  background:
    radial-gradient(900px 480px at 8% -8%, rgba(139,30,63,.28), transparent 55%),
    radial-gradient(700px 420px at 100% 0%, rgba(90,58,68,.22), transparent 50%),
    linear-gradient(180deg, var(--bg1), var(--bg0) 42%, #030203);
  min-height:100vh;
  max-width:100vw;
  overflow-x:hidden;
}
.wrap {
  width:100%; max-width:1120px; margin:0 auto;
  padding:24px 16px 64px;
}
.hero {
  display:flex; flex-wrap:wrap; gap:14px; align-items:flex-end;
  justify-content:space-between; margin-bottom:18px;
}
.brand { display:flex; gap:12px; align-items:center; min-width:0; flex:1 1 200px; }
.logo {
  width:48px; height:48px; border-radius:14px; flex:0 0 auto;
  background:linear-gradient(145deg, #c23b5f, #6e152f);
  box-shadow:0 10px 28px rgba(139,30,63,.4);
  display:grid; place-items:center; font-weight:800; color:#f8eef1; font-size:18px;
}
.brand h1 { margin:0; font-size:24px; letter-spacing:-.02em; line-height:1.15; }
.brand p { margin:3px 0 0; color:var(--muted); font-size:13px; }
.nav {
  display:flex; flex-wrap:nowrap; gap:6px; overflow-x:auto; -webkit-overflow-scrolling:touch;
  max-width:100%; padding-bottom:2px; scrollbar-width:none;
}
.nav::-webkit-scrollbar { display:none; }
.nav a {
  color:var(--muted); text-decoration:none; padding:8px 11px; border-radius:999px;
  border:1px solid var(--line); background:rgba(255,255,255,.03); white-space:nowrap; flex:0 0 auto;
  font-size:13px;
}
.nav a.on { color:#fff; background:var(--accent); border-color:var(--accent-hi); font-weight:650; }
.meta { color:var(--muted); font-size:13px; margin:0 0 16px; word-break:break-word; }
.insights {
  background:linear-gradient(135deg, rgba(139,30,63,.18), rgba(58,40,48,.35));
  border:1px solid rgba(139,30,63,.35); border-radius:var(--radius);
  padding:16px 18px; margin-bottom:16px; box-shadow:var(--shadow);
}
.insights h2 { margin:0 0 10px; font-size:16px; }
.insights ul { margin:0; padding-left:18px; }
.insights li { margin:6px 0; color:#e8dce0; }
.kpis {
  display:grid; grid-template-columns:repeat(auto-fill,minmax(140px,1fr)); gap:10px;
  margin-bottom:8px;
}
.kpi {
  background:var(--card); border:1px solid var(--line); border-radius:14px;
  padding:12px; min-width:0;
}
.kpi .v { font-size:22px; font-weight:700; letter-spacing:-.03em; word-break:break-word; }
.kpi .k { color:var(--text); font-size:12.5px; margin-top:4px; font-weight:600; }
.kpi .h { color:var(--muted); font-size:11.5px; margin-top:4px; }
.card {
  background:var(--card); border:1px solid var(--line); border-radius:var(--radius);
  padding:16px; margin-top:14px; box-shadow:0 10px 28px rgba(0,0,0,.25);
  overflow:hidden;
}
.card h2 { margin:0 0 4px; font-size:16px; }
.sub { color:var(--muted); font-size:13px; margin:0 0 12px; }
.grid2 { display:grid; grid-template-columns:repeat(auto-fit,minmax(240px,1fr)); gap:0 14px; }
.table-wrap { width:100%; overflow-x:auto; -webkit-overflow-scrolling:touch; }
table { width:100%; border-collapse:collapse; min-width:0; }
th {
  text-align:left; color:var(--muted); font-weight:600; font-size:11px;
  text-transform:uppercase; letter-spacing:.04em;
  border-bottom:1px solid var(--line); padding:8px 6px;
}
td { padding:8px 6px; border-bottom:1px solid var(--line); vertical-align:middle; font-size:13px; }
tr:last-child td { border-bottom:0; }
td.num { white-space:nowrap; font-variant-numeric:tabular-nums; }
td.name { word-break:break-word; overflow-wrap:anywhere; }
td.bar { width:36%; min-width:90px; }
.bar div { display:flex; align-items:center; gap:8px; }
.bar b { font-weight:600; min-width:28px; text-align:right; }
.bar span { flex:1; height:8px; border-radius:999px; background:#2a1c22; overflow:hidden; }
.bar span i {
  display:block; height:100%; border-radius:999px;
  background:linear-gradient(90deg, var(--accent), var(--accent-hi));
}
.feat-grid {
  display:grid; grid-template-columns:repeat(auto-fill,minmax(220px,1fr)); gap:10px;
}
.feat {
  background:var(--card2); border:1px solid var(--line); border-radius:14px;
  padding:12px; display:flex; flex-direction:column; gap:10px; min-width:0;
}
.feat-top { display:flex; gap:10px; align-items:flex-start; }
.feat-ico {
  width:38px; height:38px; border-radius:12px; flex:0 0 auto;
  display:grid; place-items:center; font-size:17px;
  background:rgba(139,30,63,.15); border:1px solid var(--line);
}
.feat-name { font-weight:650; font-size:13.5px; line-height:1.3; }
.feat-meta { color:var(--muted); font-size:12px; margin-top:3px; }
.feat-track { height:9px; border-radius:999px; background:#2a1c22; overflow:hidden; display:flex; }
.feat-track .on { background:linear-gradient(90deg,#6e152f,var(--accent-hi)); height:100%; }
.feat-track .off { background:#3a2a32; height:100%; }
.feat-pills { display:flex; flex-wrap:wrap; gap:6px; }
.pill {
  font-size:11px; font-weight:650; padding:4px 8px; border-radius:999px;
  border:1px solid var(--line);
}
.pill.yes { color:#e8c4cf; background:rgba(139,30,63,.2); border-color:rgba(194,59,95,.4); }
.pill.no { color:#c5b4ba; background:rgba(255,255,255,.04); }
.empty { color:var(--muted); padding:10px 0; }
.legend { color:var(--muted); font-size:12px; margin-bottom:8px; }
.legend i {
  display:inline-block; width:10px; height:10px; border-radius:3px; margin:0 6px 0 12px;
  vertical-align:middle;
}
.chart-wrap { width:100%; overflow-x:auto; -webkit-overflow-scrolling:touch; }
.chart-wrap svg { min-width:100%; max-width:none; height:auto; }
@media (min-width:721px) {
  .chart-wrap svg { min-width:520px; }
}
svg text { fill:var(--muted); font-size:10px; }
.footer { margin-top:28px; color:var(--muted); font-size:12.5px; text-align:center; padding:0 8px; }
@media (max-width:720px) {
  body { font-size:14px; }
  .wrap { padding:14px 10px 48px; }
  .hero { gap:10px; align-items:flex-start; }
  .brand h1 { font-size:20px; }
  .brand p { font-size:12px; }
  .logo { width:40px; height:40px; border-radius:12px; font-size:16px; }
  .kpis { grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; }
  .kpi { padding:10px; }
  .kpi .v { font-size:18px; }
  .card { padding:12px; border-radius:14px; }
  .card h2 { font-size:15px; }
  .feat-grid { grid-template-columns:1fr; }
  .grid2 { grid-template-columns:1fr; }
  table { font-size:12px; }
  th, td { padding:7px 5px; }
  td.bar { min-width:72px; width:30%; }
  .nav a { padding:7px 10px; font-size:12.5px; }
}
"""


def render_chart(points):
    w, h, pad_l, pad_b, pad_t = 1100, 230, 36, 28, 12
    top = max([max(a, n) for _, a, n in points] or [1]) or 1
    n = len(points)
    step = (w - pad_l) / max(n, 1)
    bar_w = max(step * 0.62, 1)
    plot_h = h - pad_b - pad_t
    parts = [f'<svg viewBox="0 0 {w} {h}" width="100%" preserveAspectRatio="none">']
    for i in range(5):
        y = pad_t + plot_h * i / 4
        val = round(top * (4 - i) / 4)
        parts.append(f'<line x1="{pad_l}" x2="{w}" y1="{y:.1f}" y2="{y:.1f}" stroke="#2a313d"/>')
        parts.append(f'<text x="{pad_l - 6}" y="{y + 3:.1f}" text-anchor="end">{val}</text>')
    line = []
    label_every = max(1, n // 10)
    for i, (d, active, new) in enumerate(points):
        x = pad_l + i * step + (step - bar_w) / 2
        bh = plot_h * active / top
        parts.append(
            f'<rect x="{x:.1f}" y="{pad_t + plot_h - bh:.1f}" width="{bar_w:.1f}" height="{bh:.1f}" '
            f'rx="3" fill="#8b1e3f" opacity=".95"><title>{d}: активны {active}, новых {new}</title></rect>')
        line.append(f"{x + bar_w / 2:.1f},{pad_t + plot_h - plot_h * new / top:.1f}")
        if i % label_every == 0 or i == n - 1:
            parts.append(f'<text x="{x + bar_w / 2:.1f}" y="{h - 8}" text-anchor="middle">{d[5:]}</text>')
    parts.append(f'<polyline points="{" ".join(line)}" fill="none" stroke="#c23b5f" stroke-width="2.5"/>')
    parts.append("</svg>")
    return (
        '<div class="legend"><i style="background:#8b1e3f"></i>Активные люди'
        '<i style="background:#c23b5f"></i>Новые установки</div>' + "".join(parts))


def render_table(s):
    rows, headers, bar = s.get("rows") or [], s.get("headers") or [], s.get("bar")
    if not rows:
        return '<div class="empty">Пока нет данных за этот период</div>'
    bar_max = s.get("bar_max") or (max([r[bar] for r in rows] or [1]) if bar is not None else 1)
    out = ['<div class="table-wrap"><table><thead><tr>']
    out += [f"<th>{html.escape(str(hd))}</th>" for hd in headers]
    out.append("</tr></thead><tbody>")
    for r in rows:
        out.append("<tr>")
        for i, v in enumerate(r):
            text = html.escape(str(v))
            if i == 0:
                out.append(f'<td class="name">{text}</td>')
            elif bar is not None and i == bar and isinstance(v, (int, float)):
                width = min(100.0, 100.0 * v / bar_max) if bar_max else 0
                out.append(
                    f'<td class="num bar"><div><b>{text}</b>'
                    f'<span><i style="width:{width:.1f}%"></i></span></div></td>')
            else:
                out.append(f'<td class="num">{text}</td>')
        out.append("</tr>")
    out.append("</tbody></table></div>")
    return "".join(out)


def render_features(s):
    items = s.get("items") or []
    if not items:
        return '<div class="empty">Пока нет снимков настроек за этот период</div>'
    legend = (
        '<div class="legend feat-legend">'
        '<i style="background:linear-gradient(90deg,#6e152f,#c23b5f)"></i>доля «включено»'
        '<i style="background:#3a2a32"></i>доля «выключено»'
        ' · каждая карточка — одна опция у активных за период'
        '</div>'
    )
    cards = []
    for it in items:
        on, off, total = it["on"], it["off"], it["total"] or 1
        on_w = 100.0 * on / total
        off_w = 100.0 - on_w
        tone = "yes" if on_w >= 50 else "no"
        cards.append(
            f'<div class="feat">'
            f'<div class="feat-top"><div class="feat-ico">{html.escape(it["icon"])}</div>'
            f'<div><div class="feat-name">{html.escape(it["name"])}</div>'
            f'<div class="feat-meta">Включено у <b>{on}</b> из {total} · {html.escape(it["pct"])}</div></div></div>'
            f'<div class="feat-track"><div class="on" style="width:{on_w:.1f}%"></div>'
            f'<div class="off" style="width:{off_w:.1f}%"></div></div>'
            f'<div class="feat-pills">'
            f'<span class="pill yes">✅ вкл. {on}</span>'
            f'<span class="pill no">⛔ выкл. {off}</span>'
            f'<span class="pill {tone}">{"Чаще включена" if on_w >= 50 else "Чаще выключена"}</span>'
            f'</div></div>'
        )
    return legend + '<div class="feat-grid">' + "".join(cards) + "</div>"


def _render_devices_card(devices):
    parts = ['<div class="card"><h2>💻 Версии и устройства</h2><div class="grid2">']
    for s in devices:
        sub = f'<p class="sub">{html.escape(s["subtitle"])}</p>' if s.get("subtitle") else ""
        parts.append(
            f'<div><h2 style="font-size:15px;margin:14px 0 4px">{html.escape(s["title"])}</h2>'
            f'{sub}{render_table(s)}</div>')
    parts.append("</div></div>")
    return "".join(parts)


def render_html(sections, days, since, links=False, period_label=None):
    generated = datetime.now().strftime("%d.%m.%Y %H:%M")
    if period_label is None:
        period_label = "за всё время" if days == 0 else f"последние {days} дн."
    nav = ""
    if links:
        period_links = []
        for d, label in ((1, "1 дн."), (7, "7 дн."), (30, "30 дн."), (90, "90 дн."), (0, "Всё время")):
            href = "?days=all" if d == 0 else f"?days={d}"
            cls = " class='on'" if d == days else ""
            period_links.append(f"<a href='{href}'{cls}>📅 {label}</a>")
        nav = f'<div class="nav">{"".join(period_links)}<a href="/admin">🎛️ Управление</a></div>'
    body = [f"""
<div class="hero">
  <div class="brand">
    <div class="logo">M</div>
    <div>
      <h1>Mgla Hub</h1>
      <p>📊 Статистика клиента · доступ только у вас</p>
    </div>
  </div>
  {nav}
</div>
<div class="meta">📅 Период: {html.escape(period_label)} (с {html.escape(str(since))}) · обновлено {generated} UTC</div>
"""]

    i = 0
    while i < len(sections):
        s = sections[i]
        kind = s.get("kind")
        if s.get("group") == "devices":
            devices = []
            while i < len(sections) and sections[i].get("group") == "devices":
                devices.append(sections[i])
                i += 1
            body.append(_render_devices_card(devices))
            continue
        if kind == "insights":
            items = "".join(f"<li>{html.escape(line)}</li>" for line in s["lines"])
            body.append(f'<div class="insights"><h2>{html.escape(s["title"])}</h2><ul>{items}</ul></div>')
            i += 1
            continue
        if kind == "kpis":
            body.append(f'<div class="card"><h2>{html.escape(s["title"])}</h2>')
            body.append('<div class="kpis">')
            for k, v, hint in s["kv"]:
                body.append(
                    f'<div class="kpi"><div class="v">{html.escape(str(v))}</div>'
                    f'<div class="k">{html.escape(k)}</div>'
                    f'<div class="h">{html.escape(hint or "")}</div></div>')
            body.append("</div></div>")
            i += 1
            continue
        if kind == "chart":
            inner = '<div class="chart-wrap">' + render_chart(s["chart"]) + "</div>"
        elif kind == "features":
            inner = render_features(s)
        else:
            inner = render_table(s)
        sub = f'<p class="sub">{html.escape(s["subtitle"])}</p>' if s.get("subtitle") else ""
        body.append(
            f'<div class="card"><h2>{html.escape(s["title"])}</h2>{sub}{inner}</div>')
        i += 1

    body.append(
        '<div class="footer">🔒 Данные анонимные: без Telegram ID, номеров и текстов чатов. '
        'IP клиента не сохраняется.</div>')

    return (
        "<!doctype html><html lang='ru'><head><meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width, initial-scale=1, viewport-fit=cover'>"
        "<meta name='robots' content='noindex,nofollow'>"
        f"<title>Mgla Hub — статистика</title><style>{CSS}</style></head>"
        f"<body><div class='wrap'>{''.join(body)}</div></body></html>")


def generate_html(db, days, top, links=True):
    report = Report(db, days, top)
    return render_html(
        report.build(), days, report.since, links=links, period_label=report.period_label)


def send_telegram(html_text, days, summary):
    token = os.environ.get("MGLA_STATS_BOT_TOKEN", "")
    chat_id = os.environ.get("MGLA_STATS_CHAT_ID", "")
    if not token or not chat_id:
        raise SystemExit("MGLA_STATS_BOT_TOKEN / MGLA_STATS_CHAT_ID are not set")
    boundary = uuid.uuid4().hex
    filename = f"mgla-hub-{datetime.now().strftime('%Y-%m-%d')}.html"
    parts = []
    for name, value in (("chat_id", chat_id), ("caption", summary)):
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="document"; filename="{filename}"\r\n'
        f'Content-Type: text/html\r\n\r\n'.encode() + html_text.encode("utf-8") + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    req = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/sendDocument", data=b"".join(parts),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        resp.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default=DB_PATH)
    ap.add_argument("--days", type=int, default=30, help="0 = за всё время")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--name", help="только счётчики с этим префиксом (текст)")
    ap.add_argument("--html", metavar="FILE", help="сохранить HTML в файл")
    ap.add_argument("--telegram", action="store_true", help="отправить HTML в Telegram")
    args = ap.parse_args()

    report = Report(sqlite3.connect(args.db), args.days, args.top)

    if args.telegram:
        sections = report.build()
        kpis = next((s for s in sections if s.get("kind") == "kpis"), None)
        kv = {k: v for k, v, _ in (kpis["kv"] if kpis else [])}
        period = report.period_label
        summary = (
            f"Mgla Hub — {period}\n"
            f"Сегодня: {kv.get('☀️ Сегодня', kv.get('Сегодня', '—'))}, "
            f"за 7 дн.: {kv.get('📅 За 7 дней', '—')}, "
            f"за 30 дн.: {kv.get('🗓️ За 30 дней', '—')}\n"
            f"всего: {kv.get('📦 Всего установок', '—')}")
        send_telegram(
            render_html(sections, args.days, report.since, period_label=report.period_label),
            args.days, summary)
        print("Отправлено в Telegram")
        return

    if args.name:
        rows = []
        for n, u, c in report.counters(args.name, limit=10_000):
            label = label_counter(n)
            if label:
                rows.append((label, u, pct(u, report.active), c))
        section = {
            "kind": "table",
            "title": f"Счётчики «{args.name}*» (активных: {report.active})",
            "headers": ["Что произошло", "Человек", "%", "Раз"],
            "rows": rows,
        }
        print(render_text([section], args.days, report.since))
        return

    sections = report.build()
    if args.html:
        with open(args.html, "w", encoding="utf-8") as f:
            f.write(render_html(
                sections, args.days, report.since, period_label=report.period_label))
        print(f"HTML-отчёт сохранён: {os.path.abspath(args.html)}")
    else:
        print(render_text(sections, args.days, report.since))


if __name__ == "__main__":
    main()
