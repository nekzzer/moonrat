import { $, on } from "../lib/dom.js";
import { api } from "../lib/api.js";

export function createShellView(root, context) {
  const input = $("#cmd", root);
  const output = $("#out", root);

  const write = (text) => {
    output.textContent += text;
    output.scrollTop = output.scrollHeight;
  };

  async function run() {
    const id = context.agentId();
    const command = input.value.trim();
    if (!id || !command) return;

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
    if (event.key === "Enter") run();
  });

  return { activate: () => {}, deactivate: () => {} };
}
