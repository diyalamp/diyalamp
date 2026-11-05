package com.diyalamp.desktop.update;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import java.util.regex.*;
import javax.net.ssl.HttpsURLConnection;

/** Explicit, user-triggered updates from this project's public GitHub release channel. */
public final class AppUpdater {
  private static final String REPOSITORY = "/diyalamp/diyalamp";
  private static final URI LATEST = URI.create("https://github.com" + REPOSITORY + "/releases/latest");
  private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
  private static final Set<String> ASSET_HOSTS = Set.of("github.com", "release-assets.githubusercontent.com",
      "objects.githubusercontent.com", "github-releases.githubusercontent.com");
  private static final long MAX_DOWNLOAD = 512L * 1024 * 1024;
  private final Path cache, installed;
  private final String architecture;
  private final long processId;
  private final Transport transport;
  private final Commands commands;

  public static final class Release {
    private final String version, architecture;
    private Release(String version, String architecture) { this.version = version; this.architecture = architecture; }
    public String version() { return version; }
    private String filename() { return "diyalamp-" + version + "-macos-" + architecture + ".dmg"; }
    private URI asset(String suffix) {
      return URI.create("https://github.com" + REPOSITORY + "/releases/download/v" + version + "/" + filename() + suffix);
    }
  }

  public static final class PreparedUpdate {
    private final AppUpdater owner;
    private final String version, digest;
    private final Path staged, work;
    private boolean consumed;
    private PreparedUpdate(AppUpdater owner, String version, String digest, Path staged, Path work) {
      this.owner = owner; this.version = version; this.digest = digest; this.staged = staged; this.work = work;
    }
    public String version() { return version; }
  }

