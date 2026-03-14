const ROOT_ID = "codeat-floating-root";

let rootEl;
let bodyEl;
let statusEl;
let analyzeAgainBtn;
let pending = false;
let collapsed = false;
let suppressNextToggle = false;

const DRAG_THRESHOLD_PX = 5;

init();

function init() {
  ensureWidget();
  showWidget();
  setStatus("Click Analyze to run.");
}

function ensureWidget() {
  const existingRoot = document.getElementById(ROOT_ID);
  if (existingRoot) {
    rootEl = existingRoot;
    bodyEl = rootEl.querySelector("#codeat-body");
    statusEl = rootEl.querySelector("#codeat-status");
    analyzeAgainBtn = rootEl.querySelector("#codeat-analyze-again");
    wireWidgetEvents();
    return;
  }

  const style = document.createElement("style");
  style.textContent = `
    #${ROOT_ID} {
      position: fixed;
      bottom: 16px;
      right: 16px;
      z-index: 2147483647;
      width: min(312px, calc(100vw - 24px));
      font-family: "Plus Jakarta Sans", "Manrope", "Avenir Next", "Segoe UI", sans-serif;
      color: #1f2f25;
    }
    #${ROOT_ID}.hidden { display: none; }
    #${ROOT_ID} .panel {
      border: 1px solid #d6e0d2;
      border-radius: 18px;
      overflow: hidden;
      backdrop-filter: blur(9px);
      background:
        radial-gradient(120% 110% at 100% 0%, rgba(245, 233, 206, 0.42) 0%, transparent 55%),
        linear-gradient(155deg, rgba(251, 254, 250, 0.92) 0%, rgba(243, 249, 241, 0.92) 100%);
      box-shadow: 0 16px 34px rgba(39, 62, 48, 0.22);
    }
    #${ROOT_ID} .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 13px;
      background: linear-gradient(160deg, #2f6f57 0%, #255946 100%);
      color: #f7fff9;
      font-weight: 600;
      font-size: 13px;
      cursor: pointer;
      letter-spacing: 0.01em;
    }
    #${ROOT_ID} .head-right {
      display: flex;
      gap: 8px;
      align-items: center;
      font-size: 10px;
      opacity: 0.96;
    }
    #${ROOT_ID} .toggle-btn {
      border: 1px solid rgba(255, 255, 255, 0.28);
      background: rgba(255, 255, 255, 0.08);
      color: #f7fff9;
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      line-height: 1;
      padding: 4px 8px;
      border-radius: 999px;
      cursor: pointer;
      text-decoration: none;
      transition: background-color 0.18s ease, transform 0.15s ease, border-color 0.18s ease, opacity 0.18s ease;
      opacity: 0.92;
    }
    #${ROOT_ID} .toggle-btn:hover {
      background: rgba(255, 255, 255, 0.18);
      border-color: rgba(255, 255, 255, 0.44);
      transform: translateY(-0.5px);
      opacity: 1;
    }
    #${ROOT_ID} .toggle-btn:focus-visible {
      outline: none;
      box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.25);
    }
    #${ROOT_ID} .pill {
      padding: 2px 8px;
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.26);
      border: 1px solid rgba(255, 255, 255, 0.4);
      font-weight: 700;
      letter-spacing: 0.05em;
    }
    #${ROOT_ID} .body {
      padding: 12px 13px;
      display: grid;
      gap: 10px;
    }
    #${ROOT_ID} .metric-row {
      display: flex;
      gap: 10px;
    }
    #${ROOT_ID} .metric {
      flex: 1;
      border: 1px solid #dbe7d8;
      border-radius: 12px;
      padding: 10px;
      background: #f8fcf7;
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.65);
      border-left: 4px solid #8ab59f;
    }
    #${ROOT_ID} .metric .label {
      font-size: 10px;
      color: #5d7162;
      margin: 0 0 6px 0;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      font-weight: 600;
    }
    #${ROOT_ID} .metric .value {
      font-size: 21px;
      font-weight: 700;
      color: #223629;
      line-height: 1;
      letter-spacing: -0.02em;
      margin-left: 0;
    }
    #${ROOT_ID} .line {
      font-size: 12px;
      line-height: 1.55;
      color: #2f4335;
      background: #f8fcf7;
      border: 1px solid #dbe7d8;
      border-radius: 12px;
      padding: 10px;
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.65);
      border-left: 4px solid #8ab59f;
    }
    #${ROOT_ID} .status {
      font-size: 11px;
      color: #5f7364;
    }
    #${ROOT_ID} .actions {
      display: flex;
      justify-content: flex-end;
    }
    #${ROOT_ID} .analyze-btn {
      border: 1px solid #a6c5b5;
      background: linear-gradient(170deg, #3a7d62 0%, #2c634d 100%);
      color: #f5fff8;
      border-radius: 10px;
      font-size: 11px;
      font-weight: 600;
      padding: 7px 10px;
      cursor: pointer;
      box-shadow: 0 8px 14px rgba(44, 99, 77, 0.24);
      transition: transform 0.15s ease, box-shadow 0.18s ease;
    }
    #${ROOT_ID} .analyze-btn:hover {
      transform: translateY(-1px);
      box-shadow: 0 11px 16px rgba(44, 99, 77, 0.28);
    }
    #${ROOT_ID} .analyze-btn:disabled {
      opacity: 0.65;
      cursor: not-allowed;
    }
    #${ROOT_ID} .error {
      color: #b6413e;
    }
    @media (max-width: 768px) {
      #${ROOT_ID} {
        right: 8px;
        bottom: 8px;
        width: min(300px, calc(100vw - 16px));
      }
    }
  `;
  document.documentElement.appendChild(style);

  rootEl = document.createElement("section");
  rootEl.id = ROOT_ID;
  rootEl.className = "hidden";
  rootEl.innerHTML = `
    <div class="panel">
      <div class="head" id="codeat-head">
        <span>Codeat Insight</span>
        <span class="head-right">
          <span class="pill" id="codeat-verdict">-</span>
          <button id="codeat-toggle" class="toggle-btn" type="button">Hide</button>
        </span>
      </div>
      <div class="body" id="codeat-body">
        <div class="metric-row">
          <div class="metric"><div class="label">Correctness</div><div class="value" id="codeat-accuracy">--</div></div>
          <div class="metric"><div class="label">Confidence</div><div class="value" id="codeat-confidence">--</div></div>
        </div>
        <div class="line" id="codeat-feedback">Feedback: Waiting for analysis...</div>
        <div class="actions"><button id="codeat-analyze-again" class="analyze-btn" type="button">Analyze</button></div>
        <div class="status" id="codeat-status">Click Analyze to run.</div>
      </div>
    </div>
  `;

  document.documentElement.appendChild(rootEl);

  bodyEl = rootEl.querySelector("#codeat-body");
  statusEl = rootEl.querySelector("#codeat-status");
  analyzeAgainBtn = rootEl.querySelector("#codeat-analyze-again");
  wireWidgetEvents();
}

