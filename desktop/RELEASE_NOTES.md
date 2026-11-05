## diyalamp 1.0.3

The macOS editor now uses a light-only workspace with white writing pages, soft-gray panels, and dark text. Interface and prose use the installed macOS system font; inline code and code blocks use installed Menlo. No font files are bundled. The app icon keeps the approved lamp's size and position, adding a rounded white backplate, subtle shine, and a soft shadow.

### Writing controls

- A native Format menu exposes formatting and block commands.
- Command K inserts or edits links; text typed after a link stays outside it.
- Command Shift Enter toggles the current checklist item.
- Toolbar controls accept keyboard focus, and the editor exposes checklist state to accessibility tools.
- Inline-code toggling restores the appropriate prose or code-block font.

### Downloads

- **arm64**: Apple silicon Macs.
- **x86_64**: Intel Macs.

Both DMGs include the application and runtime. No separate Java installation is required. Exact asset sizes and SHA-256 files accompany the installers. See the [installation guide](https://github.com/diyalamp/diyalamp/blob/main/docs/install.md) for architecture selection, integrity checks, and installation.

This community release remains **unsigned and not Apple-notarized**. macOS may block launch. No security protections are disabled by the installer.

### Validation

The desktop regression suite covers Markdown round trips, save conflicts, deletion recovery, keyboard actions, font selection, and light-theme contrast. Both architecture packaging jobs verify their DMGs and checksums. Native Apple silicon UI checks are recorded with the release work; physical Intel UI testing and a complete VoiceOver audit remain unverified.

Removing the bundled font reduces the application payload. The bundled runtime still dominates installed size; the under-5-MB installed target has not been achieved. No speed improvement is claimed without a benchmark.

License: Apache 2.0, with third-party and brand-asset notices retained.
