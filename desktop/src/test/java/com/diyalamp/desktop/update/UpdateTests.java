package com.diyalamp.desktop.update;

import java.io.*;
import java.net.URI;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Offline updater checks. All apps, downloads, and helper transactions are fictional temp files. */
public final class UpdateTests {
  private static int checks;
  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }
  @FunctionalInterface private interface Operation { void run() throws Exception; }
  private static void rejected(Operation action, String message) throws Exception {
    boolean rejected = false;
    try { action.run(); } catch (IOException expected) { rejected = true; }
    check(rejected, message);
  }

  public static void main(String[] args) throws Exception {
    versionsAndHosts();
    stableReleaseChecks();
    verifiedPreparationAndCancellation();
    failedPreparationIsNonDestructive();
    stagingAndOwnershipChecks();
    nativeMetadataValidation();
    runtimeFileLinks();
    verifierEntryPoint();
    helperWaitsAndRetainsBackup();
    helperRollsBack();
    helperRefusesInvalidStage();
    helperReplacementFailure();
    System.out.println("Passed " + checks + " offline update checks.");
  }

  private static void versionsAndHosts() throws Exception {
    check(AppUpdater.compare("1.10.0", "1.9.9") > 0, "Release comparison must be numeric");
    check(AppUpdater.compare("2.0.0", "1.99.99") > 0, "Major versions sort before minor versions");
    check(AppUpdater.compare("1.1.0", "1.1.0") == 0, "The current release is not an update");
    check(AppUpdater.compare("1.0.9", "1.1.0") < 0, "An older release must not be offered");
    for (String version : List.of("1.1", "1.1.0-beta", "01.1.0", "1.1.0/escape", "1.1.0;touch", "9999999999.0.0"))
      rejected(() -> AppUpdater.compare(version, "1.1.0"), "Malformed release versions must be rejected");
    for (String url : List.of("http://github.com/diyalamp", "https://github.com.evil.example/x",
        "https://evil.example/x", "https://user@github.com/x", "https://github.com:444/x", "https://github.com/x#fragment"))
      check(!AppUpdater.allowedHttps(URI.create(url)), "Download destinations must have approved HTTPS origins");
    check(AppUpdater.allowedHttps(URI.create("https://release-assets.githubusercontent.com/file?token=fixture")),
        "GitHub's signed release asset destination remains supported");
  }

  private static void stableReleaseChecks() throws Exception {
    Fixture fixture = new Fixture("arm64");
    check(fixture.requests == 0, "Constructing the updater must not contact GitHub");
    check(fixture.updater.check("1.1.0").orElseThrow().version().equals("1.2.0"), "A new stable release is discovered");
    check(fixture.updater.check("1.2.0").isEmpty(), "Equal releases do not cause a download");
    check(fixture.updater.check("2.0.0").isEmpty(), "The updater must not downgrade the app");
    check(fixture.assetRequests == 0, "Checking for updates must not download assets");
    fixture.latestDestination = "https://github.com/another/project/releases/tag/v9.0.0";
    rejected(() -> fixture.updater.check("1.1.0"), "A different repository is not a release source");
    fixture.latestDestination = "https://github.com/diyalamp/diyalamp/releases/tag/v1.2.0-beta";
    rejected(() -> fixture.updater.check("1.1.0"), "A prerelease tag cannot enter the stable updater");
    check(fixture.closed == fixture.requests, "Responses are closed on success and rejection");
  }

  private static void verifiedPreparationAndCancellation() throws Exception {
    Fixture fixture = new Fixture("arm64");
    AppUpdater.Release release = fixture.updater.check("1.1.0").orElseThrow();
    List<String> progress = new ArrayList<>();
    AppUpdater.PreparedUpdate prepared = fixture.updater.prepare(release, progress::add);
    Path staged = fixture.staged();
    check(prepared.version().equals("1.2.0"), "The prepared result identifies its verified release");
    check(staged.getParent().equals(fixture.installed.getParent()), "The staged app must be adjacent to its installation");
    check(UpdateFiles.plist(UpdateFiles.readSmall(staged.resolve("Contents/Info.plist"), 10000))
        .get("CFBundleShortVersionString").equals("1.2.0"), "Staging keeps the verified bundle version");
    check(fixture.detached, "The verified disk image must be detached after staging");
    check(fixture.commandCalls.getFirst().contains("verify"), "Disk-image verification precedes mounting");
    check(fixture.commandCalls.stream().anyMatch(c -> c.contains("-readonly") && c.contains("-plist")),
        "The image is mounted read-only with structured metadata");
    check(progress.stream().anyMatch(s -> s.contains("Preparing")), "The UI receives a meaningful preparing state");
    check(Files.readString(fixture.notes).equals("Private fictional notes stay here\n"), "Preparing does not touch notes");
    boolean[] quit = {false};
    check(!fixture.updater.installAndRestart(prepared, () -> false, () -> quit[0] = true),
        "A failed save must cancel installation");
    check(!quit[0], "A save conflict must keep the running app open");
    try (var files = Files.list(fixture.cache)) {
      check(files.noneMatch(p -> Files.exists(p.resolve("install-and-restart.sh"))),
          "A save conflict must not launch an installation helper");
    }
    fixture.updater.discard(prepared);
    check(!Files.exists(staged), "Canceling a prepared update removes its own staged app");
    check(Files.isDirectory(fixture.installed), "Canceling must preserve the installed app");
    rejected(() -> fixture.updater.discard(prepared), "A consumed preparation cannot be reused");
  }

  private static void failedPreparationIsNonDestructive() throws Exception {
    Fixture mismatch = new Fixture("arm64");
    mismatch.badChecksum = true;
    rejected(() -> mismatch.updater.prepare(mismatch.release(), s -> {}), "A checksum mismatch must block mounting");
    check(mismatch.commandCalls.isEmpty(), "Unverified installers must not be mounted");
    check(mismatch.stageCount() == 0, "Checksum failure leaves no staged app");
    check(mismatch.currentVersion().equals("1.1.0"), "Checksum failure leaves the installation unchanged");
    Fixture redirect = new Fixture("arm64");
    redirect.assetDestination = "https://unapproved.example/installer.dmg";
    rejected(() -> redirect.updater.prepare(redirect.release(), s -> {}), "Unapproved asset redirects are rejected");
    check(!redirect.unapprovedRequested, "An unapproved origin must never be contacted");
    Fixture identity = new Fixture("arm64");
    identity.imageBundleId = "com.example.other";
    rejected(() -> identity.updater.prepare(identity.release(), s -> {}), "The bundle identity must match diyalamp");
    check(identity.detached && identity.stageCount() == 0, "Invalid bundle identity detaches the image and cleans staging");
    Fixture architecture = new Fixture("arm64");
    architecture.imageArchitecture = "x86_64";
    rejected(() -> architecture.updater.prepare(architecture.release(), s -> {}), "A wrong native architecture is rejected");
    check(architecture.currentVersion().equals("1.1.0"), "Architecture mismatch cannot alter the installed app");
    Fixture canceled = new Fixture("arm64");
    boolean interrupted = false;
    try {
      canceled.updater.prepare(canceled.release(), state -> {
        if (state.contains("Downloading the Mac")) Thread.currentThread().interrupt();
      });
    } catch (InterruptedException expected) { interrupted = true; }
    finally { Thread.interrupted(); }
    check(interrupted && canceled.stageCount() == 0, "Canceled preparation does not leave a staged installation");
  }

  private static void stagingAndOwnershipChecks() throws Exception {
    Fixture linked = new Fixture("arm64");
    Path outside = Files.createDirectory(linked.base.resolve("outside"));
    Files.createSymbolicLink(linked.cache, outside);
    rejected(() -> linked.updater.prepare(linked.release(), s -> {}), "A symlinked cache must not redirect downloads");
    try (var files = Files.list(outside)) {
      check(files.findAny().isEmpty(), "A rejected cache link leaves outside files untouched");
    }
    Fixture fixture = new Fixture("x86_64");
    AppUpdater.PreparedUpdate prepared = fixture.updater.prepare(fixture.release(), s -> {});
    check(fixture.requestedUris.stream().anyMatch(u -> u.getPath().endsWith("macos-x86_64.dmg.sha256")),
        "Intel updates select the exact Intel checksum asset");
    Files.writeString(fixture.staged().resolve("Contents/changed.txt"), "Changed after verification");
    boolean[] quit = {false};
    rejected(() -> fixture.updater.installAndRestart(prepared, () -> true, () -> quit[0] = true),
        "A changed staged app must fail re-verification before helper launch");
    check(!quit[0] && fixture.currentVersion().equals("1.1.0"), "Staging tamper must not quit or replace the app");
    Fixture other = new Fixture("x86_64");
    rejected(() -> other.updater.discard(prepared), "A different updater cannot discard another preparation");
    fixture.updater.discard(prepared);
  }

  private static void nativeMetadataValidation() throws Exception {
    Path base = temporary();
    Path app = base.resolve("diyalamp.app");
    makeApp(app, "1.2.0", "arm64", UpdateFiles.BUNDLE_ID);
    UpdateFiles.validateApp(app, "1.2.0", "arm64");
    check(UpdateFiles.nativeArchitecture(app.resolve("Contents/MacOS/diyalamp"), "arm64"), "A native arm64 header is recognized");
    check(!UpdateFiles.nativeArchitecture(app.resolve("Contents/MacOS/diyalamp"), "x86_64"), "An arm64 header cannot pass as Intel");
    Files.createSymbolicLink(app.resolve("Contents/link"), base);
    rejected(() -> UpdateFiles.validateApp(app, "1.2.0", "arm64"), "App bundles containing symlinks must be rejected");
    Path secret = Files.writeString(base.resolve("fixture.txt"), "EXTERNAL-FIXTURE");
    String xml = "<!DOCTYPE plist [<!ENTITY outside SYSTEM '" + secret.toUri() + "'>]><plist><dict>"
        + "<key>value</key><string>&outside;</string></dict></plist>";
    check(!UpdateFiles.plist(xml.getBytes(StandardCharsets.UTF_8)).values().contains("EXTERNAL-FIXTURE"),
        "Plist parsing must not load external entities");
  }

  private static void helperWaitsAndRetainsBackup() throws Exception {
    Path base = Files.createDirectory(temporary().resolve("Applications ' with spaces $(literal)"));
    Path installed = Files.createDirectory(base.resolve("diyalamp.app"));
    Path staged = Files.createDirectory(base.resolve(".diyalamp-update-fixture.app"));
    Path backup = base.resolve(".diyalamp-backup-fixture.bundle");
    Files.writeString(installed.resolve("version"), "old");
    Files.writeString(staged.resolve("version"), "new");
    helperValidator(installed);
    Path opener = executable(base.resolve("open fixture"), "#!/bin/sh\nexit 0\n");
    Path script = Files.writeString(base.resolve("helper.sh"), InstallHelper.SCRIPT);
    Process parent = new ProcessBuilder("/bin/sh", "-c", "sleep 1").start();
    Process helper = new ProcessBuilder(InstallHelper.command(parent.pid(), installed, staged, backup, script, "1.2.0", opener, "arm64", "0".repeat(64)))
        .redirectErrorStream(true).redirectOutput(base.resolve("helper.log").toFile()).start();
    check(Files.readString(installed.resolve("version")).equals("old"), "The helper must wait for the running app to exit");
    check(helper.waitFor(8, TimeUnit.SECONDS) && helper.exitValue() == 0, "The fixed helper completes a fixture update");
    check(Files.readString(installed.resolve("version")).equals("new"), "The new app occupies the same installation path");
    check(Files.readString(backup.resolve("version")).equals("old"), "The previous app is retained as a backup");
    check(!Files.exists(staged), "Successful replacement consumes only the staged app");
  }

  private static void helperRollsBack() throws Exception {
    Path base = temporary();
    Path installed = Files.createDirectory(base.resolve("diyalamp.app"));
    Path staged = Files.createDirectory(base.resolve(".diyalamp-update-fixture.app"));
    Path backup = base.resolve(".diyalamp-backup-fixture.bundle");
    Files.writeString(installed.resolve("version"), "old");
    Files.writeString(staged.resolve("version"), "new");
    helperValidator(installed);
    Path opener = executable(base.resolve("open fixture"), "#!/bin/sh\nif test \"$(cat \"$2/version\")\" = new; then exit 1; fi\nexit 0\n");
    Path script = Files.writeString(base.resolve("helper.sh"), InstallHelper.SCRIPT);
    Process helper = new ProcessBuilder(InstallHelper.command(999999999, installed, staged, backup, script, "1.2.0", opener, "arm64", "0".repeat(64)))
        .redirectErrorStream(true).redirectOutput(base.resolve("helper.log").toFile()).start();
    check(helper.waitFor(8, TimeUnit.SECONDS) && helper.exitValue() != 0, "Failure to open the new app must be reported");
    check(Files.readString(installed.resolve("version")).equals("old"), "Open failure restores the previous installed app");
    check(Files.readString(staged.resolve("version")).equals("new"), "The failed new app is retained for inspection");
  }

  private static void verifierEntryPoint() throws Exception {
    Path app = temporary().resolve("diyalamp.app");
    makeApp(app, "1.2.0", "arm64", UpdateFiles.BUNDLE_ID);
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String entry = "com.diyalamp.desktop.update.UpdateVerifier";
    String digest = UpdateFiles.digest(app);
    Process valid = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), entry,
        app.toString(), "1.2.0", "arm64", digest).redirectErrorStream(true).start();
    check(valid.waitFor(10, TimeUnit.SECONDS) && valid.exitValue() == 0,
        "The helper's actual Java verification entry point accepts a verified app");
    Process changed = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), entry,
        app.toString(), "1.2.0", "arm64", "0".repeat(64)).redirectErrorStream(true).start();
    check(changed.waitFor(10, TimeUnit.SECONDS) && changed.exitValue() != 0,
        "The helper's actual Java verifier rejects a changed app before replacement");
  }

  private static void helperRefusesInvalidStage() throws Exception {
    Path base = temporary();
    Path installed = Files.createDirectory(base.resolve("diyalamp.app"));
    Path staged = Files.createDirectory(base.resolve(".diyalamp-update-fixture.app"));
    Path backup = base.resolve(".diyalamp-backup-fixture.bundle");
    Files.writeString(installed.resolve("version"), "old");
    Files.writeString(staged.resolve("version"), "new");
    helperValidator(installed);
    executable(installed.resolve("Contents/runtime/Contents/Home/bin/java"), "#!/bin/sh\nexit 1\n");
    Path opener = executable(base.resolve("open fixture"), "#!/bin/sh\nexit 0\n");
    Path script = Files.writeString(base.resolve("helper.sh"), InstallHelper.SCRIPT);
    Process helper = new ProcessBuilder(InstallHelper.command(999999999, installed, staged, backup, script,
        "1.2.0", opener, "arm64", "0".repeat(64)))
        .redirectErrorStream(true).redirectOutput(base.resolve("helper.log").toFile()).start();
    check(helper.waitFor(8, TimeUnit.SECONDS) && helper.exitValue() != 0,
        "The helper must stop when final application verification fails");
    check(Files.readString(installed.resolve("version")).equals("old") && !Files.exists(backup),
        "Failed verification leaves the running installation path intact");
  }

  private static void helperReplacementFailure() throws Exception {
    if (!System.getProperty("os.name").startsWith("Mac") || !Files.isExecutable(Path.of("/usr/bin/chflags"))) return;
    Path base = temporary();
    Path installed = Files.createDirectory(base.resolve("diyalamp.app"));
    Path staged = Files.createDirectory(base.resolve(".diyalamp-update-fixture.app"));
    Path backup = base.resolve(".diyalamp-backup-fixture.bundle");
    Files.writeString(installed.resolve("version"), "old");
    Files.writeString(staged.resolve("version"), "new");
    helperValidator(installed);
    Path opener = executable(base.resolve("open fixture"), "#!/bin/sh\nexit 0\n");
    Path script = Files.writeString(base.resolve("helper.sh"), InstallHelper.SCRIPT);
    Process lock = new ProcessBuilder("/usr/bin/chflags", "uchg", staged.toString()).start();
    if (!lock.waitFor(5, TimeUnit.SECONDS) || lock.exitValue() != 0)
      throw new IOException("The fixture's immutable-directory fault could not be prepared.");
    try {
      Process helper = new ProcessBuilder(InstallHelper.command(999999999, installed, staged, backup, script,
          "1.2.0", opener, "arm64", "0".repeat(64)))
          .redirectErrorStream(true).redirectOutput(base.resolve("helper.log").toFile()).start();
      check(helper.waitFor(8, TimeUnit.SECONDS) && helper.exitValue() != 0,
          "A failed staged-app rename must be reported");
      check(Files.readString(installed.resolve("version")).equals("old"),
          "A staged-app rename failure must restore the previous app");
      check(Files.readString(staged.resolve("version")).equals("new"),
          "A failed rename must retain the prepared fixture app");
    } finally {
      Process unlock = new ProcessBuilder("/usr/bin/chflags", "nouchg", staged.toString()).start();
      if (!unlock.waitFor(5, TimeUnit.SECONDS) || unlock.exitValue() != 0)
        throw new IOException("The temporary immutable-directory fixture could not be unlocked.");
    }
  }

  private static void runtimeFileLinks() throws Exception {
    Path base = temporary(), app = base.resolve("diyalamp.app"), copy = base.resolve("copied.app");
    makeApp(app, "1.2.0", "arm64", UpdateFiles.BUNDLE_ID);
    Path licenses = app.resolve("Contents/runtime/legal");
    Files.createDirectories(licenses.resolve("java.base"));
    Files.createDirectories(licenses.resolve("java.xml"));
    Files.writeString(licenses.resolve("java.base/LICENSE"), "Shared license\n");
    Files.writeString(licenses.resolve("java.base/OTHER"), "Shared license\n");
    Path link = licenses.resolve("java.xml/LICENSE");
    Files.createSymbolicLink(link, Path.of("../java.base/LICENSE"));
    UpdateFiles.validateApp(app, "1.2.0", "arm64");
    String original = UpdateFiles.digest(app);
    UpdateFiles.copyApp(app, copy);
    check(Files.isSymbolicLink(copy.resolve(app.relativize(link))), "Legitimate runtime links must remain links after copying");
    check(Files.readSymbolicLink(copy.resolve(app.relativize(link))).equals(Path.of("../java.base/LICENSE")),
        "Copying must preserve the original relative link target");
    check(original.equals(UpdateFiles.digest(copy)), "A copied app with legal runtime links must retain its digest");
    Files.delete(link);
    Files.createSymbolicLink(link, Path.of("../java.base/OTHER"));
    check(!original.equals(UpdateFiles.digest(app)), "The digest must incorporate a symbolic link's target, not only contents");
    for (Path target : List.of(licenses.resolve("java.base/LICENSE"), Path.of("../../../../../../outside"),
        Path.of("../java.base"), Path.of("LICENSE"), Path.of("missing"))) {
      Files.delete(link);
      Files.createSymbolicLink(link, target);
      rejected(() -> UpdateFiles.validateApp(app, "1.2.0", "arm64"),
          "Absolute, escaped, directory, cyclic, and broken links must be rejected");
    }
  }

  private static void helperValidator(Path installed) throws IOException {
    Path java = installed.resolve("Contents/runtime/Contents/Home/bin/java");
    Files.createDirectories(java.getParent());
    executable(java, "#!/bin/sh\nexit 0\n");
    Files.createDirectories(installed.resolve("Contents/app"));
    Files.writeString(installed.resolve("Contents/app/diyalamp.jar"), "Fixture validator stub");
  }

  private static Path temporary() throws IOException { return Files.createTempDirectory("diyalamp-update-tests-").toRealPath(); }
  private static Path executable(Path path, String text) throws IOException {
    Files.writeString(path, text);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    return path;
  }
  private static void makeApp(Path app, String version, String architecture, String bundleId) throws IOException {
    Files.createDirectories(app.resolve("Contents/MacOS"));
    Files.createDirectories(app.resolve("Contents/runtime"));
    String info = "<?xml version='1.0'?><!DOCTYPE plist PUBLIC '-//Apple//DTD PLIST 1.0//EN' 'http://www.apple.com/DTDs/PropertyList-1.0.dtd'>"
        + "<plist><dict><key>CFBundleIdentifier</key><string>" + bundleId + "</string>"
        + "<key>CFBundleShortVersionString</key><string>" + version + "</string>"
        + "<key>CFBundleExecutable</key><string>diyalamp</string></dict></plist>";
    Files.writeString(app.resolve("Contents/Info.plist"), info);
    ByteBuffer binary = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
    binary.putInt(0xfeedfacf).putInt(architecture.equals("arm64") ? 0x0100000c : 0x01000007);
    Path executable = app.resolve("Contents/MacOS/diyalamp");
    Files.write(executable, binary.array());
    Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
  }

  private static final class Fixture {
    final Path base = temporary(), cache = base.resolve("cache"), installed = base.resolve("diyalamp.app"), notes = base.resolve("notes.md");
    final byte[] disk = "Fictional verified disk image".getBytes(StandardCharsets.UTF_8);
    final List<List<String>> commandCalls = new ArrayList<>();
    final List<URI> requestedUris = new ArrayList<>();
    final AppUpdater updater;
    final String architecture;
    String latestDestination = "https://github.com/diyalamp/diyalamp/releases/tag/v1.2.0";
    String assetDestination = "https://release-assets.githubusercontent.com/fixture?token=not-a-secret";
    String imageBundleId = UpdateFiles.BUNDLE_ID, imageArchitecture;
    int requests, closed, assetRequests;
    boolean badChecksum, detached, unapprovedRequested;
    Fixture(String architecture) throws IOException {
      this.architecture = architecture;
      imageArchitecture = architecture;
      makeApp(installed, "1.1.0", architecture, UpdateFiles.BUNDLE_ID);
      Files.writeString(notes, "Private fictional notes stay here\n");
      updater = new AppUpdater(cache, installed, architecture, 999999999, this::get, this::command);
    }
    AppUpdater.Release release() throws Exception { return updater.check("1.1.0").orElseThrow(); }
    AppUpdater.Reply get(URI uri) throws IOException {
      requests++; requestedUris.add(uri);
      int status = 200;
      String redirect = null;
      byte[] body = new byte[0];
      if (uri.getPath().endsWith("/releases/latest")) { status = 302; redirect = latestDestination; }
      else if (uri.getPath().endsWith(".sha256")) {
        assetRequests++;
        String hash = badChecksum ? "0".repeat(64) : HexFormat.of().formatHex(UpdateFiles.sha256().digest(disk));
        body = (hash + "  diyalamp-1.2.0-macos-" + architecture + ".dmg\n").getBytes(StandardCharsets.US_ASCII);
      } else if (uri.getHost().equals("github.com")) { assetRequests++; status = 302; redirect = assetDestination; }
      else if (uri.getHost().equals("release-assets.githubusercontent.com")) body = disk;
      else { unapprovedRequested = true; throw new IOException("Unapproved fixture destination"); }
      return new AppUpdater.Reply(status, redirect, new ByteArrayInputStream(body), () -> closed++);
    }
    String command(List<String> command) throws IOException {
      commandCalls.add(List.copyOf(command));
      if (command.contains("attach")) {
        Path mount = Path.of(command.get(command.indexOf("-mountpoint") + 1));
        makeApp(mount.resolve("diyalamp.app"), "1.2.0", imageArchitecture, imageBundleId);
        return "<plist><dict><key>mount-point</key><string>" + mount + "</string></dict></plist>";
      }
      if (command.contains("detach")) {
        detached = true;
        UpdateFiles.deleteOwnedTree(Path.of(command.getLast()));
      }
      return "";
    }
    int stageCount() throws IOException {
      try (var files = Files.list(base)) { return (int) files.filter(p -> p.getFileName().toString().startsWith(".diyalamp-update-")).count(); }
    }
    Path staged() throws IOException {
      try (var files = Files.list(base)) { return files.filter(p -> p.getFileName().toString().startsWith(".diyalamp-update-")).findFirst().orElseThrow(); }
    }
    String currentVersion() throws IOException {
      return UpdateFiles.plist(UpdateFiles.readSmall(installed.resolve("Contents/Info.plist"), 10000)).get("CFBundleShortVersionString");
    }
  }
}
