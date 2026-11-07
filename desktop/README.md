# diyalamp desktop

A focused, local Markdown writing app for macOS. The 1.2.2 development interface uses JavaFX 27, maintained AtlantaFX CupertinoDark controls, RichTextFX rendered editing, bundled static Inter text, and installed Menlo for code. No backend, model provider, database, or account is required.

## Build and run

Install a JDK 25 or newer, then run from the repository root:

```sh
sh desktop/build.sh
sh desktop/run.sh
```

On macOS the script selects an installed JDK 25 or newer. To choose another installation, set `DIYALAMP_JAVA_HOME` to the JDK home.

A separate folder of fictional notes is useful while developing:

```sh
sh desktop/run.sh --workspace /tmp/diyalamp-notes
```

Build outputs and downloaded dependencies stay in ignored `desktop/build/`. The build pins and verifies dependency SHA-256 checksums, including the platform-specific JavaFX artifacts in `fx-dependencies.sha256`. Four official static Inter 4.1 faces are bundled (1,672,748 bytes total) to provide consistent ordinary, italic, semibold, and semibold-italic text. Code uses installed Menlo. Font provenance, checksums, and the OFL license are included.

## Tests

```sh
sh desktop/test.sh
```

The tests cover nested formatting, literal punctuation, fenced-code round trips, real writing actions, external-edit conflicts, reopen/reload recovery, shared-view disposal, file creation and rename collisions, recoverable deletion, path boundaries, and temporary-file cleanup. The desktop UI also needs manual macOS checks: create, format, save, reopen, split, rename, delete, and restore a fictional note.

## macOS package

On a Mac with JDK 25 or newer and Apple's command-line tools:

```sh
sh desktop/package-macos.sh
```

This produces a self-contained `.app`, a compressed DMG for the current processor architecture, and a SHA-256 file. The runtime is included; users do not need to install Java. The manual GitHub packaging workflow builds separate Apple silicon and Intel artifacts. Both the artifact names and draft release tag use the application version declared in `DesktopLauncher.VERSION`; published assets are never overwritten.

This community release is unsigned and not Apple-notarized. Do not describe it as notarized or suppress macOS security checks. Signing requires the owner's Apple Developer identity and a separate notarization setup.

The interface is dark-only, with no theme switch. Its colors follow the official [VS Code 2026 Dark theme](https://github.com/microsoft/vscode/blob/main/extensions/theme-defaults/themes/2026-dark.json): editor `#121314` with `#BBBEBF` text, sidebar/top/status chrome `#191A1B`, separators `#2A2B2C`, text `#BFBFBF`, muted text `#8C8C8C`, inputs `#191A1B` with `#333536` borders and `#555555` placeholders, default action blue `#297AA0`, and link blue `#48A0C7`. Every text surface uses 16 px, including headings, graph labels, dialogs, tooltips, menus, popovers, and timer digits. Inter SemiBold changes weight without enlarging headings; real italic faces preserve emphasis. Code uses installed Menlo 16. Alternate accents receive dark-aware foreground contrast and transition smoothly.

Workspace spacing uses an 8 px grid. Toolbar controls are 36 px tall inside a 52 px row, with 8 px control corners. Tabs and the file list use an 8 px outer inset. Dialogs use 24 px inner padding and 12 px surface corners. Brief entrance motion leaves adjacent controls in place and honors the reduced-motion setting.

## Writing and storage

- Open a folder of `.md` or `.markdown` notes. The toolbar displays the full folder path. Click the search icon or press Command-P to filter the note tree by name or relative path. Enter opens the first result and Escape restores the folder path.
- Use rendered text, headings, emphasis, simple lists, checklists, quotes, and fenced code blocks.
- Type `/` on an empty line for blocks, or use formatting controls and keyboard shortcuts.
- Use New note, New folder, and Split from the toolbar alongside the native macOS window buttons. For individual file operations, use the note and folder tree's context menus.
- Drag a sidebar note to a tab area; dropping near its right edge opens a second pane. Drag tabs between panes or onto a workspace folder to move the local file without overwriting a collision.
- The bottom status bar reports characters, words, and logical lines from rendered text, plus the UTF-8 size of the current Markdown, including current unsaved edits. Wrapped display lines do not inflate the logical-line count.
- Autosave writes local files. A changed disk fingerprint stops an overwrite and keeps edits open for export. Use File → Reload note from disk… to reload after exporting a recovery copy; unsaved edits require confirmation before replacement.
- Deleted notes move to `.diyalamp-trash` inside the chosen folder. They can be restored from the app.

Common Markdown is supported; this first release does not implement the full CommonMark specification, rich table layout, remote-image loading, plugins, sync, or team workspaces. HTML remains literal text. Unsupported text is retained rather than executed.

## Icon assets

`src/main/resources/app-icon.png` preserves the approved profile artwork's lamp size and position, with a dark backplate, restrained edge shading, and a soft shadow. The packaged macOS app retains its bundled ICNS for the Dock rather than replacing it at runtime. This guard preserves the packaged icon; it does not override or guarantee operating-system Dock zoom behavior. Development launches outside the app bundle use the PNG window icon. `icon-source/` contains the unchanged composition source and a reproducible macOS renderer; it is not bundled into the app. `lamp.png` remains the separate, compact inline brand mark.

## Connected notes and focus

Type `[[Reference]]` or `[[Folder/Reference|label]]` for local links. The graph uses SmartGraph 2.6.1; the read-only index includes saved local wiki and Markdown links. The right-hand diagram is limited to 60 visible nodes, while the Incoming/Outgoing lists show every indexed connection. Index limits are 5,000 notes, 30,000 traversed entries, 1 MiB per note, and 32 MiB per scan; incomplete scans are identified.

The existing SmartGraph and its zoom container stay in place as the active note or safely saved links change. Shared vertex coordinates and zoom are retained; returning local vertices reuse cached positions. The maintained force layout reheats for interaction and settles after five idle seconds. Hiding the inspector or window, closing it, or switching folders stops the old simulation. Hovering a node highlights its connected neighbors and links.

Drag the maintained JavaFX SplitPane dividers to resize the files and Connections sidebars independently. Their pixel widths and open/closed states are remembered. The top-bar Reset sidebar widths icon restores 272 px on the left and 304 px on the right, subject to window bounds, without changing visibility, notes, selection, or Zen. A narrow window clamps sidebar widths to leave room for writing.

Use the Zen toolbar control or Shift-Command-F to apply a visual fade around the current caret line. The toolbar, sidebars, tabs, status, and Zen button remain visible and in place; the fade does not intercept input.

Pomodoro is an icon-only top-toolbar control, also available from the View menu. Its tooltip and accessibility help carry the phase, remaining time, and running/stopped state; its accessible name stays stable. The animated popover provides Start, Stop, and Reset. Its 25-minute focus and 5-minute break phases start manually, and the timer stops when the app exits. The accent control is a color circle opening the six-choice accent popover.

The website and browser notebook remain a separate dark presentation and do not sync with local folders. Full VoiceOver review and physical Intel GUI validation remain open; passing component tests and producing both architecture packages do not replace those checks.
