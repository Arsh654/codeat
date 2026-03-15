const ROOT_ID = "codeat-floating-root";
const POSITION_KEY = "codeat.widget.position.v1";

let rootEl;
let bodyEl;
let statusEl;
let analyzeAgainBtn;
let pending = false;
let collapsed = false;
let suppressNextToggle = false;
let feedbackExpanded = false;
let lastFeedback = "";
let lastResult = null;

const DRAG_THRESHOLD_PX = 5;

init();

function init() {
  ensureWidget();
  applyBuildVersion();
  restorePosition();
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
      width: min(382px, calc(100vw - 24px));
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
    #${ROOT_ID} .head-left {
      display: flex;
      align-items: baseline;
      gap: 8px;
      min-width: 0;
    }
    #${ROOT_ID} .build-tag {
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.06em;
      opacity: 0.9;
      border: 1px solid rgba(255, 255, 255, 0.35);
      border-radius: 999px;
      padding: 2px 6px;
      line-height: 1;
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
    #${ROOT_ID} .meta-row {
      display: grid;
      grid-template-columns: 1fr 1fr;
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
    #${ROOT_ID} .line-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
      margin-bottom: 6px;
    }
    #${ROOT_ID} .line .k {
      font-size: 10px;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: #5d7162;
      font-weight: 700;
      margin-bottom: 0;
    }
    #${ROOT_ID} .line .v {
      font-size: 12px;
      color: #2f4335;
      line-height: 1.45;
      word-break: break-word;
    }
    #${ROOT_ID} .line-btns {
      display: flex;
      gap: 6px;
      align-items: center;
    }
    #${ROOT_ID} .chip-btn {
      border: 1px solid #c5d7bf;
      background: #f2f8f0;
      color: #2d4e3f;
      border-radius: 999px;
      font-size: 10px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      padding: 4px 8px;
      line-height: 1;
      cursor: pointer;
    }
    #${ROOT_ID} .chip-btn:hover {
      background: #e8f2e5;
    }
    #${ROOT_ID} .more-wrap {
      border: 1px dashed #c9d8c5;
      border-radius: 12px;
      padding: 8px 10px;
      background: rgba(245, 251, 243, 0.7);
    }
    #${ROOT_ID} .more-header {
      display: flex;
      justify-content: flex-end;
      margin-bottom: 6px;
    }
    #${ROOT_ID} .more-body {
      display: none;
      gap: 8px;
      grid-template-columns: 1fr;
    }
    #${ROOT_ID} .more-body.open {
      display: grid;
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
        width: min(340px, calc(100vw - 16px));
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
        <span class="head-left">
          <span>Codeat Insight</span>
          <span class="build-tag" id="codeat-build">v?.?.?</span>
        </span>
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
        <div class="meta-row">
          <div class="line">
            <div class="k">LLM Used</div>
            <div class="v" id="codeat-model">N/A</div>
          </div>
          <div class="line">
            <div class="k">Analyzed At</div>
            <div class="v" id="codeat-updated-at">--</div>
          </div>
        </div>
        <div class="line">
          <div class="line-head">
            <div class="k">Feedback</div>
            <div class="line-btns">
              <button id="codeat-feedback-copy" class="chip-btn" type="button">Copy</button>
              <button id="codeat-feedback-toggle" class="chip-btn" type="button">Expand</button>
            </div>
          </div>
          <div class="v" id="codeat-feedback">Waiting for analysis...</div>
        </div>
        <div class="more-wrap">
          <div class="more-header">
            <button id="codeat-more-toggle" class="chip-btn" type="button">Show More Metrics</button>
          </div>
          <div class="more-body" id="codeat-more-body">
            <div class="line"><div class="k">Compile Likely Valid</div><div class="v" id="codeat-compile">--</div></div>
            <div class="line"><div class="k">Style Score</div><div class="v" id="codeat-style-score">--</div></div>
            <div class="line"><div class="k">Review Summary</div><div class="v" id="codeat-review-summary">--</div></div>
          </div>
        </div>
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
  rootEl.querySelector("#codeat-feedback-copy").addEventListener("click", onCopyFeedback);
  rootEl.querySelector("#codeat-feedback-toggle").addEventListener("click", onToggleFeedback);
  rootEl.querySelector("#codeat-more-toggle").addEventListener("click", onToggleMoreMetrics);

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
      persistPosition();
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
  const modelEl = rootEl.querySelector("#codeat-model");
  const updatedAtEl = rootEl.querySelector("#codeat-updated-at");
  const compileEl = rootEl.querySelector("#codeat-compile");
  const styleScoreEl = rootEl.querySelector("#codeat-style-score");
  const reviewSummaryEl = rootEl.querySelector("#codeat-review-summary");

  const verdict = (result.leetcodeLikelyVerdict || "N/A").toUpperCase();
  const accuracyText = typeof result.accuracyPercentage === "number" ? `${Math.round(result.accuracyPercentage)}%` : "--";
  const confidenceText = typeof result.confidencePercentage === "number" ? `${Math.round(result.confidencePercentage)}%` : "--";

  accuracyEl.textContent = accuracyText;
  confidenceEl.textContent = confidenceText;
  verdictEl.textContent = verdict;
  modelEl.textContent = (result.modelUsed || "").trim() || "N/A";
  updatedAtEl.textContent = formatUpdatedAt(updatedAt);
  lastResult = result;
  lastFeedback = (result.feedback || "No feedback returned.").trim();
  feedbackEl.textContent = feedbackExpanded ? lastFeedback : truncate(lastFeedback, 180);
  compileEl.textContent = typeof result.compileLikelyValid === "boolean" ? (result.compileLikelyValid ? "Yes" : "No") : "N/A";
  styleScoreEl.textContent = typeof result.styleScorePercentage === "number" ? `${Math.round(result.styleScorePercentage)}%` : "N/A";
  reviewSummaryEl.textContent = (result.reviewSummary || "").trim() || "No review summary available.";
  syncFeedbackToggleLabel();

  verdictEl.style.background = verdictColor(verdict);
  setStatus(updatedAt ? `Updated ${new Date(updatedAt).toLocaleTimeString()}` : "Updated", false);
}

