const ROOT_ID = "codeat-floating-root";
const POLL_MS = 12000;

let rootEl;
let bodyEl;
let statusEl;
let analyzeAgainBtn;
let pending = false;
let collapsed = false;

init();

function init() {
  ensureWidget();
  refresh(true);
  setInterval(() => refresh(false), POLL_MS);

  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) {
      refresh(false);
    }
  });
}

function ensureWidget() {
  if (document.getElementById(ROOT_ID)) {
    return;
  }

  const style = document.createElement("style");
  style.textContent = `
    #${ROOT_ID} {
      position: fixed;
      top: 96px;
      right: 20px;
      z-index: 2147483647;
      width: 280px;
      font-family: "Segoe UI", Tahoma, sans-serif;
      color: #0f172a;
    }
    #${ROOT_ID}.hidden { display: none; }
    #${ROOT_ID} .panel {
      border: 1px solid #cbd5e1;
      border-radius: 12px;
      overflow: hidden;
      background: linear-gradient(165deg, #ffffff 0%, #f8fafc 100%);
      box-shadow: 0 8px 24px rgba(15, 23, 42, 0.16);
    }
    #${ROOT_ID} .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 12px;
      background: #0b3f8a;
      color: #fff;
      font-weight: 600;
      font-size: 13px;
      cursor: pointer;
    }
    #${ROOT_ID} .head-right {
      display: flex;
      gap: 8px;
      align-items: center;
      font-size: 11px;
      opacity: 0.95;
    }
    #${ROOT_ID} .pill {
      padding: 2px 7px;
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.2);
      border: 1px solid rgba(255, 255, 255, 0.35);
    }
    #${ROOT_ID} .body {
      padding: 10px 12px;
      display: grid;
      gap: 8px;
    }
    #${ROOT_ID} .metric-row {
      display: flex;
      gap: 8px;
    }
    #${ROOT_ID} .metric {
      flex: 1;
      border: 1px solid #dbe3ef;
      border-radius: 10px;
      padding: 8px;
      background: #fff;
    }
    #${ROOT_ID} .metric .label {
      font-size: 11px;
      color: #475569;
      margin-bottom: 4px;
    }
    #${ROOT_ID} .metric .value {
      font-size: 18px;
      font-weight: 700;
      color: #0f172a;
      line-height: 1;
    }
    #${ROOT_ID} .line {
      font-size: 12px;
      line-height: 1.35;
      color: #1e293b;
    }
    #${ROOT_ID} .status {
      font-size: 11px;
      color: #64748b;
    }
    #${ROOT_ID} .actions {
      display: flex;
      justify-content: flex-end;
    }
    #${ROOT_ID} .analyze-btn {
      border: 1px solid #bfdbfe;
      background: #eff6ff;
      color: #1e3a8a;
      border-radius: 8px;
      font-size: 11px;
      font-weight: 600;
      padding: 5px 8px;
      cursor: pointer;
    }
    #${ROOT_ID} .analyze-btn:disabled {
      opacity: 0.65;
      cursor: not-allowed;
    }
    #${ROOT_ID} .error {
      color: #b91c1c;
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
          <span id="codeat-toggle">Hide</span>
        </span>
      </div>
      <div class="body" id="codeat-body">
        <div class="metric-row">
          <div class="metric"><div class="label">Correctness</div><div class="value" id="codeat-accuracy">--</div></div>
          <div class="metric"><div class="label">Confidence</div><div class="value" id="codeat-confidence">--</div></div>
        </div>
        <div class="line" id="codeat-problem">Problem: -</div>
        <div class="line" id="codeat-feedback">Feedback: Waiting for analysis...</div>
        <div class="actions"><button id="codeat-analyze-again" class="analyze-btn" type="button">Analyze Again</button></div>
        <div class="status" id="codeat-status">Initializing...</div>
      </div>
    </div>
  `;

  document.documentElement.appendChild(rootEl);

  bodyEl = rootEl.querySelector("#codeat-body");
  statusEl = rootEl.querySelector("#codeat-status");
  analyzeAgainBtn = rootEl.querySelector("#codeat-analyze-again");

  rootEl.querySelector("#codeat-head").addEventListener("click", () => {
    collapsed = !collapsed;
    bodyEl.style.display = collapsed ? "none" : "grid";
    rootEl.querySelector("#codeat-toggle").textContent = collapsed ? "Show" : "Hide";
  });

  analyzeAgainBtn.addEventListener("click", async (event) => {
    event.stopPropagation();
    await refresh(true);
  });
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

    if (!response || response.status === "unsupported" || response.status === "no_code") {
      hideWidget();
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

    hideWidget();
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
  const problemEl = rootEl.querySelector("#codeat-problem");
  const feedbackEl = rootEl.querySelector("#codeat-feedback");

  const verdict = (result.leetcodeLikelyVerdict || "N/A").toUpperCase();
  const accuracyText = typeof result.accuracyPercentage === "number" ? `${Math.round(result.accuracyPercentage)}%` : "--";
  const confidenceText = typeof result.confidencePercentage === "number" ? `${Math.round(result.confidencePercentage)}%` : "--";

  accuracyEl.textContent = accuracyText;
  confidenceEl.textContent = confidenceText;
  verdictEl.textContent = verdict;
  problemEl.textContent = `Problem: ${result.matchedProblemTitle || result.matchedProblemId || "Unknown"}`;
  feedbackEl.textContent = `Feedback: ${truncate(result.feedback || "No feedback returned.", 140)}`;

  verdictEl.style.background = verdictColor(verdict);
  setStatus(updatedAt ? `Updated ${new Date(updatedAt).toLocaleTimeString()}` : "Updated", false);
}

function verdictColor(verdict) {
  if (verdict === "PASS") return "rgba(22, 163, 74, 0.35)";
  if (verdict === "MAY_PASS") return "rgba(234, 88, 12, 0.35)";
  if (verdict === "FAIL") return "rgba(220, 38, 38, 0.35)";
  return "rgba(59, 130, 246, 0.35)";
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

function truncate(value, max) {
  if (!value || value.length <= max) {
    return value || "";
  }
  return `${value.slice(0, max - 1)}...`;
}
