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
from datetime import datetime, timedelta, timezone

DB_PATH = os.environ.get("MGLA_STATS_DB", "/opt/mgla-ws-relay/stats.db")

# --- Словарь: технические имена → понятный русский ---

SETTING_LABELS = {
    "chat_time_seconds": "Время сообщений с секундами",
    "sticker_time_hidden": "Скрыто время на стикерах",
    "hide_keyboard_on_scroll": "Скрывать клавиатуру при прокрутке",
    "comma_after_mention": "Запятая после упоминания",
    "ai_enabled": "ИИ включён",
    "ai_summary": "Краткая свозка",
    "ai_retell": "Пересказ сообщений",
    "ai_editor": "AI-редактор",
    "ai_chat_dna": "Chat DNA",
    "transcribe_enabled": "Расшифровка голосовых",
    "text_anim_enabled": "Анимация ввода текста",
    "ws_enabled": "Обход блокировок (WS)",
    "camera_api": "Камера (API)",
    "camera_x_60fps": "Камера 60 FPS",
    "accounts": "Число аккаунтов в приложении",
    "recent_stickers_limit": "Лимит недавних стикеров",
    "bottom_button_mode": "Нижняя кнопка в чате",
    "double_tap_out": "Двойной тап (исходящие)",
    "double_tap_in": "Двойной тап (входящие)",
    "ai_provider": "Провайдер ИИ",
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

SCREEN_LABELS = {
    "MglaMainSettingsActivity": "Главные настройки Mgla",
    "MglaChatsSettingsActivity": "Настройки «Чаты»",
    "MglaAppearanceSettingsActivity": "Внешний вид",
    "MglaTextAnimationActivity": "Анимация текста",
    "MglaMessageMenuSettingsActivity": "Меню сообщения",
    "MglaAiSettingsActivity": "Настройки ИИ",
    "PasscodeActivity": "Код-пароль",
    "DialogsActivity": "Список чатов",
    "ChatActivity": "Чат",
    "SettingsActivity": "Настройки Telegram",
    "ProfileActivity": "Профиль",
}


def pct(part, whole):
    return f"{100.0 * part / whole:.0f}%" if whole else "—"


def label_setting(key):
    return SETTING_LABELS.get(key, key.replace("_", " "))


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
        cls = name[7:]
        short = cls.rsplit(".", 1)[-1]
        return f"Открыли: {SCREEN_LABELS.get(short, short)}"
    if name.startswith("set:"):
        body = name[4:]
        if "=" in body:
            k, v = body.split("=", 1)
            pretty = label_setting(k)
            if v in ("true", "1"):
                return f"Включили «{pretty}»"
            if v in ("false", "0"):
                return f"Выключили «{pretty}»"
            return f"Изменили «{pretty}» → {v}"
        return f"Изменили настройку: {body}"
    if name.startswith("search_open:"):
        return f"Перешли из поиска настроек (№{name.split(':', 1)[-1]})"
    if name.startswith("deeplink:"):
        return f"Открыли по ссылке: {name.split(':', 1)[-1]}"
    return name


class Report:
    def __init__(self, db, days, top):
        self.db = db
        self.days = days
        self.top = top
        self.today = datetime.now(timezone.utc).date()
        self.since = (self.today - timedelta(days=days - 1)).isoformat()
        self.active = self.active_between(self.today - timedelta(days=days - 1), self.today)

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
            "title": "Коротко: что происходит",
            "lines": self.insight_lines(total, new, dau, wau, mau, ai_users, quota_users, misses_top),
        })

        sections.append({
            "kind": "kpis",
            "title": "Сколько людей",
            "kv": [
                ("Всего установок", total, "Сколько раз поставили Mgla (анонимно)"),
                (f"Новых за {self.days} дн.", new, "Первый раз прислали статистику"),
                ("Сегодня", dau, "Хоть раз что-то сделали сегодня"),
                ("Вчера", dau_y, "Были активны вчера"),
                ("За 7 дней", wau, "Уникальные установки за неделю"),
                ("За 30 дней", mau, "Уникальные установки за месяц"),
                ("Возвращаемость", pct(retained, cohort),
                 "Из тех, кто пришёл 8–14 дней назад, сколько ещё заходят"),
            ],
        })

        active_by_day = dict(self.q(
            "SELECT day, COUNT(DISTINCT iid) FROM counters WHERE day >= ? GROUP BY day", (self.since,)))
        new_by_day = dict(self.q(
            "SELECT first_seen, COUNT(*) FROM installs WHERE first_seen >= ? GROUP BY first_seen",
            (self.since,)))
        chart = []
        for i in range(self.days):
            d = (today - timedelta(days=self.days - 1 - i)).isoformat()
            chart.append((d, active_by_day.get(d, 0), new_by_day.get(d, 0)))
        sections.append({
            "kind": "chart",
            "title": "Активность по дням",
            "subtitle": "Синие столбцы — сколько людей пользовались приложением. Зелёная линия — новые установки.",
            "chart": chart,
        })

        quota_events = self.one(
            "SELECT SUM(count) FROM counters WHERE day >= ? AND name = 'ai:quota_exhausted'",
            (self.since,))
        sections.append(self.counter_section(
            "ai:", "Искусственный интеллект",
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
            "title": "Что делают в приложении",
            "subtitle": "Самые заметные действия за период (не настройки).",
            "headers": ["Действие", "Сколько человек", "Доля активных", "Сколько раз"],
            "rows": usage_rows[: self.top],
            "bar": 1,
            "bar_max": self.active or 1,
        })

        sections.append(self.counter_section(
            "screen:", "Какие экраны открывают",
            "Помогает понять, куда люди заходят в настройках Mgla."))
        sections.append(self.counter_section(
            "set:", "Что меняют в настройках",
            "Каждую строку читайте как «столько человек сделали это действие»."))

        rows = self.q(
            """SELECT s.key, s.value, COUNT(*) FROM snapshot s JOIN installs i ON i.iid = s.iid
               WHERE i.last_seen >= ? GROUP BY s.key, s.value""", (self.since,))
        by_key = {}
        for key, value, n in rows:
            by_key.setdefault(key, {})[value] = n
        booleans, others = [], []
        for key, values in by_key.items():
            n = sum(values.values())
            pretty = label_setting(key)
            if set(values) <= {"true", "false"}:
                on = values.get("true", 0)
                booleans.append((pretty, on, n, pct(on, n)))
            else:
                dist = ", ".join(
                    f"{v}: {c}" for v, c in sorted(values.items(), key=lambda x: -x[1])[:6])
                others.append((pretty, n, dist))
        booleans.sort(key=lambda r: (-r[1], r[0]))
        sections.append({
            "kind": "table",
            "title": "Какие функции сейчас включены",
            "subtitle": "По последнему снимку настроек у тех, кто был активен за период.",
            "headers": ["Функция", "Включено у", "Всего ответов", "Доля"],
            "rows": booleans,
            "bar": 1,
            "bar_max": max([r[2] for r in booleans] or [1]),
        })
        sections.append({
            "kind": "table",
            "title": "Другие значения настроек",
            "headers": ["Параметр", "Установок", "Как настроено"],
            "rows": sorted(others),
        })

        miss_rows = self.q(
            "SELECT query, SUM(count) FROM search_miss WHERE day >= ? GROUP BY query "
            "ORDER BY 2 DESC LIMIT ?", (self.since, self.top))
        sections.append({
            "kind": "table",
            "title": "Ищут в настройках — и не находят",
            "subtitle": "Это готовый список идей: чего люди ждут, а чего ещё нет.",
            "headers": ["Что искали", "Сколько раз"],
            "rows": miss_rows,
            "bar": 1,
        })

        for column, title, hint in (
            ("ver", "Версии Mgla", "Какая сборка у людей"),
            ("man", "Производители телефонов", None),
            ("model", "Модели", None),
            ("sdk", "Версия Android (SDK)", "Чем выше — тем новее Android"),
            ("lang", "Язык системы", None),
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
        return sections

    def counter_section(self, prefix, title, note=None):
        rows = [(label_counter(n), u, pct(u, self.active), c) for n, u, c in self.counters(prefix)]
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
    out = [f"Mgla Hub — статистика за {days} дн. (с {since})"]
    for s in sections:
        out += ["", "=" * 72, s["title"], "=" * 72]
        if s.get("kind") == "insights":
            out += [f"  • {line}" for line in s["lines"]]
        elif s.get("kind") == "kpis":
            for k, v, hint in s["kv"]:
                out.append(f"  {k}: {v}" + (f"  ({hint})" if hint else ""))
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
  --bg0:#0b0d10;
  --bg1:#12161c;
  --card:#171c24;
  --card2:#1c222c;
  --line:#2a313d;
  --text:#eef1f6;
  --muted:#9aa3b2;
  --accent:#e8a54b;
  --accent2:#3ecf8e;
  --accent3:#5b8def;
  --danger:#e85d5d;
  --radius:18px;
  --shadow:0 18px 50px rgba(0,0,0,.35);
}
* { box-sizing:border-box; }
html { scroll-behavior:smooth; }
body {
  margin:0; color:var(--text);
  font:15px/1.5 "Segoe UI", "SF Pro Display", system-ui, sans-serif;
  background:
    radial-gradient(1200px 600px at 10% -10%, rgba(232,165,75,.16), transparent 55%),
    radial-gradient(900px 500px at 100% 0%, rgba(91,141,239,.12), transparent 50%),
    linear-gradient(180deg, var(--bg1), var(--bg0) 40%, #090b0e);
  min-height:100vh;
}
.wrap { max-width:1120px; margin:0 auto; padding:28px 18px 72px; }
.hero {
  display:flex; flex-wrap:wrap; gap:18px; align-items:flex-end;
  justify-content:space-between; margin-bottom:22px;
}
.brand {
  display:flex; gap:14px; align-items:center;
}
.logo {
  width:52px; height:52px; border-radius:16px;
  background:linear-gradient(145deg, #f0b45a, #c47a22);
  box-shadow:0 10px 30px rgba(232,165,75,.35);
  display:grid; place-items:center; font-weight:800; color:#1a1208; font-size:20px;
}
.brand h1 { margin:0; font-size:28px; letter-spacing:-.02em; }
.brand p { margin:2px 0 0; color:var(--muted); font-size:14px; }
.nav { display:flex; flex-wrap:wrap; gap:8px; }
.nav a {
  color:var(--muted); text-decoration:none; padding:8px 12px; border-radius:999px;
  border:1px solid var(--line); background:rgba(255,255,255,.02);
}
.nav a.on { color:#1a1208; background:var(--accent); border-color:var(--accent); font-weight:650; }
.meta { color:var(--muted); font-size:13px; margin:0 0 18px; }
.insights {
  background:linear-gradient(135deg, rgba(232,165,75,.12), rgba(91,141,239,.08));
  border:1px solid rgba(232,165,75,.28); border-radius:var(--radius);
  padding:18px 20px; margin-bottom:18px; box-shadow:var(--shadow);
}
.insights h2 { margin:0 0 10px; font-size:17px; }
.insights ul { margin:0; padding-left:18px; }
.insights li { margin:6px 0; color:#dfe5ef; }
.kpis {
  display:grid; grid-template-columns:repeat(auto-fill,minmax(148px,1fr)); gap:12px;
  margin-bottom:8px;
}
.kpi {
  background:var(--card); border:1px solid var(--line); border-radius:16px;
  padding:14px 14px 12px; min-height:96px;
}
.kpi .v { font-size:26px; font-weight:700; letter-spacing:-.03em; }
.kpi .k { color:var(--text); font-size:13px; margin-top:4px; font-weight:600; }
.kpi .h { color:var(--muted); font-size:12px; margin-top:4px; }
.card {
  background:var(--card); border:1px solid var(--line); border-radius:var(--radius);
  padding:18px; margin-top:16px; box-shadow:0 10px 28px rgba(0,0,0,.18);
}
.card h2 { margin:0 0 4px; font-size:17px; }
.sub { color:var(--muted); font-size:13px; margin:0 0 12px; }
.grid2 { display:grid; grid-template-columns:repeat(auto-fit,minmax(320px,1fr)); gap:0 18px; }
table { width:100%; border-collapse:collapse; }
th {
  text-align:left; color:var(--muted); font-weight:600; font-size:12px;
  text-transform:uppercase; letter-spacing:.04em;
  border-bottom:1px solid var(--line); padding:8px;
}
td { padding:9px 8px; border-bottom:1px solid var(--line); vertical-align:middle; }
tr:last-child td { border-bottom:0; }
td.num { white-space:nowrap; font-variant-numeric:tabular-nums; }
td.name { word-break:break-word; }
td.bar { width:40%; }
.bar div { display:flex; align-items:center; gap:10px; }
.bar b { font-weight:600; min-width:36px; text-align:right; }
.bar span { flex:1; height:8px; border-radius:999px; background:#232a35; overflow:hidden; }
.bar span i {
  display:block; height:100%; border-radius:999px;
  background:linear-gradient(90deg, var(--accent), #f2c57a);
}
.empty { color:var(--muted); padding:10px 0; }
.legend { color:var(--muted); font-size:12.5px; margin-bottom:8px; }
.legend i {
  display:inline-block; width:10px; height:10px; border-radius:3px; margin:0 6px 0 12px;
  vertical-align:middle;
}
svg text { fill:var(--muted); font-size:10px; }
.footer {
  margin-top:28px; color:var(--muted); font-size:12.5px; text-align:center;
}
@media (max-width:640px) {
  .brand h1 { font-size:22px; }
  .kpi .v { font-size:22px; }
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
            f'rx="3" fill="#5b8def" opacity=".9"><title>{d}: активны {active}, новых {new}</title></rect>')
        line.append(f"{x + bar_w / 2:.1f},{pad_t + plot_h - plot_h * new / top:.1f}")
        if i % label_every == 0 or i == n - 1:
            parts.append(f'<text x="{x + bar_w / 2:.1f}" y="{h - 8}" text-anchor="middle">{d[5:]}</text>')
    parts.append(f'<polyline points="{" ".join(line)}" fill="none" stroke="#3ecf8e" stroke-width="2.5"/>')
    parts.append("</svg>")
    return (
        '<div class="legend"><i style="background:#5b8def"></i>Активные люди'
        '<i style="background:#3ecf8e"></i>Новые установки</div>' + "".join(parts))


def render_table(s):
    rows, headers, bar = s.get("rows") or [], s.get("headers") or [], s.get("bar")
    if not rows:
        return '<div class="empty">Пока нет данных за этот период</div>'
    bar_max = s.get("bar_max") or (max([r[bar] for r in rows] or [1]) if bar is not None else 1)
    out = ["<table><thead><tr>"]
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
    out.append("</tbody></table>")
    return "".join(out)


def render_html(sections, days, since, links=False):
    generated = datetime.now().strftime("%d.%m.%Y %H:%M")
    nav = ""
    if links:
        nav = '<div class="nav">' + "".join(
            f"<a href='?days={d}' class='{'on' if d == days else ''}'>{d} дн.</a>"
            for d in (1, 7, 30, 90)) + "</div>"
    body = [f"""
<div class="hero">
  <div class="brand">
    <div class="logo">M</div>
    <div>
      <h1>Mgla Hub</h1>
      <p>Панель статистики клиента · доступ только у вас</p>
    </div>
  </div>
  {nav}
</div>
<div class="meta">Период: последние {days} дн. (с {since}) · обновлено {generated} UTC</div>
"""]

    devices = []
    for s in sections:
        kind = s.get("kind")
        if kind == "insights":
            items = "".join(f"<li>{html.escape(line)}</li>" for line in s["lines"])
            body.append(f'<div class="insights"><h2>{html.escape(s["title"])}</h2><ul>{items}</ul></div>')
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
            continue
        if s.get("group") == "devices":
            devices.append(s)
            continue
        if kind == "chart":
            inner = render_chart(s["chart"])
        else:
            inner = render_table(s)
        sub = f'<p class="sub">{html.escape(s["subtitle"])}</p>' if s.get("subtitle") else ""
        body.append(
            f'<div class="card"><h2>{html.escape(s["title"])}</h2>{sub}{inner}</div>')

    if devices:
        body.append('<div class="card"><h2>Версии и устройства</h2><div class="grid2">')
        for s in devices:
            sub = f'<p class="sub">{html.escape(s["subtitle"])}</p>' if s.get("subtitle") else ""
            body.append(
                f'<div><h2 style="font-size:15px;margin:14px 0 4px">{html.escape(s["title"])}</h2>'
                f'{sub}{render_table(s)}</div>')
        body.append("</div></div>")

    body.append(
        '<div class="footer">Данные анонимные: без Telegram ID, номеров и текстов чатов. '
        'IP клиента не сохраняется.</div>')

    return (
        "<!doctype html><html lang='ru'><head><meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        "<meta name='robots' content='noindex,nofollow'>"
        f"<title>Mgla Hub — статистика</title><style>{CSS}</style></head>"
        f"<body><div class='wrap'>{''.join(body)}</div></body></html>")


def generate_html(db, days, top, links=True):
    report = Report(db, days, top)
    return render_html(report.build(), days, report.since, links=links)


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
    ap.add_argument("--days", type=int, default=30)
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
        summary = (
            f"Mgla Hub — {args.days} дн.\n"
            f"Сегодня: {kv.get('Сегодня', '—')}, за 7 дн.: {kv.get('За 7 дней', '—')}, "
            f"за 30 дн.: {kv.get('За 30 дней', '—')}\n"
            f"Новых: {kv.get(f'Новых за {args.days} дн.', '—')}, "
            f"всего: {kv.get('Всего установок', '—')}")
        send_telegram(render_html(sections, args.days, report.since), args.days, summary)
        print("Отправлено в Telegram")
        return

    if args.name:
        rows = [(label_counter(n), u, pct(u, report.active), c)
                for n, u, c in report.counters(args.name, limit=10_000)]
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
            f.write(render_html(sections, args.days, report.since))
        print(f"HTML-отчёт сохранён: {os.path.abspath(args.html)}")
    else:
        print(render_text(sections, args.days, report.since))


if __name__ == "__main__":
    main()
