(function () {
  function reorder(container) {
    const kind = container.dataset.kind;
    if (!kind) return;
    const rows = [...container.querySelectorAll(":scope > [data-id]")];
    const order = rows.map((el) => el.dataset.id);
    fetch("/admin/reorder", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ kind, order }),
    }).catch(() => {});
  }

  function enableDrag(container, itemSelector) {
    let dragEl = null;
    container.querySelectorAll(itemSelector).forEach((row) => {
      const handle = row.querySelector(".drag-handle");
      if (!handle) return;
      handle.setAttribute("draggable", "true");
      handle.addEventListener("dragstart", (e) => {
        dragEl = row;
        row.style.opacity = "0.5";
        e.dataTransfer.effectAllowed = "move";
      });
      handle.addEventListener("dragend", () => {
        if (dragEl) dragEl.style.opacity = "";
        dragEl = null;
        reorder(container);
      });
      row.addEventListener("dragover", (e) => {
        e.preventDefault();
        if (!dragEl || dragEl === row) return;
        const rect = row.getBoundingClientRect();
        const before = e.clientY < rect.top + rect.height / 2;
        container.insertBefore(dragEl, before ? row : row.nextSibling);
      });
    });
  }

  const sections = document.getElementById("sections");
  if (sections) {
    enableDrag(sections, ".section-card");
    sections.querySelectorAll(".chapters").forEach((ch) => enableDrag(ch, ".chapter-row"));
  }

  window.MglaChapterEditor = {
    init() {
      const body = document.getElementById("body");
      const preview = document.getElementById("preview");
      const btn = document.getElementById("btn-preview");
      const file = document.getElementById("img-upload");
      if (!body || !preview) return;

      async function refresh() {
        const res = await fetch("/admin/preview-md", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ body: body.value }),
        });
        const data = await res.json();
        if (data.ok) preview.innerHTML = data.html;
      }
      btn && btn.addEventListener("click", refresh);

      file &&
        file.addEventListener("change", async () => {
          if (!file.files || !file.files[0]) return;
          const fd = new FormData();
          fd.append("file", file.files[0]);
          const res = await fetch("/admin/upload", { method: "POST", body: fd });
          const data = await res.json();
          if (!data.ok) {
            alert(data.error || "upload failed");
            return;
          }
          const md = `\n![image](${data.url})\n`;
          const start = body.selectionStart || body.value.length;
          body.value = body.value.slice(0, start) + md + body.value.slice(start);
          refresh();
          file.value = "";
        });
    },
  };
})();
