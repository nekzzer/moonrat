import { $, on } from "../lib/dom.js";
import { api } from "../lib/api.js";

const POLL_MS = 1500;
const MAX_LINES = 400;

export function createClipView(root, context) {
  const list = $("#clip-list", root);
  const info = $("#clip-info", root);
  const errors = $("#clip-err", root);
  let since = 0;
  let watching = false;
  let poll = 0;

  const prepend = (text) => {
    if (!text) return;
    const fresh = text.split("\n").filter(Boolean).reverse();
    const kept = list.textContent ? list.textContent.split("\n").filter(Boolean) : [];
    list.textContent = [...fresh, ...kept].slice(0, MAX_LINES).join("\n");
  };

  const tick = async () => {
    const id = context.agentId();
    if (!id) return;

    try {
      const result = await api.clip(id, "history", since);
      since = result.total || since;
      prepend(result.text);
      info.textContent = `monitoring · ${since} entries`;
    } catch (error) {
      errors.textContent = error.message;
    }
  };

  const stopPolling = () => {
    if (poll) {
      clearInterval(poll);
      poll = 0;
    }
  };

  on($("#btn-clip-start", root), "click", async () => {
    const id = context.agentId();
    if (!id) return;

    errors.textContent = "";
    try {
      await api.clip(id, "start");
      since = 0;
      watching = true;
      stopPolling();
      poll = setInterval(tick, POLL_MS);
      tick();
    } catch (error) {
      errors.textContent = error.message;
    }
  });

  on($("#btn-clip-stop", root), "click", async () => {
    const id = context.agentId();
    if (!id) return;

    errors.textContent = "";
    try {
      await api.clip(id, "stop");
    } catch (error) {
      errors.textContent = error.message;
    }
    watching = false;
    stopPolling();
    info.textContent = "monitor off";
  });

  on($("#btn-clip-clear", root), "click", async () => {
    const id = context.agentId();
    if (!id) return;

    errors.textContent = "";
    try {
      const result = await api.clip(id, "clear");
      since = result.total || 0;
      list.textContent = "";
      info.textContent = watching ? `monitoring · ${since} entries` : "cleared";
    } catch (error) {
      errors.textContent = error.message;
    }
  });

  return {
    activate: () => {
      if (!context.agentId()) {
        errors.textContent = "no client selected";
        return;
      }
      if (watching) {
        stopPolling();
        poll = setInterval(tick, POLL_MS);
      }
    },
    deactivate: stopPolling,
  };
}
