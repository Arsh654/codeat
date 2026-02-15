const DEFAULT_SETTINGS = {
  apiBaseUrl: "http://localhost:3502",
  analyzePath: "/api/v1/analyze"
};

const tabCache = new Map();

chrome.runtime.onInstalled.addListener(() => {
  chrome.action.setBadgeBackgroundColor({ color: "#0b3f8a" });
});

chrome.tabs.onActivated.addListener(async ({ tabId }) => {
  await analyzeTabAndUpdateBadge(tabId, false);
});

chrome.tabs.onUpdated.addListener(async (tabId, changeInfo, tab) => {
  if (changeInfo.status !== "complete") {
    return;
  }
  if (!tab.active) {
    return;
  }
  await analyzeTabAndUpdateBadge(tabId, false);
});

chrome.tabs.onRemoved.addListener((tabId) => {
  tabCache.delete(tabId);
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "codeat:get-analysis") {
    (async () => {
      try {
        const tabId = sender?.tab?.id ?? message.tabId;
        if (typeof tabId !== "number") {
          sendResponse({ status: "error", error: "No tab context" });
          return;
        }
        const response = await analyzeTabAndUpdateBadge(tabId, Boolean(message.force));
        sendResponse(response);
      } catch (err) {
        sendResponse({ status: "error", error: err.message || "Unknown error" });
      }
    })();
    return true;
  }

  return false;
});

async function analyzeTabAndUpdateBadge(tabId, force) {
  try {
    const tab = await chrome.tabs.get(tabId);
    if (!isAnalyzableUrl(tab?.url || "")) {
      clearBadge(tabId);
      return { status: "unsupported" };
    }

    const extracted = await extractContextFromTab(tabId);
    if (!extracted || !extracted.sourceCode || extracted.sourceCode.length < 20) {
      clearBadge(tabId);
      tabCache.delete(tabId);
      return { status: "no_code" };
    }

    const fingerprint = fingerprintCode(extracted.sourceCode);
    const cached = tabCache.get(tabId);
    if (!force && cached && cached.fingerprint === fingerprint) {
      await renderBadge(tabId, cached.result);
      return {
        status: "ok",
        cached: true,
        result: cached.result,
        extracted: cached.extracted,
        updatedAt: cached.updatedAt
      };
    }

    const result = await requestAnalyze(extracted);
    const entry = {
      fingerprint,
      result,
      extracted,
      updatedAt: new Date().toISOString()
    };
    tabCache.set(tabId, entry);
    await renderBadge(tabId, result);

    return {
      status: "ok",
      cached: false,
      result,
      extracted,
      updatedAt: entry.updatedAt
    };
  } catch (err) {
    await chrome.action.setBadgeText({ tabId, text: "ERR" });
    await chrome.action.setBadgeBackgroundColor({ tabId, color: "#b91c1c" });
    await chrome.action.setTitle({ tabId, title: `Codeat: ${err.message || "Analyze failed"}` });
    return { status: "error", error: err.message || "Analyze failed" };
  }
}

async function extractContextFromTab(tabId) {
  const [{ result }] = await chrome.scripting.executeScript({
    target: { tabId },
    func: () => {
      const cleanup = (value) =>
        (value || "")
          .replace(/\u00a0/g, " ")
          .replace(/\n{3,}/g, "\n\n")
          .trim();

      const pickLongest = (values) => values.filter(Boolean).sort((a, b) => b.length - a.length)[0] || "";

      const fromTextarea = pickLongest(
        Array.from(document.querySelectorAll("textarea")).map((el) => el.value || "")
      );

      const fromMonaco = pickLongest(
        Array.from(document.querySelectorAll(".view-lines, .monaco-editor .lines-content")).map((el) => el.innerText || "")
      );

      const fromCodeBlocks = pickLongest(
        Array.from(document.querySelectorAll("pre, code, .cm-content")).map((el) => el.innerText || "")
      );

      const sourceCandidate = pickLongest([
        fromTextarea,
        fromMonaco,
        fromCodeBlocks
      ]).trim();

      const sourceCode = cleanup(sourceCandidate);
      const classMatch = sourceCode.match(/\bclass\s+([A-Za-z_][A-Za-z0-9_]*)/);
      const className = classMatch ? classMatch[1] : "";

      const heading = cleanup(document.querySelector("h1")?.innerText || "");
      const bodySnippet = cleanup(
        Array.from(document.querySelectorAll(".question-content, .content__u3I1, .elfjS, article")).map((el) => el.innerText || "").join("\n")
      );
      const problemStatement = bodySnippet || heading;

      const url = window.location.href;
      const leetcodeMatch = url.match(/\/problems\/([^/]+)/);
      const problemId = leetcodeMatch ? leetcodeMatch[1] : "";

      return {
        sourceCode,
        className,
        problemStatement,
        problemId
      };
    }
  });

  return result;
}

async function requestAnalyze(extracted) {
  const settings = await chrome.storage.sync.get(Object.keys(DEFAULT_SETTINGS));
  const apiBaseUrl = (settings.apiBaseUrl || DEFAULT_SETTINGS.apiBaseUrl).replace(/\/$/, "");
  const analyzePath = settings.analyzePath || DEFAULT_SETTINGS.analyzePath;
  const path = analyzePath.startsWith("/") ? analyzePath : `/${analyzePath}`;
  const url = `${apiBaseUrl}${path}`;

  const payload = {
    problemId: toNull(extracted.problemId),
    problemStatement: toNull(extracted.problemStatement),
    sourceCode: extracted.sourceCode,
    className: toNull(extracted.className)
  };

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
    throw new Error(data.error || `HTTP ${response.status}`);
  }

  return data;
}

async function renderBadge(tabId, result) {
  const accuracy = typeof result.accuracyPercentage === "number"
    ? Math.round(result.accuracyPercentage)
    : null;

  const text = accuracy == null ? "--" : `${Math.max(0, Math.min(100, accuracy))}%`;
  await chrome.action.setBadgeText({ tabId, text });

  const verdict = (result.leetcodeLikelyVerdict || "").toUpperCase();
  let color = "#0b3f8a";
  if (verdict === "PASS") color = "#15803d";
  if (verdict === "MAY_PASS") color = "#b45309";
  if (verdict === "FAIL") color = "#b91c1c";
  if (verdict === "UNCERTAIN") color = "#1d4ed8";
  await chrome.action.setBadgeBackgroundColor({ tabId, color });

  const title = [
    `Verdict: ${result.leetcodeLikelyVerdict || "N/A"}`,
    `Accuracy: ${accuracy == null ? "N/A" : `${accuracy}%`}`,
    `Confidence: ${typeof result.confidencePercentage === "number" ? `${Math.round(result.confidencePercentage)}%` : "N/A"}`
  ].join("\n");

  await chrome.action.setTitle({ tabId, title });
}

function clearBadge(tabId) {
  chrome.action.setBadgeText({ tabId, text: "" });
  chrome.action.setTitle({ tabId, title: "Codeat Analyzer" });
}

function isAnalyzableUrl(url) {
  if (!url) {
    return false;
  }
  return !url.startsWith("chrome://")
    && !url.startsWith("chrome-extension://")
    && !url.startsWith("devtools://")
    && !url.startsWith("edge://")
    && !url.startsWith("about:")
    && !url.startsWith("view-source:");
}

function fingerprintCode(sourceCode) {
  const normalized = sourceCode.replace(/\s+/g, " ").trim();
  return `${normalized.length}:${normalized.slice(0, 120)}`;
}

function toNull(value) {
  const trimmed = (value || "").trim();
  return trimmed ? trimmed : null;
}
