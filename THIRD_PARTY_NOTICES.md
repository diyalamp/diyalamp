# Third-party notices

## Desktop writing app

- FlatLaf 3.7.2, copyright FormDev Software GmbH, Apache License 2.0. Official project: https://github.com/JFormDesigner/FlatLaf. The pinned JAR retains its upstream notices. The dependency checksum is verified during the build.
- CommonMark Java 0.32.0 (core and GFM strikethrough), copyright Atlassian Pty Ltd and contributors, BSD 2-Clause. Official project: https://github.com/commonmark/commonmark-java. The build pins both artifact checksums; upstream licenses are retained in the JARs and application resources.
- FlatLaf Extras 3.7.2 (Apache 2.0) and JSVG 2.1.0 (MIT) render bundled SVG icons. Upstream JAR notices are retained.
- Lucide icons, ISC and Feather-derived MIT notices: bundled `Lucide-LICENSE.txt`. Source revision 1980e3d59b0fa1a2aeb566d5902b25ee98204001; Bold icon from official Lucide tag 0.468.0.
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
