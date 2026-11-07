# Contributing to diyalamp

The maintained app is a native Markdown editor for macOS. Contributions improve writing, local file safety, and AppKit integration.

## Test the Mac app

Install the current build from the [macOS installation guide](docs/install.md). Open a folder of fictional notes and try editing, saving, searching, or following a note link using the keyboard or VoiceOver. Report what you expected, what happened, and which app version you tested.

Include your macOS version and whether the Mac uses Apple silicon or Intel. Share the smallest Markdown example that reproduces the problem. Keep private notes, credentials, and personal paths out of reports. For sensitive security reports, follow [SECURITY.md](SECURITY.md).

## Build from source

Use a Mac with macOS 15 or later, the Xcode command-line tools, and Python 3. From the repository root, prepare the pinned Markdown parser and run the source tests.

```sh
native="$PWD/native/macos"
cmark="$native/build/cmark"
sh "$native/prepare-cmark.sh" "$cmark"
sh "$native/test.sh" "$cmark"
```

The [native build guide](native/macos/README.md) explains how to build and verify the universal installer. Test changes in a disposable notes folder.

## Pull requests

The maintained app lives in `native/macos/`. The Java implementation in `desktop/` is historical, and the [website](https://github.com/diyalamp/diyalamp.github.io) has its own repository.

Open an issue before a large change. Small documentation corrections and reproducible fixes can go straight to a pull request. Describe the problem, the resulting behavior, and how you checked it. Add focused coverage for changes to file safety, recovery, or Markdown round trips. For UI changes, include a screenshot and check the affected flow with the keyboard.

Use the existing AppKit controls and align related controls to the workspace’s shared spacing. Keep the writing flow independent of sign-in, telemetry, and hosted services.

Contributions are licensed under the repository’s [Apache License 2.0](LICENSE).