function wireWidgetEvents() {
  if (!rootEl || rootEl.dataset.wired === "true") {
    return;
  }

  const headEl = rootEl.querySelector("#codeat-head");
  setupDrag(headEl);

  rootEl.querySelector("#codeat-toggle").addEventListener("click", (event) => {
    event.stopPropagation();
    if (suppressNextToggle) {
      suppressNextToggle = false;
      return;
    }
    collapsed = !collapsed;
    bodyEl.style.display = collapsed ? "none" : "grid";
    rootEl.querySelector("#codeat-toggle").textContent = collapsed ? "Show" : "Hide";
  });

  analyzeAgainBtn.addEventListener("click", async (event) => {
    event.stopPropagation();
    await refresh(true);
  });

  rootEl.dataset.wired = "true";
}

function setupDrag(handleEl) {
  let dragging = false;
  let moved = false;
  let pointerId = null;
  let pointerOffsetX = 0;
  let pointerOffsetY = 0;

  handleEl.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) {
      return;
    }
    if (event.target instanceof Element && event.target.closest("#codeat-toggle")) {
      return;
    }

    const rect = rootEl.getBoundingClientRect();
    pointerId = event.pointerId;
    pointerOffsetX = event.clientX - rect.left;
    pointerOffsetY = event.clientY - rect.top;
    dragging = true;
    moved = false;
    handleEl.setPointerCapture(pointerId);
  });

  handleEl.addEventListener("pointermove", (event) => {
    if (!dragging || event.pointerId !== pointerId) {
      return;
    }

    const rect = rootEl.getBoundingClientRect();
    const nextLeft = event.clientX - pointerOffsetX;
    const nextTop = event.clientY - pointerOffsetY;

    const deltaX = Math.abs(nextLeft - rect.left);
    const deltaY = Math.abs(nextTop - rect.top);
    if (!moved && deltaX < DRAG_THRESHOLD_PX && deltaY < DRAG_THRESHOLD_PX) {
      return;
    }

    moved = true;
    rootEl.style.right = "auto";
    rootEl.style.bottom = "auto";

    const maxLeft = Math.max(0, window.innerWidth - rect.width);
    const maxTop = Math.max(0, window.innerHeight - rect.height);
    rootEl.style.left = `${clamp(nextLeft, 0, maxLeft)}px`;
    rootEl.style.top = `${clamp(nextTop, 0, maxTop)}px`;
  });

  const endDrag = (event) => {
    if (!dragging || event.pointerId !== pointerId) {
      return;
    }
    dragging = false;
    if (moved) {
      suppressNextToggle = true;
    }
    handleEl.releasePointerCapture(pointerId);
    pointerId = null;
  };

  handleEl.addEventListener("pointerup", endDrag);
  handleEl.addEventListener("pointercancel", endDrag);
}

