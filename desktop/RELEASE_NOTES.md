## diyalamp 1.3.3 · development

The dark-only macOS workspace combines AtlantaFX CupertinoDark controls, RichTextFX editing, and SmartGraph note navigation. These notes describe the 1.3.3 development build. See the README for the published downloads and measurements.

### Connected notes

- Type `[[Note name]]` to link one note to another. Wiki markup remains in the saved Markdown; the editor shows a local-note link.
- The right panel shows a directed graph above incoming and outgoing note lists. Incoming notes point into the current note; outgoing notes are linked from it.
- The graph keeps the same SmartGraph instance and zoom/pan container through note selection and safe saves. Shared node coordinates and zoom remain stable; returning local nodes restore cached positions. Interaction reheats the library layout, which settles after five idle seconds and stops immediately when hidden or closed. Hover highlights related notes and links; switching folders stops the old workspace simulation.
- Connections are indexed from local notes in the background, with visible limits for partial scans. Web links, hidden files, and paths outside the selected workspace are excluded.

### Focus and interface

- Focus fades text to black around the active caret line and dims the workspace chrome, inactive split pane, and native macOS window buttons. Tabs, sidebars, and controls remain usable. Use the moon toolbar control, View menu, or Shift-Command-F.
- Red `#EE0000` is the default accent. Blue, yellow, green, white, and custom hex are also available. The transition continues smoothly when a new choice interrupts it. The explicit reduced-motion option remains.
- Workspace controls and prose use 16 px static Inter, and code uses 16 px Menlo. Rendered H1, H2, and H3 use 28, 23, and 19 px Inter Semibold. Blockquotes use an inset surface and an accent rule.
- The slash-command menu has a Lucide icon for each block. Click outside or press Escape to dismiss it. Use the arrow keys and Enter to select a command.
- The workspace uses a shared 8/16/24 spacing scale, `#111213` main surfaces, `#202122` raised surfaces, and `#333536` dividers. A continuous rule aligns the Files, tabs, and Connections bands.
- The wordmark, toolbar controls, and tabs use 36 px control height and 8 px spacing. The files, tabs, and Connections bands share one 52 px row. The native toolbar combines the folder icon and workspace path in one control.
- The files sidebar includes new-folder and new-note actions. Each note row exposes split, inline rename, and recoverable delete, with an 8 px gap between row highlights.
- Workspace search covers filenames and Markdown text, including unsaved changes. The options icon beside the query expands scope, case, whole-word, regex, and path filters. Text matches are grouped by note with clickable line previews.
- Checklists use readable rounded boxes with the same row spacing as bullets and prose. Wrapped tasks align their box with the first line.
- Accent and Pomodoro use the same rounded AtlantaFX popup surface with a subtle border and short slide and fade. Selecting an accent keeps every option the same width.
- App dialogs use a rounded dark surface, a consistent title and field layout, and a brief entrance transition.
- The bottom status bar shows rendered character/word/logical-line counts and the current Markdown's UTF-8 byte size. Both sidebars have native draggable dividers and remember their pixel widths and visibility independently. The reset icon restores 272/304 px widths within window bounds without changing open notes, selection, visibility, or Zen.
- Hover paint transitions do not lift, scale, or move controls. The explicit reduced-motion option removes those transitions.
- Pomodoro shows Focus or Break in the toolbar with the popover closed. Set both durations and use the icon-labeled one-cycle or repeat controls. Start, Pause, and Reset are available in the popover, and the timer stops when the app quits.
- Tabs show an insertion marker while dragging and move into place after dropping. Drag between split panes or move notes into workspace folders. Folder moves preserve note bytes and reject overwrites or destinations outside the workspace.
- The dark icon keeps the approved lamp geometry. At launch, the app clears the JavaFX icon override so AppKit renders the bundled icon in the Dock for both running and closed states.

### Files and updates

Notes remain ordinary local Markdown files. Save conflicts, recoverable deletion, safe folder boundaries, and explicit verified in-app updates remain in place. Both architecture DMGs include the runtime. Matching SHA-256 files accompany the installers.

This community release is **unsigned and not Apple-notarized**. A checksum verifies downloaded bytes, not cryptographic publisher identity. See the [installation guide](https://github.com/diyalamp/diyalamp/blob/main/docs/install.md).

The under 5 MB installed target is not achieved. Download and installed measurements differ; see the actual release assets and README. Full VoiceOver review and physical Intel UI testing remain open.

License: Apache 2.0. Upstream licenses, including SmartGraph MIT notices, are retained.
