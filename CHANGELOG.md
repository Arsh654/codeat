# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog, and this project follows Semantic Versioning.

## [Unreleased]

### Added
- Multi-language analysis positioning in documentation.
- Conditional code review and style analysis fields for predicted 100% PASS solutions.
- Open-source governance docs: CONTRIBUTING, CODE_OF_CONDUCT, SECURITY, SUPPORT.
- Open source maintainer guide PDF in project root.

### Changed
- Extension analysis is manual-trigger only.
- Popup UI redesigned for cleaner readability and improved visual hierarchy.
- In-page widget redesigned and made draggable.
- Backend prompting updated to avoid Java-only language bias and align feedback with detected language.

### Fixed
- Popup feedback text no longer truncates in a way that hides meaningful content.
- Visual distinction improved between top metrics and content sections.

## [0.1.0] - 2026-03-11

### Added
- Initial public-ready baseline for Codeat backend + extension.
- LLM-based correctness/confidence estimation and likely verdict.
- Strengths, improvements, and likely failing scenario outputs.