async function refresh(force) {
  if (pending) {
    return;
  }
  pending = true;
  if (analyzeAgainBtn) {
    analyzeAgainBtn.disabled = true;
  }

  try {
    setStatus(force ? "Analyzing current code..." : "Refreshing...");
    const response = await chrome.runtime.sendMessage({
      type: "codeat:get-analysis",
      force
    });

    if (!response || response.status === "unsupported") {
      showWidget();
      setStatus("Page not supported for analysis.", true);
      return;
    }

    if (response.status === "no_code") {
      showWidget();
      setStatus("No sufficient code detected.", true);
      return;
    }

    if (response.status === "error") {
      showWidget();
      setStatus(response.error || "Analysis error", true);
      return;
    }

    if (response.status === "ok") {
      showWidget();
      renderResult(response.result, response.updatedAt);
      return;
    }
    setStatus("No analysis response.", true);
  } catch (err) {
    showWidget();
    setStatus(err.message || "Failed to refresh", true);
  } finally {
    pending = false;
    if (analyzeAgainBtn) {
      analyzeAgainBtn.disabled = false;
    }
  }
}

function renderResult(result, updatedAt) {
  const accuracyEl = rootEl.querySelector("#codeat-accuracy");
  const confidenceEl = rootEl.querySelector("#codeat-confidence");
  const verdictEl = rootEl.querySelector("#codeat-verdict");
  const feedbackEl = rootEl.querySelector("#codeat-feedback");

  const verdict = (result.leetcodeLikelyVerdict || "N/A").toUpperCase();
  const accuracyText = typeof result.accuracyPercentage === "number" ? `${Math.round(result.accuracyPercentage)}%` : "--";
  const confidenceText = typeof result.confidencePercentage === "number" ? `${Math.round(result.confidencePercentage)}%` : "--";

  accuracyEl.textContent = accuracyText;
  confidenceEl.textContent = confidenceText;
  verdictEl.textContent = verdict;
  feedbackEl.textContent = `Feedback: ${truncate(result.feedback || "No feedback returned.", 140)}`;

  verdictEl.style.background = verdictColor(verdict);
  setStatus(updatedAt ? `Updated ${new Date(updatedAt).toLocaleTimeString()}` : "Updated", false);
}

function verdictColor(verdict) {
  if (verdict === "PASS") return "rgba(22, 163, 74, 0.45)";
  if (verdict === "MAY_PASS") return "rgba(217, 119, 6, 0.45)";
  if (verdict === "FAIL") return "rgba(220, 38, 38, 0.45)";
  return "rgba(30, 136, 120, 0.4)";
}

function setStatus(message, isError = false) {
  statusEl.textContent = message;
  statusEl.classList.toggle("error", Boolean(isError));
}

function showWidget() {
  rootEl.classList.remove("hidden");
}

function hideWidget() {
  rootEl.classList.add("hidden");
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function truncate(value, max) {
  if (!value || value.length <= max) {
    return value || "";
  }
  return `${value.slice(0, max - 1)}...`;
}
