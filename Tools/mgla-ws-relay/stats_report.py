#!/usr/bin/env python3
"""Report over the Mgla analytics database.

    python3 stats_report.py                          # text, last 30 days
    python3 stats_report.py --days 7
    python3 stats_report.py --name ai:               # every counter with that prefix
    python3 stats_report.py --html report.html       # self-contained HTML page
    python3 stats_report.py --telegram               # send the HTML page via the Bot API
"""

import argparse
import html
import os
import sqlite3
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

DB_PATH = os.environ.get("MGLA_STATS_DB", "/opt/mgla-ws-relay/stats.db")


def pct(part, whole):
    return f"{100.0 * part / whole:.1f}%" if whole else "-"


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
        return self.one("SELECT COUNT(DISTINCT iid) FROM counters WHERE day BETWEEN ? AND ?",
                        (start.isoformat(), end.isoformat()))

    def counters(self, prefix, limit=None):
        rows = self.q(
            """SELECT name, COUNT(DISTINCT iid), SUM(count) FROM counters
               WHERE day >= ? AND name LIKE ? GROUP BY name ORDER BY 2 DESC, 3 DESC LIMIT ?""",
            (self.since, prefix + "%", limit or self.top))
        return [(n[len(prefix):], u, c) for n, u, c in rows]

    def build(self):
        """Returns a list of sections; each is a dict understood by both renderers."""
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
        cohort = self.one("SELECT COUNT(*) FROM installs WHERE first_seen BETWEEN ? AND ?",
                          (cohort_start, cohort_end))
        retained = self.one(
            """SELECT COUNT(DISTINCT c.iid) FROM counters c JOIN installs i ON i.iid = c.iid
               WHERE i.first_seen BETWEEN ? AND ? AND c.day >= ?""",
            (cohort_start, cohort_end, (today - timedelta(days=6)).isoformat()))

        sections.append({"title": "Аудитория", "kv": [
            ("Всего установок", total),
            (f"Новых за {self.days} дн.", new),
            ("Активны сегодня", dau),
            ("Активны вчера", dau_y),
            ("За неделю (WAU)", wau),
            ("За месяц (MAU)", mau),
            ("Вовлечённость DAU/MAU", pct(dau_y, mau)),
            ("Удержание 2-й недели", f"{pct(retained, cohort)} ({retained}/{cohort})"),
        ], "note": "Сегодняшние данные неполные: клиенты отправляют статистику раз в 6 часов. "
                   "Удержание — доля установок, впервые замеченных 8–14 дней назад, активных за последние 7 дней."})

        active_by_day = dict(self.q(
            "SELECT day, COUNT(DISTINCT iid) FROM counters WHERE day >= ? GROUP BY day", (self.since,)))
        new_by_day = dict(self.q(
            "SELECT first_seen, COUNT(*) FROM installs WHERE first_seen >= ? GROUP BY first_seen", (self.since,)))
        chart = []
        for i in range(self.days):
            d = (today - timedelta(days=self.days - 1 - i)).isoformat()
            chart.append((d, active_by_day.get(d, 0), new_by_day.get(d, 0)))
        sections.append({"title": "Активность по дням", "chart": chart})

        ai_users = self.one(
            "SELECT COUNT(DISTINCT iid) FROM counters WHERE day >= ? AND name LIKE 'ai:request:%'", (self.since,))
        quota_users = self.one(
            "SELECT COUNT(DISTINCT iid) FROM counters WHERE day >= ? AND name = 'ai:quota_exhausted'", (self.since,))
        quota_events = self.one(
            "SELECT SUM(count) FROM counters WHERE day >= ? AND name = 'ai:quota_exhausted'", (self.since,))
        sections.append(self.counter_section(
            "ai:", "ИИ-функции",
            f"Пользуются ИИ: {ai_users} ({pct(ai_users, self.active)} активных). "
            f"Упёрлись в дневной лимит: {quota_users} ({pct(quota_users, ai_users)} пользователей ИИ), "
            f"{quota_events} раз — главный сигнал для платного тарифа."))
        sections.append(self.counter_section("screen:", "Экраны"))
        sections.append(self.counter_section("set:", "Изменения настроек Mgla"))

        rows = self.q(
            """SELECT s.key, s.value, COUNT(*) FROM snapshot s JOIN installs i ON i.iid = s.iid
               WHERE i.last_seen >= ? GROUP BY s.key, s.value""", (self.since,))
        by_key = {}
        for key, value, n in rows:
            by_key.setdefault(key, {})[value] = n
        booleans, others = [], []
        for key, values in by_key.items():
            n = sum(values.values())
            if set(values) <= {"true", "false"}:
                on = values.get("true", 0)
                booleans.append((key, on, n, pct(on, n)))
            else:
                dist = ", ".join(f"{v}: {c}" for v, c in sorted(values.items(), key=lambda x: -x[1])[:6])
                others.append((key, n, dist))
        booleans.sort(key=lambda r: (-r[1], r[0]))
        sections.append({"title": "Включённые функции", "headers": ["Настройка", "Включено", "Всего", "%"],
                         "rows": booleans, "bar": 1, "bar_max": max([r[2] for r in booleans] or [1]),
                         "note": "По последнему снимку настроек установок, активных за период."})
        sections.append({"title": "Прочие настройки", "headers": ["Настройка", "Установок", "Значения"],
                         "rows": sorted(others)})

        rows = self.q("SELECT query, SUM(count) FROM search_miss WHERE day >= ? GROUP BY query "
                      "ORDER BY 2 DESC LIMIT ?", (self.since, self.top))
        sections.append({"title": "Поиск по настройкам без результатов", "headers": ["Запрос", "Раз"],
                         "rows": rows, "bar": 1, "note": "Что ищут, но чего нет — спрос на новые функции."})

        sections.append(self.counter_section("search_open:", "Переходы из поиска по настройкам (guid)"))
        sections.append(self.counter_section("deeplink:", "Переходы по ссылкам tg://settings/mgla/…"))

        for column, title in (("ver", "Версии Mgla"), ("man", "Производители"), ("model", "Модели"),
                              ("sdk", "Android SDK"), ("lang", "Язык системы")):
            rows = self.q(f"SELECT COALESCE({column}, '?'), COUNT(*) FROM installs WHERE last_seen >= ? "
                          f"GROUP BY 1 ORDER BY 2 DESC LIMIT ?", (self.since, self.top))
            s = sum(r[1] for r in rows)
            sections.append({"title": title, "headers": ["Значение", "Установок", "%"],
                             "rows": [(v, n, pct(n, s)) for v, n in rows], "bar": 1, "group": "devices"})
        return sections

    def counter_section(self, prefix, title, note=None):
        rows = [(n, u, pct(u, self.active), c) for n, u, c in self.counters(prefix)]
        return {"title": title, "headers": ["Название", "Пользователей", "% активных", "Событий"],
                "rows": rows, "bar": 1, "bar_max": self.active or 1, "note": note}


