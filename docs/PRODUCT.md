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

Lowercase **diyalamp**, the existing fixed lamp image, and “A tool for the mind.” The desktop app is light-only: quiet writing pages, soft-gray panels, readable text, and a user-selected accent color. Use static Inter Regular 16 for ordinary interface and prose, exact Inter SemiBold 20/22 for headings, and installed Menlo 16 for code. Static italic faces preserve Markdown emphasis. The font payload is a measured visual-quality tradeoff; do not add a dark-mode switch. Use maintained AtlantaFX Cupertino controls and Popover components with RichTextFX text layout. Keep Open Folder, file actions, and the accent picker discoverable. The app icon retains the approved lamp's size and position on a subtly shaded white backplate. The website and browser prototype retain their separately approved dark presentation.

## Release truth

Do not advertise an installer before its artifact exists and opens. Label architecture support, signing, and notarization according to actual results. Never replace or migrate existing personal notes without an explicit user action. Save failures must leave the last good file intact.

## Size budget

Owner direction: minimize footprint and set an ambitious target of under 5 MB installed. This is a target, not an achieved claim or a promised release date. Record actual download and installed measurements by release and architecture in the README; those measured values are authoritative. Evaluate runtime and packaging changes against launch reliability and the no-separate-runtime installation experience. Never market the target as a measured current size.

## Connected writing and focus tools

Wiki links use `[[Note name]]` and remain in the saved Markdown. The right panel places a directed SmartGraph visualization above Incoming and Outgoing lists for the active note. Indexing is read-only and runs off the UI thread. It excludes web, hidden, and outside-folder paths; partial indexing is identified when limits are reached.

Zen mode hides workspace chrome while preserving the editor and split arrangement. Accents transition between six muted choices. Dialogs use the same AtlantaFX component styling. A foreground Pomodoro in the top toolbar provides 25-minute focus sessions and 5-minute breaks through a compact animated popover, started explicitly; it stops when the app quits.

Tab transfers retain their existing editor, selection and history. Folder moves preserve file bytes and reject collisions; moving a file does not rewrite folder-qualified links in other notes. Use stable note names or update explicit paths after reorganizing folders.
