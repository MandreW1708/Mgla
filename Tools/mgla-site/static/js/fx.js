(function () {
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const fine = window.matchMedia("(pointer: fine)").matches;
  document.documentElement.classList.add("fx-ready");
  if (reduce) {
    document.documentElement.classList.add("fx-reduced");
    return;
  }

  // --- ambient canvas ---
  const canvas = document.getElementById("fx-canvas");
  if (canvas) {
    const ctx = canvas.getContext("2d", { alpha: true });
    let w = 0;
    let h = 0;
    let raf = 0;
    let particles = [];
    const DPR = Math.min(window.devicePixelRatio || 1, 2);

    function resize() {
      w = window.innerWidth;
      h = window.innerHeight;
      canvas.width = Math.floor(w * DPR);
      canvas.height = Math.floor(h * DPR);
      canvas.style.width = w + "px";
      canvas.style.height = h + "px";
      ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
      const count = Math.min(48, Math.floor((w * h) / 28000));
      particles = Array.from({ length: count }, () => spawn(true));
    }

    function spawn(randomY) {
      return {
        x: Math.random() * w,
        y: randomY ? Math.random() * h : h + 20,
        r: 0.6 + Math.random() * 2.2,
        vx: -0.15 + Math.random() * 0.3,
        vy: -0.25 - Math.random() * 0.55,
        a: 0.15 + Math.random() * 0.45,
        hue: 210 + Math.random() * 40,
      };
    }

    function frame() {
      ctx.clearRect(0, 0, w, h);
      for (const p of particles) {
        p.x += p.vx;
        p.y += p.vy;
        if (p.y < -20 || p.x < -20 || p.x > w + 20) {
          Object.assign(p, spawn(false));
          p.x = Math.random() * w;
        }
        const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.r * 8);
        g.addColorStop(0, `hsla(${p.hue}, 90%, 70%, ${p.a})`);
        g.addColorStop(1, `hsla(${p.hue}, 90%, 50%, 0)`);
        ctx.fillStyle = g;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.r * 8, 0, Math.PI * 2);
        ctx.fill();
      }
      raf = requestAnimationFrame(frame);
    }

    resize();
    frame();
    window.addEventListener("resize", resize, { passive: true });
    document.addEventListener("visibilitychange", () => {
      if (document.hidden) cancelAnimationFrame(raf);
      else raf = requestAnimationFrame(frame);
    });
  }

  // --- pointer spotlight ---
  const spot = document.getElementById("fx-spot");
  if (spot && fine) {
    let sx = window.innerWidth / 2;
    let sy = window.innerHeight / 3;
    let tx = sx;
    let ty = sy;
    window.addEventListener(
      "pointermove",
      (e) => {
        tx = e.clientX;
        ty = e.clientY;
      },
      { passive: true }
    );
    (function follow() {
      sx += (tx - sx) * 0.08;
      sy += (ty - sy) * 0.08;
      spot.style.transform = `translate(${sx}px, ${sy}px)`;
      requestAnimationFrame(follow);
    })();
  }

  // --- header scroll state ---
  const header = document.querySelector(".site-header");
  if (header) {
    const onScroll = () => {
      header.classList.toggle("is-scrolled", window.scrollY > 12);
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
  }

  // --- reveal on scroll ---
  const reveals = document.querySelectorAll("[data-reveal]");
  if (reveals.length) {
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          const el = entry.target;
          const delay = Number(el.getAttribute("data-reveal-delay") || 0);
          el.style.transitionDelay = delay + "ms";
          el.classList.add("is-in");
          io.unobserve(el);
        });
      },
      { threshold: 0.12, rootMargin: "0px 0px -8% 0px" }
    );
    reveals.forEach((el) => io.observe(el));
  }

  // --- magnetic buttons / cards ---
  if (fine) {
    document.querySelectorAll(".btn, .card").forEach((el) => {
      el.classList.add("fx-magnet");
      el.addEventListener("pointermove", (e) => {
        const r = el.getBoundingClientRect();
        const x = ((e.clientX - r.left) / r.width) * 100;
        const y = ((e.clientY - r.top) / r.height) * 100;
        el.style.setProperty("--mx", x + "%");
        el.style.setProperty("--my", y + "%");
        const dx = (e.clientX - (r.left + r.width / 2)) / (r.width / 2);
        const dy = (e.clientY - (r.top + r.height / 2)) / (r.height / 2);
        if (el.classList.contains("btn")) {
          el.style.transform = `translate(${dx * 4}px, ${dy * 4}px)`;
        } else {
          el.style.transform = `perspective(900px) rotateY(${dx * 5}deg) rotateX(${-dy * 5}deg) translateY(-2px)`;
        }
      });
      el.addEventListener("pointerleave", () => {
        el.style.transform = "";
      });
    });
    document.querySelectorAll(".chapter, .settings-hit, .guide-search").forEach((el) => {
      el.addEventListener("pointermove", (e) => {
        const r = el.getBoundingClientRect();
        el.style.setProperty("--mx", ((e.clientX - r.left) / r.width) * 100 + "%");
        el.style.setProperty("--my", ((e.clientY - r.top) / r.height) * 100 + "%");
      });
    });
  }

  // --- button ripples ---
  document.querySelectorAll(".btn").forEach((btn) => {
    btn.addEventListener("pointerdown", (e) => {
      const r = btn.getBoundingClientRect();
      const ripple = document.createElement("span");
      ripple.className = "fx-ripple";
      const size = Math.max(r.width, r.height);
      ripple.style.width = ripple.style.height = size + "px";
      ripple.style.left = e.clientX - r.left - size / 2 + "px";
      ripple.style.top = e.clientY - r.top - size / 2 + "px";
      btn.appendChild(ripple);
      setTimeout(() => ripple.remove(), 650);
    });
  });

  // --- hero title letter split ---
  const title = document.querySelector(".hero-copy h1");
  if (title && !title.dataset.split) {
    title.dataset.split = "1";
    const text = title.textContent || "";
    title.textContent = "";
    title.classList.add("fx-title");
    [...text].forEach((ch, i) => {
      const span = document.createElement("span");
      span.className = "fx-char";
      span.style.setProperty("--i", String(i));
      span.textContent = ch === " " ? "\u00A0" : ch;
      title.appendChild(span);
    });
  }

  // --- active section highlight in TOC + click scroll/focus ---
  const chapters = [...document.querySelectorAll(".chapter[id]")];
  const tocLinks = [...document.querySelectorAll('.toc-body a[href^="#ch-"]')];
  let focusTimer = 0;
  function focusChapter(id, smooth) {
    const ch = document.getElementById(id);
    if (!ch) return;
    chapters.forEach((c) => c.classList.remove("is-focused"));
    tocLinks.forEach((a) => a.classList.toggle("is-active", a.getAttribute("href") === "#" + id));
    ch.classList.add("is-focused");
    ch.scrollIntoView({ behavior: smooth ? "smooth" : "auto", block: "start" });
    clearTimeout(focusTimer);
    focusTimer = setTimeout(() => ch.classList.remove("is-focused"), 2200);
  }
  if (chapters.length && tocLinks.length) {
    const map = new Map(tocLinks.map((a) => [a.getAttribute("href").slice(1), a]));
    const io2 = new IntersectionObserver(
      (entries) => {
        if (window.matchMedia("(max-width: 900px)").matches) return;
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          tocLinks.forEach((a) => a.classList.remove("is-active"));
          const link = map.get(entry.target.id);
          if (link) link.classList.add("is-active");
        });
      },
      { rootMargin: "-35% 0px -50% 0px", threshold: 0.01 }
    );
    chapters.forEach((ch) => io2.observe(ch));
    tocLinks.forEach((a) => {
      a.addEventListener("click", (e) => {
        if (window.matchMedia("(max-width: 900px)").matches) return;
        const id = (a.getAttribute("href") || "").slice(1);
        if (!id || !document.getElementById(id)) return;
        e.preventDefault();
        focusChapter(id, true);
        history.replaceState(null, "", "#" + id);
      });
    });
  }

  // --- page boot ---
  requestAnimationFrame(() => document.body.classList.add("fx-booted"));
})();
