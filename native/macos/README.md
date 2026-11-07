# DiyaLamp native macOS preview

This preview uses AppKit for the window, editor and file list. It links a pinned cmark-gfm build into the executable. The disk image contains a universal binary for Apple silicon and Intel, the existing DiyaLamp icon, and the Inter fonts. It requires macOS 15 or later and runs with the system frameworks.

Open a folder to list Markdown files, including notes in subfolders. Select a note to edit it. If the current note has unsaved changes, choose Save, Discard or Cancel before switching. A file that changed on disk blocks the save and leaves the editor text open. The file list skips hidden files and symbolic links. Supported rich-text edits map back to Markdown, with the source pane available for structural changes.

Use the Files sidebar to search filenames or Markdown text, including changes in the active editor. The search options offer match case, whole words, regular expressions, and include and exclude path globs. Press Command-P for filename search or Command-Option-F for text search. Double-click a result or press Return to open it. Text results select the match in the Markdown source pane. Large or unreadable notes can be skipped, and the sidebar indicates when a search returns partial results.

The editor keeps a local recovery copy of unsaved changes to the active note, checkpointed at 250 ms intervals while editing. On restart, an unchanged original reopens with the recovered edits still unsaved. If the file changed or disappeared, save the recovered text to a separate Markdown file. Changes made since the last checkpoint can be lost in a crash.

## Build and verify

From the repository root on a Mac with Xcode command-line tools and Python 3 installed:

```sh
preview="$PWD/native/macos"
cmark="$preview/build/cmark"
sh "$preview/prepare-cmark.sh" "$cmark"
sh "$preview/test.sh" "$cmark"
sh "$preview/build.sh" "$PWD/desktop/src/main/resources" "$cmark"
```

The preparation step verifies the archive checksum before compiling the parser. Pass a local copy of the pinned archive as the optional second argument for an offline build. The app build losslessly recompresses the existing icon and prints the output directory, DMG size, and installed bundle size. It rejects builds at or above 5,000,000 bytes for either the DMG or bundle. The stretch target is a DMG below 2,000,000 bytes.

Run the verifier with the DMG from the printed build directory:

```sh
sh "$preview/verify.sh" /absolute/path/to/DiyaLamp-Native-Preview.dmg \
  "$cmark/cmark-gfm-0.29.0.gfm.13/COPYING"
```

Verification checks the mounted package, signature, executable slices, bundled parser license, and editing, folder and search operations in disposable test files. It kills the running app after a recovery checkpoint, then checks recovery after restart with unchanged, externally changed, missing, or substituted files. It runs on Apple silicon and, where available, Intel through Rosetta.

The preview has a separate bundle identifier. It is ad hoc signed for local testing. Autosave to the original Markdown file, linked-note navigation, and migration from the current desktop app still need release work.
