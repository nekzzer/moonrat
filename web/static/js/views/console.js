import { $, on } from "../lib/dom.js";
import { api } from "../lib/api.js";

const LOG_BYTES = 32768;
const POLL_MS = 1500;

export function createConsoleView(root, context) {
  const info = $("#game-info", root);
  const log = $("#game-log", root);
  const input = $("#game-cmd", root);
  const sendButton = $("#btn-game-send", root);
  const refreshButton = $("#btn-game-refresh", root);

  let timer = null;
  const history = [];
  let historyIndex = 0;

  function renderLog(text) {
    const stick = log.scrollTop + log.clientHeight >= log.scrollHeight - 24;
    log.textContent = text;
    if (stick) log.scrollTop = log.scrollHeight;
  }

  async function pollInfo() {
    try {
      const data = await api.game(context.agentId(), "info");
      const count = data.plugins ? data.plugins.split(",").filter(Boolean).length : 0;
      info.textContent = `pid ${data.pid} · ${data.dir} · ${count} plugin${count === 1 ? "" : "s"}`;
      info.title = data.plugins ? data.plugins.split(",").join("\n") : "";
      return true;
    } catch (failure) {
      info.textContent = failure.message;
      return false;
    }
  }

  async function pollLog() {
    try {
      renderLog(await api.gameLog(context.agentId(), LOG_BYTES));
    } catch {}
  }

  function stop() {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  async function activate() {
    stop();
    if (!context.agentId()) {
      info.textContent = "no agent selected";
      return;
    }
    if (await pollInfo()) {
      await pollLog();
      timer = setInterval(pollLog, POLL_MS);
    }
  }

  async function send() {
    const command = input.value.trim();
    if (!command || !context.agentId()) return;
    try {
      await api.game(context.agentId(), "send", command);
      history.push(command);
      historyIndex = history.length;
      input.value = "";
      await pollLog();
    } catch (failure) {
      info.textContent = failure.message;
    }
  }

  on(sendButton, "click", send);
  on(refreshButton, "click", activate);
  on(input, "keydown", (event) => {
    if (event.key === "Enter") {
      send();
      return;
    }
    if (event.key === "ArrowUp" && history.length) {
      historyIndex = Math.max(0, historyIndex - 1);
      input.value = history[historyIndex];
      event.preventDefault();
    } else if (event.key === "ArrowDown" && history.length) {
      historyIndex = Math.min(history.length, historyIndex + 1);
      input.value = history[historyIndex] ?? "";
      event.preventDefault();
    }
  });

  return { activate, deactivate: stop };
}
