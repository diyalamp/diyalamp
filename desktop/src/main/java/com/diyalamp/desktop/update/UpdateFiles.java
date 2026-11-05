package com.diyalamp.desktop.update;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Filesystem checks shared by staging and installation. No note paths are accepted here. */
final class UpdateFiles {
  static final String BUNDLE_ID = "com.diyalamp.desktop";

  static void interrupted() throws InterruptedException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Update canceled.");
  }

  static Path realDirectory(Path directory) throws IOException {
    Path absolute = directory.toAbsolutePath().normalize();
    Path current = absolute.getRoot();
    for (Path part : absolute) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) throw new IOException("Update folders cannot be symbolic links.");
    }
    if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
        || !absolute.toRealPath().equals(absolute))
      throw new IOException("The update folder is not a real directory.");
    return absolute;
  }

  static Path createDirectories(Path directory) throws IOException {
    Path absolute = directory.toAbsolutePath().normalize();
    Path current = absolute.getRoot();
    for (Path part : absolute) {
      current = current.resolve(part);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) realDirectory(current);
      else Files.createDirectory(current);
    }
    return realDirectory(absolute);
  }

  static Map<String, String> plist(byte[] xml) throws IOException {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setExpandEntityReferences(false);
      Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
      Map<String, String> values = new HashMap<>();
      NodeList keys = document.getElementsByTagName("key");
      for (int i = 0; i < keys.getLength(); i++) {
        Node value = keys.item(i).getNextSibling();
        while (value != null && value.getNodeType() != Node.ELEMENT_NODE) value = value.getNextSibling();
        if (value != null && (value.getNodeName().equals("string") || value.getNodeName().equals("integer")))
          values.put(keys.item(i).getTextContent(), value.getTextContent());
      }
      return Map.copyOf(values);
    } catch (Exception error) {
      throw new IOException("The update contains invalid macOS metadata.", error);
    }
  }

  static byte[] readSmall(Path file, int maximum) throws IOException {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Expected a regular update file.");
    try (InputStream input = Files.newInputStream(file)) {
      byte[] bytes = input.readNBytes(maximum + 1);
      if (bytes.length > maximum) throw new IOException("Update metadata exceeds the supported size.");
      return bytes;
    }
  }

  static void validateApp(Path app, String version, String architecture) throws IOException {
    if (!Set.of("arm64", "x86_64").contains(architecture)) throw new IOException("Unsupported update architecture.");
    realDirectory(app);
    try (var paths = Files.walk(app)) {
      long size = 0;
      int count = 0;
      for (Iterator<Path> entries = paths.iterator(); entries.hasNext();) {
        Path file = entries.next();
        if (Files.isSymbolicLink(file)) checkedLink(app, file);
        else if (!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("The update app contains an unsupported filesystem entry.");
        if (++count > 50000) throw new IOException("The update app contains too many entries.");
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) size += Files.size(file);
        if (size > 1024L * 1024 * 1024) throw new IOException("The update app exceeds the supported size.");
      }
    }
    Map<String, String> info = plist(readSmall(app.resolve("Contents/Info.plist"), 1024 * 1024));
    if (!BUNDLE_ID.equals(info.get("CFBundleIdentifier"))
        || !version.equals(info.get("CFBundleShortVersionString"))
        || !"diyalamp".equals(info.get("CFBundleExecutable")))
      throw new IOException("The update's application identity or version does not match.");
    Path executable = app.resolve("Contents/MacOS/diyalamp");
    if (!Files.isExecutable(executable) || !nativeArchitecture(executable, architecture))
      throw new IOException("The update does not contain the expected native Mac executable.");
    if (!Files.isDirectory(app.resolve("Contents/runtime"), LinkOption.NOFOLLOW_LINKS))
      throw new IOException("The update is missing its bundled runtime.");
  }

  /** jlink uses relative file links for shared license notices; those must stay intact. */
  static Path checkedLink(Path app, Path link) throws IOException {
    Path target = Files.readSymbolicLink(link);
    Path lexical = link.getParent().resolve(target).normalize();
    if (target.isAbsolute() || !lexical.startsWith(app))
      throw new IOException("An update link points outside its application.");
    Path real = link.toRealPath();
    if (!real.startsWith(app) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Update links must resolve to files inside their application.");
    return target;
  }

  static boolean nativeArchitecture(Path executable, String architecture) throws IOException {
    byte[] header;
    try (InputStream input = Files.newInputStream(executable)) {
      header = input.readNBytes(4096);
    }
    if (header.length < 8) return false;
    int cpu = architecture.equals("arm64") ? 0x0100000c : 0x01000007;
    ByteBuffer buffer = ByteBuffer.wrap(header);
    int magic = buffer.getInt();
    if (magic == 0xfeedfacf || magic == 0xcffaedfe) {
      buffer.order(magic == 0xfeedfacf ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
      return buffer.getInt(4) == cpu;
    }
    boolean fat64 = magic == 0xcafebabf || magic == 0xbfbafeca;
    if (magic != 0xcafebabe && magic != 0xbebafeca && !fat64) return false;
    buffer.order(magic == 0xbebafeca || magic == 0xbfbafeca ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    int count = buffer.getInt(4), entrySize = fat64 ? 32 : 20;
    if (count < 1 || count > 16 || 8 + count * entrySize > header.length) return false;
    for (int i = 0; i < count; i++) if (buffer.getInt(8 + i * entrySize) == cpu) return true;
    return false;
  }

  static void copyApp(Path source, Path target) throws IOException, InterruptedException {
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Update staging already exists.");
    try {
      Files.walkFileTree(source, new SimpleFileVisitor<>() {
        @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
            throws IOException {
          checkInterrupted();
          if (Files.isSymbolicLink(directory)) throw new IOException("Update directories cannot be links.");
          Files.createDirectory(target.resolve(source.relativize(directory)));
          return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
          checkInterrupted();
          Path destination = target.resolve(source.relativize(file));
          if (attributes.isSymbolicLink()) Files.createSymbolicLink(destination, checkedLink(source, file));
          else if (!attributes.isRegularFile())
            throw new IOException("The update contains an unsupported filesystem entry.");
          else Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
          return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
          if (error != null) throw error;
          PosixFileAttributeView view = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
          if (view != null) Files.setPosixFilePermissions(target.resolve(source.relativize(directory)), view.readAttributes().permissions());
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (InterruptedIOException error) {
      throw new InterruptedException("Update canceled.");
    }
  }

  private static void checkInterrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Update canceled.");
  }

  static String digest(Path app) throws IOException, InterruptedException {
    MessageDigest digest = sha256();
    try (var stream = Files.walk(app)) {
      for (Path file : stream.sorted(Comparator.comparing(Path::toString)).toList()) {
        interrupted();
        digest.update(app.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        if (Files.isSymbolicLink(file)) {
          digest.update((byte) 'L');
          digest.update(checkedLink(app, file).toString().getBytes(StandardCharsets.UTF_8));
          digest.update((byte) 0);
        } else if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          digest.update((byte) 'F');
          MessageDigest fileDigest = sha256();
          try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) != -1;) {
              interrupted();
              fileDigest.update(buffer, 0, read);
            }
          }
          digest.update(fileDigest.digest());
        } else if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) digest.update((byte) 'D');
        else
          throw new IOException("The staged app contains an unsupported filesystem entry.");
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static MessageDigest sha256() {
    try { return MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }

  static void deleteOwnedTree(Path directory) throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
    realDirectory(directory.getParent());
    if (Files.isSymbolicLink(directory)) throw new IOException("Refusing to clean a linked update directory.");
    Files.walkFileTree(directory, new SimpleFileVisitor<>() {
      @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }
      @Override public FileVisitResult postVisitDirectory(Path file, IOException error) throws IOException {
        if (error != null) throw error;
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private UpdateFiles() {}
}
