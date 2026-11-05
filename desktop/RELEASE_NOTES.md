## diyalamp 1.1.1

The macOS app now uses maintained JavaFX, AtlantaFX, and RichTextFX components for its light-only writing workspace. The custom Swing text renderer is replaced by RichTextFX text layout, selection, caret, and undo behavior. The existing Markdown parser remains a semantic adapter.

### Workspace

- A folder explorer with nested Markdown files, visible extensions, rounded search, and hover/focus file actions.
- Closable, reorderable tabs, with new note, split view, and a visible accent picker together.
- Six muted accent choices with circular previews, quieter light surfaces, installed macOS System text faces, and Menlo for code. No font files are bundled.
- Rendered editing, slash commands, links, lists, checklists, quotes, code blocks, keyboard commands, and side-by-side notes.
- Debounced and incremental Markdown serialization to keep whole-note conversion off the immediate typing path.
- The approved padded lamp icon, with subtle backplate shine and shadow.

### Local files and updates

- Save conflicts retain edits instead of overwriting external changes.
- Recoverable deletion preserves original nested locations across app restarts.
- Folder/trash boundary checks reject links that would move notes outside the selected folder.
- Help → Check for updates contacts the project's fixed GitHub release channel only when requested. It can download and verify the matching Mac installer, save notes, quit, replace the app, and restart. Save conflicts cancel installation. A rollback copy is retained; administrator access and security bypasses are not requested.

### Downloads and verification

Apple silicon and Intel DMGs include the app and runtime. No separate Java installation or account is required for writing. Matching SHA-256 files accompany the installers. See the [installation guide](https://github.com/diyalamp/diyalamp/blob/main/docs/install.md).

This community release is **unsigned and not Apple-notarized**. A matching checksum verifies downloaded bytes, not cryptographic publisher identity. macOS may require per-app first-launch approval.

Tests cover Markdown preservation, actual JavaFX controls, keyboard editing and undo, immediate saves after typing bursts, conflict handling, folder boundaries, and updater verification/rollback fixtures. Physical Intel UI testing and a complete VoiceOver review remain unverified. Actual artifact and deployment checks are recorded with the release work.

The bundled runtime dominates installed size. The under-5-MB installed target remains unachieved; exact download sizes are listed with the release assets. No unmeasured startup or throughput claims are made.

License: Apache 2.0. Upstream component and brand-asset notices are retained.
