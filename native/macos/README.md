# DiyaLamp for macOS

DiyaLamp uses AppKit for the window, editor and file list. It links a pinned cmark-gfm build into the executable. The disk image contains a universal binary for Apple silicon and Intel, the DiyaLamp icon, and the Inter fonts. It targets macOS 15 or later and runs with the system frameworks.

Open a folder to list Markdown files, including notes in subfolders. Select a note to edit it. If the current note has unsaved changes, choose Save, Discard or Cancel before switching. A file that changed on disk blocks the save and leaves the editor text open. The file list skips hidden files and symbolic links. Supported rich-text edits map back to Markdown, with the source pane available for structural changes.

Use the Files sidebar to search filenames or Markdown text, including changes in the active editor. The search options offer match case, whole words, regular expressions, and include and exclude path globs. Press Command-P for filename search or Command-Option-F for text search. Double-click a result or press Return to open it. Text results select the match in the Markdown source pane. Large or unreadable notes can be skipped, and the sidebar indicates when a search returns partial results.

Use `[[Note]]` or `[[folder/Note#heading|alias]]` to link Markdown notes. Click a wiki link in the rendered editor or open a result in the Connections sidebar. Incoming links are notes that point to the open note. Outgoing links include resolved wiki links and local Markdown links. The sidebar reflects unsaved changes to the open note and reports unresolved wiki links. Use its refresh button to pick up changes made outside DiyaLamp.

The editor keeps a local recovery copy of unsaved changes to the active note, checkpointed at 250 ms intervals while editing. On restart, an unchanged original reopens with the recovered edits still unsaved. If the file changed or disappeared, save the recovered text to a separate Markdown file. Changes made since the last checkpoint can be lost in a crash.

## Build and verify

From the repository root on a Mac with Xcode command-line tools and Python 3 installed:

```sh
native="$PWD/native/macos"
cmark="$native/build/cmark"
sh "$native/prepare-cmark.sh" "$cmark"
sh "$native/test.sh" "$cmark"
sh "$native/build.sh" "$PWD/desktop/src/main/resources" "$cmark"
```

The preparation step verifies the archive checksum before compiling the parser. Pass a local copy of the pinned archive as the optional second argument for an offline build. The app build losslessly recompresses the existing icon and prints the output directory, DMG size, and installed bundle size. It rejects builds at or above 5,000,000 bytes for either the DMG or bundle. The stretch target is a DMG below 2,000,000 bytes.

Run the verifier with the DMG from the printed build directory:

```sh
sh "$native/verify.sh" /absolute/path/to/DiyaLamp-macOS.dmg \
  "$cmark/cmark-gfm-0.29.0.gfm.13/COPYING"
```

Verification checks the mounted package, signature, executable slices, bundled parser license, and editing, folder, search and link operations in disposable test files. It kills the running app after a recovery checkpoint, then checks recovery after restart with unchanged, externally changed, missing, or substituted files. It runs on Apple silicon and, where available, Intel through Rosetta.

The app is ad hoc signed. See the [install guide](../../docs/install.md) for first launch and how to open an existing DiyaLamp folder after replacing the older app.
