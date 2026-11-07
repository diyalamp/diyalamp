# Install diyalamp on macOS

The DMG contains the app and its runtime. You do **not** need to install Java, use a terminal, or create an account to write notes. Terminal commands below are optional download-integrity checks.

## Choose the download

Open Apple menu → About This Mac, then choose the matching asset from [GitHub Releases](https://github.com/diyalamp/diyalamp/releases):

| Your Mac | Download name ends with |
| --- | --- |
| **Chip** lists Apple M-series | `macos-arm64.dmg` |
| **Processor** lists Intel | `macos-x86_64.dmg` |

Download the `.dmg` and, if you want to verify it, its matching `.dmg.sha256` file from the **same release**. Use the native architecture; an Apple silicon Mac does not need Rosetta for the arm64 build.

### macOS compatibility

Both architecture packages are built and checked on **macOS 15** in [the packaging workflow](../.github/workflows/desktop-packages.yml). These automated checks cover compilation, regression tests, DMG integrity, and checksums; they do not establish full UI or VoiceOver compatibility. Older macOS versions have not been validated, and the project has not established a minimum supported macOS version. In particular, a low minimum version in generated package metadata is not evidence that the app works on that version.

If installation fails, [report an issue](https://github.com/diyalamp/diyalamp/issues) with your macOS version, chip/processor, app version, and exact error message. Leave personal notes out of the report.

## Verify a download (optional)

Keep the DMG and its checksum file in the same folder without renaming either file. For example, for release **1.1.3** downloaded into Downloads, open Terminal and run the command for your architecture:

Apple silicon:

```sh
cd ~/Downloads
shasum -a 256 -c diyalamp-1.1.3-macos-arm64.dmg.sha256
```

Intel:

```sh
cd ~/Downloads
shasum -a 256 -c diyalamp-1.1.3-macos-x86_64.dmg.sha256
```

For another release, replace `1.1.3` with that release's version in the command. The result should end with **`OK`**. A missing-file error means the files are not together or their names differ. A checksum mismatch means the download does not match the published checksum; discard it and download the matching pair again. This check detects download changes; it does not replace code signing, notarization, or a security review.

## Install and open

1. Double-click the DMG in Finder.
2. Drag **diyalamp** into **Applications**.
3. Eject the mounted diyalamp disk image.
4. Open diyalamp from Applications.

This community release is **unsigned and not Apple-notarized**, so macOS may block the first launch. If you trust the release source and choose to approve this app, try opening it once, then use System Settings → Privacy & Security → **Open Anyway** and review the confirmation. This is Apple's per-app approval flow; see [Apple's current instructions](https://support.apple.com/en-us/102445). If macOS reports malware or a damaged app, stop and report the message instead of trying to bypass it. Organization-managed Macs may require an administrator's approval.

## Updating from the app

From version 1.1.1 onward, choose Help → **Check for updates**. Checking is explicit and does not download or install anything. If a newer stable release exists, choose **Download update**, then **Install and Restart** after verification completes. The app saves pending edits before quitting; a save conflict cancels installation so you can resolve it first.

The updater uses only this project's GitHub release channel and the installer for your Mac architecture. It verifies the SHA-256 checksum, disk image, bundle identity/version, architecture, and staged app. It keeps the previous app as a hidden `.diyalamp-backup-….bundle` beside the installation, and attempts rollback if replacement or launch fails. It does not request administrator rights or change macOS security settings. If your Applications folder is not writable by your account, [update from a DMG](#update-from-a-dmg).

Earlier versions without the updater need one manual replacement. These releases are unsigned; checksum verification is not publisher signature verification.

## Start writing

Choose File → **Open folder…** or press `⌘O` to select your notes folder. Existing notes remain in their original folder. Select that folder to reopen them. The editor reads `.md` and `.markdown` files and saves changes locally. Start with a new or backed-up folder when trying the app for the first time.

## Update from a DMG

To update, quit diyalamp, install the newer DMG, and replace the app in Applications. Notes live separately from the application bundle. Keep a backup of your notes before an update; reinstalling the app does not restore files you deleted from the notes folder.

## Building from source

Only contributors need **JDK 25 or newer**. The refreshed desktop build uses Temurin 25 in CI and targets Java 25 bytecode. Earlier 1.0.x packages used Java 21. macOS packaging also needs Apple's command-line tools. See [the desktop build guide](../desktop/README.md) for build, test, and packaging commands.
