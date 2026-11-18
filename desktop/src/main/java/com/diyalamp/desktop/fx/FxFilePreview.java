package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.NoteStore;
import com.diyalamp.desktop.NoteStore.DocumentKind;
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
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/** A local read-only tab. File validation and workspace permissions belong to the caller. */
final class FxFilePreview extends BorderPane {
  private static final int MEBIBYTE = 1024 * 1024;
  private static final int MAX_TEXT_BYTES = 5 * MEBIBYTE;
  private static final int MAX_IMAGE_BYTES = 32 * MEBIBYTE;
  private static final long MAX_IMAGE_PIXELS = 32_000_000;
  private static final int MAX_RASTER_EDGE = 2400;
  private static final int MAX_DISPLAY_EDGE = 32_768;
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
  private FxPdfPages pdf;
  private int page, pageCount;
  private final Label pageNumber = new Label();
  private final Button previous = FxTheme.iconButton("chevron-right", "Previous PDF page", () -> pdf.goToPage(page - 1));
  private final Button next = FxTheme.iconButton("chevron-right", "Next PDF page", () -> pdf.goToPage(page + 1));
  private final IntegerProperty zoomPercent = new SimpleIntegerProperty();
  private final MenuButton zoom = new MenuButton("Fit");
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
    if (kind == DocumentKind.PDF || kind == DocumentKind.IMAGE) buildImageControls();
    loadCurrent();
  }

  private void buildImageControls() {
    zoom.getStyleClass().addAll("workspace-tool", "file-preview-zoom");
    zoom.disableProperty().bind(Bindings.createBooleanBinding(
        () -> !(getCenter() instanceof ScrollPane), centerProperty()));
    ToggleGroup options = new ToggleGroup();
    for (int percent : new int[] {0, 100, 150, 200}) {
      String label = percent == 0 ? (kind == DocumentKind.PDF ? "Fit to width" : "Fit image") : percent + "%";
      RadioMenuItem item = new RadioMenuItem(label);
      item.setToggleGroup(options);
      item.setOnAction(event -> {
        zoomPercent.set(percent);
        zoom.setText(percent == 0 ? "Fit" : label);
        zoom.setAccessibleText("Preview zoom, " + label);
      });
      zoom.getItems().add(item);
    }
    RadioMenuItem fit = (RadioMenuItem) zoom.getItems().getFirst();
    fit.setSelected(true);
    zoom.setAccessibleText("Preview zoom, " + fit.getText());

    HBox navigation = new HBox(4);
    navigation.getStyleClass().add("workspace-band");
    if (kind == DocumentKind.PDF) {
      previous.getGraphic().setRotate(180);
      previous.getStyleClass().add("file-row-action");
      next.getStyleClass().add("file-row-action");
      pageNumber.getStyleClass().add("document-breadcrumb");
      pageNumber.setMinWidth(0);
      navigation.setAlignment(Pos.CENTER);
      navigation.getChildren().addAll(previous, pageNumber, next);
      updateNavigation();
    } else {
      navigation.setAlignment(Pos.CENTER_RIGHT);
    }
    navigation.getChildren().add(zoom);
    setTop(navigation);
  }

  private void loadCurrent() {
    Path source = path;
    switch (kind) {
      case TEXT -> submit(() -> loadText(source), this::showText);
      case IMAGE -> submit(() -> loadImage(source), result -> showImage(result.image(), result.naturalWidth()));
      case PDF -> submit(() -> FxPdfPages.read(source), pages -> {
        pageCount = pages.size();
        pdf = new FxPdfPages(source, pages, zoomPercent, LOADER, index -> {
          page = index;
          updateNavigation();
        });
        setCenter(pdf);
        updateNavigation();
      });
      default -> showError("This file type cannot be previewed.");
    }
  }

  void relocate(Path checkedPath) {
    if (disposed || checkedPath.equals(path)) return;
    path = checkedPath;
    describePath();
    if (loading != null) loadCurrent();
    if (pdf != null) pdf.relocate(checkedPath);
  }

  private void describePath() { setAccessibleText("Read-only preview of " + path.getFileName()); }

  void focus() {
    if (getCenter() instanceof TextArea || getCenter() instanceof ScrollPane) getCenter().requestFocus();
    else requestFocus();
  }

  void dispose() {
    disposed = true;
    zoom.hide();
    generation++;
    if (loading != null) loading.cancel(true);
    loading = null;
    if (pdf != null) pdf.dispose();
  }

  private void updateNavigation() {
    pageNumber.setText(pageCount == 0 ? "PDF" : (page + 1) + " / " + pageCount);
    pageNumber.setAccessibleText(pageCount == 0 ? "PDF" : "Page " + (page + 1) + " of " + pageCount);
    previous.setDisable(pageCount == 0 || page == 0);
    next.setDisable(pageCount == 0 || page >= pageCount - 1);
  }

  private void showImage(Image image, double naturalWidth) {
    ImageView view = new ImageView(image);
    view.setPreserveRatio(true);
    view.setSmooth(true);
    view.setAccessibleText(path.getFileName().toString());
    StackPane sheet = new StackPane(view);
    sheet.setPadding(new Insets(IMAGE_GUTTER));
    ScrollPane scroll = new ScrollPane(sheet);
    scroll.getStyleClass().addAll("edge-to-edge", "file-preview-scroll");
    scroll.setFitToWidth(true);
    scroll.setFitToHeight(true);
    scroll.setPannable(true);
    scroll.setFocusTraversable(true);
    scroll.setAccessibleText("Image preview");
    // Bound layout as well as raster memory.
    double maxWidth = MAX_DISPLAY_EDGE * image.getWidth() / Math.max(image.getWidth(), image.getHeight());
    view.fitWidthProperty().bind(Bindings.createDoubleBinding(() -> zoomPercent.get() == 0
        ? Math.min(image.getWidth(), Math.max(1, scroll.getViewportBounds().getWidth() - 2 * IMAGE_GUTTER))
        : Math.min(maxWidth, naturalWidth * zoomPercent.get() / 100), zoomPercent, scroll.viewportBoundsProperty()));
    view.fitHeightProperty().bind(Bindings.createDoubleBinding(() -> zoomPercent.get() == 0
        ? Math.min(image.getHeight(), Math.max(1, scroll.getViewportBounds().getHeight() - 2 * IMAGE_GUTTER))
        : 0, zoomPercent, scroll.viewportBoundsProperty()));
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
      var owner = getScene() == null ? null : getScene().getFocusOwner();
      boolean focused = owner != null && (owner == this || owner == getCenter());
      display.run();
      if (focused) focus();
    });
  }

  private static String loadText(Path path) throws IOException {
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(
        readBounded(path, MAX_TEXT_BYTES, "This text file is larger than 5 MiB."))).toString();
    return text.startsWith("\uFEFF") ? text.substring(1) : text;
  }

  private static PreviewImage loadImage(Path path) throws IOException {
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
    return new PreviewImage(image, width);
  }

  private static byte[] readBounded(Path path, int maximum, String tooLarge) throws IOException {
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(maximum + 1);
      if (bytes.length > maximum) throw new PreviewProblem(tooLarge);
      return bytes;
    }
  }

  private record PreviewImage(Image image, double naturalWidth) {}

  @FunctionalInterface
  private interface ReadPreview<T> { T read() throws IOException; }

  static final class PreviewProblem extends IOException {
    PreviewProblem(String message) { super(message); }
  }
}
