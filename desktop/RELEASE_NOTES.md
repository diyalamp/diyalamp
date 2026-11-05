## diyalamp 1.1.2

The light macOS workspace adds connected-note navigation, focus tools, and consistent dialogs using JavaFX, AtlantaFX, RichTextFX, CommonMark, and SmartGraph components.

### Connected notes

- Type `[[Note name]]` to link one note to another. Wiki markup remains in the saved Markdown; the editor shows a local-note link.
- The right panel shows a directed graph above incoming and outgoing note lists. Incoming notes point into the current note; outgoing notes are linked from it.
- The graph uses SmartGraph's force layout and zoom/pan controls. Connections are indexed from local notes in the background, with visible limits for partial scans. Web links, hidden files, and paths outside the selected workspace are excluded.

### Focus and interface

- Zen mode smoothly hides workspace controls and restores the previous sidebar and split arrangement. Use the toolbar, View menu, or Shift-Command-F; an exit control remains available.
- Six muted accents transition smoothly, including when a new choice interrupts a transition. Dialogs share the workspace's native System text, neutral surfaces, spacing, and AtlantaFX button styling.
- A simple in-app Pomodoro provides 25-minute focus and 5-minute breaks with Start, Pause, and Reset. Each next session starts explicitly; completion uses an in-app notice. The timer stops when the app quits.
- Move tabs between split panes and move notes into workspace folders by dragging. Folder moves preserve note bytes and reject overwrites or destinations outside the workspace.
- The lamp and product name appear together on one line. Packaged macOS launches retain the bundled ICNS rather than replacing it with a separate runtime image. Global Dock settings are unchanged.

### Files and updates

Notes remain ordinary local Markdown files. Save conflicts, recoverable deletion, safe folder boundaries, and explicit verified in-app updates remain in place. Both architecture DMGs include the runtime. Matching SHA-256 files accompany the installers.

This community release is **unsigned and not Apple-notarized**. A checksum verifies downloaded bytes, not cryptographic publisher identity. See the [installation guide](https://github.com/diyalamp/diyalamp/blob/main/docs/install.md).

The under 5 MB installed target is not achieved. Download and installed measurements differ; see the actual release assets and README. Full VoiceOver review and physical Intel UI testing remain open.

License: Apache 2.0. Upstream licenses, including SmartGraph MIT notices, are retained.