  /** Creating an updater does not contact the network or change the installation. */
  public static AppUpdater create() throws IOException {
    if (!System.getProperty("os.name", "").startsWith("Mac"))
      throw new IOException("In-app installation is available in the macOS application.");
    Path home = Path.of(System.getProperty("user.home")).toRealPath();
    String executable = System.getProperty("jpackage.app-path");
    if (executable == null || executable.isBlank()) executable = ProcessHandle.current().info().command().orElse("");
    Path app = null;
    if (!executable.isBlank()) {
      for (Path path = Path.of(executable).toAbsolutePath().normalize(); path != null; path = path.getParent()) {
        if (path.getFileName() != null && path.getFileName().toString().equals("diyalamp.app")) { app = path; break; }
      }
    }
    if (app == null || !(app.getParent().equals(Path.of("/Applications"))
        || app.getParent().equals(home.resolve("Applications"))))
      throw new IOException("Run the installed app from Applications to install an update.");
    String arch = switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
      case "arm64", "aarch64" -> "arm64";
      case "x86_64", "amd64" -> "x86_64";
      default -> throw new IOException("This Mac architecture does not have an update package.");
    };
    return new AppUpdater(home.resolve("Library/Caches/diyalamp/updates"), app, arch,
        ProcessHandle.current().pid(), AppUpdater::connect, AppUpdater::run);
  }

  AppUpdater(Path cache, Path installed, String architecture, long processId, Transport transport, Commands commands) {
    this.cache = cache.toAbsolutePath().normalize(); this.installed = installed.toAbsolutePath().normalize();
    this.architecture = architecture; this.processId = processId; this.transport = transport; this.commands = commands;
    if (!Set.of("arm64", "x86_64").contains(architecture)) throw new IllegalArgumentException("Unsupported architecture");
  }

  /** No download occurs here. GitHub's stable latest-release link supplies the release tag. */
  public Optional<Release> check(String currentVersion) throws IOException, InterruptedException {
    versionParts(currentVersion);
    UpdateFiles.interrupted();
    try (Reply reply = transport.get(LATEST)) {
      if (!redirect(reply.status) || reply.location == null)
        throw new IOException("GitHub did not return a stable release. Please try again later.");
      URI tag = location(LATEST, reply.location);
      if (!allowedHttps(tag) || !"github.com".equalsIgnoreCase(tag.getHost()) || tag.getRawQuery() != null)
        throw new IOException("GitHub returned an unexpected release destination.");
      String prefix = REPOSITORY + "/releases/tag/v";
      if (!tag.getRawPath().startsWith(prefix)) throw new IOException("GitHub returned an unexpected release tag.");
      String latest = tag.getRawPath().substring(prefix.length());
      versionParts(latest);
      return compare(latest, currentVersion) > 0 ? Optional.of(new Release(latest, architecture)) : Optional.empty();
    }
  }

  /** Download and verify in a background task. No running app or notes are changed. */
  public PreparedUpdate prepare(Release release, Consumer<String> progress)
      throws IOException, InterruptedException {
    Objects.requireNonNull(progress);
    if (release == null || !architecture.equals(release.architecture)) throw new IOException("Wrong update architecture.");
    versionParts(release.version);
    Path parent = writableInstallDirectory();
    Map<String, String> current = UpdateFiles.plist(UpdateFiles.readSmall(installed.resolve("Contents/Info.plist"), 1024 * 1024));
    String currentVersion = current.get("CFBundleShortVersionString");
    if (!UpdateFiles.BUNDLE_ID.equals(current.get("CFBundleIdentifier")) || compare(release.version, currentVersion) <= 0)
      throw new IOException("The installed application cannot be replaced with this release.");
    UpdateFiles.createDirectories(cache);
    Path work = Files.createTempDirectory(cache, "update-");
    Path staged = parent.resolve(".diyalamp-update-" + UUID.randomUUID() + ".app");
    Path mount = work.resolve("mount");
    boolean[] mounted = {false};
    try {
      progress.accept("Downloading the release checksum…");
      String checksumText = new String(downloadSmall(release.asset(".sha256"), 1024), StandardCharsets.US_ASCII).strip();
      Matcher checksum = Pattern.compile("([a-fA-F0-9]{64})[ \\t]+\\*?" + Pattern.quote(release.filename())).matcher(checksumText);
      if (!checksum.matches()) throw new IOException("The release checksum does not match its installer filename.");
      byte[] expected = HexFormat.of().parseHex(checksum.group(1));
      progress.accept("Downloading the Mac update…");
      Path disk = work.resolve(release.filename());
      byte[] actual = download(release.asset(""), disk);
      if (!MessageDigest.isEqual(expected, actual)) throw new IOException("The update checksum does not match. The installed app is unchanged.");
      progress.accept("Verifying the disk image…");
      commands.run(List.of("/usr/bin/hdiutil", "verify", disk.toString()));
      Files.createDirectory(mount);
      progress.accept("Checking the application…");
      PreparedUpdate prepared;
      try {
        String attached = commands.run(List.of("/usr/bin/hdiutil", "attach", "-readonly", "-nobrowse", "-plist", "-mountpoint", mount.toString(), disk.toString()));
        mounted[0] = true;
        Map<String, String> image = UpdateFiles.plist(attached.getBytes(StandardCharsets.UTF_8));
        if (!mount.toString().equals(image.get("mount-point"))) throw new IOException("The installer mounted at an unexpected location.");
        Path source = mount.resolve("diyalamp.app");
        UpdateFiles.validateApp(source, release.version, architecture);
        String sourceDigest = UpdateFiles.digest(source);
        progress.accept("Preparing the verified app…");
        UpdateFiles.copyApp(source, staged);
        UpdateFiles.validateApp(staged, release.version, architecture);
        if (!sourceDigest.equals(UpdateFiles.digest(staged))) throw new IOException("The staged app does not match the verified image.");
        UpdateFiles.interrupted();
        prepared = new PreparedUpdate(this, release.version, sourceDigest, staged, work);
      } finally {
        if (mounted[0]) {
          boolean interrupted = Thread.interrupted();
          try {
            commands.run(List.of("/usr/bin/hdiutil", "detach", mount.toString()));
            mounted[0] = false;
          } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
      }
      UpdateFiles.interrupted();
      return prepared;
    } catch (IOException | InterruptedException | RuntimeException error) {
      try { UpdateFiles.deleteOwnedTree(staged); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
      if (!mounted[0]) try { UpdateFiles.deleteOwnedTree(work); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
      throw error;
    }
  }

  /** Call on the UI thread: save conflicts cancel before a helper or quit is requested. */
  public boolean installAndRestart(PreparedUpdate update, BooleanSupplier saveAll, Runnable quit) throws IOException {
    owned(update);
    if (!saveAll.getAsBoolean()) return false;
    writableInstallDirectory();
    try {
      UpdateFiles.validateApp(update.staged, update.version, architecture);
      if (!update.digest.equals(UpdateFiles.digest(update.staged))) throw new IOException("The prepared update changed. Download it again.");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Update installation was canceled.", interrupted);
    }
    Path backup = installed.getParent().resolve(".diyalamp-backup-" + UUID.randomUUID() + ".bundle");
    InstallHelper.launch(processId, installed, update.staged, backup, update.work, update.version,
        architecture, update.digest);
    update.consumed = true;
    quit.run();
    return true;
  }

  /** A prepared update can be discarded only before its installation helper starts. */
  public void discard(PreparedUpdate update) throws IOException {
    owned(update);
    UpdateFiles.deleteOwnedTree(update.staged);
    UpdateFiles.deleteOwnedTree(update.work);
    update.consumed = true;
  }

  private void owned(PreparedUpdate update) throws IOException {
    if (update == null || update.owner != this || update.consumed
        || !update.staged.getParent().equals(installed.getParent())
        || !update.staged.getFileName().toString().matches("\\.diyalamp-update-[a-f0-9-]{36}\\.app")
        || !cache.equals(update.work.getParent()) || !update.work.getFileName().toString().startsWith("update-"))
      throw new IOException("This prepared update is no longer available.");
    UpdateFiles.realDirectory(update.work);
  }

  private Path writableInstallDirectory() throws IOException {
    UpdateFiles.realDirectory(installed);
    Path parent = UpdateFiles.realDirectory(installed.getParent());
    if (!Files.isWritable(parent) || !Files.isWritable(installed))
      throw new IOException("Applications is not writable by this account. Install the verified DMG manually; no administrator access was requested.");
    return parent;
  }

  static int compare(String first, String second) throws IOException {
    int[] a = versionParts(first), b = versionParts(second);
    for (int i = 0; i < 3; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
    return 0;
  }

  private static int[] versionParts(String version) throws IOException {
    if (version == null) throw new IOException("A release version is missing.");
    Matcher match = VERSION.matcher(version);
    if (!match.matches()) throw new IOException("Only stable numbered diyalamp releases can be installed.");
    return new int[] {Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3))};
  }

  private byte[] downloadSmall(URI uri, int maximum) throws IOException, InterruptedException {
    try (Reply reply = asset(uri)) {
      byte[] data = reply.body.readNBytes(maximum + 1);
      UpdateFiles.interrupted();
      if (data.length > maximum) throw new IOException("The release checksum file is too large.");
      return data;
    }
  }

  private byte[] download(URI uri, Path target) throws IOException, InterruptedException {
    MessageDigest digest = UpdateFiles.sha256();
    try (Reply reply = asset(uri); OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
      byte[] buffer = new byte[65536];
      long total = 0;
      for (int count; (count = reply.body.read(buffer)) != -1;) {
        UpdateFiles.interrupted();
        total += count;
        if (total > MAX_DOWNLOAD) throw new IOException("This installer exceeds the supported update size.");
        digest.update(buffer, 0, count);
        output.write(buffer, 0, count);
      }
      if (total == 0) throw new IOException("The update download is empty.");
    }
    return digest.digest();
  }

  private Reply asset(URI uri) throws IOException, InterruptedException {
    for (int redirects = 0; redirects <= 6; redirects++) {
      UpdateFiles.interrupted();
      if (!allowedHttps(uri)) throw new IOException("The update redirected to an unapproved destination.");
      Reply reply = transport.get(uri);
      if (reply.status == 200) return reply;
      String location = reply.location;
      int status = reply.status;
      reply.close();
      if (!redirect(status) || location == null) throw new IOException("The release asset could not be downloaded. Please try again later.");
      uri = location(uri, location);
    }
    throw new IOException("The update download redirected too many times.");
  }

  static boolean allowedHttps(URI uri) {
    return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
        && ASSET_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
        && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null && uri.getRawFragment() == null;
  }

  private static URI location(URI current, String location) throws IOException {
    try { return current.resolve(new URI(location)); }
    catch (IllegalArgumentException | URISyntaxException error) { throw new IOException("Invalid update redirect.", error); }
  }

  private static boolean redirect(int status) { return status == 301 || status == 302 || status == 303 || status == 307 || status == 308; }

  interface Transport { Reply get(URI uri) throws IOException, InterruptedException; }
  interface Commands { String run(List<String> command) throws IOException, InterruptedException; }

  static final class Reply implements AutoCloseable {
    final int status;
    final String location;
    final InputStream body;
    final Runnable disconnect;
    Reply(int status, String location, InputStream body) { this(status, location, body, () -> {}); }
    Reply(int status, String location, InputStream body, Runnable disconnect) {
      this.status = status; this.location = location; this.body = body; this.disconnect = disconnect;
    }
    @Override public void close() throws IOException { try { body.close(); } finally { disconnect.run(); } }
  }

  private static Reply connect(URI uri) throws IOException {
    HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(10000);
    connection.setReadTimeout(30000);
    connection.setRequestProperty("User-Agent", "diyalamp-updater");
    connection.setRequestProperty("Accept", "application/octet-stream");
    try {
      int status = connection.getResponseCode();
      InputStream body = status == 200 ? connection.getInputStream() : InputStream.nullInputStream();
      return new Reply(status, connection.getHeaderField("Location"), body, connection::disconnect);
    } catch (IOException error) { connection.disconnect(); throw error; }
  }

  private static String run(List<String> command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      if (!process.waitFor(90, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("The macOS installer check timed out.");
      }
      byte[] output = process.getInputStream().readNBytes(1024 * 1024);
      if (process.exitValue() != 0) throw new IOException("A macOS installer verification or mount command failed.");
      return new String(output, StandardCharsets.UTF_8);
    } catch (InterruptedException error) { process.destroyForcibly(); throw error; }
  }
}
