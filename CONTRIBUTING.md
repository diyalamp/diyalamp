# Contributing to diyalamp

Contributions improve the local Markdown editor, file safety, and macOS integration.

## Test the Mac app

Install the current build using the [macOS install guide](docs/install.md). Pick one writing task, such as finding a note, closing a tab, or switching between split panes. Try it with the keyboard or VoiceOver in a folder of fictional notes. Report what you expected, what happened, and the app version from **Help → About diyalamp**. [Open VoiceOver review](https://github.com/diyalamp/diyalamp/issues/1)

For other issues, include your macOS version and whether the Mac uses Apple silicon or Intel. Share the smallest Markdown example that reproduces the problem. Keep private notes, credentials, and personal paths out of reports. For sensitive security reports, follow [SECURITY.md](SECURITY.md).

## Build from source

Use JDK 25 or newer on a Mac. Run these commands from the root of your clone. The first build downloads and verifies pinned dependencies.

```sh
sh desktop/test.sh

workspace=$(mktemp -d "${TMPDIR:-/tmp}/diyalamp-contribute.XXXXXX")
printf '# Draft\n\nRead [[Reference]].\n' > "$workspace/Draft.md"
printf '# Reference\n\nA fictional note for testing.\n' > "$workspace/Reference.md"
sh desktop/run.sh --workspace "$workspace"
```

Open both notes and follow the link from Draft to Reference. The temporary workspace keeps tests away from your own files, and this launch does not replace the app's last-opened folder. App-wide preferences still apply. See [desktop development](desktop/README.md) for JDK selection and packaging, or [the installer size issue](https://github.com/diyalamp/diyalamp/issues/2) for runtime and package work.

## Pull requests

The desktop application lives in `desktop/` and the website has [its own repository](https://github.com/diyalamp/diyalamp.github.io). Check the [product scope](docs/PRODUCT.md) and open an issue before a large change. Small documentation corrections and reproducible bug fixes can go straight to a pull request.

Describe the problem, the resulting behavior, and how you checked it. Add focused tests for changes that could affect file safety or Markdown round trips. For UI changes, include a screenshot and check the affected flow with the keyboard.

## Design

Follow the [desktop design rules](desktop/README.md#interface-design). Reuse the shared spacing tokens and existing AtlantaFX controls, RichTextFX editor, and SmartGraph panel. Keep the local writing flow independent of sign-in, telemetry, and hosted services.

Contributions are licensed under the repository's Apache 2.0 license.
