# Contributing to diyalamp

Contributions improve the local Markdown editor, file safety, and macOS integration.

## Start here

The current product is a local macOS Markdown editor. See [product scope](docs/PRODUCT.md). The desktop application belongs in `desktop/`; the website has its own repository. Earlier agent orchestration, browser automation, database, and cloud prototypes are outside the current release.

Before a large change, open an issue describing the writing problem and proposed behavior. Small documentation corrections and reproducible bug fixes can go straight to a pull request.

## Development

Use JDK 25 or newer. Build with `sh desktop/build.sh`, run with `sh desktop/run.sh`, and test with `sh desktop/test.sh`. See [desktop development](desktop/README.md) for packaging details. Keep sample notes fictional and never commit your personal writing or credentials.

## Pull requests

Describe the user-visible problem, the resulting behavior, and how you checked it. Add focused tests for file safety, Markdown round trips, and other behavior that could lose writing. UI changes should include a screenshot and keyboard-access check. Keep unrelated formatting changes out of the patch.

## Design

Use the fixed lamp asset and lowercase **diyalamp** name. The desktop interface is light-only: soft-gray panels, quiet writing surfaces, and a selectable muted accent. Use static Inter Regular 16 for ordinary interface/prose, exact Inter SemiBold faces for headings/emphasis, and installed Menlo 16 for code. Keep the font license/provenance with the bundled static faces. Reuse AtlantaFX Cupertino controls and Popover components, RichTextFX editing behavior, and SmartGraph for note graphs. Maintain compact navigation, readable content, consistent spacing, and native macOS interactions. Avoid sign-in, telemetry, AI services, and paid dependencies in the local writing flow.

## Reporting a problem

Include your macOS version, Mac architecture (Apple silicon or Intel), application version, steps to reproduce, and what you expected. Attach a minimal fictional Markdown example when relevant. Do not include private notes, tokens, or personal file paths.

For sensitive security reports, follow [SECURITY.md](SECURITY.md).

Contributions are licensed under the repository's Apache 2.0 license.
