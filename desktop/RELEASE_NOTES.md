## diyalamp 1.0.0

Open-source Markdown editor for macOS. Live formatting, local `.md` files, multiple tabs, and split view. No sign-up or backend.

### Downloads

- **arm64**: Apple silicon Macs. Download: 35,661,621 bytes (35.7 MB).
- **x86_64**: Intel Macs. Download: 37,309,904 bytes (37.3 MB).

Each DMG contains a self-contained application and runtime. No separate Java installation is required. Drag diyalamp to Applications to install. SHA-256 files accompany both installers.

**This first community release is unsigned and not Apple-notarized. macOS may show a Gatekeeper warning or block launch.** Apple signing and notarization are not configured for this release.

### Included

- Dark-only interface with JRXNA black, white, red, and dark-gray writing surfaces.
- Compact file navigation, card-style tabs, quiet formatting controls, and an adaptive writing column.
- Rendered headings, emphasis, links, lists, checklists, quotes, and code blocks.
- Formatting controls, typing shortcuts, slash commands, and undo/redo.
- Local folders, note creation/import/rename/export, filename search, tabs, and split editing.
- Autosave with external-edit conflict detection; recoverable deletion inside the notes folder.

### Scope and size

This release supports common Markdown, not the entire CommonMark specification. Rich tables, remote-image previews, plugins, sync, team databases, and publication services are outside this release. The browser notebook is separate and does not automatically sync with desktop files.

**Under 5 MB installed is an aspirational size target, not an achieved result.** The local Apple silicon app occupies approximately 46 MiB installed; release download sizes are listed above. The bundled runtime accounts for most of the footprint. Exact download sizes are shown beside the release assets.

### Validation

The build runs Markdown and local-file safety checks on macOS. Both architecture jobs verify their DMG images. Apple silicon was also opened locally to check rendered notes, direct typing, undo, saving, slash commands, tabs, and split view. Intel UI behavior has not been manually tested on a physical Intel Mac.

Source: [desktop/](https://github.com/diyalamp/diyalamp/tree/main/desktop). License: Apache 2.0, with third-party runtime, UI, font, and artwork notices retained.
