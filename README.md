<div align="center">
  <h1><img src="assets/lamp.png" width="32" height="32" alt="" align="absmiddle"> diyalamp</h1>
  <p><strong>Open-source Markdown editor for macOS.</strong></p>
  <p>Rendered Markdown · Local files · Workspace search · Backlinks</p>
  <p><a href="https://diyalamp.com">Website</a> · <a href="https://github.com/diyalamp/diyalamp/releases/tag/v0.1.0">Release</a> · <a href="https://github.com/diyalamp/diyalamp/issues">Issues</a> · <a href="CONTRIBUTING.md">Contributing</a></p>
  <img alt="Apache 2.0" src="https://img.shields.io/badge/license-Apache%202.0-3C4545?style=flat-square">
  <img alt="macOS" src="https://img.shields.io/badge/platform-macOS-3C4545?style=flat-square">
</div>

diyalamp opens a folder of Markdown files on your Mac. You can write in rendered Markdown, search the folder, and follow links between notes. It works offline with ordinary `.md` and `.markdown` files.

## Download for macOS

**Version 0.1.0** requires **macOS 15 or later**. The same installer works on Apple silicon and Intel Macs. The app occupies **3.69 MB** on disk.

- [Universal Mac DMG · 1.92 MB](https://github.com/diyalamp/diyalamp/releases/download/v0.1.0/DiyaLamp-macOS.dmg)
- [SHA-256 checksum](https://github.com/diyalamp/diyalamp/releases/download/v0.1.0/DiyaLamp-macOS.dmg.sha256)

Quit diyalamp and drag the app into Applications. If you're moving from the previous Java app, replace it in Applications and choose **File → Open Folder…** to open your existing notes folder. [Installation and first launch](docs/install.md)

The app is ad hoc signed and distributed without Apple notarization. macOS may ask for first-launch approval. [Apple’s first-launch guidance](https://support.apple.com/en-us/102445)

## Features

- Browse Markdown notes and subfolders from the Files sidebar.
- Edit rendered Markdown or switch to the source pane for direct changes.
- Search filenames and note text, with case, whole-word, regex, and path filters.
- Link notes with `[[Note name]]` and use the Connections sidebar to follow outgoing links and backlinks.
- Save changes to the original file. The editor detects external changes before saving and keeps a recovery copy of pending edits to the active note.

## Build and contribute

The maintained macOS app uses Swift, AppKit, and cmark-gfm. The [build guide](native/macos/README.md) covers the pinned parser, source tests, and universal installer. The previous Java app in `desktop/` and its earlier releases are historical.

See [CONTRIBUTING.md](CONTRIBUTING.md) for testing and pull requests. Website source lives in [diyalamp.github.io](https://github.com/diyalamp/diyalamp.github.io).

## License

The project is licensed under [Apache License 2.0](LICENSE). The Mac app bundles the cmark-gfm and Inter license texts. [Third-party notices](THIRD_PARTY_NOTICES.md)
