# diyalamp desktop

A focused, local Markdown writing app for macOS. The 1.3.4 development interface uses JavaFX 27, AtlantaFX CupertinoDark controls, RichTextFX rendered editing, bundled Inter text, and installed Menlo for code. No backend, model provider, database, or account is required.

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

This produces a self-contained `.app`, a compressed DMG for the current processor architecture, and a SHA-256 file. The runtime is included, so users can run the app directly. FlatLaf, FlatLaf Extras, and JSVG remain available to the source build and Swing tests. The packaging script uses stronger lossless DMG compression when it makes the download smaller.

The manual GitHub packaging workflow builds separate Apple silicon and Intel artifacts and tests each bundled runtime from its mounted DMG. Select **Build and test installers without staging a release** to validate a branch. A release dispatch stages both verified installers in a draft using the version declared in `DesktopLauncher.VERSION`.

The community installers are ad hoc signed. Developer ID signing and Apple notarization require a separate release setup. See the [install guide](../docs/install.md) for the current first-launch process.

## Interface design

Use lowercase **diyalamp** and the fixed lamp asset. The dark workspace uses one charcoal surface (`#111213`) and black (`#000000`) in Focus mode. Dividers, foreground text, and control states use the shared tokens in `fx-workspace.css`. The default accent is red (`#EE0000`). Preset and custom accents keep readable foreground contrast and transition smoothly.

The interface and prose use 16 px Inter. Markdown H1, H2, and H3 use Inter SemiBold at 28, 23, and 19 px. H4–H6 use the 16 px body size. Quotes use a rounded inset with an accent rule. Code uses installed Menlo, and static italic faces preserve emphasis. Use AtlantaFX CupertinoDark controls and Popover, RichTextFX for editing, and SmartGraph for the Connections panel.

Workspace spacing uses an 8 px grid. The wordmark, toolbar controls, search fields, dialog actions, and popover actions share a 36 px control height and 8 px corners. The toolbar and pane header rows are 52 px tall. Tabs fill the editor header row and keep their labels and close targets centered. Use the shared 140 ms control transition for hover and focus. Dialogs use 24 px inner padding and 12 px surface corners. Entrance motion leaves adjacent controls in place and honors reduced motion.

## Writing and storage

- Click the workspace path in the toolbar to open a folder of `.md` or `.markdown` notes.
- Search filenames with Command-P or note contents with Option-Command-F. Match case, whole words, or regular expressions, and use include/exclude globs to narrow the workspace. Open a text match to reveal its location in the note. Escape returns to the file tree.
- Use rendered text, headings, emphasis, simple lists, checklists, quotes, and fenced code blocks.
- Type `/` on an empty line for blocks, or use formatting controls and keyboard shortcuts.
- Use New note and New folder from the sidebar. Every note row has split, rename, and recoverable delete actions. For rename, type in the row and press Enter to save. Escape or a click outside cancels.
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

Use the moon toolbar control or Shift-Command-F to focus on the active caret line. Surrounding text fades, and the workspace chrome and inactive split pane dim. The toolbar, sidebars, tabs, and status stay visible and usable.

Pomodoro shows the active phase and countdown in the toolbar. Open its popover to start, pause, reset, and set focus and break lengths. One cycle runs a focus session and its break before stopping. Repeat alternates focus and break until paused or reset. Duration and repeat settings persist across launches. The timer stops when the app exits.

The website and browser notebook remain a separate dark presentation and do not sync with local folders. Full VoiceOver review and physical Intel GUI validation remain open; passing component tests and producing both architecture packages do not replace those checks.
