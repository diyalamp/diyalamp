<div align="center">
  <img src="assets/lamp.png" width="64" height="64" alt="diyalamp">
  <h1>diyalamp</h1>
  <p><strong>Open-source Markdown editor for macOS.</strong></p>
  <p>Live formatting · Local .md files · Multiple tabs · Split view · No sign-up</p>
  <p><a href="https://diyalamp.com">Website</a> · <a href="https://github.com/diyalamp/diyalamp/releases">Releases</a> · <a href="https://github.com/diyalamp/diyalamp/issues">Issues</a> · <a href="CONTRIBUTING.md">Contributing</a></p>
  <img alt="Apache 2.0" src="https://img.shields.io/badge/license-Apache%202.0-3C4545?style=flat-square">
  <img alt="macOS" src="https://img.shields.io/badge/platform-macOS-3C4545?style=flat-square">
</div>

## Overview

diyalamp is a desktop Markdown editor with rendered formatting and local file storage. It is an open-source alternative to Typora and a no-sign-up option for personal writing in a Notion-like interface.

The application opens a folder of `.md` or `.markdown` files. Editing does not require an account, subscription, server, database, or internet connection. Notes remain readable by other Markdown tools.

## Features

- Rendered headings, bold, italic, inline code, links, lists, checklists, quotes, and fenced code blocks.
- Formatting controls, Markdown typing shortcuts, and slash commands.
- Search by filename, multiple closable note tabs, and side-by-side editing.
- Local autosave with external-file conflict detection.
- Note creation, import, rename, export, recoverable deletion, and restore.
- Undo/redo and macOS keyboard shortcuts.

## macOS downloads

Apple silicon and Intel installers are being verified for the first release. They will be available from [GitHub Releases](https://github.com/diyalamp/diyalamp/releases) and [diyalamp.com](https://diyalamp.com).

The package includes a runtime; users do not need to install Java separately. The current local Apple silicon build is approximately **32 MB downloaded and 46 MB installed**. These are measured package sizes, not a sub-10 MB installation claim. Architecture and runtime builds can differ; release assets provide exact download sizes and SHA-256 checksums.

The first community release is **unsigned and not Apple-notarized**. macOS may display a security warning. Signing and notarization are not configured yet.

## Build and test

Requires JDK 21 or newer. The desktop app uses Swing, FlatLaf, and Inter. There is no JavaScript runtime or web backend in the desktop application.

```sh
sh desktop/build.sh
java -jar desktop/build/diyalamp.jar
sh desktop/test.sh
```

To package a self-contained app and DMG on macOS:

```sh
sh desktop/package-macos.sh
```

See [`desktop/README.md`](desktop/README.md) for dependency checksums, architecture builds, and development with a separate notes folder. Tests cover Markdown round trips, save conflicts, filename collisions, deletion recovery, and file boundaries.

## Storage and privacy

- The writer chooses the notes folder. The default is `~/Documents/diyalamp`.
- Notes are UTF-8 Markdown files, saved locally.
- A file changed by another application is not silently overwritten; edits stay open for export.
- Deleted notes move to `.diyalamp-trash` inside the notes folder and can be restored.
- Embedded HTML is treated as text. Remote images and scripts are not downloaded or executed.
- No account or telemetry service is part of the desktop writing flow.

## Scope

This release supports common Markdown editing. It does not implement the complete CommonMark specification, rich table rendering, remote-image previews, plugins, sync, team databases, or publication integrations. Unsupported text is retained rather than executed.

The [browser notebook](https://diyalamp.com/#workspace) is a separate demonstration; its browser storage is not automatically synced with desktop files. Website source is in [diyalamp.github.io](https://github.com/diyalamp/diyalamp.github.io).

Previous AI-agent, browser-automation, and writing-review experiments remain in the repository history and existing folders. They are not dependencies of the desktop editor. Current application development belongs in `desktop/`.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) and [product scope](docs/PRODUCT.md). Use [Issues](https://github.com/diyalamp/diyalamp/issues) for reproducible bugs, Markdown compatibility cases, accessibility checks, and proposed improvements. Do not attach private notes or credentials.

## License

[Apache License 2.0](LICENSE). Dependencies and brand assets retain their own notices in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
