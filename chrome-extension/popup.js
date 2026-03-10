const DEFAULT_SETTINGS = {
  apiBaseUrl: "http://localhost:3502",
  analyzePath: "/api/v1/analyze"
};

const els = {
  className: document.getElementById("className"),
  sourceCode: document.getElementById("sourceCode"),
  analyze: document.getElementById("analyze"),
  analyzeNow: document.getElementById("analyzeNow"),
  analyzeAgain: document.getElementById("analyzeAgain"),
  extractCode: document.getElementById("extractCode"),
  openOptions: document.getElementById("openOptions"),
  status: document.getElementById("status"),
  resultCard: document.getElementById("resultCard"),
  accuracyValue: document.getElementById("accuracyValue"),
  confidenceValue: document.getElementById("confidenceValue"),
  verdictPill: document.getElementById("verdictPill"),
  compileLikely: document.getElementById("compileLikely"),
  feedbackText: document.getElementById("feedbackText"),
  strengthList: document.getElementById("strengthList"),
  improvementList: document.getElementById("improvementList"),
  scenarioList: document.getElementById("scenarioList"),
  reviewSection: document.getElementById("reviewSection"),
  reviewSummaryText: document.getElementById("reviewSummaryText"),
  styleScoreValue: document.getElementById("styleScoreValue"),
  styleFindingList: document.getElementById("styleFindingList"),
  reviewSuggestionList: document.getElementById("reviewSuggestionList")
};

let settings = { ...DEFAULT_SETTINGS };
let hiddenContext = {
  problemId: "",
  problemStatement: ""
};

init().catch((err) => {
  setStatus(`Init error: ${err.message}`, true);
});

async function init() {
  const stored = await chrome.storage.sync.get(Object.keys(DEFAULT_SETTINGS));
  settings = {
    apiBaseUrl: stored.apiBaseUrl || DEFAULT_SETTINGS.apiBaseUrl,
    analyzePath: stored.analyzePath || DEFAULT_SETTINGS.analyzePath
  };

  wireEvents();

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!isAnalyzableUrl(tab?.url || "")) {
    setStatus("Open a supported coding platform (e.g. LeetCode) to auto-detect code.");
    return;
  }

  setStatus("Detecting code from active tab...");

  const extracted = await extractContextFromActiveTab();
  if (!extracted.sourceCode) {
    setStatus("No code auto-detected. Use Extract From Tab or paste code.");
    clearResult();
    return;
  }

  if (extracted.sourceCode.length < 20) {
    populateForm(extracted);
    setStatus("Code too short to analyze meaningfully.", true);
    clearResult();
    return;
  }

  populateForm(extracted);
  setStatus("Code detected. Click 'Analyze' to start analysis.");
  clearResult();
}

function isAnalyzableUrl(url) {
  if (!url) {
    return false;
  }
  const supportedPlatforms = [
    "leetcode.com",
    "hackerrank.com",
    "geeksforgeeks.org",
    "codeforces.com",
    "interviewbit.com",
    "lintcode.com",
    "localhost",
    "127.0.0.1"
  ];
  try {
    const parsedUrl = new URL(url);
    const hostname = parsedUrl.hostname.toLowerCase();
    return supportedPlatforms.some(platform => hostname === platform || hostname.endsWith("." + platform));
  } catch (e) {
    return false;
  }
}

function wireEvents() {
  els.analyze.addEventListener("click", onAnalyze);
  els.analyzeNow.addEventListener("click", onAnalyzeAgain);
  els.analyzeAgain.addEventListener("click", onAnalyzeAgain);
  els.extractCode.addEventListener("click", onExtractCode);
  els.openOptions.addEventListener("click", () => chrome.runtime.openOptionsPage());
}

async function onAnalyzeAgain() {
  setStatus("Running fresh analysis...");
  disableActions(true);

  try {
    const extracted = await extractContextFromActiveTab();
    if (extracted.sourceCode) {
      els.sourceCode.value = extracted.sourceCode;
    }
    if (extracted.className) {
      els.className.value = extracted.className;
    }
    await onAnalyze();
  } finally {
    disableActions(false);
  }
}

