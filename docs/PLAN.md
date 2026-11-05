# 🪔 diyalamp implementation plan

**Historical plan.** This document records an earlier AI writing-review experiment. Current desktop scope is in [PRODUCT.md](PRODUCT.md); the macOS editor does not require Notion, a hosted service, billing, or an AI provider.

Defensible writing with AI in Notion. Java 21. The local CLI is the existing foundation; the next release is a hosted Notion-first service. See [hosted architecture](HOSTED_ARCHITECTURE.md) for onboarding, billing, access gates, and open-source boundaries.

## First release: implemented

- Typed five-phase workflow, missing-evidence gates, source fingerprints, and explicit author approval.
- Persistent local sessions, context responses, report export, private files, and concurrent-session locks.
- Notion import with pagination, nested text, block references, unsupported-content checks, and revision checks.
- Codex-only review using saved CLI sign-in, explicit transmission consent, ephemeral read-only runs, structured output, and strict phase-result validation.
- Guided terminal actions with in-memory credential reuse, inline author responses, and cancellation before requests.
- Live Notion source checks before approval and publication; separate review pages, no source overwrites.
- Durable publication receipts and reconciliation of uncertain writes without repeating the create request.
- Packaged unsigned CLI with launcher, examples, dependency notices, and Linux/macOS CI.
- Offline component and connected workflow tests. Each milestone is committed and pushed independently.

## Required live validation

Use a user-selected test page and destination with read/insert permissions, a signed-in Codex CLI, and an optional available Codex model. Import a rough draft, run five phases with evidence responses, inspect and approve, publish separately, and verify the original source was not edited. This has not been performed with live credentials. Do not claim a production-ready or live-validated release until demonstrated.

## Later releases

Notion OS credential storage, public Notion OAuth, signed macOS app with bundled runtime, richer block support, larger publication batches, safe read retry scheduling, release hosting, per-edit approvals and application, review provenance history, accessibility, and independent security review.

These are future features, not hidden requirements for the first CLI release. Code signing and public OAuth additionally need account setup outside the repository.

## Correctness and privacy

A review supports judgment, not proof of truth. Empty model questions do not establish that evidence is complete. Keys never belong in source, Notion, arguments, sessions, or fixtures. Runtime documents stay outside tracked files. Codex usage follows its active sign-in and account limits.

Notion source checks and page creation are separate requests. A write may succeed even when its response fails. Preserve reviewed fingerprints and pending receipts; never blindly repeat a publication.
