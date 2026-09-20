import { $, el, on } from "../lib/dom.js";
import { api } from "../lib/api.js";
import { formatBytes, joinPath, parentPath } from "../lib/format.js";

const CHUNK = 4 * 1024 * 1024;

export function createFilesView(root, context) {
  const pathInput = $("#path", root);
  const errors = $("#files-err", root);
  const info = $("#files-info", root);
  const table = $("#files", root);
  const picker = $("#file", root);

  const launch = async (id, path, name) => {
    const args = window.prompt(`arguments for ${name} (optional):`, "");
    if (args === null) return;

    errors.textContent = "";
    info.textContent = "launching…";

    try {
      const result = await api.run(id, path, args);
      info.textContent = `launched ${name}${result.pid ? ` (pid ${result.pid})` : ""}`;
    } catch (error) {
      info.textContent = "";
      errors.textContent = error.message;
    }
  };

  const renderEntry = (id, directory, entry) => {
    const full = joinPath(directory, entry.name);
    const link = entry.dir
      ? el(
          "a",
          {
            href: "#",
            class: "dir",
            onclick: (event) => {
              event.preventDefault();
              open(full);
            },
          },
          `${entry.name}/`,
        )
      : el("a", { href: api.downloadUrl(id, full) }, entry.name);

    const runButton = entry.dir
      ? null
      : el(
          "button",
          { type: "button", class: "action mini", onclick: () => launch(id, full, entry.name) },
          "run",
        );

    return el(
      "tr",
      {},
      el("td", {}, link),
      el("td", { class: "meta dim right" }, entry.dir ? "dir" : formatBytes(entry.size)),
      el("td", { class: "actions right" }, runButton),
    );
  };

  async function open(directory = null) {
    const id = context.agentId();
    if (!id) return;

    errors.textContent = "";
    info.textContent = "";

    try {
      const listing = await api.listing(id, directory);
      context.store.update(() => ({ cwd: listing.path }));
      pathInput.value = listing.path;
      table.replaceChildren(...listing.entries.map((entry) => renderEntry(id, listing.path, entry)));
    } catch (error) {
      errors.textContent = error.message;
    }
  }

  async function uploadFile(id, file) {
    const destination = joinPath(context.store.get().cwd, file.name);
    let offset = 0;

    do {
      const blob = file.slice(offset, Math.min(offset + CHUNK, file.size));
      await api.upload(id, destination, offset, blob);
      offset += blob.size;
      info.textContent = `uploading ${file.name} ${Math.round((offset * 100) / Math.max(file.size, 1))}%`;
    } while (offset < file.size);

    info.textContent = `uploaded ${file.name} (${formatBytes(file.size)})`;
  }

  async function upload(files) {
    const id = context.agentId();
    if (!id) return;

    for (const file of files) {
      try {
        await uploadFile(id, file);
      } catch (error) {
        info.textContent = `${file.name}: ${error.message}`;
      }
    }

    if (context.store.get().cwd) open(context.store.get().cwd);
  }

  on($("#btn-root", root), "click", () => open(null));
  on($("#btn-up", root), "click", () => open(parentPath(context.store.get().cwd)));
  on($("#btn-go", root), "click", () => open(pathInput.value.trim()));
  on($("#btn-reload", root), "click", () => open(context.store.get().cwd));
  on(pathInput, "keydown", (event) => {
    if (event.key === "Enter") open(pathInput.value.trim());
  });
  on($("#btn-upload", root), "click", () => {
    if (context.agentId() && context.store.get().cwd) picker.click();
  });
  on($("#btn-zip", root), "click", () => {
    const id = context.agentId();
    const directory = context.store.get().cwd;
    if (id && directory) window.location.assign(api.archiveUrl(id, directory));
  });
  on(picker, "change", async () => {
    await upload(Array.from(picker.files));
    picker.value = "";
  });

  const activate = () => {
    if (!context.agentId()) {
      table.replaceChildren();
      errors.textContent = "no agent selected";
      return;
    }
    open(context.store.get().cwd);
  };

  return { activate, deactivate: () => {} };
}
