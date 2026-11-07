# Product scope

## Purpose

**diyalamp is a lightweight, open source Markdown writing app for macOS.** It provides a quiet, rendered editing surface and keeps the user's work in ordinary local Markdown files.

The public story leads with writing and ownership. Java is an implementation detail for contributors.

## Desktop scope

- No account or service connection required.
- A folder of `.md` notes with create, rename, search, open, and recoverable delete.
- Rendered editing of common Markdown with useful formatting commands.
- Multiple closable note tabs and an optional second writing pane.
- Safe local file saving, undo/redo, and clear failure feedback.
- A bundled runtime and downloadable macOS DMG linked from GitHub and the website.

## Outside this release

Cloud storage, account backends, AI agents, embeddings, collaboration, databases, and direct publication integrations. Do not build or provision these as dependencies of the editor.

## Identity

Use lowercase **diyalamp** and the existing lamp image. Follow the [desktop design rules](../desktop/README.md#interface-design) for the shared dark surfaces, typography, spacing, and controls. The app icon keeps the approved lamp size and position on the dark backplate. Packaged launches use the ICNS asset. The product website uses its own dark presentation and shows screenshots of the Mac app.

## Release truth

Do not advertise an installer before its artifact exists and opens. Label architecture support, signing, and notarization according to actual results. Never replace or migrate existing personal notes without an explicit user action. Save failures must leave the last good file intact.

## Size budget

Owner direction: minimize footprint and set an ambitious target of under 5 MB installed. This is a target, not an achieved claim or a promised release date. Record actual download and installed measurements by release and architecture in the README; those measured values are authoritative. Evaluate runtime and packaging changes against launch reliability and the no-separate-runtime installation experience. Never market the target as a measured current size.

## Connected writing and focus tools

Wiki links use `[[Note name]]` and remain in the saved Markdown. The right panel places a directed SmartGraph visualization above Incoming and Outgoing lists for the active note. Indexing is read-only and runs off the UI thread. It excludes web, hidden, and outside-folder paths; partial indexing is identified when limits are reached. Keep the SmartGraph instance and zoom container across safe graph refreshes: retain shared vertex positions and zoom, restore cached positions for returning local vertices, and briefly reheat the maintained layout after interaction. Stop idle simulation after five seconds and stop it immediately when the inspector/window hides or closes; folder switches stop the old workspace simulation. Hover may emphasize related notes and connections without changing note files.

Zen is a visual current-line focus fade: text away from the caret line is subdued while all workspace chrome remains visible. Keep its toggle in the same position and let input pass through the visual overlay. Both sidebars use maintained JavaFX SplitPane dividers. Remember their pixel widths and visibility independently, clamp them to sensible bounds, and leave a usable writing pane. A top-toolbar reset restores the 272/304 px defaults without changing visibility, document state, or Zen.

The toolbar shares a row with the native macOS window buttons. The workspace path is also the folder picker. The files sidebar holds search, note and folder creation, and the folder tree. Each note row has split, inline rename, and recoverable delete actions. The toolbar also exposes the sidebar toggles, Pomodoro, accent, Focus, and layout reset. The status bar shows character, word, and logical-line counts for rendered text and the current Markdown's UTF-8 byte size.

Accents transition between five presets and a custom hex color. Dialogs use the shared AtlantaFX styling. Pomodoro shows the current phase and countdown in the toolbar. Its popover supports start, pause, reset, editable focus and break durations, and one-cycle or repeat schedules. It starts explicitly and stops when the app quits. The accessible name stays stable while the tooltip and accessibility help expose the changing time. Hover transitions preserve control geometry.

Tab transfers retain their existing editor, selection and history. Folder moves preserve file bytes and reject collisions; moving a file does not rewrite folder-qualified links in other notes. Use stable note names or update explicit paths after reorganizing folders.
