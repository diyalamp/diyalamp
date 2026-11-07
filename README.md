<div align="center">
  <h1><img src="assets/lamp.png" width="32" height="32" alt="" align="absmiddle"> diyalamp</h1>
  <p><strong>Open-source Markdown editor for macOS.</strong></p>
  <p>Live formatting · Local .md files · Multiple tabs · Split view · No sign-up</p>
  <p><a href="https://diyalamp.com">Website</a> · <a href="https://github.com/diyalamp/diyalamp/releases">Releases</a> · <a href="https://github.com/diyalamp/diyalamp/issues">Issues</a> · <a href="CONTRIBUTING.md">Contributing</a></p>
  <img alt="Apache 2.0" src="https://img.shields.io/badge/license-Apache%202.0-3C4545?style=flat-square">
  <img alt="macOS" src="https://img.shields.io/badge/platform-macOS-3C4545?style=flat-square">
</div>

## Overview

diyalamp is a desktop Markdown editor with rendered formatting and local file storage. It is an open-source alternative to Typora and a no-sign-up option for personal writing in a Notion-like interface.

The application opens a folder of `.md` or `.markdown` files. Editing does not require an account, subscription, server, database, or internet connection. Notes remain readable by other Markdown tools.

## Development features · 1.3.0

This describes the 1.3.0 development workspace. See macOS downloads below for the published release.

- Dark-only macOS workspace using maintained AtlantaFX CupertinoDark controls and the official VS Code 2026 Dark palette, with a file explorer, note tabs, and flat Lucide icons.
- One 16 px text size throughout: static Inter for interface/prose and headings, with weight indicating headings or emphasis; installed Menlo for code.
- Rendered headings, bold, italic, inline code, links, lists, checklists, quotes, and fenced code blocks.
- Formatting controls, Markdown typing shortcuts, and slash commands.
- Folder explorer with nested Markdown files, closable note tabs, and side-by-side editing.
- Workspace search for filenames or note text, with case, whole-word, regex, and include/exclude filters. Text matches are grouped by note.
- Local autosave with external-file conflict detection.
- Note creation, import, rename, export, recoverable deletion, and restore.
- Undo/redo and macOS keyboard shortcuts.
- `[[Note name]]` links with directed incoming/outgoing navigation and a SmartGraph panel that retains layout and zoom during note selection and safe saves.
- Zen visually fades text away from the current caret line while keeping the toolbar, sidebars, tabs, and status visible. Its control stays in the same place.
- Smooth accent transitions, matching dark dialogs, and restrained hover paint transitions without moving controls.
- Icon-only top-toolbar Pomodoro and a color-circle accent control. Pomodoro opens an animated popover for Start, Stop, and Reset; its tooltip and accessibility help show the phase and remaining time.
- Drag tabs between panes or move notes into workspace folders without overwrites.
- A unified macOS toolbar with centered native window buttons and one clickable workspace path. The files sidebar has search and creation controls, with split, inline rename, and recoverable delete on each note row.
- Character, word, and logical-line counts for rendered text, plus the current Markdown's UTF-8 byte size in the status bar.
- Native draggable dividers for both sidebars, with saved pixel widths and independent open/closed states. An icon-only reset restores the default widths without closing notes or changing Zen.
- Six accent choices with a blue default and explicit in-app update checks.

## macOS downloads

Version **1.1.3** is available from [GitHub Releases](https://github.com/diyalamp/diyalamp/releases/tag/v1.1.3) and [diyalamp.com](https://diyalamp.com).

- [Apple silicon DMG · 48.0 MB](https://github.com/diyalamp/diyalamp/releases/download/v1.1.3/diyalamp-1.1.3-macos-arm64.dmg)
- [Intel DMG · 49.5 MB](https://github.com/diyalamp/diyalamp/releases/download/v1.1.3/diyalamp-1.1.3-macos-x86_64.dmg)

Drag diyalamp to Applications. See the [installation guide](docs/install.md) for choosing Apple silicon or Intel, verifying the SHA-256 checksum, first-launch approval, and current macOS compatibility limits.

**Size target: under 5 MB installed. This target has not been achieved.** Footprint reduction is tracked as an engineering objective, not a current product claim.

The package includes a runtime; users do not need to install Java separately. The apps from the verified release DMGs occupy approximately **64.8 MiB** (Apple silicon) and **67.3 MiB** (Intel). Release downloads are **48,033,713 bytes** (Apple silicon) and **49,514,944 bytes** (Intel). Architecture and runtime builds can differ; release assets provide SHA-256 checksums.

This community release is **unsigned and not Apple-notarized**. macOS may display a security warning. Signing and notarization are not configured yet.

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

The dark [website and browser notebook](https://diyalamp.com/#workspace) retain their separately approved presentation. The notebook is a separate demonstration, not a screenshot of the desktop app; its browser storage is not automatically synced with desktop files. Website source is in [diyalamp.github.io](https://github.com/diyalamp/diyalamp.github.io).

Previous AI-agent, browser-automation, and writing-review experiments remain in the repository history and existing folders. They are not dependencies of the desktop editor. Current application development belongs in `desktop/`.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) and [product scope](docs/PRODUCT.md). Use [Issues](https://github.com/diyalamp/diyalamp/issues) for reproducible bugs, Markdown compatibility cases, accessibility checks, and proposed improvements. Do not attach private notes or credentials.

## License

[Apache License 2.0](LICENSE). Dependencies and brand assets retain their own notices in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
