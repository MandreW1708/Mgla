(() => {
  const modal = document.getElementById("pay-checkout-modal");
  const form = document.getElementById("pay-checkout-form");
  const nextBox = document.getElementById("pay-checkout-next");
  const planLine = document.getElementById("pay-plan-line");
  const planInput = document.getElementById("pay-plan-input");
  const priceInput = document.getElementById("pay-price-input");
  const emailInput = document.getElementById("pay-email");
  const tgInput = document.getElementById("pay-tg");
  const errEl = document.getElementById("pay-form-error");
  const nextText = document.getElementById("pay-next-text");
  if (!modal || !form) return;

  const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/i;

  function normalizeTg(raw) {
    let v = (raw || "").trim();
    if (!v) return "";
    if (v.startsWith("https://t.me/")) v = v.slice("https://t.me/".length);
    if (v.startsWith("t.me/")) v = v.slice("t.me/".length);
    if (v.startsWith("@")) v = v.slice(1);
    v = v.replace(/[^A-Za-z0-9_]/g, "");
    return v ? "@" + v : "";
  }

  function openCheckout(plan, price) {
    planInput.value = plan || "";
    priceInput.value = price || "";
    planLine.textContent = plan && price
      ? `${plan} · ${price} ₽/мес`
      : (plan || "Mgla Premium");
    form.hidden = false;
    nextBox.hidden = true;
    errEl.hidden = true;
    emailInput.value = "";
    tgInput.value = "";
    modal.hidden = false;
    document.body.style.overflow = "hidden";
    setTimeout(() => emailInput.focus(), 40);
  }

  function closeCheckout() {
    modal.hidden = true;
    document.body.style.overflow = "";
  }

  function showNext(email, tg, plan, price) {
    form.hidden = true;
    nextBox.hidden = false;
    const contact = email || tg;
    nextText.innerHTML =
      `Контакт <strong>${escapeHtml(contact)}</strong> принят для плана ` +
      `<strong>${escapeHtml(plan || "Premium")}</strong>` +
      (price ? ` (${escapeHtml(String(price))} ₽/мес)` : "") +
      `. Следующий шаг — оплата через платёжного провайдера. ` +
      `Подключение кассы ещё не завершено: после активации вы будете ` +
      `перенаправлены на страницу оплаты автоматически.`;
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  document.addEventListener("click", (e) => {
    const btn = e.target.closest(".premium-pay-btn");
    if (btn) {
      e.preventDefault();
      openCheckout(btn.getAttribute("data-plan"), btn.getAttribute("data-price"));
      return;
    }
    if (e.target.closest("[data-pay-close]")) {
      closeCheckout();
    }
  });

  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !modal.hidden) {
      closeCheckout();
    }
  });

  form.addEventListener("submit", (e) => {
    e.preventDefault();
    errEl.hidden = true;
    const email = (emailInput.value || "").trim();
    const tg = normalizeTg(tgInput.value);
    if (tg) tgInput.value = tg;

    const emailOk = email && EMAIL_RE.test(email);
    const tgOk = tg.length >= 2;
    if (!emailOk && !tgOk) {
      errEl.hidden = false;
      errEl.textContent = email
        ? "Проверьте почту или укажите Telegram-username."
        : "Укажите почту или Telegram-username.";
      (email ? emailInput : tgInput).focus();
      return;
    }
    if (email && !emailOk && !tgOk) {
      errEl.hidden = false;
      errEl.textContent = "Некорректная почта. Исправьте или укажите Telegram.";
      emailInput.focus();
      return;
    }

    // Persist locally for when real payment gateway is wired.
    try {
      sessionStorage.setItem(
        "mgla_pay_contact",
        JSON.stringify({
          plan: planInput.value,
          price: priceInput.value,
          email: emailOk ? email : "",
          tg: tgOk ? tg : "",
          at: Date.now(),
        })
      );
    } catch (_) {}

    showNext(emailOk ? email : "", tgOk ? tg : "", planInput.value, priceInput.value);
  });
})();
