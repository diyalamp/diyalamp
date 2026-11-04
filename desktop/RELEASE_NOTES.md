## diyalamp 1.0.2

The macOS application icon now uses the existing diyalamp profile-picture artwork: the lamp centered on a white background with padding. Both the packaged icon and application-window icon use this asset. The smaller inline lamp mark is unchanged.

### Downloads

- **arm64**: Apple silicon Macs.
- **x86_64**: Intel Macs.

Both DMGs include the application and runtime. No separate Java installation is required. Exact asset sizes and SHA-256 files accompany the installers.

This community release remains **unsigned and not Apple-notarized**. macOS may block launch. No security protections are disabled by the installer.

### Validation

The automated checks verify that the application artwork remains square and padded and that the display icon uses it. Both architecture jobs run the desktop regression suite, verify their DMGs, and validate checksums. The native app keeps its dark Inter workspace and local Markdown storage from 1.0.1.

The under-5-MB installed footprint remains an unachieved engineering target. Release 1.0.1 occupied about 47 MiB locally on Apple silicon; exact download sizes for this release appear with the assets.

License: Apache 2.0, with third-party and brand-asset notices retained.