async function onAnalyze() {
  const sourceCode = els.sourceCode.value.trim();
  if (!sourceCode) {
    setStatus("Source code is required.", true);
    clearResult();
    return;
  }

  if (sourceCode.length < 20) {
    setStatus("Code too short to analyze meaningfully.", true);
    clearResult();
    return;
  }

  setStatus("Analyzing...");
  disableActions(true);

  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });

    if (tab?.id && isAnalyzableUrl(tab.url || "")) {
      const response = await chrome.runtime.sendMessage({
        type: "codeat:get-analysis",
        tabId: tab.id,
        force: true
      });

      if (response && response.status === "ok" && response.result) {
        renderResult(response.result);
        setStatus("Analysis complete.");
        return;
      }

      if (response && response.status === "no_code") {
        setStatus("No sufficient code detected in the editor.", true);
        clearResult();
        return;
      }

      if (response && response.status === "error") {
        throw new Error(response.error || "Analysis failed");
      }
    }

    const payload = {
      problemId: nullIfBlank(hiddenContext.problemId),
      problemStatement: nullIfBlank(hiddenContext.problemStatement),
      sourceCode,
      className: nullIfBlank(els.className.value)
    };

    const url = buildAnalyzeUrl();
    const response = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify(payload)
    });

    const text = await response.text();
    const data = text ? JSON.parse(text) : {};

    if (!response.ok) {
      const message = data.error || `Request failed with ${response.status}`;
      throw new Error(message);
    }

    renderResult(data);
    setStatus("Analysis complete.");
  } catch (err) {
    setStatus(err.message || "Analyze failed.", true);
    clearResult();
  } finally {
    disableActions(false);
  }
}

async function onExtractCode() {
  setStatus("Extracting from active tab...");
  disableActions(true);

  try {
    const extracted = await extractContextFromActiveTab();
    if (!extracted.sourceCode) {
      throw new Error("No code detected. Paste code manually.");
    }

    populateForm(extracted);
    setStatus("Code and metadata extracted.");
  } catch (err) {
    setStatus(err.message || "Could not extract code.", true);
  } finally {
    disableActions(false);
  }
}

async function extractContextFromActiveTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) {
    throw new Error("No active tab found.");
  }

  const [{ result }] = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    func: () => {
      const cleanup = (value) =>
        (value || "")
          .replace(/\u00a0/g, " ")
          .replace(/\n{3,}/g, "\n\n")
          .trim();

      const pickLongest = (values) => values.filter(Boolean).sort((a, b) => b.length - a.length)[0] || "";

      const isLikelyJavaCode = (text) => {
        if (!text || text.length < 20) return false;
        const hasClass = /\bclass\s+\w+/.test(text);
        const hasMethod = /\b(public|private|protected|static)\s+\w+\s+\w+\s*\(/.test(text);
        const hasBraces = text.includes("{") && text.includes("}");
        return hasClass || (hasMethod && hasBraces);
      };

      const fromTextarea = pickLongest(
        Array.from(document.querySelectorAll("textarea")).map((el) => el.value || "").filter(isLikelyJavaCode)
      );

      const fromMonaco = pickLongest(
        Array.from(document.querySelectorAll(".view-lines, .monaco-editor .lines-content")).map((el) => el.innerText || "").filter(isLikelyJavaCode)
      );

      const fromCodeMirror = pickLongest(
        Array.from(document.querySelectorAll(".cm-content, .CodeMirror-code")).map((el) => el.innerText || "").filter(isLikelyJavaCode)
      );

      const sourceCandidate = pickLongest([
        fromTextarea,
        fromMonaco,
        fromCodeMirror
      ].filter(Boolean)).trim();

      const sourceCode = sourceCandidate ? cleanup(sourceCandidate) : pickLongest(
        Array.from(document.querySelectorAll("textarea")).map((el) => el.value || "")
      );

      const classMatch = sourceCode.match(/\bclass\s+([A-Za-z_][A-Za-z0-9_]*)/);
      const className = classMatch ? classMatch[1] : "";

      const heading = cleanup(document.querySelector("h1")?.innerText || "");
      const bodySnippet = cleanup(
        Array.from(document.querySelectorAll(".question-content, .content__u3I1, .elfjS, article")).map((el) => el.innerText || "").join("\n")
      );
      const problemStatement = bodySnippet || heading;

      const leetcodeMatch = window.location.href.match(/\/problems\/([^/]+)/);
      const problemId = leetcodeMatch ? leetcodeMatch[1] : "";

      return {
        sourceCode,
        className,
        problemStatement,
        problemId
      };
    }
  });

  return result || {
    sourceCode: "",
    className: "",
    problemStatement: "",
    problemId: ""
  };
}

function populateForm(extracted) {
  hiddenContext = {
    problemId: extracted.problemId || hiddenContext.problemId,
    problemStatement: extracted.problemStatement || hiddenContext.problemStatement
  };

  if (!els.sourceCode.value.trim() && extracted.sourceCode) {
    els.sourceCode.value = extracted.sourceCode;
  }
  if (!els.className.value.trim() && extracted.className) {
    els.className.value = extracted.className;
  }
}

