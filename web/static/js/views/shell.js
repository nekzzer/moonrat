import { $, on } from "../lib/dom.js";
import { api } from "../lib/api.js";

const HISTORY_KEY = "moonrat-cmd-history";
const HISTORY_LIMIT = 200;

export function createShellView(root, context) {
  const input = $("#cmd", root);
  const output = $("#out", root);

  let history = [];
  try {
    history = JSON.parse(localStorage.getItem(HISTORY_KEY) || "[]");
  } catch {}
  let historyIndex = history.length;

  const remember = (command) => {
    if (history[history.length - 1] === command) return;
    history.push(command);
    if (history.length > HISTORY_LIMIT) history.shift();
    historyIndex = history.length;
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
  };

  const write = (text) => {
    output.textContent += text;
    output.scrollTop = output.scrollHeight;
  };

  async function run() {
    const id = context.agentId();
    const command = input.value.trim();
    if (!id || !command) return;

    remember(command);
    write(`\n$ ${command}\n`);

    try {
      const result = await api.exec(id, command);
      if (result.error) write(`(error) ${result.error}\n`);
      if (result.output) write(result.output.endsWith("\n") ? result.output : `${result.output}\n`);
      write(`[exit ${result.exit}]\n`);
    } catch (error) {
      write(`error: ${error.message}\n`);
    }

    input.value = "";
    input.focus();
  }

  on($("#btn-run", root), "click", run);
  on($("#btn-clear", root), "click", () => {
    output.textContent = "";
  });
  on(input, "keydown", (event) => {
    if (event.key === "Enter") {
      run();
      return;
    }

    if (event.key === "ArrowUp") {
      event.preventDefault();
      if (history.length === 0) return;
      historyIndex = Math.max(0, historyIndex - 1);
      input.value = history[historyIndex] ?? "";
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      historyIndex = Math.min(history.length, historyIndex + 1);
      input.value = history[historyIndex] ?? "";
    }
  });

  return { activate: () => {}, deactivate: () => {} };
}
