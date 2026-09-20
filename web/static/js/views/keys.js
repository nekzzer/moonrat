import { $, on } from "../lib/dom.js";
import { api } from "../lib/api.js";

export function createKeysView(root, context) {
  const log = $("#keys", root);
  const info = $("#keys-info", root);
  let timer = null;

  async function load() {
    const id = context.agentId();
    if (!id) return;

    try {
      const { text } = await api.keylog(id, "dump");
      const content = text ?? "";
      const atBottom = log.scrollTop + log.clientHeight >= log.scrollHeight - 32;
      log.textContent = content;
      if (atBottom) log.scrollTop = log.scrollHeight;
      info.textContent = `${content.length} chars`;
    } catch (error) {
      info.textContent = error.message;
    }
  }

  async function control(action) {
    const id = context.agentId();
    if (!id) return;

    try {
      const result = await api.keylog(id, action);
      info.textContent = result.text ?? action;
      if (action === "clear") log.textContent = "";
    } catch (error) {
      info.textContent = error.message;
    }
  }

  function activate() {
    load();
    if (!timer) timer = setInterval(() => !document.hidden && load(), 1500);
  }

  function deactivate() {
    clearInterval(timer);
    timer = null;
  }

  on($("#btn-kl-start", root), "click", () => control("start"));
  on($("#btn-kl-stop", root), "click", () => control("stop"));
  on($("#btn-kl-clear", root), "click", () => control("clear"));
  on($("#btn-kl-refresh", root), "click", load);
  on(document, "visibilitychange", () => {
    if (document.hidden) deactivate();
    else if (context.store.get().tab === "keys") activate();
  });

  return { activate, deactivate };
}
