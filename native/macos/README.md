# DiyaLamp native macOS preview

This preview uses AppKit for the window, editor and file list. It links a pinned cmark-gfm build into the executable. The disk image contains a universal binary for Apple silicon and Intel, the existing DiyaLamp icon, and the Inter fonts. It requires macOS 15 or later and runs with the system frameworks.

Open a folder to list Markdown files, including notes in subfolders. Select a note to edit it. If the current note has unsaved changes, choose Save, Discard or Cancel before switching. A file that changed on disk blocks the save and leaves the editor text open. The file list skips hidden files and symbolic links. Supported rich-text edits map back to Markdown, with the source pane available for structural changes.

## Build and verify

From the repository root on a Mac with Xcode command-line tools and Python 3 installed:

```sh
preview="$PWD/native/macos"
cmark="$preview/build/cmark"
sh "$preview/prepare-cmark.sh" "$cmark"
sh "$preview/test.sh" "$cmark"
sh "$preview/build.sh" "$PWD/desktop/src/main/resources" "$cmark"
```

The preparation step verifies the archive checksum before compiling the parser. Pass a local copy of the pinned archive as the optional second argument for an offline build. The app build prints the output directory, DMG size, and installed bundle size. It rejects builds at or above 5,000,000 bytes for either the DMG or bundle. The stretch target is a DMG below 2,000,000 bytes.

Run the verifier with the DMG from the printed build directory:

```sh
sh "$preview/verify.sh" /absolute/path/to/DiyaLamp-Native-Preview.dmg \
  "$cmark/cmark-gfm-0.29.0.gfm.13/COPYING"
```

Verification checks the mounted package, signature, executable slices, bundled parser license, and editing and folder operations in disposable test files. It runs on Apple silicon and, where available, Intel through Rosetta.

The preview has a separate bundle identifier. It is ad hoc signed for local testing. Autosave and crash recovery, workspace search, linked-note navigation, and migration from the current desktop app still need release work.
