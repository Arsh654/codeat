# Contributing to Codeat

Thanks for contributing to Codeat.

## Before You Start

- Search existing issues/PRs before opening a new one.
- For larger features, open an issue first to align on approach.
- Keep changes focused and minimal.

## Development Setup

1. Start backend:

```bash
./mvnw spring-boot:run
```

2. Run tests:

```bash
./mvnw test
```

3. Load extension from `chrome-extension/` via `chrome://extensions` (`Load unpacked`).

## Branch and Commit Style

- Use a feature branch from `main`.
- Prefer Conventional Commits:
  - `feat: ...`
  - `fix: ...`
  - `docs: ...`
  - `refactor: ...`
  - `test: ...`
  - `chore: ...`

## Pull Request Guidelines

Each PR should include:
- Clear summary of what changed and why.
- Linked issue (if applicable).
- Test evidence (`./mvnw test` output summary).
- UI screenshots/GIF for extension UI changes.

Keep PRs small and reviewable when possible.

## Code Standards

- Avoid unrelated refactors in the same PR.
- Do not commit secrets or API keys.
- Update docs when behavior/API changes.

## Reporting Bugs

When reporting a bug, include:
- Steps to reproduce
- Expected vs actual behavior
- Sample input/code snippet
- Environment details (OS, browser, Java version)

## Security Issues

Do not file public issues for vulnerabilities.
Use the process in `SECURITY.md`.
