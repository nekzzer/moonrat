import { $, on } from "../lib/dom.js";
import { api } from "../lib/api.js";

export function createMediaView(root, context) {
  const shot = $("#cam-shot", root);
  const audio = $("#mic-audio", root);
  const info = $("#media-info", root);
  const errors = $("#media-err", root);

  on($("#btn-cam", root), "click", async () => {
    const id = context.agentId();
    if (!id) return;

    errors.textContent = "";
    info.textContent = "capturing frame…";

    try {
      await new Promise((resolve, reject) => {
        shot.onload = resolve;
        shot.onerror = () => reject(new Error("camera unavailable"));
        shot.src = api.camUrl(id);
      });
      shot.hidden = false;
      info.textContent = "frame captured";
    } catch (error) {
      info.textContent = "";
      errors.textContent = error.message;
    }
  });

  on($("#btn-mic", root), "click", async () => {
    const id = context.agentId();
    if (!id) return;

    const seconds = Math.max(1, Math.min(120, parseInt($("#mic-sec", root).value, 10) || 5));
    errors.textContent = "";
    info.textContent = `recording ${seconds}s…`;

    try {
      const url = await api.mic(id, seconds);
      audio.src = url;
      audio.hidden = false;
      info.textContent = `recorded ${seconds}s`;
    } catch (error) {
      info.textContent = "";
      errors.textContent = error.message;
    }
  });

  return {
    activate: () => {
      errors.textContent = context.agentId() ? "" : "no client selected";
    },
    deactivate: () => {},
  };
}
