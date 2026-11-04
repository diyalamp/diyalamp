# Third-party notices

## Desktop writing app

- FlatLaf 3.7.2, copyright FormDev Software GmbH, Apache License 2.0. Official project: https://github.com/JFormDesigner/FlatLaf. The pinned JAR retains its upstream notices. The dependency checksum is verified during the build.
- Inter 4.1, copyright the Inter Project Authors, SIL Open Font License 1.1. The font and license are bundled in the desktop resources. https://github.com/rsms/inter
- The self-contained macOS package includes an OpenJDK runtime. Its upstream notices are retained in `Contents/runtime/Contents/Home/legal`.
- The approved lamp artwork is a project brand asset. It is not relicensed as original Apache-licensed artwork.

## Historical prototypes

Gson 2.14.0 is used for JSON parsing, copyright Google Inc., licensed under Apache License 2.0.

- Project: https://github.com/google/gson
- License: [bundled Apache License 2.0](licenses/Gson-LICENSE.txt)

The build pins the artifact SHA-256 and downloads it into the ignored build directory. Distributions must retain Gson's bundled license and notices.

## Optional database integration test

PostgreSQL JDBC 42.7.14 is downloaded from Maven Central into the ignored build
directory by `coordination-test.sh`, with a pinned SHA-256. It is not bundled in
the CLI distribution. The driver and its dependencies retain the license files
inside the upstream JAR, including `META-INF/LICENSE` and `META-INF/licenses/`.
Project and upstream license: https://github.com/pgjdbc/pgjdbc

## TypeScript workspace

The new workspace uses Playwright (Apache-2.0), TypeScript (Apache-2.0), tsx (MIT), esbuild (MIT), and Node.js types (MIT). Exact dependency versions and integrity hashes are recorded in workspace/pnpm-lock.yaml. The Node.js runtime retains its upstream license. Inter is provided under the SIL Open Font License in workspace/public/fonts/Inter-LICENSE.txt. Dependencies retain their own upstream notices; they are not relicensed by this project.

CommonMark Java 0.32.0 (core and GFM strikethrough), Atlassian Pty Ltd and contributors. BSD 2-Clause license: CommonMark-LICENSE.txt. https://github.com/commonmark/commonmark-java

FlatLaf Extras 3.7.2 (Apache2.0), JSVG2.1.0 (MIT; notices retained in dependency JAR), and Lucide SVG icons (ISC/FeatherMIT; Lucide-LICENSE.txt). Native icon source revision1980e3d59b0fa1a2aeb566d5902b25ee98204001, Bold from Lucide0.468.0.
