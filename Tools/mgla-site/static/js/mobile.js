(function () {
  const toggle = document.querySelector(".nav-toggle");
  const nav = document.getElementById("site-nav");
  if (toggle && nav) {
    toggle.addEventListener("click", (e) => {
      e.stopPropagation();
      const open = !nav.classList.contains("is-open");
      nav.classList.toggle("is-open", open);
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
      document.body.classList.toggle("nav-open", open);
    });
    nav.querySelectorAll("a").forEach((a) => {
      a.addEventListener("click", () => {
        nav.classList.remove("is-open");
        toggle.setAttribute("aria-expanded", "false");
        document.body.classList.remove("nav-open");
      });
    });
    document.addEventListener("click", (e) => {
      if (!nav.classList.contains("is-open")) return;
      if (nav.contains(e.target) || toggle.contains(e.target)) return;
      nav.classList.remove("is-open");
      toggle.setAttribute("aria-expanded", "false");
      document.body.classList.remove("nav-open");
    });
  }

  const sheet = document.getElementById("chapter-sheet");
  if (!sheet) return;

  const titleEl = sheet.querySelector("[data-sheet-title]");
  const bodyEl = sheet.querySelector("[data-sheet-body]");
  const isMobile = () => window.matchMedia("(max-width: 900px)").matches;
  let sheetOpen = false;
  let closing = false;

  function fillSheet(id) {
    const article = document.getElementById(id);
    if (!article) return false;
    const title = article.querySelector("h3");
    const prose = article.querySelector(".prose");
    if (titleEl) titleEl.textContent = title ? title.textContent : "";
    if (bodyEl) bodyEl.innerHTML = prose ? prose.innerHTML : "";
    if (bodyEl) bodyEl.scrollTop = 0;
    return true;
  }

  function showSheet(id) {
    if (!fillSheet(id)) return false;
    closing = false;
    sheet.hidden = false;
    sheet.setAttribute("aria-hidden", "false");
    document.body.classList.add("sheet-open");
    // next frame so CSS transition runs
    requestAnimationFrame(() => {
      requestAnimationFrame(() => sheet.classList.add("is-open"));
    });
    sheetOpen = true;
    return true;
  }

  function hideSheet(immediate) {
    if (!sheetOpen && sheet.hidden) return;
    closing = true;
    sheet.classList.remove("is-open");
    document.body.classList.remove("sheet-open");
    sheet.setAttribute("aria-hidden", "true");

    const finish = () => {
      if (!closing) return;
      sheet.hidden = true;
      if (bodyEl) bodyEl.innerHTML = "";
      sheetOpen = false;
      closing = false;
    };

    if (immediate) {
      finish();
      return;
    }
    const onEnd = (e) => {
      if (e.target !== sheet) return;
      sheet.removeEventListener("transitionend", onEnd);
      finish();
    };
    sheet.addEventListener("transitionend", onEnd);
    setTimeout(finish, 420);
  }

  function openChapter(id) {
    if (!isMobile()) return;
    if (!showSheet(id)) return;
    const url = window.location.pathname + window.location.search + "#" + id;
    if (history.state && history.state.mglaChapter) {
      history.replaceState({ mglaChapter: id }, "", url);
    } else {
      history.pushState({ mglaChapter: id }, "", url);
    }
  }

  function requestClose() {
    if (!sheetOpen) return;
    if (history.state && history.state.mglaChapter) {
      history.back();
      return;
    }
    hideSheet(false);
    if (window.location.hash) {
      history.replaceState(null, "", window.location.pathname + window.location.search);
    }
  }

  document.querySelectorAll("[data-open-chapter]").forEach((btn) => {
    btn.addEventListener("click", () => openChapter(btn.getAttribute("data-open-chapter")));
  });
  sheet.querySelectorAll("[data-close-chapter]").forEach((btn) => {
    btn.addEventListener("click", (e) => {
      e.preventDefault();
      requestClose();
    });
  });

  window.addEventListener("popstate", () => {
    if (sheetOpen) {
      hideSheet(false);
    } else if (history.state && history.state.mglaChapter && isMobile()) {
      showSheet(history.state.mglaChapter);
    }
  });

  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && sheetOpen) requestClose();
  });

  if (isMobile() && window.location.hash) {
    const id = window.location.hash.replace(/^#/, "");
    if (document.getElementById(id)) {
      showSheet(id);
      history.replaceState({ mglaChapter: id }, "", window.location.href);
    }
  }
})();
