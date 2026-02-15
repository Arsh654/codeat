const DEFAULT_SETTINGS = {
  apiBaseUrl: "http://localhost:3502",
  analyzePath: "/api/v1/analyze"
};

const apiBaseUrlInput = document.getElementById("apiBaseUrl");
const analyzePathInput = document.getElementById("analyzePath");
const saveButton = document.getElementById("save");
const statusText = document.getElementById("status");

init().catch((err) => setStatus(`Init error: ${err.message}`, true));

async function init() {
  const stored = await chrome.storage.sync.get(Object.keys(DEFAULT_SETTINGS));
  apiBaseUrlInput.value = stored.apiBaseUrl || DEFAULT_SETTINGS.apiBaseUrl;
  analyzePathInput.value = stored.analyzePath || DEFAULT_SETTINGS.analyzePath;

  saveButton.addEventListener("click", onSave);
}

async function onSave() {
  const apiBaseUrl = (apiBaseUrlInput.value || "").trim();
  const analyzePath = (analyzePathInput.value || "").trim();

  if (!apiBaseUrl) {
    setStatus("API Base URL is required.", true);
    return;
  }

  if (!analyzePath) {
    setStatus("Analyze Path is required.", true);
    return;
  }

  await chrome.storage.sync.set({ apiBaseUrl, analyzePath });
  setStatus("Settings saved.");
}

function setStatus(message, isError = false) {
  statusText.textContent = message;
  statusText.classList.toggle("error", Boolean(isError));
}
