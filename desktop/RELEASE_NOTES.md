## diyalamp 1.0.1

A redesigned dark workspace and note-integrity update for the local macOS Markdown editor.

### Interface

- Inter throughout menus, controls, documents, and code text.
- A compact action rail, file explorer, topmost note tabs, and sticky document controls.
- FlatLaf components and official Lucide vector icons replace the old text toolbar.
- The website notebook uses adapted shadcn/ui sidebar-07 components with the matching workspace composition.

### Fixed

- Closing the final saved tab now releases its session; reopening reads the current file on disk.
- **File → Reload note from disk…** provides an explicit path out of save conflicts. Unsaved edits require confirmation before replacement. A failed read leaves the recovery copy open.
- Nested emphasis, escaped Markdown punctuation, link destinations and titles, and fenced code examples survive save/reopen.
- Empty and adjacent fenced code blocks keep their boundaries and languages. Longer fences can safely contain shorter fence examples.
- Closing a shared split view detaches its document observers without disconnecting the remaining view.
- Folder changes clear the previous folder's undo-delete target.

### Downloads

- **arm64**: Apple silicon Macs.
- **x86_64**: Intel Macs.

Both DMGs include an application and runtime; no separate Java installation is required. Drag diyalamp to Applications. Exact download sizes and SHA-256 files are listed with the release assets.

**This community release is unsigned and not Apple-notarized. macOS may show a Gatekeeper warning or block launch.** Signing and notarization are not configured.

### Scope and footprint

The app supports common Markdown editing, not the full CommonMark block specification. Rich tables, remote-image loading, plugins, sync, team databases, and publication services remain outside this release. The browser notebook stores its own separate notes.

Under 5 MB installed remains an engineering target, not an achieved result. The bundled runtime accounts for most of the app's footprint; download sizes are measured per architecture. The previous local Apple silicon installation occupied about 46 MiB.

### Validation

Regression checks exercise formatting round trips, real writing actions, save conflicts, reopen/reload behavior, and disposal of shared editing views. Both architecture jobs verify their disk images and checksums. The local Apple silicon installation occupies about 47 MiB. Native interaction checks use fictional notes. Intel UI behavior has not been manually tested on a physical Intel Mac.

Tracked issue: https://github.com/diyalamp/diyalamp/issues/3

Source: https://github.com/diyalamp/diyalamp/tree/main/desktop. License: Apache 2.0, with runtime, component, parser, font, and artwork notices retained.
