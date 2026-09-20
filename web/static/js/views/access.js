import { $, el, on } from "../lib/dom.js";
import { api } from "../lib/api.js";

const EYE =
  '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>';

const EYE_OFF =
  '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49"/><path d="M14.084 14.158a3 3 0 0 1-4.242-4.242"/><path d="M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143"/><path d="m2 2 20 20"/></svg>';

export function createAccessView(root, context) {
  const list = $("#access-list", root);
  const label = $("#access-label", root);
  const role = $("#access-role", root);
  const notice = $("#access-notice", root);

  const render = (entry) => {
    const keyValue = el("code", { class: "key-value blurred" }, entry.key);

    const toggle = () => {
      const hidden = keyValue.classList.toggle("blurred");
      eye.innerHTML = hidden ? EYE : EYE_OFF;
      eye.title = hidden ? "reveal key" : "hide key";
    };

    const eye = el("button", { type: "button", class: "action mini eye-btn", title: "reveal key", onclick: toggle });
    eye.innerHTML = EYE;
    keyValue.addEventListener("click", toggle);

    const remove = el(
      "button",
      {
        type: "button",
        class: "action mini danger",
        disabled: entry.current,
        onclick: async () => {
          try {
            await api.deleteKey(entry.key);
            notice.textContent = `removed ${entry.label}`;
            refresh();
          } catch (error) {
            notice.textContent = error.message;
          }
        },
      },
      entry.current ? "current" : "remove",
    );

    return el(
      "div",
      { class: "fun-card" },
      el("span", { class: "label" }, entry.role),
      keyValue,
      eye,
      el("span", { class: "dim" }, entry.label),
      el("span", { class: "spacer" }),
      remove,
    );
  };

  async function refresh() {
    try {
      list.replaceChildren(...(await api.keys()).map(render));
    } catch (error) {
      notice.textContent = error.message;
    }
  }

  on($("#btn-access-add", root), "click", async () => {
    notice.textContent = "";
    try {
      const created = await api.createKey(label.value.trim(), role.value);
      notice.textContent = `created ${created.key} (${created.role})`;
      label.value = "";
      refresh();
    } catch (error) {
      notice.textContent = error.message;
    }
  });

  return { activate: refresh, deactivate: () => {} };
}
