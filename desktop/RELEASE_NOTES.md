## diyalamp 1.3.1 · development

The dark-only macOS workspace combines AtlantaFX CupertinoDark controls with the official VS Code 2026 Dark palette, RichTextFX editing, and SmartGraph note navigation. These notes describe the 1.3.1 development build. See the README for the published downloads and measurements.

### Connected notes

- Type `[[Note name]]` to link one note to another. Wiki markup remains in the saved Markdown; the editor shows a local-note link.
- The right panel shows a directed graph above incoming and outgoing note lists. Incoming notes point into the current note; outgoing notes are linked from it.
- The graph keeps the same SmartGraph instance and zoom/pan container through note selection and safe saves. Shared node coordinates and zoom remain stable; returning local nodes restore cached positions. Interaction reheats the library layout, which settles after five idle seconds and stops immediately when hidden or closed. Hover highlights related notes and links; switching folders stops the old workspace simulation.
- Connections are indexed from local notes in the background, with visible limits for partial scans. Web links, hidden files, and paths outside the selected workspace are excluded.

### Focus and interface

- Zen now visually fades text away from the current caret line. All chrome remains visible; the same toolbar toggle stays in place. The visual overlay does not intercept input. Use the toolbar, View menu, or Shift-Command-F.
- Six accent choices with a blue default transition smoothly, including when a new choice interrupts a transition. Accessibility attachment no longer disables animations; the explicit reduced-motion option remains.
- All text is 16 px, including headings, graph labels, dialog headers, tooltips, menus, popovers, and timer digits. Static Inter Regular is used for ordinary text; exact Inter SemiBold changes heading weight without enlarging it, and Menlo 16 is used for code. Static italic faces preserve emphasis. Font files add about 1.6 MiB, measured separately from the runtime.
- The global toolbar, sidebar, tabs, graph, dialogs, and popovers use a shared 8/16/24 spacing scale and CupertinoDark controls. Editor `#121314`/`#BBBEBF`, chrome `#191A1B`, border `#2A2B2C`, text `#BFBFBF`, and muted text `#8C8C8C` follow 2026 Dark. Inputs use `#191A1B` with `#333536` borders and `#555555` placeholders; default action/link blues are `#297AA0` and `#48A0C7`.
- The native toolbar combines the folder icon and workspace path in one control. The files sidebar, note tabs, and Connections header share one 52 px band and 8 px inset.
- The files sidebar includes new-folder and new-note actions. Each note row exposes split, inline rename, and recoverable delete, with an 8 px gap between row highlights.
- Workspace search covers filenames and Markdown text, including unsaved changes. Case, whole-word, regex, and path glob filters narrow results. Text matches are grouped by note with clickable line previews.
- Accent and Pomodoro use the same rounded AtlantaFX popup surface with a subtle border and short slide and fade.
- App dialogs use a rounded dark surface, a consistent title and field layout, and a brief entrance transition.
- The bottom status bar shows rendered character/word/logical-line counts and the current Markdown's UTF-8 byte size. Both sidebars have native draggable dividers and remember their pixel widths and visibility independently. The reset icon restores 272/304 px widths within window bounds without changing open notes, selection, visibility, or Zen.
- Hover paint transitions do not lift, scale, or move controls. The explicit reduced-motion option removes those transitions.
- A compact toolbar Pomodoro opens an animated bubble for 25-minute focus and 5-minute breaks with Start, Stop, and Reset. Each next session starts explicitly; completion uses an in-app notice. The timer stops when the app quits.
- Move tabs between split panes and move notes into workspace folders by dragging. Folder moves preserve note bytes and reject overwrites or destinations outside the workspace.
- The dark icon keeps the approved lamp geometry. At launch, the app clears the JavaFX icon override so AppKit renders the bundled icon in the Dock for both running and closed states.

### Files and updates

Notes remain ordinary local Markdown files. Save conflicts, recoverable deletion, safe folder boundaries, and explicit verified in-app updates remain in place. Both architecture DMGs include the runtime. Matching SHA-256 files accompany the installers.

This community release is **unsigned and not Apple-notarized**. A checksum verifies downloaded bytes, not cryptographic publisher identity. See the [installation guide](https://github.com/diyalamp/diyalamp/blob/main/docs/install.md).

The under 5 MB installed target is not achieved. Download and installed measurements differ; see the actual release assets and README. Full VoiceOver review and physical Intel UI testing remain open.

License: Apache 2.0. Upstream licenses, including SmartGraph MIT notices, are retained.
