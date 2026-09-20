const UNITS = ["b", "kb", "mb", "gb", "tb"];

export function formatBytes(bytes = 0) {
  let value = Number(bytes) || 0;
  let unit = 0;

  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }

  const digits = unit === 0 ? 0 : value < 10 ? 2 : 1;
  return `${value.toFixed(digits)} ${UNITS[unit]}`;
}

export function joinPath(directory, name) {
  if (!directory) return name;
  const separator = directory.includes("\\") ? "\\" : "/";
  return directory.endsWith(separator) ? `${directory}${name}` : `${directory}${separator}${name}`;
}

export function parentPath(path) {
  if (!path) return path;

  const trimmed = path.replace(/[\\/]+$/, "");
  if (!trimmed) return "/";
  if (/^[A-Za-z]:$/.test(trimmed)) return `${trimmed}\\`;

  const cut = Math.max(trimmed.lastIndexOf("/"), trimmed.lastIndexOf("\\"));
  if (cut < 0) return trimmed;

  const head = trimmed.slice(0, cut);
  if (/^[A-Za-z]:$/.test(head)) return `${head}\\`;
  return head || "/";
}