def render_text(sections, days, since):
    out = [f"Mgla — статистика за {days} дн. (с {since})"]
    for s in sections:
        out += ["", "=" * 72, s["title"], "=" * 72]
        if "kv" in s:
            w = max(len(k) for k, _ in s["kv"])
            out += [f"  {k.ljust(w)}  {v}" for k, v in s["kv"]]
        elif "chart" in s:
            out += [f"  {d}  активны: {a:<6} новых: {n}" for d, a, n in s["chart"]]
        else:
            rows, headers = s["rows"], s["headers"]
            if not rows:
                out.append("  (нет данных)")
            else:
                widths = [max(len(str(h)), *(len(str(r[i])) for r in rows)) for i, h in enumerate(headers)]
                out.append("  " + "  ".join(str(h).ljust(w) for h, w in zip(headers, widths)))
                out += ["  " + "  ".join(str(v).ljust(w) for v, w in zip(r, widths)) for r in rows]
        if s.get("note"):
            out.append(f"  * {s['note']}")
    return "\n".join(out)


CSS = """
:root { --bg:#0f1115; --card:#181b22; --line:#262a33; --text:#e6e8ec; --muted:#8a93a3;
        --accent:#4f8cff; --accent2:#3ccf91; }
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--text);
       font:14px/1.45 -apple-system, "Segoe UI", Roboto, Arial, sans-serif; }
.wrap { max-width:1180px; margin:0 auto; padding:28px 20px 60px; }
h1 { font-size:24px; margin:0 0 4px; }
.sub { color:var(--muted); margin-bottom:24px; }
.kpis { display:grid; grid-template-columns:repeat(auto-fill,minmax(170px,1fr)); gap:12px; }
.kpi { background:var(--card); border:1px solid var(--line); border-radius:14px; padding:14px 16px; }
.kpi .v { font-size:24px; font-weight:650; }
.kpi .k { color:var(--muted); font-size:12.5px; margin-top:2px; }
.card { background:var(--card); border:1px solid var(--line); border-radius:14px;
        padding:18px 18px 12px; margin-top:18px; }
.card h2 { font-size:16px; margin:0 0 12px; }
.note { color:var(--muted); font-size:12.5px; margin:10px 0 4px; }
.grid2 { display:grid; grid-template-columns:repeat(auto-fit,minmax(360px,1fr)); gap:0 18px; }
table { width:100%; border-collapse:collapse; }
th { text-align:left; color:var(--muted); font-weight:500; font-size:12.5px;
     border-bottom:1px solid var(--line); padding:6px 8px; }
td { padding:6px 8px; border-bottom:1px solid var(--line); vertical-align:middle; }
tr:last-child td { border-bottom:0; }
td.num { white-space:nowrap; font-variant-numeric:tabular-nums; }
td.name { word-break:break-word; }
td.bar { width:42%; }
.bar div { display:flex; align-items:center; gap:10px; }
.bar b { font-weight:500; min-width:34px; text-align:right; }
.bar span { flex:1; height:8px; border-radius:4px; background:#232733; overflow:hidden; }
.bar span i { display:block; height:100%; border-radius:4px;
              background:linear-gradient(90deg,var(--accent),#7aa8ff); }
.empty { color:var(--muted); padding:8px 0; }
.legend { color:var(--muted); font-size:12.5px; margin-bottom:6px; }
.legend i { display:inline-block; width:10px; height:10px; border-radius:2px; margin:0 5px 0 12px; }
svg text { fill:var(--muted); font-size:10px; }
.sub a { color:var(--accent); text-decoration:none; margin-left:6px; padding:2px 8px;
         border:1px solid var(--line); border-radius:8px; }
.sub a.on { background:var(--accent); color:#fff; border-color:var(--accent); }
"""


