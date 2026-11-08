<div align="center">
  <h1><img src="assets/lamp.png" width="32" height="32" alt="" align="absmiddle"> diyalamp</h1>
  <p><strong>Open-source Markdown editor for macOS.</strong></p>
  <p>Live formatting · Local .md files · Multiple tabs · Split view · No sign-up</p>
  <p><a href="https://diyalamp.com">Website</a> · <a href="https://github.com/diyalamp/diyalamp/releases">Releases</a> · <a href="https://github.com/diyalamp/diyalamp/issues">Issues</a> · <a href="CONTRIBUTING.md">Contributing</a></p>
  <img alt="Apache 2.0" src="https://img.shields.io/badge/license-Apache%202.0-3C4545?style=flat-square">
  <img alt="macOS" src="https://img.shields.io/badge/platform-macOS-3C4545?style=flat-square">
</div>

## Overview

diyalamp is a desktop Markdown editor with rendered formatting and local file storage. It is a free, open-source option for local Markdown writing as an alternative to Obsidian, Notion, and Typora.

The application opens a folder of `.md` or `.markdown` files. Editing does not require an account, subscription, server, database, or internet connection. Notes remain readable by other Markdown tools.

## Desktop features · 1.3.5

- Dark-only macOS workspace using AtlantaFX CupertinoDark controls, aligned dividers, and flat Lucide icons. Normal mode uses one charcoal background and Focus uses black.
- 16 px Inter for the interface and prose, with distinct sizes for the first three headings inside notes. Code uses installed Menlo.
- Rendered headings, bold, italic, inline code, links, lists, checklists, inset quotes, and fenced code blocks.
- Formatting controls, Markdown typing shortcuts, and keyboard-accessible slash commands with icons.
- Folder explorer with nested Markdown files, closable note tabs, and side-by-side editing.
- Workspace search for filenames or note text, with an icon beside the query to show or hide advanced filters. Text matches are grouped by note.
- Local autosave with external-file conflict detection.
- Note creation, import, rename, export, recoverable deletion, and restore.
- Undo/redo and macOS keyboard shortcuts.
- `[[Note name]]` links with directed incoming/outgoing navigation and a SmartGraph panel that retains layout and zoom during note selection and safe saves.
- Focus fades text away from the active caret line and dims the workspace chrome while keeping tabs, sidebars, and controls available.
- Smooth accent transitions, matching dark dialogs, and restrained hover paint transitions without moving controls.
- A toolbar Pomodoro that shows Focus or Break with the popover closed, with editable durations and one-cycle or repeat controls. The color-circle accent picker keeps its geometry stable when switching colors.
- Drag tabs between panes or move notes into workspace folders without overwrites.
- A unified macOS toolbar with centered native window buttons and one clickable workspace path. The files sidebar has search and creation controls, with split, inline rename, and recoverable delete on each note row.
- Character, word, and logical-line counts for rendered text, plus the current Markdown's UTF-8 byte size in the status bar.
- Native draggable dividers for both sidebars, with saved pixel widths and independent open/closed states. An icon-only reset restores the default widths without closing notes or changing Zen.
- Five accent presets with a red default and a custom hex option. In-app update checks are explicit.

## macOS downloads

Version **1.3.5** is available from [GitHub Releases](https://github.com/diyalamp/diyalamp/releases/tag/v1.3.5) and [diyalamp.com](https://diyalamp.com).

- [Apple silicon DMG](https://github.com/diyalamp/diyalamp/releases/download/v1.3.5/diyalamp-1.3.5-macos-arm64.dmg)
- [Intel DMG](https://github.com/diyalamp/diyalamp/releases/download/v1.3.5/diyalamp-1.3.5-macos-x86_64.dmg)

Drag diyalamp to Applications. See the [installation guide](docs/install.md) for choosing Apple silicon or Intel, verifying the SHA-256 checksum, first-launch approval, and current macOS compatibility limits.

The [installed size target](https://github.com/diyalamp/diyalamp/issues/2) is under 5 MB and is still open.

Both installers include a runtime and provide matching SHA-256 checksums. No separate Java installation is required.

This release is **ad hoc signed**, without Developer ID or Apple notarization. macOS may require approval on first launch.

## Build and test

Requires JDK 25 or newer. The desktop app uses JavaFX, AtlantaFX, RichTextFX, Lucide icons, and CommonMark Java. Four static Inter faces are bundled for consistent reading and emphasis; Menlo comes from macOS. There is no JavaScript runtime or web backend in the desktop application.

```sh
sh desktop/build.sh
sh desktop/run.sh
sh desktop/test.sh
```

To package a self-contained app and DMG on macOS:

```sh
sh desktop/package-macos.sh
```

See [`desktop/README.md`](desktop/README.md) for dependency checksums, architecture builds, and development with a separate notes folder. Tests cover Markdown round trips, save conflicts, filename collisions, deletion recovery, and file boundaries.

## Storage and privacy

- Choose the notes folder with Open Folder; Markdown stays in that folder.
- Notes are UTF-8 Markdown files, saved locally.
- A file changed by another application is not silently overwritten; edits stay open for export.
- Deleted notes move to `.diyalamp-trash` inside the notes folder and can be restored.
- Embedded HTML is treated as text. Remote images and scripts are not downloaded or executed.
- No account or telemetry service is part of the desktop writing flow.

## Scope

This release supports common Markdown editing. It does not implement the complete CommonMark specification, rich table rendering, remote-image previews, plugins, sync, team databases, or publication integrations. Unsupported text is retained rather than executed.

See [macOS screenshots and comparisons](https://diyalamp.com/) on the product website. Website source is in [diyalamp.github.io](https://github.com/diyalamp/diyalamp.github.io).

Previous AI-agent, browser-automation, and writing-review experiments remain in the repository history and existing folders. They are not dependencies of the desktop editor. Current application development belongs in `desktop/`.

## Contributing

You can help by testing one writing task with the keyboard or VoiceOver. For code contributions, the [contribution guide](CONTRIBUTING.md) includes a local test workspace and build steps. [VoiceOver review](https://github.com/diyalamp/diyalamp/issues/1) · [Product scope](docs/PRODUCT.md)

## License

[Apache License 2.0](LICENSE). Dependencies and brand assets retain their own notices in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
