package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.NoteStore;
import com.diyalamp.desktop.NoteStore.DocumentKind;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** A local read-only tab. File validation and workspace permissions belong to the caller. */
final class FxFilePreview extends BorderPane {
  private static final int MEBIBYTE = 1024 * 1024;
  private static final int MAX_TEXT_BYTES = 5 * MEBIBYTE;
  private static final int MAX_IMAGE_BYTES = 32 * MEBIBYTE;
  private static final long MAX_IMAGE_PIXELS = 32_000_000;
  private static final long MAX_PDF_BYTES = 100L * MEBIBYTE;
  private static final int MAX_RASTER_EDGE = 2400;
  private static final double MAX_PAGE_PIXELS = 4_000_000;
  private static final double IMAGE_GUTTER = 24;
  private static final ScheduledThreadPoolExecutor LOADER = new ScheduledThreadPoolExecutor(2, action -> {
    Thread thread = new Thread(action, "diyalamp-file-preview");
    thread.setDaemon(true);
    return thread;
  });

  static {
    LOADER.setRemoveOnCancelPolicy(true);
  }

  private Path path;
  private final DocumentKind kind;
  private int page, pageCount;
  private final Label pageNumber = new Label();
  private final Button previous = FxTheme.iconButton("chevron-right", "Previous PDF page", () -> loadPage(page - 1));
  private final Button next = FxTheme.iconButton("chevron-right", "Next PDF page", () -> loadPage(page + 1));
  private Future<?> loading;
  private long generation;
  private boolean disposed;

  FxFilePreview(Path checkedPath) {
    path = checkedPath;
    kind = NoteStore.documentKind(path);
    getStyleClass().add("file-preview");
    setMinSize(0, 0);
    setFocusTraversable(true);
    describePath();
    showLoading();
    if (kind == DocumentKind.PDF) {
      previous.getGraphic().setRotate(180);
      previous.getStyleClass().add("file-row-action");
      next.getStyleClass().add("file-row-action");
      pageNumber.getStyleClass().add("document-breadcrumb");
      HBox navigation = new HBox(8, previous, pageNumber, next);
      navigation.getStyleClass().add("workspace-band");
      navigation.setAlignment(Pos.CENTER);
      setTop(navigation);
    }
    loadCurrent();
  }

  private void loadCurrent() {
    Path source = path;
    switch (kind) {
      case TEXT -> submit(() -> loadText(source), this::showText);
      case IMAGE -> submit(() -> loadImage(source), image -> showImage(image, false));
      case PDF -> loadPage(page);
      default -> showError("This file type cannot be previewed.");
    }
  }

  void relocate(Path checkedPath) {
    if (disposed || checkedPath.equals(path)) return;
    path = checkedPath;
    describePath();
    if (loading != null) loadCurrent();
  }

  private void describePath() { setAccessibleText("Read-only preview of " + path.getFileName()); }

  void focus() {
    if (getCenter() instanceof TextArea || getCenter() instanceof ScrollPane) getCenter().requestFocus();
    else requestFocus();
  }

  void dispose() {
    disposed = true;
    generation++;
    if (loading != null) loading.cancel(true);
    loading = null;
  }

  private void loadPage(int requested) {
    if (requested < 0 || pageCount > 0 && requested >= pageCount) return;
    page = requested;
    updateNavigation();
    pageNumber.setText("Loading page " + (requested + 1) + "…");
    Path source = path;
    submit(() -> renderPage(source, requested), this::showPage);
  }

  private void showPage(PdfPage result) {
    page = result.index();
    pageCount = result.count();
    WritableImage image = new WritableImage(result.width(), result.height());
    image.getPixelWriter().setPixels(0, 0, result.width(), result.height(),
        PixelFormat.getIntArgbInstance(), result.pixels(), 0, result.width());
    showImage(image, true);
    updateNavigation();
  }

  private void updateNavigation() {
    pageNumber.setText(pageCount == 0 ? "PDF" : "Page " + (page + 1) + " of " + pageCount);
    previous.setDisable(pageCount == 0 || page == 0);
    next.setDisable(pageCount == 0 || page >= pageCount - 1);
  }

  private void showImage(Image image, boolean pdf) {
    ImageView view = new ImageView(image);
    view.setPreserveRatio(true);
    view.setSmooth(true);
    view.setAccessibleText(pdf ? "PDF page " + (page + 1) : path.getFileName().toString());
    StackPane sheet = new StackPane(view);
    sheet.setPadding(new Insets(IMAGE_GUTTER));
    ScrollPane scroll = new ScrollPane(sheet);
    scroll.getStyleClass().addAll("edge-to-edge", "file-preview-scroll");
    scroll.setFitToWidth(true);
    scroll.setFitToHeight(!pdf);
    scroll.setPannable(true);
    scroll.setFocusTraversable(true);
    scroll.setAccessibleText(pdf ? "PDF page " + (page + 1) : "Image preview");
    view.fitWidthProperty().bind(Bindings.createDoubleBinding(() -> Math.min(image.getWidth(),
        Math.max(1, scroll.getViewportBounds().getWidth() - 2 * IMAGE_GUTTER)), scroll.viewportBoundsProperty()));
    if (!pdf) view.fitHeightProperty().bind(Bindings.createDoubleBinding(() -> Math.min(image.getHeight(),
        Math.max(1, scroll.getViewportBounds().getHeight() - 2 * IMAGE_GUTTER)), scroll.viewportBoundsProperty()));
    setCenter(scroll);
  }

