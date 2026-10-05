#!/usr/bin/env python3
"""Text report over the Mgla analytics database.

    python3 stats_report.py              # last 30 days
    python3 stats_report.py --days 7
    python3 stats_report.py --name ai:   # every counter with that prefix
"""

import argparse
import os
import sqlite3
from datetime import datetime, timedelta, timezone

DB_PATH = os.environ.get("MGLA_STATS_DB", "/opt/mgla-ws-relay/stats.db")


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


def table(rows, headers):
    if not rows:
        print("  (нет данных)")
        return
    widths = [max(len(str(h)), *(len(str(r[i])) for r in rows)) for i, h in enumerate(headers)]
    print("  " + "  ".join(str(h).ljust(w) for h, w in zip(headers, widths)))
    for r in rows:
        print("  " + "  ".join(str(v).ljust(w) for v, w in zip(r, widths)))


def pct(part, whole):
    return f"{100.0 * part / whole:.1f}%" if whole else "-"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default=DB_PATH)
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--name", help="show only counters starting with this prefix")
    args = ap.parse_args()

    db = sqlite3.connect(args.db)
    today = datetime.now(timezone.utc).date()
    since = (today - timedelta(days=args.days - 1)).isoformat()
    top = args.top

    def active_between(start, end):
        return db.execute(
            "SELECT COUNT(DISTINCT iid) FROM counters WHERE day BETWEEN ? AND ?",
            (start.isoformat(), end.isoformat())).fetchone()[0]

    active = active_between(today - timedelta(days=args.days - 1), today)

    if args.name:
        section(f"Счётчики «{args.name}*» за {args.days} дн. (активных установок: {active})")
        rows = db.execute(
            """SELECT name, COUNT(DISTINCT iid), SUM(count) FROM counters
               WHERE day >= ? AND name LIKE ? GROUP BY name ORDER BY 2 DESC, 3 DESC""",
            (since, args.name + "%")).fetchall()
        table([(n, u, pct(u, active), c) for n, u, c in rows], ["counter", "users", "% active", "events"])
        return

    section(f"Аудитория (окно {args.days} дн., с {since})")
    total = db.execute("SELECT COUNT(*) FROM installs").fetchone()[0]
    new = db.execute("SELECT COUNT(*) FROM installs WHERE first_seen >= ?", (since,)).fetchone()[0]
    dau = active_between(today, today)
    dau_y = active_between(today - timedelta(days=1), today - timedelta(days=1))
    wau = active_between(today - timedelta(days=6), today)
    mau = active_between(today - timedelta(days=29), today)
    print(f"  Всего установок:       {total}")
    print(f"  Новых за окно:         {new}")
    print(f"  DAU сегодня / вчера:   {dau} / {dau_y}   (сегодня неполный: данные приходят раз в 6 ч)")
    print(f"  WAU / MAU:             {wau} / {mau}")
    print(f"  Вовлечённость DAU/MAU: {pct(dau_y, mau)}")

    section("Удержание: из установок, впервые замеченных 8–14 дней назад, активны за последние 7 дней")
    cohort_start = (today - timedelta(days=14)).isoformat()
    cohort_end = (today - timedelta(days=8)).isoformat()
    cohort = db.execute(
        "SELECT COUNT(*) FROM installs WHERE first_seen BETWEEN ? AND ?", (cohort_start, cohort_end)).fetchone()[0]
    retained = db.execute(
        """SELECT COUNT(DISTINCT c.iid) FROM counters c JOIN installs i ON i.iid = c.iid
           WHERE i.first_seen BETWEEN ? AND ? AND c.day >= ?""",
        (cohort_start, cohort_end, (today - timedelta(days=6)).isoformat())).fetchone()[0]
    print(f"  Когорта: {cohort}, удержано: {retained} ({pct(retained, cohort)})")

    def counter_report(prefix, title, strip=True):
        section(title)
        rows = db.execute(
            """SELECT name, COUNT(DISTINCT iid), SUM(count) FROM counters
               WHERE day >= ? AND name LIKE ? GROUP BY name ORDER BY 2 DESC, 3 DESC LIMIT ?""",
            (since, prefix + "%", top)).fetchall()
        table([(n[len(prefix):] if strip else n, u, pct(u, active), c) for n, u, c in rows],
              ["name", "users", "% active", "events"])

    counter_report("ai:", "ИИ-функции (кто пользуется и сколько) — главный кандидат на платный тариф")
    quota = db.execute(
        "SELECT COUNT(DISTINCT iid), SUM(count) FROM counters WHERE day >= ? AND name = 'ai:quota_exhausted'",
        (since,)).fetchone()
    ai_users = db.execute(
        "SELECT COUNT(DISTINCT iid) FROM counters WHERE day >= ? AND name LIKE 'ai:request:%'",
        (since,)).fetchone()[0]
    print(f"\n  Упёрлись в дневной лимит ИИ: {quota[0] or 0} установок ({pct(quota[0] or 0, ai_users)} от "
          f"пользователей ИИ), {quota[1] or 0} раз")

    counter_report("screen:", "Экраны (что реально открывают)")
    counter_report("set:", "Изменения настроек Mgla (key=value)")
    counter_report("search_open:", "Переходы из поиска по настройкам (guid)")
    counter_report("deeplink:", "Переходы по ссылкам tg://settings/mgla/...")

    section("Включённые функции (снимок настроек у активных за окно установок)")
    rows = db.execute(
        """SELECT s.key, s.value, COUNT(*) FROM snapshot s
           JOIN installs i ON i.iid = s.iid
           WHERE i.last_seen >= ? GROUP BY s.key, s.value""", (since,)).fetchall()
    by_key = {}
    for key, value, n in rows:
        by_key.setdefault(key, {})[value] = n
    booleans = []
    others = []
    for key, values in by_key.items():
        n = sum(values.values())
        if set(values) <= {"true", "false"}:
            booleans.append((key, values.get("true", 0), n, pct(values.get("true", 0), n)))
        else:
            dist = ", ".join(f"{v}:{c}" for v, c in sorted(values.items(), key=lambda x: -x[1])[:6])
            others.append((key, n, dist))
    booleans.sort(key=lambda r: -r[1])
    table(booleans, ["setting", "on", "reported", "% on"])
    print()
    table(sorted(others), ["setting", "reported", "values"])

    section("Поиск по настройкам без результатов — что ищут, но чего нет")
    rows = db.execute(
        "SELECT query, SUM(count) FROM search_miss WHERE day >= ? GROUP BY query ORDER BY 2 DESC LIMIT ?",
        (since, top)).fetchall()
    table(rows, ["query", "times"])

    def install_dist(column, title):
        section(title)
        rows = db.execute(
            f"SELECT COALESCE({column}, '?'), COUNT(*) FROM installs WHERE last_seen >= ? "
            f"GROUP BY 1 ORDER BY 2 DESC LIMIT ?", (since, top)).fetchall()
        table([(v, n, pct(n, sum(r[1] for r in rows))) for v, n in rows], [column, "installs", "%"])

    install_dist("ver", "Версии Mgla")
    install_dist("man", "Производители")
    install_dist("model", "Модели")
    install_dist("sdk", "Android SDK")
    install_dist("lang", "Язык системы")


if __name__ == "__main__":
    main()
