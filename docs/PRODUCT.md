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

Lowercase **diyalamp**, the existing fixed lamp image, and “A tool for the mind.” The desktop app is light-only: quiet writing pages, soft-gray panels, readable text, and a user-selected accent color. Use the installed macOS system font for prose and interface text, and installed Menlo for inline code and fenced code blocks. Do not bundle a replacement font or add a dark-mode switch. Use maintained JavaFX/AtlantaFX controls and RichTextFX text layout instead of custom text painting. Keep Open Folder, file actions, and the accent picker discoverable. The app icon retains the approved lamp's size and position on a subtly shaded white backplate. The website and browser prototype retain their separately approved dark presentation.

## Release truth

Do not advertise an installer before its artifact exists and opens. Label architecture support, signing, and notarization according to actual results. Never replace or migrate existing personal notes without an explicit user action. Save failures must leave the last good file intact.

## Size budget

Owner direction: minimize footprint and set an ambitious target of under 5 MB installed. This is a target, not an achieved claim or a promised release date. The bundled Apple silicon build in release 1.0.1 is approximately 36.7 MB downloaded and 47 MiB installed; the runtime dominates that size. Record exact per-architecture release measurements. Evaluate runtime and packaging changes against launch reliability and the no-separate-runtime installation experience. Never market the target as a measured current size.
