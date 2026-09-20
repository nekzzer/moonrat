import { $ } from "./lib/dom.js";
import { api } from "./lib/api.js";
import { applySavedTheme } from "./lib/themes.js";

applySavedTheme();

const keyInput = $("#key");
const error = $("#login-error");

async function submit() {
  const key = keyInput.value.trim();
  if (!key) return;

  error.textContent = "";

  try {
    await api.login(key);
    window.location.assign("/");
  } catch (failure) {
    error.textContent = failure.message;
    keyInput.select();
  }
}

$("#login-submit").addEventListener("click", submit);
keyInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") submit();
});
keyInput.focus();
