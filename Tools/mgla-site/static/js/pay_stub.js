(() => {
  const modal = document.getElementById("pay-stub-modal");
  const textEl = document.getElementById("pay-stub-text");
  if (!modal) return;

  const STUB =
    "Оплата пока недоступна. Подключение платёжной системы ещё не завершено.";

  function openStub(plan, price) {
    if (textEl) {
      textEl.textContent = plan && price
        ? `${plan} · ${price} ₽/мес. ${STUB}`
        : STUB;
    }
    modal.hidden = false;
    document.body.style.overflow = "hidden";
  }

  function closeStub() {
    modal.hidden = true;
    document.body.style.overflow = "";
  }

  document.addEventListener("click", (e) => {
    const btn = e.target.closest(".premium-pay-btn");
    if (btn) {
      e.preventDefault();
      openStub(btn.getAttribute("data-plan"), btn.getAttribute("data-price"));
      return;
    }
    if (e.target.closest("[data-pay-stub-close]")) {
      closeStub();
    }
  });

  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !modal.hidden) {
      closeStub();
    }
  });
})();