function formatUpdatedAt(updatedAt) {
  if (!updatedAt) {
    return "--";
  }
  const parsed = new Date(updatedAt);
  if (Number.isNaN(parsed.getTime())) {
    return "--";
  }
  return parsed.toLocaleTimeString();
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

function onToggleFeedback(event) {
  event.stopPropagation();
  feedbackExpanded = !feedbackExpanded;
  const feedbackEl = rootEl.querySelector("#codeat-feedback");
  if (feedbackEl) {
    feedbackEl.textContent = feedbackExpanded ? (lastFeedback || "No feedback returned.") : truncate(lastFeedback || "No feedback returned.", 180);
  }
  syncFeedbackToggleLabel();
}

async function onCopyFeedback(event) {
  event.stopPropagation();
  const text = (lastFeedback || "").trim();
  if (!text) {
    setStatus("No feedback to copy.", true);
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    setStatus("Feedback copied.");
  } catch (err) {
    setStatus("Copy failed. Clipboard blocked on this page.", true);
  }
}

function onToggleMoreMetrics(event) {
  event.stopPropagation();
  const body = rootEl.querySelector("#codeat-more-body");
  const btn = rootEl.querySelector("#codeat-more-toggle");
  if (!body || !btn) {
    return;
  }
  const isOpen = body.classList.toggle("open");
  btn.textContent = isOpen ? "Hide More Metrics" : "Show More Metrics";
}

function syncFeedbackToggleLabel() {
  const toggleBtn = rootEl.querySelector("#codeat-feedback-toggle");
  if (toggleBtn) {
    toggleBtn.textContent = feedbackExpanded ? "Collapse" : "Expand";
  }
}

function applyBuildVersion() {
  const buildEl = rootEl?.querySelector("#codeat-build");
  if (!buildEl) {
    return;
  }
  try {
    const version = chrome.runtime.getManifest()?.version || "?.?.?";
    buildEl.textContent = `v${version}`;
  } catch (err) {
    buildEl.textContent = "v?.?.?";
  }
}

async function persistPosition() {
  if (!rootEl) {
    return;
  }
  const left = parseFloat(rootEl.style.left);
  const top = parseFloat(rootEl.style.top);
  if (!Number.isFinite(left) || !Number.isFinite(top)) {
    return;
  }
  try {
    await chrome.storage.local.set({ [POSITION_KEY]: { left, top } });
  } catch (err) {
    // Ignore storage errors
  }
}

async function restorePosition() {
  try {
    const stored = await chrome.storage.local.get(POSITION_KEY);
    const pos = stored?.[POSITION_KEY];
    if (!pos || !Number.isFinite(pos.left) || !Number.isFinite(pos.top)) {
      return;
    }
    const rect = rootEl.getBoundingClientRect();
    const maxLeft = Math.max(0, window.innerWidth - rect.width);
    const maxTop = Math.max(0, window.innerHeight - rect.height);
    rootEl.style.right = "auto";
    rootEl.style.bottom = "auto";
    rootEl.style.left = `${clamp(pos.left, 0, maxLeft)}px`;
    rootEl.style.top = `${clamp(pos.top, 0, maxTop)}px`;
  } catch (err) {
    // Ignore storage errors
  }
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
