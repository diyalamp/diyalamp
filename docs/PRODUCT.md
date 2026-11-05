# Product scope

## Purpose

**diyalamp is a lightweight, open source Markdown writing app for macOS.** It provides a quiet, rendered editing surface and keeps the user's work in ordinary local Markdown files.

The public story leads with writing and ownership. Java is an implementation detail for contributors.

## Desktop scope · 1.2.0 development

- No account or service connection required.
- A folder of `.md` notes with create, rename, search, open, and recoverable delete.
- Rendered editing of common Markdown with useful formatting commands.
- Multiple closable note tabs and an optional second writing pane.
- Safe local file saving, undo/redo, and clear failure feedback.
- A bundled runtime and downloadable macOS DMG linked from GitHub and the website.

## Outside this release

Cloud storage, account backends, AI agents, embeddings, collaboration, databases, and direct publication integrations. Do not build or provision these as dependencies of the editor.

## Identity

Lowercase **diyalamp**, the existing fixed lamp image, and “A tool for the mind.” The desktop app is dark-only, using maintained AtlantaFX CupertinoDark controls with the official VS Code 2026 Dark palette: editor `#121314`/`#BBBEBF`, chrome `#191A1B`, border `#2A2B2C`, text `#BFBFBF`, muted text `#8C8C8C`, input `#191A1B`/`#333536`, placeholder `#555555`, default blue `#297AA0`, and link blue `#48A0C7`. All text is 16 px, including headings, graph labels, dialog headers, menus, popovers, and Pomodoro digits. Use static Inter Regular for ordinary interface/prose, actual Inter SemiBold for heading weight without enlarging it, and installed Menlo 16 for code. Static italic faces preserve Markdown emphasis. The font payload is a measured visual-quality tradeoff; do not add another appearance mode. Use maintained Popover components and RichTextFX text layout. Keep Open Folder, file actions, and the accent picker discoverable. The app icon retains the approved lamp's size and position on a subtly shaded dark backplate; packaged launches keep the ICNS asset without claiming control of operating-system Dock zoom. The website and browser prototype retain their separately approved dark presentation.

## Release truth

Do not advertise an installer before its artifact exists and opens. Label architecture support, signing, and notarization according to actual results. Never replace or migrate existing personal notes without an explicit user action. Save failures must leave the last good file intact.

## Size budget

Owner direction: minimize footprint and set an ambitious target of under 5 MB installed. This is a target, not an achieved claim or a promised release date. Record actual download and installed measurements by release and architecture in the README; those measured values are authoritative. Evaluate runtime and packaging changes against launch reliability and the no-separate-runtime installation experience. Never market the target as a measured current size.

## Connected writing and focus tools

Wiki links use `[[Note name]]` and remain in the saved Markdown. The right panel places a directed SmartGraph visualization above Incoming and Outgoing lists for the active note. Indexing is read-only and runs off the UI thread. It excludes web, hidden, and outside-folder paths; partial indexing is identified when limits are reached. Keep the SmartGraph instance and zoom container across safe graph refreshes: retain shared vertex positions and zoom, restore cached positions for returning local vertices, and briefly reheat the maintained layout after interaction. Stop idle simulation after five seconds and stop it immediately when the inspector/window hides or closes; folder switches stop the old workspace simulation. Hover may emphasize related notes and connections without changing note files.

Zen is a visual current-line focus fade: text away from the caret line is subdued while all workspace chrome remains visible. Keep its toggle in the same position and let input pass through the visual overlay. Both sidebars use maintained JavaFX SplitPane dividers. Remember their pixel widths and visibility independently, clamp them to sensible bounds, and leave a usable writing pane. A top-toolbar reset restores the 272/304 px defaults without changing visibility, document state, or Zen.

The top toolbar uses icon-only Pomodoro and color-circle accent controls. New-note and split actions belong in the top toolbar or sidebar controls, not duplicated in the editor. The bottom bar shows character/word/logical-line counts of rendered text and the current Markdown's UTF-8 byte size.

Accents transition between six choices with readable dark-surface foregrounds. Dialogs use the same AtlantaFX component styling. Pomodoro provides 25-minute focus sessions and 5-minute breaks through an animated popover with Start, Stop, and Reset. It starts explicitly and stops when the app quits; the icon tooltip and accessibility help expose changing time while its accessible name stays stable. Hover animation changes paint only, with no layout shift, lift, or zoom.

Tab transfers retain their existing editor, selection and history. Folder moves preserve file bytes and reject collisions; moving a file does not rewrite folder-qualified links in other notes. Use stable note names or update explicit paths after reorganizing folders.
