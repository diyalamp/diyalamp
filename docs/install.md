# Install diyalamp on macOS

Download [DiyaLamp-macOS.dmg](https://github.com/diyalamp/diyalamp/releases/download/v0.1.0/DiyaLamp-macOS.dmg) for macOS 15 or later. The universal installer runs on Apple silicon and Intel Macs with macOS system frameworks.

## Install or replace the app

1. If diyalamp is open, save your notes and quit with `⌘Q`.
2. Open the DMG in Finder and drag **diyalamp.app** into **Applications**. Choose **Replace** if Finder asks about an existing copy.
3. Eject the disk image and open diyalamp from **Applications**.
4. Choose **File → Open Folder…** or press `⇧⌘O`. Select the same notes folder if you used the previous Java app.

The replacement affects `/Applications/diyalamp.app`. Your saved Markdown stays in its existing folder. For the move from Java, save or export any unfinished drafts and restore any notes you want from the old app before replacing it. Choose your folder again in the native app. Use this same manual replacement process to install future releases.

## First launch

The app is ad hoc signed and distributed without Apple notarization. If macOS says the developer can’t be verified, check that the DMG came from this project’s GitHub release. If you trust the download and choose to open it, try opening the app once, then go to **System Settings → Privacy & Security → Open Anyway** and review the confirmation. If macOS reports malware or a damaged app, stop and report the message. [Apple’s first-launch guidance](https://support.apple.com/en-us/102445)

## Verify the download

For an optional integrity check, download the [matching SHA-256 file](https://github.com/diyalamp/diyalamp/releases/download/v0.1.0/DiyaLamp-macOS.dmg.sha256). Keep both files in the same folder with their original names. If you downloaded them to Downloads, run this in Terminal.

```sh
cd ~/Downloads
shasum -a 256 -c DiyaLamp-macOS.dmg.sha256
```

The result should read `DiyaLamp-macOS.dmg: OK`. If the checksum differs, download the matching pair again. The check verifies that the disk image matches the published file.

## Get help

If you run into a problem, include the app version, your macOS version, your Mac chip or processor, and the exact error. Leave private notes and paths out of the report. [Report an issue](https://github.com/diyalamp/diyalamp/issues)