def render_chart(points):
    w, h, pad_l, pad_b, pad_t = 1100, 220, 34, 26, 10
    top = max([max(a, n) for _, a, n in points] or [1]) or 1
    n = len(points)
    step = (w - pad_l) / max(n, 1)
    bar_w = max(step * 0.62, 1)
    plot_h = h - pad_b - pad_t
    parts = [f'<svg viewBox="0 0 {w} {h}" width="100%" preserveAspectRatio="none">']
    for i in range(5):
        y = pad_t + plot_h * i / 4
        val = round(top * (4 - i) / 4)
        parts.append(f'<line x1="{pad_l}" x2="{w}" y1="{y:.1f}" y2="{y:.1f}" stroke="#262a33"/>')
        parts.append(f'<text x="{pad_l - 6}" y="{y + 3:.1f}" text-anchor="end">{val}</text>')
    line = []
    label_every = max(1, n // 10)
    for i, (d, active, new) in enumerate(points):
        x = pad_l + i * step + (step - bar_w) / 2
        bh = plot_h * active / top
        parts.append(f'<rect x="{x:.1f}" y="{pad_t + plot_h - bh:.1f}" width="{bar_w:.1f}" height="{bh:.1f}" '
                     f'rx="2" fill="#4f8cff" opacity=".85"><title>{d}: активны {active}, новых {new}</title></rect>')
        line.append(f"{x + bar_w / 2:.1f},{pad_t + plot_h - plot_h * new / top:.1f}")
        if i % label_every == 0 or i == n - 1:
            parts.append(f'<text x="{x + bar_w / 2:.1f}" y="{h - 8}" text-anchor="middle">{d[5:]}</text>')
    parts.append(f'<polyline points="{" ".join(line)}" fill="none" stroke="#3ccf91" stroke-width="2"/>')
    parts.append("</svg>")
    return ('<div class="legend"><i style="background:#4f8cff"></i>Активные установки'
            '<i style="background:#3ccf91"></i>Новые установки</div>' + "".join(parts))


def render_table(s):
    rows, headers, bar = s["rows"], s["headers"], s.get("bar")
    if not rows:
        return '<div class="empty">Нет данных</div>'
    bar_max = s.get("bar_max") or max([r[bar] for r in rows] or [1]) if bar is not None else 1
    out = ["<table><thead><tr>"]
    out += [f"<th>{html.escape(str(hd))}</th>" for hd in headers]
    out.append("</tr></thead><tbody>")
    for r in rows:
        out.append("<tr>")
        for i, v in enumerate(r):
            text = html.escape(str(v))
            if i == 0:
                out.append(f'<td class="name">{text}</td>')
            elif i == bar and isinstance(v, (int, float)):
                width = min(100.0, 100.0 * v / bar_max) if bar_max else 0
                out.append(f'<td class="num bar"><div><b>{text}</b>'
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
        nav = " · " + " ".join(
            f"<a href='?days={d}' class='{'on' if d == days else ''}'>{d} дн.</a>" for d in (1, 7, 30, 90))
    body = [f"<h1>Mgla — статистика</h1><div class='sub'>Последние {days} дн. (с {since}) · "
            f"сформировано {generated}{nav}</div>"]
    devices = []
    for s in sections:
        if "kv" in s:
            body.append('<div class="kpis">')
            body += [f'<div class="kpi"><div class="v">{html.escape(str(v))}</div>'
                     f'<div class="k">{html.escape(k)}</div></div>' for k, v in s["kv"]]
            body.append("</div>")
            if s.get("note"):
                body.append(f'<div class="note">{html.escape(s["note"])}</div>')
            continue
        if s.get("group") == "devices":
            devices.append(s)
            continue
        inner = render_chart(s["chart"]) if "chart" in s else render_table(s)
        note = f'<div class="note">{html.escape(s["note"])}</div>' if s.get("note") else ""
        body.append(f'<div class="card"><h2>{html.escape(s["title"])}</h2>{note}{inner}</div>')
    if devices:
        body.append('<div class="card"><h2>Версии и устройства</h2><div class="grid2">')
        body += [f'<div><h2 style="font-size:14px;margin:14px 0 6px">{html.escape(s["title"])}</h2>'
                 f'{render_table(s)}</div>' for s in devices]
        body.append("</div></div>")
    return ("<!doctype html><html lang='ru'><head><meta charset='utf-8'>"
            "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            f"<title>Mgla — статистика</title><style>{CSS}</style></head>"
            f"<body><div class='wrap'>{''.join(body)}</div></body></html>")


def generate_html(db, days, top, links=True):
    report = Report(db, days, top)
    return render_html(report.build(), days, report.since, links=links)


def send_telegram(html_text, days, summary):
    """Sends the report as a file via the Bot API (MGLA_STATS_BOT_TOKEN, MGLA_STATS_CHAT_ID)."""
    token = os.environ.get("MGLA_STATS_BOT_TOKEN", "")
    chat_id = os.environ.get("MGLA_STATS_CHAT_ID", "")
    if not token or not chat_id:
        raise SystemExit("MGLA_STATS_BOT_TOKEN / MGLA_STATS_CHAT_ID are not set")
    boundary = uuid.uuid4().hex
    filename = f"mgla-stats-{datetime.now().strftime('%Y-%m-%d')}.html"
    parts = []
    for name, value in (("chat_id", chat_id), ("caption", summary)):
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="document"; filename="{filename}"\r\n'
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
    ap.add_argument("--name", help="show only counters starting with this prefix (text)")
    ap.add_argument("--html", metavar="FILE", help="write an HTML report to FILE")
    ap.add_argument("--telegram", action="store_true", help="send the HTML report to Telegram")
    args = ap.parse_args()

    report = Report(sqlite3.connect(args.db), args.days, args.top)

    if args.telegram:
        sections = report.build()
        kv = dict(sections[0]["kv"])
        summary = (f"Mgla — статистика за {args.days} дн.\n"
                   f"Активны вчера: {kv['Активны вчера']}, за неделю: {kv['За неделю (WAU)']}, "
                   f"за месяц: {kv['За месяц (MAU)']}\nНовых: {kv[f'Новых за {args.days} дн.']}, "
                   f"всего установок: {kv['Всего установок']}")
        send_telegram(render_html(sections, args.days, report.since), args.days, summary)
        print("Отправлено в Telegram")
        return

    if args.name:
        rows = [(n, u, pct(u, report.active), c) for n, u, c in report.counters(args.name, limit=10_000)]
        section = {"title": f"Счётчики «{args.name}*» (активных установок: {report.active})",
                   "headers": ["counter", "users", "% active", "events"], "rows": rows}
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
