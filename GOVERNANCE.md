# Governance

## Project Model

Codeat is currently maintained under a single lead-maintainer model.

- Lead Maintainer: Arshad
- Current decision authority: Lead Maintainer

As the project grows, additional maintainers may be added by explicit announcement in this file.

## Decision Making

### Day-to-day changes
- Bugs, docs, and small features are decided through normal PR review.
- Maintainer approval is required before merge.

### Significant changes
A change is considered significant if it impacts:
- API contract/response shape
- Scoring semantics or verdict calibration behavior
- Extension UX flow in a breaking way
- Security model

Significant changes should start as an issue discussion before implementation.

## Maintainer Responsibilities

The maintainer is responsible for:
- Reviewing and merging PRs
- Triage of issues
- Release management
- Security response coordination
- Keeping docs accurate

## Contribution Acceptance Criteria

A change is typically accepted when:
- Scope is clear and focused
- Tests are added/updated where needed
- No unrelated refactors are bundled
- Docs are updated for behavior/API changes

## Conflict Resolution

If maintainers and contributors disagree:
- Discuss tradeoffs in issue/PR comments.
- Favor clear technical reasoning and user impact.
- Final decision rests with the lead maintainer.

## Inactive Contributions

- PRs with no activity for 30+ days may be marked stale.
- Stale PRs may be closed after follow-up if no response is received.
- Closed stale PRs are welcome to be reopened with updates.

## Security and Conduct

- Security handling follows `SECURITY.md`.
- Community behavior follows `CODE_OF_CONDUCT.md`.
