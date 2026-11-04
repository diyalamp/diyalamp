# diyalamp desktop

A focused, local Markdown writing app for macOS. Built with Java 21, Swing, the FlatLaf macOS dark component theme, and Inter. No backend, model provider, database, or account is required.

## Build and run

Install a JDK 21 or newer, then run from the repository root:

```sh
sh desktop/build.sh
java -jar desktop/build/diyalamp.jar
```

On macOS the script selects JDK 21 when available. To choose another installation, set `DIYALAMP_JAVA_HOME` to the JDK home.

A separate folder of fictional notes is useful while developing:

```sh
java -jar desktop/build/diyalamp.jar --workspace /tmp/diyalamp-notes
```

Build outputs and downloaded dependencies stay in ignored `desktop/build/`. The build pins and verifies FlatLaf's SHA-256. Inter and its license are tracked application resources.

## Tests

```sh
sh desktop/test.sh
```

The tests cover common Markdown round trips, literal HTML, external-edit conflicts, file creation and rename collisions, recoverable deletion, path boundaries, and temporary-file cleanup. The desktop UI also needs manual macOS checks: create, format, save, reopen, split, rename, delete, and restore a fictional note.

## macOS package

On a Mac with JDK 21 and Apple's command-line tools:

```sh
sh desktop/package-macos.sh
```

This produces a self-contained `.app`, a compressed DMG for the current processor architecture, and a SHA-256 file. The runtime is included; users do not need to install Java. The manual GitHub packaging workflow builds separate Apple silicon and Intel artifacts.

The first community release is unsigned and not Apple-notarized. Do not describe it as notarized or suppress macOS security checks. Signing requires the owner's Apple Developer identity and a separate notarization setup.

The interface is dark-only. JRXNA black, white, and #EE0000 red provide the identity; darker gray writing surfaces reduce glare. There is no theme switch.

## Writing and storage

- Open a folder of `.md` or `.markdown` notes. The sidebar searches note names.
- Use rendered text, headings, emphasis, simple lists, checklists, quotes, and fenced code blocks.
- Type `/` on an empty line for blocks, or use formatting controls and keyboard shortcuts.
- Drag a sidebar note to a tab area; dropping near its right edge opens a second pane.
- Autosave writes local files. A changed disk fingerprint stops an overwrite and keeps edits open for export.
- Deleted notes move to `.diyalamp-trash` inside the chosen folder. They can be restored from the app.

Common Markdown is supported; this first release does not implement the full CommonMark specification, rich table layout, remote-image loading, plugins, sync, or team workspaces. HTML remains literal text. Unsupported text is retained rather than executed.
