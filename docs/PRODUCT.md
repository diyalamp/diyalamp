# Product scope

## Purpose

**diyalamp is a lightweight, open source Markdown writing app for macOS.** It provides a quiet, rendered editing surface and keeps the user's work in ordinary local Markdown files.

The public story leads with writing and ownership. Java is an implementation detail for contributors.

## First desktop release

- No account or service connection required.
- A folder of `.md` notes with create, rename, search, open, and recoverable delete.
- Rendered editing of common Markdown with useful formatting commands.
- Multiple closable note tabs and an optional second writing pane.
- Safe local file saving, undo/redo, and clear failure feedback.
- A bundled runtime and downloadable macOS DMG linked from GitHub and the website.

## Outside this release

Cloud storage, account backends, AI agents, embeddings, collaboration, databases, and direct publication integrations. Do not build or provision these as dependencies of the editor.

## Identity

Lowercase **diyalamp**, the existing fixed lamp image, and “A tool for the mind.” Keep the website's approved identity; do not replace it with unrelated JRXNA merchandise styling. The app is dark-only, using JRXNA black, white, and #EE0000 red, with dark-gray writing surfaces. There is no light mode or appearance switch. The website and component-based browser prototype establish the visual direction.

## Release truth

Do not advertise an installer before its artifact exists and opens. Label architecture support, signing, and notarization according to actual results. Never replace or migrate existing personal notes without an explicit user action. Save failures must leave the last good file intact.

## Size budget

Owner direction: minimize footprint and set an ambitious target of under 5 MB installed. This is a target, not an achieved claim or a promised release date. The current bundled Apple silicon build is approximately 35.7 MB downloaded and 46 MiB installed in release 1.0.0; the runtime dominates that size. Record exact per-architecture release measurements. Evaluate runtime and packaging changes against launch reliability and the no-separate-runtime installation experience. Never market the target as a measured current size.
