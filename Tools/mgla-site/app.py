#!/usr/bin/env python3
"""Mgla public site + admin CMS for Инструкция / Поддержать.

Runs on 127.0.0.1 only; nginx terminates TLS for mgla.home.mooo.com.
Does not touch mglahub / apiws / tproxy paths.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import sqlite3
import time
from datetime import timedelta
from functools import wraps
from pathlib import Path

from flask import (
    Flask,
    abort,
    flash,
    g,
    jsonify,
    redirect,
    render_template,
    request,
    send_from_directory,
    session,
    url_for,
)
from markdown import markdown
from werkzeug.utils import secure_filename

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("MGLA_SITE_DATA", str(BASE_DIR)))
DB_PATH = DATA_DIR / "site.db"
UPLOAD_DIR = DATA_DIR / "uploads"
SECRET_FILE = DATA_DIR / "secret.key"

PAGE_SLUGS = {
    "guide": "Инструкция",
    "support": "Поддержать",
}

FEATURE_PREVIEWS = [
    {
        "title": "Подключение без VPN",
        "text": "Авто-обход блокировок: Web Proxy, WebSocket-релей и ByeDPI — один переключатель.",
        "icon": "shield",
    },
    {
        "title": "Чаты и приватность",
        "text": "Скрытые чаты, история правок, удалённые сообщения и тонкая настройка меню.",
        "icon": "chat",
    },
    {
        "title": "Искусственный интеллект",
        "text": "Расшифровка голосовых, локальные модели и облачные ответы прямо в клиенте.",
        "icon": "ai",
    },
    {
        "title": "Внешний вид",
        "text": "Темы, анимации, чистый хедер и камера — под себя, без чужого брендинга.",
        "icon": "palette",
    },
    {
        "title": "Уведомления",
        "text": "Гибкие алерты и popup-уведомления, чтобы ничего важного не пропустить.",
        "icon": "bell",
    },
    {
        "title": "Обновления",
        "text": "Проверка и установка APK с хаба — без магазинов и лишних посредников.",
        "icon": "update",
    },
]

# Mirrors MglaPremiumActivity.fillFeaturesForPlan — keep in sync with the app.
_PREMIUM_BASE_FEATURES = [
    {
        "title": "Новые анимации",
        "text": "Очень много крутых новых анимаций интерфейса",
    },
    {
        "title": "Планировщик задач",
        "text": "Удобный список задач прямо в приложении",
    },
    {
        "title": "Локальные модели",
        "text": "Используйте локальные ИИ-модели на устройстве",
    },
    {
        "title": "Живая память",
        "text": "Персональная память ассистента, которая растёт вместе с вами",
    },
    {
        "title": "Темы для новых иконок",
        "text": "Специально созданные темы под все иконки нового типа",
    },
    {
        "title": "Автоперевод",
        "text": "Автоматический перевод сообщений и чатов",
    },
]

_PREMIUM_PLUS_EXTRA = [
    {
        "title": "Облачные ИИ-модели",
        "text": "Более умные облачные модели в дополнение к локальным",
        "tier": "plus",
    },
]

_PREMIUM_ULTRA_EXTRA = [
    {
        "title": "Продвинутые ИИ-модели",
        "text": "Ещё более умные и дорогие облачные модели",
        "tier": "ultra",
    },
    {
        "title": "Приоритет и автообход",
        "text": "Приоритетная скорость и подключение автообхода",
        "tier": "ultra",
    },
]

PREMIUM_PLANS = [
    {
        "id": "premium",
        "name": "Premium",
        "tagline": "Базовый премиум",
        "price": 200,
        "accent": "blue",
        "features": list(_PREMIUM_BASE_FEATURES),
    },
    {
        "id": "premium_plus",
        "name": "Premium+",
        "tagline": "Облачный ИИ",
        "price": 400,
        "accent": "red",
        "features": list(_PREMIUM_BASE_FEATURES) + list(_PREMIUM_PLUS_EXTRA),
    },
    {
        "id": "premium_ultra",
        "name": "Premium Ultra",
        "tagline": "Максимум",
        "price": 1500,
        "accent": "gold",
        "features": list(_PREMIUM_BASE_FEATURES)
        + list(_PREMIUM_PLUS_EXTRA)
        + list(_PREMIUM_ULTRA_EXTRA),
    },
]


def _load_secret() -> str:
    if SECRET_FILE.exists():
        return SECRET_FILE.read_text(encoding="utf-8").strip()
    secret = secrets.token_hex(32)
    SECRET_FILE.write_text(secret, encoding="utf-8")
    os.chmod(SECRET_FILE, 0o600)
    return secret


def create_app() -> Flask:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

    app = Flask(
        __name__,
        static_folder=str(BASE_DIR / "static"),
        template_folder=str(BASE_DIR / "templates"),
    )
    app.config["SECRET_KEY"] = os.environ.get("MGLA_SITE_SECRET") or _load_secret()
    app.config["MAX_CONTENT_LENGTH"] = 12 * 1024 * 1024
    app.config["ADMIN_PASSWORD"] = os.environ.get("MGLA_SITE_ADMIN_PASSWORD", "")
    app.config["ADMIN_USER"] = os.environ.get("MGLA_SITE_ADMIN_USER", "admin")
    app.config["PERMANENT_SESSION_LIFETIME"] = timedelta(days=14)

    def get_db() -> sqlite3.Connection:
        if "db" not in g:
            g.db = sqlite3.connect(DB_PATH)
            g.db.row_factory = sqlite3.Row
            g.db.execute("PRAGMA foreign_keys = ON")
        return g.db

    @app.teardown_appcontext
    def close_db(_exc=None):
        db = g.pop("db", None)
        if db is not None:
            db.close()

    def init_db():
        db = get_db()
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS pages (
                slug TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                intro TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                page_slug TEXT NOT NULL REFERENCES pages(slug) ON DELETE CASCADE,
                title TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                published INTEGER NOT NULL DEFAULT 1
            );
            CREATE TABLE IF NOT EXISTS chapters (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                section_id INTEGER NOT NULL REFERENCES sections(id) ON DELETE CASCADE,
                title TEXT NOT NULL,
                body TEXT NOT NULL DEFAULT '',
                sort_order INTEGER NOT NULL DEFAULT 0,
                published INTEGER NOT NULL DEFAULT 1
            );
            CREATE TABLE IF NOT EXISTS home_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                hero_title TEXT NOT NULL,
                hero_subtitle TEXT NOT NULL,
                contact_url TEXT NOT NULL,
                contact_label TEXT NOT NULL
            );
            """
        )
        now = int(time.time())
        for slug, title in PAGE_SLUGS.items():
            db.execute(
                "INSERT OR IGNORE INTO pages(slug, title, intro, updated_at) VALUES(?,?,?,?)",
                (slug, title, "", now),
            )
        db.execute(
            """
            INSERT OR IGNORE INTO home_settings(id, hero_title, hero_subtitle, contact_url, contact_label)
            VALUES(1, ?, ?, ?, ?)
            """,
            (
                "Mgla",
                "Клиент Telegram с обходом блокировок, ИИ и гибкими настройками.",
                "https://t.me/mgla_admin",
                "Помощь",
            ),
        )
        # Seed guide if empty (full examples also via seed_guide.py on deploy)
        guide_sections = db.execute(
            "SELECT COUNT(*) AS c FROM sections WHERE page_slug='guide'"
        ).fetchone()["c"]
        if guide_sections == 0:
            db.execute(
                "UPDATE pages SET intro=? WHERE slug='guide'",
                (
                    "Краткие гайды по функциям клиента Mgla. "
                    "Слева — поиск по путям настроек.",
                ),
            )
            cur = db.execute(
                "INSERT INTO sections(page_slug, title, sort_order, published) VALUES(?,?,?,1)",
                ("guide", "Подключение", 0),
            )
            db.execute(
                "INSERT INTO chapters(section_id, title, body, sort_order, published) VALUES(?,?,?,?,1)",
                (
                    cur.lastrowid,
                    "Авто-обход без VPN",
                    "**Путь:** `Настройки Mgla → Подключение без VPN → Авто-обход`\n\n"
                    "Включите авто-обход без системного VPN. Основной маршрут — "
                    "WebSocket-релей, резерв — Web Proxy.",
                    0,
                ),
            )
        support_sections = db.execute(
            "SELECT COUNT(*) AS c FROM sections WHERE page_slug='support'"
        ).fetchone()["c"]
        if support_sections == 0:
            cur = db.execute(
                "INSERT INTO sections(page_slug, title, sort_order, published) VALUES(?,?,?,1)",
                ("support", "Как поддержать", 0),
            )
            db.execute(
                "INSERT INTO chapters(section_id, title, body, sort_order, published) VALUES(?,?,?,?,1)",
                (
                    cur.lastrowid,
                    "Связь и поддержка проекта",
                    "Напишите в Telegram: [@mgla_admin](https://t.me/mgla_admin).\n\n"
                    "Здесь позже появятся реквизиты и другие способы поддержки — "
                    "их можно добавить в конструкторе.",
                    0,
                ),
            )
        db.commit()

    with app.app_context():
        init_db()

    def admin_required(fn):
        @wraps(fn)
        def wrapper(*args, **kwargs):
            if not session.get("admin"):
                return redirect(url_for("admin_login", next=request.path))
            return fn(*args, **kwargs)

        return wrapper

    def check_password(password: str) -> bool:
        expected = app.config["ADMIN_PASSWORD"]
        if not expected:
            return False
        # length-safe compare via fixed digests
        left = hashlib.sha256(password.encode("utf-8")).digest()
        right = hashlib.sha256(expected.encode("utf-8")).digest()
        return hmac.compare_digest(left, right)

    def render_md(text: str) -> str:
        return markdown(
            text or "",
            extensions=["extra", "sane_lists", "nl2br"],
        )

    def fetch_page_tree(slug: str, only_published: bool = True):
        db = get_db()
        page = db.execute("SELECT * FROM pages WHERE slug=?", (slug,)).fetchone()
        if not page:
            return None, []
        q = "SELECT * FROM sections WHERE page_slug=? "
        if only_published:
            q += "AND published=1 "
        q += "ORDER BY sort_order ASC, id ASC"
        sections = db.execute(q, (slug,)).fetchall()
        tree = []
        for sec in sections:
            cq = "SELECT * FROM chapters WHERE section_id=? "
            if only_published:
                cq += "AND published=1 "
            cq += "ORDER BY sort_order ASC, id ASC"
            chapters = db.execute(cq, (sec["id"],)).fetchall()
            tree.append({"section": sec, "chapters": chapters})
        return page, tree

    # ---------- public ----------

    @app.route("/")
    def home():
        db = get_db()
        home_row = db.execute("SELECT * FROM home_settings WHERE id=1").fetchone()
        return render_template(
            "home.html",
            home=home_row,
            features=FEATURE_PREVIEWS,
            premium_plans=PREMIUM_PLANS,
            active="home",
        )

    @app.route("/guide/")
    @app.route("/guide")
    def guide():
        page, tree = fetch_page_tree("guide")
        if not page:
            abort(404)
        return render_template(
            "doc.html",
            page=page,
            tree=tree,
            render_md=render_md,
            active="guide",
        )

    @app.route("/support/")
    @app.route("/support")
    def support():
        page, tree = fetch_page_tree("support")
        if not page:
            abort(404)
        return render_template(
            "doc.html",
            page=page,
            tree=tree,
            render_md=render_md,
            active="support",
        )

    @app.route("/uploads/<path:filename>")
    def uploaded_file(filename):
        return send_from_directory(UPLOAD_DIR, filename)

    # ---------- admin auth ----------

    @app.route("/admin/login", methods=["GET", "POST"])
    def admin_login():
        if session.get("admin"):
            return redirect(url_for("admin_dashboard"))
        error = None
        if request.method == "POST":
            user = (request.form.get("username") or "").strip()
            password = request.form.get("password") or ""
            if user == app.config["ADMIN_USER"] and check_password(password):
                session["admin"] = True
                session.permanent = True
                nxt = request.args.get("next") or url_for("admin_dashboard")
                if not nxt.startswith("/admin"):
                    nxt = url_for("admin_dashboard")
                return redirect(nxt)
            error = "Неверный логин или пароль"
            time.sleep(0.4)
        return render_template("admin/login.html", error=error)

    @app.route("/admin/logout")
    def admin_logout():
        session.clear()
        return redirect(url_for("admin_login"))

    @app.route("/admin/")
    @app.route("/admin")
    @admin_required
    def admin_dashboard():
        db = get_db()
        pages = db.execute("SELECT * FROM pages ORDER BY slug").fetchall()
        counts = {}
        for p in pages:
            sec = db.execute(
                "SELECT COUNT(*) AS c FROM sections WHERE page_slug=?", (p["slug"],)
            ).fetchone()["c"]
            ch = db.execute(
                """
                SELECT COUNT(*) AS c FROM chapters c
                JOIN sections s ON s.id = c.section_id
                WHERE s.page_slug=?
                """,
                (p["slug"],),
            ).fetchone()["c"]
            counts[p["slug"]] = {"sections": sec, "chapters": ch}
        home_row = db.execute("SELECT * FROM home_settings WHERE id=1").fetchone()
        return render_template(
            "admin/dashboard.html",
            pages=pages,
            counts=counts,
            home=home_row,
        )

    @app.route("/admin/home", methods=["GET", "POST"])
    @admin_required
    def admin_home():
        db = get_db()
        if request.method == "POST":
            db.execute(
                """
                UPDATE home_settings SET
                    hero_title=?, hero_subtitle=?, contact_url=?, contact_label=?
                WHERE id=1
                """,
                (
                    (request.form.get("hero_title") or "Mgla").strip(),
                    (request.form.get("hero_subtitle") or "").strip(),
                    (request.form.get("contact_url") or "https://t.me/mgla_admin").strip(),
                    (request.form.get("contact_label") or "Помощь").strip(),
                ),
            )
            db.commit()
            flash("Главная сохранена", "ok")
            return redirect(url_for("admin_home"))
        home_row = db.execute("SELECT * FROM home_settings WHERE id=1").fetchone()
        return render_template("admin/home_edit.html", home=home_row)

    @app.route("/admin/page/<slug>")
    @admin_required
    def admin_page(slug):
        if slug not in PAGE_SLUGS:
            abort(404)
        page, tree = fetch_page_tree(slug, only_published=False)
        return render_template("admin/page_edit.html", page=page, tree=tree, slug=slug)

    @app.route("/admin/page/<slug>/intro", methods=["POST"])
    @admin_required
    def admin_page_intro(slug):
        if slug not in PAGE_SLUGS:
            abort(404)
        db = get_db()
        db.execute(
            "UPDATE pages SET title=?, intro=?, updated_at=? WHERE slug=?",
            (
                (request.form.get("title") or PAGE_SLUGS[slug]).strip(),
                request.form.get("intro") or "",
                int(time.time()),
                slug,
            ),
        )
        db.commit()
        flash("Страница обновлена", "ok")
        return redirect(url_for("admin_page", slug=slug))

    @app.route("/admin/page/<slug>/section", methods=["POST"])
    @admin_required
    def admin_add_section(slug):
        if slug not in PAGE_SLUGS:
            abort(404)
        title = (request.form.get("title") or "").strip() or "Новый раздел"
        db = get_db()
        max_ord = db.execute(
            "SELECT COALESCE(MAX(sort_order), -1) AS m FROM sections WHERE page_slug=?",
            (slug,),
        ).fetchone()["m"]
        db.execute(
            "INSERT INTO sections(page_slug, title, sort_order, published) VALUES(?,?,?,1)",
            (slug, title, max_ord + 1),
        )
        db.execute(
            "UPDATE pages SET updated_at=? WHERE slug=?", (int(time.time()), slug)
        )
        db.commit()
        flash("Раздел добавлен", "ok")
        return redirect(url_for("admin_page", slug=slug))

    @app.route("/admin/section/<int:section_id>", methods=["POST"])
    @admin_required
    def admin_update_section(section_id):
        db = get_db()
        sec = db.execute("SELECT * FROM sections WHERE id=?", (section_id,)).fetchone()
        if not sec:
            abort(404)
        action = request.form.get("action") or "save"
        if action == "delete":
            db.execute("DELETE FROM sections WHERE id=?", (section_id,))
            flash("Раздел удалён", "ok")
        elif action == "toggle":
            db.execute(
                "UPDATE sections SET published=? WHERE id=?",
                (0 if sec["published"] else 1, section_id),
            )
            flash("Видимость раздела изменена", "ok")
        else:
            title = (request.form.get("title") or "").strip() or sec["title"]
            db.execute("UPDATE sections SET title=? WHERE id=?", (title, section_id))
            flash("Раздел сохранён", "ok")
        db.execute(
            "UPDATE pages SET updated_at=? WHERE slug=?",
            (int(time.time()), sec["page_slug"]),
        )
        db.commit()
        return redirect(url_for("admin_page", slug=sec["page_slug"]))

    @app.route("/admin/section/<int:section_id>/chapter", methods=["POST"])
    @admin_required
    def admin_add_chapter(section_id):
        db = get_db()
        sec = db.execute("SELECT * FROM sections WHERE id=?", (section_id,)).fetchone()
        if not sec:
            abort(404)
        title = (request.form.get("title") or "").strip() or "Новая глава"
        max_ord = db.execute(
            "SELECT COALESCE(MAX(sort_order), -1) AS m FROM chapters WHERE section_id=?",
            (section_id,),
        ).fetchone()["m"]
        db.execute(
            "INSERT INTO chapters(section_id, title, body, sort_order, published) VALUES(?,?,?,?,1)",
            (section_id, title, "", max_ord + 1),
        )
        db.execute(
            "UPDATE pages SET updated_at=? WHERE slug=?",
            (int(time.time()), sec["page_slug"]),
        )
        db.commit()
        flash("Глава добавлена", "ok")
        return redirect(url_for("admin_page", slug=sec["page_slug"]))

    @app.route("/admin/chapter/<int:chapter_id>", methods=["GET", "POST"])
    @admin_required
    def admin_chapter(chapter_id):
        db = get_db()
        ch = db.execute(
            """
            SELECT c.*, s.page_slug, s.title AS section_title
            FROM chapters c JOIN sections s ON s.id = c.section_id
            WHERE c.id=?
            """,
            (chapter_id,),
        ).fetchone()
        if not ch:
            abort(404)
        if request.method == "POST":
            action = request.form.get("action") or "save"
            if action == "delete":
                db.execute("DELETE FROM chapters WHERE id=?", (chapter_id,))
                db.execute(
                    "UPDATE pages SET updated_at=? WHERE slug=?",
                    (int(time.time()), ch["page_slug"]),
                )
                db.commit()
                flash("Глава удалена", "ok")
                return redirect(url_for("admin_page", slug=ch["page_slug"]))
            if action == "toggle":
                db.execute(
                    "UPDATE chapters SET published=? WHERE id=?",
                    (0 if ch["published"] else 1, chapter_id),
                )
            else:
                title = (request.form.get("title") or "").strip() or ch["title"]
                body = request.form.get("body") or ""
                db.execute(
                    "UPDATE chapters SET title=?, body=? WHERE id=?",
                    (title, body, chapter_id),
                )
            db.execute(
                "UPDATE pages SET updated_at=? WHERE slug=?",
                (int(time.time()), ch["page_slug"]),
            )
            db.commit()
            flash("Глава сохранена", "ok")
            if action == "toggle":
                return redirect(url_for("admin_page", slug=ch["page_slug"]))
            return redirect(url_for("admin_chapter", chapter_id=chapter_id))
        return render_template(
            "admin/chapter_edit.html",
            chapter=ch,
            preview_html=render_md(ch["body"]),
        )

    @app.route("/admin/reorder", methods=["POST"])
    @admin_required
    def admin_reorder():
        data = request.get_json(silent=True) or {}
        kind = data.get("kind")
        order = data.get("order") or []
        if kind not in ("sections", "chapters") or not isinstance(order, list):
            return jsonify({"ok": False, "error": "bad request"}), 400
        db = get_db()
        table = "sections" if kind == "sections" else "chapters"
        for idx, item_id in enumerate(order):
            try:
                iid = int(item_id)
            except (TypeError, ValueError):
                continue
            db.execute(f"UPDATE {table} SET sort_order=? WHERE id=?", (idx, iid))
        db.commit()
        return jsonify({"ok": True})

    @app.route("/admin/upload", methods=["POST"])
    @admin_required
    def admin_upload():
        f = request.files.get("file")
        if not f or not f.filename:
            return jsonify({"ok": False, "error": "no file"}), 400
        name = secure_filename(f.filename)
        ext = Path(name).suffix.lower()
        if ext not in {".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg"}:
            return jsonify({"ok": False, "error": "bad type"}), 400
        stored = f"{secrets.token_hex(8)}{ext}"
        f.save(UPLOAD_DIR / stored)
        url = url_for("uploaded_file", filename=stored)
        return jsonify({"ok": True, "url": url})

    @app.route("/admin/preview-md", methods=["POST"])
    @admin_required
    def admin_preview_md():
        body = (request.get_json(silent=True) or {}).get("body", "")
        return jsonify({"ok": True, "html": render_md(body)})

    @app.get("/healthz")
    def healthz():
        return jsonify({"ok": True, "service": "mgla-site"})

    return app


app = create_app()


if __name__ == "__main__":
    host = os.environ.get("MGLA_SITE_HOST", "127.0.0.1")
    port = int(os.environ.get("MGLA_SITE_PORT", "8770"))
    app.run(host=host, port=port, debug=False)
