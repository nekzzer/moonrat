import { $, on } from "../lib/dom.js";
import { api } from "../lib/api.js";

const PRESETS = {
  low: { q: 50, w: 1366, fps: 15 },
  med: { q: 75, w: 1920, fps: 24 },
  high: { q: 85, w: 2560, fps: 30 },
};

export function createScreenView(root, context) {
  const image = $("#stream", root);
  const live = $("#live", root);
  const quality = $("#quality", root);
  const status = $("#shot-info", root);
  const snapButton = $("#btn-snap", root);
  const fullButton = $("#btn-full", root);

  let streaming = false;

  const preset = () => PRESETS[quality.value] ?? PRESETS.med;

  const wanted = () =>
    Boolean(context.agentId()) && context.store.get().tab === "screen" && live.checked && !document.hidden;

  function start() {
    if (!wanted() || streaming) return;
    streaming = true;
    root.classList.add("is-live");
    status.textContent = `live · ${preset().fps} fps`;
    image.onerror = () => {
      streaming = false;
      root.classList.remove("is-live");
      status.textContent = "stream error";
    };
    image.src = api.streamUrl(context.agentId(), new URLSearchParams(preset()));
  }

  function stop() {
    if (!streaming) return;
    streaming = false;
    root.classList.remove("is-live");
    image.removeAttribute("src");
    status.textContent = "stopped";
  }

  function snap() {
    const id = context.agentId();
    if (!id) return;

    stop();
    const started = performance.now();
    status.textContent = "capturing…";

    image.onload = () => {
      status.textContent = `${image.naturalWidth}×${image.naturalHeight} · ${Math.round(performance.now() - started)} ms`;
    };
    image.onerror = () => {
      status.textContent = "capture failed";
    };
    image.src = api.snapshotUrl(id);
  }

  function toggleFullscreen() {
    if (document.fullscreenElement) {
      document.exitFullscreen();
      return;
    }
    if (image.requestFullscreen) image.requestFullscreen();
    else if (image.webkitRequestFullscreen) image.webkitRequestFullscreen();
  }

  on(live, "change", () => (live.checked ? start() : stop()));
  on(quality, "change", () => {
    if (!streaming) return;
    stop();
    start();
  });
  on(snapButton, "click", snap);
  on(fullButton, "click", toggleFullscreen);
  on(image, "dblclick", toggleFullscreen);
  on(document, "visibilitychange", () => (wanted() ? start() : stop()));

  return {
    activate: () => (live.checked ? start() : snap()),
    deactivate: stop,
  };
}
