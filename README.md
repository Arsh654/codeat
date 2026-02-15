# Codeat API (Java DSA LLM Evaluator)

API service to evaluate Java DSA submissions using an LLM and return:
- matched problem guess
- accuracy and confidence percentages
- LeetCode-likely verdict (`PASS`, `MAY_PASS`, `FAIL`, `UNCERTAIN`)
- structured failing scenarios (when applicable)

## Tech Stack
- Java 17
- Spring Boot 4
- Maven

## Run Locally

```bash
./mvnw spring-boot:run
```

Service runs on port `3502` (see `src/main/resources/application.yml`).

## Environment Variables

```bash
# Provider: openai | groq
export LLM_PROVIDER=groq

# Use one of the keys below
export GROQ_API_KEY=your_groq_key
# or
export LLM_API_KEY=your_openai_key

# Optional
export LLM_MODEL=llama-3.3-70b-versatile
export LLM_API_URL= # optional override; provider defaults are auto-resolved
```

Provider defaults:
- `openai` -> `https://api.openai.com/v1/chat/completions`
- `groq` -> `https://api.groq.com/openai/v1/chat/completions`

## API Endpoints

### 1) List Problems
`GET /api/v1/problems`

Returns in-memory sample problems used as hints in MVP.

### 2) Analyze Submission
`POST /api/v1/analyze`

Request body:
```json
{
  "problemId": "1",
  "problemStatement": "optional",
  "className": "Solution",
  "sourceCode": "class Solution { ... }"
}
```

`problemId`, `problemStatement`, and `className` are optional hints.
`sourceCode` is required.

Response shape:
```json
{
  "matchedProblemId": "1",
  "matchedProblemTitle": "Two Sum",
  "accuracyPercentage": 100.0,
  "confidencePercentage": 96.0,
  "leetcodeLikelyVerdict": "PASS",
  "estimatedPassedTestCases": 100,
  "estimatedTotalTestCases": 100,
  "compileLikelyValid": true,
  "feedback": "...",
  "strengths": ["..."],
  "improvements": ["..."],
  "failingScenarios": [
    {
      "inputExample": "...",
      "expectedBehavior": "...",
      "predictedBehavior": "...",
      "reason": "..."
    }
  ],
  "modelUsed": "llama-3.3-70b-versatile"
}
```

## Verdict Meaning
- `PASS`: strong evidence code should pass hidden tests.
- `MAY_PASS`: high chance to pass, but not fully certain.
- `FAIL`: likely to fail hidden tests.
- `UNCERTAIN`: insufficient signal or conflicting signal.

## Notes on Scoring
- The service is LLM-first (no deterministic code execution engine in this MVP).
- A normalization layer is applied to reduce contradictory LLM outputs.
- Structured failing scenarios are filtered for low-quality/hallucinated entries.

## Tests

```bash
./mvnw test
```

## Chrome Extension (MVP)

A Chrome extension scaffold is available in `chrome-extension/`.

### Load in Chrome

1. Open `chrome://extensions`
2. Enable `Developer mode`
3. Click `Load unpacked`
4. Select the `chrome-extension` folder

### Configure

1. Open extension `Settings`
2. Set:
   - `API Base URL`: `http://localhost:3502`
   - `Analyze Path`: `/api/v1/analyze`

### Use

1. Open a coding page with visible source code.
2. The extension shows an in-page floating widget with correctness, confidence, verdict, and feedback.
3. Open the extension popup for curated insights (strengths, improvements, likely failing scenarios).
4. You can still click `Extract From Tab` or `Analyze` manually if needed.

The extension sends a request to your backend and displays relevant result signals in both the page widget and popup.

### Auto Badge Behavior

- Without opening the popup, the extension analyzes detected code on the active tab.
- The extension icon badge shows estimated correctness percentage (e.g. `92%`).
- Badge color maps to verdict:
  - Green: `PASS`
  - Orange: `MAY_PASS`
  - Red: `FAIL`

## Current Limitations
- LLM-based evaluation can still be noisy for edge cases.
- Problem matching is heuristic.
- Should not be used as final authoritative judge for paid/high-stakes decisions without deterministic execution checks.
