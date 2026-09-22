import { el } from "./dom.js";

const THEME_KEY = "moonrat-theme";
const ACCENT_KEY = "moonrat-accent";

const THEMES = [
  { id: "nord", dots: ["#a9c4d8", "#d98a9a", "#0c0c0e"] },
  { id: "blood", dots: ["#e08a8a", "#a9c4d8", "#0c0c0e"] },
  { id: "moss", dots: ["#9ec49a", "#d98a9a", "#0c0c0e"] },
  { id: "amber", dots: ["#d8bd8a", "#d98a9a", "#0c0c0e"] },
  { id: "paper", dots: ["#38678c", "#ab3f52", "#f4f5f7"] },
];

const ACCENTS = [
  "#a9c4d8", "#8ad0c2", "#9ec49a", "#d8bd8a",
  "#e08a8a", "#d98a9a", "#c9a2e0", "#7fa3e0",
];

const BACKGROUNDS = {
  nord: "#070709",
  blood: "#070709",
  moss: "#070709",
  amber: "#070709",
  paper: "#eceef1",
};

const HEX = /^#[0-9a-f]{6}$/i;

function soft(hex, alpha) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

function readTheme() {
  const saved = localStorage.getItem(THEME_KEY);
  return THEMES.some((item) => item.id === saved) ? saved : "nord";
}

function readAccent() {
  const saved = localStorage.getItem(ACCENT_KEY);
  return saved && HEX.test(saved) ? saved.toLowerCase() : null;
}

function applyAccent(hex) {
  const root = document.documentElement.style;
  if (hex) {
    root.setProperty("--fjord", hex);
    root.setProperty("--fjord-soft", soft(hex, 0.14));
  } else {
    root.removeProperty("--fjord");
    root.removeProperty("--fjord-soft");
  }
}

function syncMeta(theme) {
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.content = BACKGROUNDS[theme] ?? "#070709";
}

export function applySavedTheme() {
  const theme = readTheme();
  document.documentElement.dataset.theme = theme;
  applyAccent(readAccent());
  syncMeta(theme);
  return theme;
}

export function initThemePicker(button) {
  applySavedTheme();

  let theme = readTheme();
  let accent = readAccent();
  let open = false;

  const wrap = el("div", { class: "theme-wrap" });
  button.replaceWith(wrap);

  button.textContent = "";
  button.setAttribute("aria-haspopup", "true");
  const label = el("span");
  button.append(label);

  const swatches = THEMES.map((item) => {
    const swatch = el(
      "button",
      { type: "button", class: "theme-swatch" },
      el("span", { class: "dots" }, item.dots.map((color) => el("i", { style: `background:${color}` }))),
      el("span", { class: "name" }, item.id),
    );
    swatch.addEventListener("click", () => {
      theme = item.id;
      localStorage.setItem(THEME_KEY, theme);
      document.documentElement.dataset.theme = theme;
      syncMeta(theme);
      render();
    });
    return swatch;
  });

  const presetDots = ACCENTS.map((color) => {
    const preset = el("button", { type: "button", class: "accent-dot", style: `background:${color}`, title: color });
    preset.addEventListener("click", () => setAccent(color, color));
    return preset;
  });

  const colorInput = el("input", {
    type: "color",
    class: "color-field",
    value: accent ?? "#a9c4d8",
  });
  colorInput.addEventListener("input", () => setAccent(colorInput.value.toLowerCase(), null));

  const reset = el("button", { type: "button", class: "action mini" }, "reset accent");
  reset.addEventListener("click", () => setAccent(null, "#a9c4d8"));

  function setAccent(hex, input) {
    accent = hex;
    if (hex) localStorage.setItem(ACCENT_KEY, hex);
    else localStorage.removeItem(ACCENT_KEY);
    applyAccent(hex);
    if (input) colorInput.value = input;
    render();
  }

  const pop = el(
    "div",
    { class: "theme-pop" },
    el("span", { class: "pop-label" }, "theme"),
    el("div", { class: "theme-grid" }, swatches),
    el("span", { class: "pop-label" }, "accent"),
    el("div", { class: "accent-row" }, presetDots),
    el("div", { class: "pop-row" }, colorInput, reset),
  );

  wrap.append(button, pop);

  function render() {
    swatches.forEach((swatch, index) => swatch.classList.toggle("on", THEMES[index].id === theme));
    presetDots.forEach((preset, index) => preset.classList.toggle("on", ACCENTS[index] === accent));
    pop.classList.toggle("on", open);
    button.classList.toggle("open", open);
    button.setAttribute("aria-expanded", String(open));
    label.textContent = "theme: " + theme;
  }

  function setOpen(next) {
    open = next;
    render();
  }

  button.addEventListener("click", (event) => {
    event.stopPropagation();
    setOpen(!open);
  });

  pop.addEventListener("click", (event) => event.stopPropagation());
  document.addEventListener("click", () => setOpen(false));
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") setOpen(false);
  });

  render();
}