  private void showText(String contents) {
    TextArea text = new TextArea(contents);
    text.getStyleClass().addAll("edge-to-edge", "file-preview-text");
    text.setFont(FxTheme.textFont(FxTheme.TEXT_SIZE, false, false));
    text.setEditable(false);
    text.setWrapText(true);
    text.setAccessibleText("Read-only text preview");
    setCenter(text);
  }

  private void showLoading() {
    ProgressIndicator progress = new ProgressIndicator();
    progress.setMaxSize(28, 28);
    Label label = new Label("Opening preview…");
    label.getStyleClass().add("empty-detail");
    VBox state = new VBox(8, progress, label);
    state.setAlignment(Pos.CENTER);
    setCenter(state);
  }

  private void showError(String message) {
    Label label = new Label(message);
    label.setWrapText(true);
    label.getStyleClass().add("empty-detail");
    StackPane state = new StackPane(label);
    state.setPadding(new Insets(IMAGE_GUTTER));
    setCenter(state);
    if (kind == DocumentKind.PDF) updateNavigation();
  }

  private <T> void submit(ReadPreview<T> read, Consumer<T> show) {
    if (disposed) return;
    if (loading != null) loading.cancel(true);
    long request = ++generation;
    loading = LOADER.submit(() -> {
      try {
        T result = read.read();
        complete(request, () -> show.accept(result));
      } catch (InvalidPasswordException error) {
        complete(request, () -> showError("This PDF requires a password."));
      } catch (CharacterCodingException error) {
        complete(request, () -> showError("This text file is not valid UTF-8."));
      } catch (PreviewProblem error) {
        complete(request, () -> showError(error.getMessage()));
      } catch (IOException | RuntimeException error) {
        complete(request, () -> showError("This file could not be previewed."));
      }
    });
  }

  private void complete(long request, Runnable display) {
    if (Thread.currentThread().isInterrupted()) return;
    Platform.runLater(() -> {
      if (disposed || generation != request) return;
      loading = null;
      boolean focused = isFocused();
      display.run();
      if (focused) focus();
    });
  }

  private static String loadText(Path path) throws IOException {
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(
        readBounded(path, MAX_TEXT_BYTES, "This text file is larger than 5 MiB."))).toString();
    return text.startsWith("\uFEFF") ? text.substring(1) : text;
  }

  private static Image loadImage(Path path) throws IOException {
    byte[] bytes = readBounded(path, MAX_IMAGE_BYTES, "This image file is larger than 32 MiB.");
    int width, height, frames = 1;
    try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw new PreviewProblem("This is not a readable image.");
      var reader = readers.next();
      try {
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!format.equals("png") && !format.equals("jpeg") && !format.equals("gif") && !format.equals("bmp"))
          throw new PreviewProblem("This image format is not supported.");
        reader.setInput(stream, false, true);
        if (format.equals("gif") && bytes.length >= 10) {
          // GIF animation draws onto the logical screen, which can exceed the first frame.
          width = Byte.toUnsignedInt(bytes[6]) | Byte.toUnsignedInt(bytes[7]) << 8;
          height = Byte.toUnsignedInt(bytes[8]) | Byte.toUnsignedInt(bytes[9]) << 8;
          frames = reader.getNumImages(true);
        } else {
          width = reader.getWidth(0);
          height = reader.getHeight(0);
        }
      } finally { reader.dispose(); }
    }
    if (width < 1 || height < 1 || frames < 1 || frames > 120 || (long) width * height * frames > MAX_IMAGE_PIXELS)
      throw new PreviewProblem("This image is too large to preview.");
    Image image = new Image(new ByteArrayInputStream(bytes), Math.min(width, MAX_RASTER_EDGE),
        Math.min(height, MAX_RASTER_EDGE), true, true);
    if (image.isError()) throw new PreviewProblem("This image could not be opened.");
    return image;
  }

  private static PdfPage renderPage(Path path, int requested) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("PDF is not available");
    if (Files.size(path) > MAX_PDF_BYTES) throw new PreviewProblem("This PDF is larger than 100 MiB.");
    try (PDDocument document = Loader.loadPDF(path.toFile(),
        () -> new ScratchFile(MemoryUsageSetting.setupTempFileOnly(3 * MAX_PDF_BYTES)))) {
      int count = document.getNumberOfPages();
      if (count == 0) throw new PreviewProblem("This PDF has no pages.");
      int index = Math.min(requested, count - 1);
      var box = document.getPage(index).getCropBox();
      double width = box.getWidth(), height = box.getHeight();
      if (!Double.isFinite(width * height) || width <= 0 || height <= 0)
        throw new PreviewProblem("This PDF page has invalid dimensions.");
      float scale = (float) Math.min(2, Math.min(MAX_RASTER_EDGE / Math.max(width, height),
          Math.sqrt(MAX_PAGE_PIXELS / (width * height))));
      PDFRenderer renderer = new PDFRenderer(document);
      renderer.setSubsamplingAllowed(true);
      BufferedImage image = renderer.renderImage(index, scale, ImageType.RGB);
      int renderedWidth = image.getWidth(), renderedHeight = image.getHeight();
      return new PdfPage(index, count, renderedWidth, renderedHeight,
          image.getRGB(0, 0, renderedWidth, renderedHeight, null, 0, renderedWidth));
    }
  }

  private static byte[] readBounded(Path path, int maximum, String tooLarge) throws IOException {
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(maximum + 1);
      if (bytes.length > maximum) throw new PreviewProblem(tooLarge);
      return bytes;
    }
  }

  private record PdfPage(int index, int count, int width, int height, int[] pixels) {}

  @FunctionalInterface
  private interface ReadPreview<T> { T read() throws IOException; }

  private static final class PreviewProblem extends IOException {
    PreviewProblem(String message) { super(message); }
  }
}