function renderResult(data) {
  const verdict = (data.leetcodeLikelyVerdict || "UNCERTAIN").toUpperCase();

  els.accuracyValue.textContent = toPercent(data.accuracyPercentage);
  els.confidenceValue.textContent = toPercent(data.confidencePercentage);
  els.verdictPill.textContent = verdict;
  styleVerdict(els.verdictPill, verdict);

  els.compileLikely.textContent = typeof data.compileLikelyValid === "boolean"
    ? (data.compileLikelyValid ? "Yes" : "No")
    : "N/A";
  els.feedbackText.textContent = data.feedback || "No feedback provided.";

  renderList(els.strengthList, (data.strengths || []).slice(0, 3), "No strengths provided.");
  renderList(els.improvementList, (data.improvements || []).slice(0, 3), "No improvements provided.");

  const scenarios = (data.failingScenarios || []).slice(0, 2).map((item) => {
    const reason = item.reason || "Potential edge-case mismatch.";
    const input = item.inputExample ? `Input: ${item.inputExample}` : "";
    return [reason, input].filter(Boolean).join(" ");
  });
  renderList(els.scenarioList, scenarios, "No likely failing scenarios.");
  renderReviewSection(data);

  els.resultCard.classList.remove("hidden");
}

function clearResult() {
  els.resultCard.classList.add("hidden");
  els.accuracyValue.textContent = "N/A";
  els.confidenceValue.textContent = "N/A";
  els.verdictPill.textContent = "-";
  els.compileLikely.textContent = "N/A";
  els.feedbackText.textContent = "No analysis available.";
  renderList(els.strengthList, [], "No strengths provided.");
  renderList(els.improvementList, [], "No improvements provided.");
  renderList(els.scenarioList, [], "No likely failing scenarios.");
  clearReviewSection();
}

function renderReviewSection(data) {
  const hasSummary = Boolean((data.reviewSummary || "").trim());
  const hasStyleScore = typeof data.styleScorePercentage === "number";
  const styleFindings = (data.styleFindings || []).filter(Boolean).slice(0, 4);
  const reviewSuggestions = (data.reviewSuggestions || []).filter(Boolean).slice(0, 4);
  const hasReview = hasSummary || hasStyleScore || styleFindings.length > 0 || reviewSuggestions.length > 0;

  if (!hasReview) {
    clearReviewSection();
    return;
  }

  els.reviewSummaryText.textContent = hasSummary ? data.reviewSummary : "No review summary provided.";
  els.styleScoreValue.textContent = hasStyleScore ? toPercent(data.styleScorePercentage) : "N/A";
  renderList(els.styleFindingList, styleFindings, "No style findings.");
  renderList(els.reviewSuggestionList, reviewSuggestions, "No review suggestions.");
  els.reviewSection.classList.remove("hidden");
}

function clearReviewSection() {
  els.reviewSection.classList.add("hidden");
  els.reviewSummaryText.textContent = "No review summary provided.";
  els.styleScoreValue.textContent = "N/A";
  renderList(els.styleFindingList, [], "No style findings.");
  renderList(els.reviewSuggestionList, [], "No review suggestions.");
}

function renderList(container, items, fallback) {
  container.innerHTML = "";
  const values = items.filter(Boolean);
  if (values.length === 0) {
    const li = document.createElement("li");
    li.textContent = fallback;
    container.appendChild(li);
    return;
  }

  for (const item of values) {
    const li = document.createElement("li");
    li.textContent = item;
    container.appendChild(li);
  }
}

function styleVerdict(el, verdict) {
  let bg = "#ddf0e9";
  let fg = "#2d6b53";
  let border = "#a7cfbc";

  if (verdict === "PASS") {
    bg = "#d9f3e3";
    fg = "#1f6a3d";
    border = "#8dcfac";
  } else if (verdict === "MAY_PASS") {
    bg = "#fff1da";
    fg = "#8d4f17";
    border = "#f0c388";
  } else if (verdict === "FAIL") {
    bg = "#ffe5e5";
    fg = "#982727";
    border = "#e7aaaa";
  }

  el.style.background = bg;
  el.style.color = fg;
  el.style.borderColor = border;
}

function buildAnalyzeUrl() {
  const baseUrl = settings.apiBaseUrl.replace(/\/$/, "");
  const analyzePath = settings.analyzePath.startsWith("/")
    ? settings.analyzePath
    : `/${settings.analyzePath}`;
  return `${baseUrl}${analyzePath}`;
}

function toPercent(value) {
  if (typeof value !== "number") return "N/A";
  return `${value.toFixed(1)}%`;
}

function nullIfBlank(value) {
  const trimmed = (value || "").trim();
  return trimmed ? trimmed : null;
}

function disableActions(disabled) {
  els.analyze.disabled = disabled;
  els.analyzeNow.disabled = disabled;
  els.analyzeAgain.disabled = disabled;
  els.extractCode.disabled = disabled;
}

function setStatus(message, isError = false) {
  els.status.textContent = message;
  els.status.classList.toggle("error", Boolean(isError));
}

function fingerprintCode(sourceCode) {
  const normalized = (sourceCode || "").replace(/\s+/g, " ").trim();
  return `${normalized.length}:${normalized.slice(0, 120)}`;
}
