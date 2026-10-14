(function () {
  const root = document.getElementById("guide-search");
  if (!root) return;

  const input = root.querySelector("[data-search-input]");
  const settingsBox = root.querySelector("[data-settings-results]");
  const emptyHint = document.querySelector("[data-search-empty]");
  const chapters = [...document.querySelectorAll(".chapter")];
  const navLinks = [...document.querySelectorAll(".toc-body a[href^='#ch-']")];
  const guideItems = [...document.querySelectorAll("[data-open-chapter]")];
  const guideCats = [...document.querySelectorAll("[data-cat]")];
  const caption = root.querySelector(".search-caption");

  let settings = [];
  fetch("/static/js/settings_index.json")
    .then((r) => r.json())
    .then((data) => {
      settings = Array.isArray(data) ? data : [];
      renderPaths("");
    })
    .catch(() => {
      settings = [];
    });

  function norm(s) {
    return (s || "").toLowerCase().replace(/ё/g, "е");
  }

  function chapterText(el) {
    return norm(el.innerText || el.textContent || "");
  }

  function chapterTitle(el) {
    const h = el.querySelector("h3");
    return norm(h ? h.textContent : "");
  }

  function queryMatch(hay, nq) {
    if (!nq) return true;
    if (hay.includes(nq)) return true;
    return nq.split(/\s+/).every((w) => !w || hay.includes(w));
  }

  function findChapterIdByGuide(guide) {
    const g = norm(guide);
    if (!g) return null;
    for (const ch of chapters) {
      if (chapterTitle(ch).includes(g)) return ch.id;
    }
    return null;
  }

  function matchingChapterIds(nq) {
    const ids = new Set();
    if (!nq) return ids;

    chapters.forEach((ch) => {
      if (queryMatch(chapterText(ch), nq)) ids.add(ch.id);
    });

    settings.forEach((item) => {
      const hay = norm(item.path + " " + item.keys + " " + (item.hint || ""));
      if (!queryMatch(hay, nq)) return;
      const id = findChapterIdByGuide(item.guide || "");
      if (id) ids.add(id);
    });

    return ids;
  }

  function matchingSettings(nq) {
    if (!nq) return [];
    return settings
      .filter((item) => {
        const hay = norm(item.path + " " + item.keys + " " + (item.hint || ""));
        return queryMatch(hay, nq);
      })
      .slice(0, 12);
  }

  function openChapter(id) {
    if (!id) return;
    const mobile = window.matchMedia("(max-width: 900px)").matches;
    if (mobile) {
      const btn = document.querySelector('[data-open-chapter="' + id + '"]');
      if (btn) btn.click();
      return;
    }
    const ch = document.getElementById(id);
    if (!ch) return;
    chapters.forEach((c) => c.classList.remove("is-focused"));
    navLinks.forEach((a) => a.classList.toggle("is-active", a.getAttribute("href") === "#" + id));
    ch.classList.add("is-focused");
    ch.scrollIntoView({ behavior: "smooth", block: "start" });
    history.replaceState(null, "", "#" + id);
    setTimeout(() => ch.classList.remove("is-focused"), 2200);
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function renderPaths(q) {
    if (!settingsBox) return;
    const nq = norm(q);
    const hits = matchingSettings(nq).filter((item) => item.guide);
    settingsBox.hidden = !nq || !hits.length;
    if (caption) {
      caption.hidden = settingsBox.hidden;
      caption.textContent = "Пути в клиенте → откроют инструкцию";
    }
    if (!nq || !hits.length) {
      settingsBox.innerHTML = "";
      return;
    }

    // Deduplicate by linked chapter so "Скрытые чаты" + "Уведомления скрытых" → one card + paths
    const byChapter = new Map();
    hits.forEach((item) => {
      const id = findChapterIdByGuide(item.guide);
      if (!id) return;
      if (!byChapter.has(id)) byChapter.set(id, []);
      byChapter.get(id).push(item);
    });

    settingsBox.innerHTML = [...byChapter.entries()]
      .map(([id, items], i) => {
        const ch = document.getElementById(id);
        const title = ch && ch.querySelector("h3") ? ch.querySelector("h3").textContent : items[0].guide;
        const paths = items
          .map((it) => `<div class="settings-path">${escapeHtml(it.path)}</div>`)
          .join("");
        return (
          `<button type="button" class="settings-hit settings-hit-btn" data-goto-chapter="${escapeHtml(id)}" style="animation-delay:${i * 40}ms">` +
          `<div class="settings-guide-title">${escapeHtml(title)}</div>` +
          paths +
          `<div class="settings-hint">Нажмите, чтобы открыть инструкцию</div>` +
          `</button>`
        );
      })
      .join("");

    settingsBox.querySelectorAll("[data-goto-chapter]").forEach((btn) => {
      btn.addEventListener("click", () => openChapter(btn.getAttribute("data-goto-chapter")));
    });
  }

  function filterChapters(q) {
    const nq = norm(q);
    const ids = matchingChapterIds(nq);
    let visible = 0;

    chapters.forEach((ch) => {
      const ok = !nq || ids.has(ch.id);
      ch.style.display = ok ? "" : "none";
      if (ok) visible++;
      navLinks.forEach((a) => {
        if (a.getAttribute("href") === "#" + ch.id) {
          a.style.display = ok ? "" : "none";
        }
      });
    });

    guideItems.forEach((btn) => {
      const id = btn.getAttribute("data-open-chapter");
      btn.style.display = !nq || ids.has(id) ? "" : "none";
    });

    guideCats.forEach((cat) => {
      const any = [...cat.querySelectorAll("[data-open-chapter]")].some((b) => b.style.display !== "none");
      cat.style.display = !nq || any ? "" : "none";
    });

    // Hide empty TOC section titles on desktop
    document.querySelectorAll(".toc-body .sec-title").forEach((sec) => {
      if (!nq) {
        sec.style.display = "";
        return;
      }
      let el = sec.nextElementSibling;
      let any = false;
      while (el && !el.classList.contains("sec-title")) {
        if (el.tagName === "A" && el.style.display !== "none") any = true;
        el = el.nextElementSibling;
      }
      sec.style.display = any ? "" : "none";
    });

    if (emptyHint) {
      emptyHint.hidden = !(nq && visible === 0);
    }
  }

  function onInput() {
    const q = input.value.trim();
    filterChapters(q);
    renderPaths(q);
  }

  input.addEventListener("input", onInput);
  input.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      input.value = "";
      onInput();
      input.blur();
    }
  });
})();
