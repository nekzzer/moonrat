import { el } from "../lib/dom.js";
import { api } from "../lib/api.js";

export function createAgentsView({ root, store, onSelect }) {
  let signature = "";

  const render = (agent) => {
    const selected = agent.id === store.get().selectedId;
    const env = String(agent.env ?? "?");
    const classes = ["env-badge"];
    if (env.startsWith("pterodactyl")) classes.push("ptero");
    if (env.endsWith(":root")) classes.push("root");
    return el(
      "button",
      {
        type: "button",
        class: `agent${selected ? " sel" : ""}`,
        onclick: () => onSelect(agent.id),
      },
      el("span", { class: "agent-host" }, agent.host, el("span", { class: "dim" }, ` ${agent.user}`)),
      el(
        "span",
        { class: "agent-meta" },
        `${agent.os} · `,
        el("span", { class: classes.join(" ") }, env),
        ` · ${agent.id}`,
      ),
    );
  };

  async function refresh() {
    let agents = [];
    try {
      agents = await api.agents();
    } catch {}

    const current = store.get();
    const stillThere = agents.some((agent) => agent.id === current.selectedId);
    const selectedId = stillThere ? current.selectedId : agents[0]?.id ?? null;

    store.update(() => ({ agents, selectedId, cwd: stillThere ? current.cwd : null }));

    const next = [selectedId, ...agents.map((agent) => [agent.id, agent.host, agent.user, agent.os, agent.env].join(":"))].join("|");
    if (next === signature) return false;

    signature = next;
    root.replaceChildren(
      ...(agents.length === 0
        ? [el("div", { class: "rail-empty" }, "no clients yet", el("span", { class: "dim" }, "curl the payload to begin"))]
        : agents.map(render)),
    );
    return true;
  }

  return { refresh };
}
