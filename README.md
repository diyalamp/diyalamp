<div align="center">
  <img src="assets/lamp.png" width="72" height="72" alt="diyalamp lamp">
  <h1>diyalamp</h1>
  <p><strong>A tool for the mind.</strong></p>
  <p>A quiet, open source Markdown writing app for macOS.<br>Familiar pages. Your own files. Room to think.</p>
  <p><a href="https://diyalamp.com">Website</a> · <a href="https://github.com/diyalamp/diyalamp/releases">Downloads</a> · <a href="https://github.com/diyalamp/diyalamp/issues">Issues</a> · <a href="CONTRIBUTING.md">Contribute</a></p>
  <img alt="License: Apache 2.0" src="https://img.shields.io/badge/license-Apache%202.0-3C4545?style=flat-square">
  <img alt="Platform: macOS" src="https://img.shields.io/badge/platform-macOS-3C4545?style=flat-square">
</div>

---

## A simpler place to write

If you enjoy Notion's calm writing surface or Typora's focus on Markdown, diyalamp is being built for that kind of work: open a folder, write in readable pages, and keep your notes as ordinary `.md` files.

No account, subscription, cloud backend, or proprietary document format is required for local writing.

## Desktop release

The macOS app is being implemented in [`desktop/`](desktop/). A downloadable DMG will appear in [Releases](https://github.com/diyalamp/diyalamp/releases) after packaging and local verification. There is no desktop download to install yet. The [website](https://diyalamp.com) already includes a working browser notebook.

The first desktop release is focused on:

- Rendered Markdown writing with headings, emphasis, lists, quotes, and code blocks.
- A local folder of notes, search, multiple tabs, and side-by-side writing.
- File saving, renaming, deletion with recovery, and keyboard shortcuts.
- A self-contained macOS app: installation will not require a separate Java setup.

This is a focused writing app, rather than a replacement for Notion's team databases, Obsidian's plugin ecosystem, or a general-purpose code editor. Independent projects are named only to explain the intended writing experience.

## Help shape it

Small, thoughtful contributions are welcome. Start with the [contributor guide](CONTRIBUTING.md), read the [product scope](docs/PRODUCT.md), or browse [open issues](https://github.com/diyalamp/diyalamp/issues). Report a reproducible problem, suggest a writing workflow, or help improve accessibility and documentation.

The desktop application is built with Java 21 and Swing. The website lives in [diyalamp.github.io](https://github.com/diyalamp/diyalamp.github.io). Older AI-agent and writing-review experiments remain in the history and existing folders; they are not the desktop product or required services.

## License

[Apache License 2.0](LICENSE). Third-party components retain their own notices in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
