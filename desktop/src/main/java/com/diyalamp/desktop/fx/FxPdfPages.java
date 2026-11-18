package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.fx.FxFilePreview.PreviewProblem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableIntegerValue;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** One scrollable document surface with raster images for the pages near the viewport. */
final class FxPdfPages extends ScrollPane {
  private static final long MAX_PDF_BYTES = 100L * 1024 * 1024;
  private static final double MAX_RASTER_EDGE = 2400;
  private static final double MAX_DISPLAY_EDGE = 32_768;
  private static final double MAX_PAGE_PIXELS = 4_000_000;
  private static final int GUTTER = 24;
  private static final int PAGE_GAP = 16;

  private final Pane surface = new Pane();
  private final List<PageSize> pages;
  private final Map<Integer, Sheet> sheets = new HashMap<>();
  private final double[] tops, widths, heights;
  private final ExecutorService loader;
  private final IntConsumer pageChanged;
  private final ObservableIntegerValue zoom;
  private double documentHeight, viewportWidth, viewportHeight;
  private int current, firstVisible, lastVisible;
  private final ChangeListener<Number> zoomChanged = (observable, old, value) -> resizePages(viewportWidth, viewportHeight);
  private Path path;
  private Future<?> rendering;
  private long renderGeneration;
  private boolean positioning, disposed;

  FxPdfPages(Path path, List<PageSize> pages, ObservableIntegerValue zoom, ExecutorService loader,
      IntConsumer pageChanged) {
    this.path = path;
    this.pages = pages;
    this.zoom = zoom;
    this.loader = loader;
    this.pageChanged = pageChanged;
    tops = new double[pages.size()];
    widths = new double[pages.size()];
    heights = new double[pages.size()];
    surface.setMinSize(USE_PREF_SIZE, USE_PREF_SIZE);
    surface.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
    setContent(surface);
    getStyleClass().addAll("edge-to-edge", "file-preview-scroll");
    setPannable(true);
    setFocusTraversable(true);
    setAccessibleText("PDF document preview");
    viewportBoundsProperty().addListener((observable, old, bounds) -> {
      if (viewportWidth == bounds.getWidth() && viewportHeight == bounds.getHeight()) return;
      resizePages(bounds.getWidth(), bounds.getHeight());
    });
    vvalueProperty().addListener((observable, old, value) -> {
      if (!positioning) {
        int index = getVvalue() >= getVmax() && scrollDistance() > 0 ? pages.size() - 1
            : pageAt(offset() + Math.min(96, viewportHeight / 4));
        updatePage(index);
        updateVisiblePages();
      }
    });
    sceneProperty().addListener((observable, old, scene) -> {
      if (scene == null) clearSheets();
      else updateVisiblePages();
    });
    zoom.addListener(zoomChanged);
    resizePages(0, 0);
  }

  void goToPage(int index) {
    if (index < 0 || index >= pages.size()) return;
    positionAt(tops[index] - GUTTER);
    updatePage(index);
    updateVisiblePages();
  }

  void relocate(Path path) {
    this.path = path;
    cancelRender();
    renderNext();
  }

  void dispose() {
    disposed = true;
    zoom.removeListener(zoomChanged);
    clearSheets();
  }

  private void resizePages(double viewportWidth, double viewportHeight) {
    if (disposed) return;
    double previousHeight = heights[current];
    double withinPage = previousHeight == 0 ? 0
        : Math.clamp((offset() - tops[current] + GUTTER) / previousHeight, 0, 1);
    this.viewportWidth = viewportWidth;
    this.viewportHeight = viewportHeight;
    int percent = zoom.get();
    double top = GUTTER;
    double documentWidth = viewportWidth;
    for (int index = 0; index < pages.size(); index++) {
      PageSize size = pages.get(index);
      double scale = percent == 0
          ? Math.min(size.rasterScale(), Math.max(1, viewportWidth - 2 * GUTTER) / size.width())
          : Math.min(percent / 100.0, MAX_DISPLAY_EDGE / size.longEdge());
      tops[index] = top;
      widths[index] = Math.max(1, size.width() * scale);
      heights[index] = Math.max(1, size.height() * scale);
      documentWidth = Math.max(documentWidth, widths[index] + 2 * GUTTER);
      top += heights[index] + PAGE_GAP;
    }
    documentHeight = top - PAGE_GAP + GUTTER;
    surface.setPrefSize(documentWidth, documentHeight);
    positionAt(tops[current] - GUTTER + withinPage * heights[current]);
    updateVisiblePages();
  }

  private void positionAt(double top) {
    double distance = scrollDistance();
    positioning = true;
    setVvalue(distance == 0 ? 0 : Math.clamp(top / distance, 0, 1));
    positioning = false;
  }

  private double scrollDistance() { return Math.max(0, documentHeight - viewportHeight); }

  private double offset() { return getVvalue() * scrollDistance(); }

  private int pageAt(double offset) {
    int index = Arrays.binarySearch(tops, offset);
    return index >= 0 ? index : Math.max(0, -index - 2);
  }

  private void updatePage(int index) {
    if (index == current) return;
    current = index;
    pageChanged.accept(index);
  }

  private void updateVisiblePages() {
    if (disposed || getScene() == null || viewportHeight <= 0) return;
    double top = offset();
    firstVisible = pageAt(top);
    lastVisible = pageAt(top + viewportHeight);
    int start = Math.max(0, firstVisible - 1);
    int end = Math.min(pages.size() - 1, lastVisible + 1);
    sheets.entrySet().removeIf(entry -> {
      if (entry.getKey() >= start && entry.getKey() <= end) return false;
      surface.getChildren().remove(entry.getValue().frame);
      return true;
    });
    for (int index = start; index <= end; index++) {
      Sheet sheet = sheets.computeIfAbsent(index, this::createSheet);
      sheet.view.setFitWidth(widths[index]);
      sheet.view.setFitHeight(heights[index]);
      sheet.frame.resizeRelocate((surface.getPrefWidth() - widths[index]) / 2, tops[index],
          widths[index], heights[index]);
    }
    renderNext();
  }

  private Sheet createSheet(int index) {
    ImageView view = new ImageView();
    view.setSmooth(true);
    view.setAccessibleText("PDF page " + (index + 1));
    StackPane frame = new StackPane(view);
    frame.setManaged(false);
    frame.setBackground(Background.fill(Color.WHITE));
    surface.getChildren().add(frame);
    return new Sheet(view, frame);
  }

  private void renderNext() {
    if (rendering != null || sheets.isEmpty()) return;
    // Render the reading position first, and prefetch only after the visible sheets.
    int index = sheets.entrySet().stream().filter(entry -> !entry.getValue().finished)
        .min((left, right) -> Integer.compare(renderPriority(left.getKey()), renderPriority(right.getKey())))
        .map(Map.Entry::getKey).orElse(-1);
    if (index < 0) return;
    Path source = path;
    long request = ++renderGeneration;
    rendering = loader.submit(() -> {
      Raster result;
      try {
        result = render(source, index);
      } catch (IOException | RuntimeException error) {
        result = null;
      }
      Raster raster = result;
      if (!Thread.currentThread().isInterrupted()) Platform.runLater(() -> showRaster(request, index, raster));
    });
  }

  private int renderPriority(int index) {
    boolean visible = index >= firstVisible && index <= lastVisible;
    return visible ? Math.abs(index - current) : pages.size() + Math.abs(index - current);
  }

  private void showRaster(long request, int index, Raster raster) {
    if (request != renderGeneration || disposed) return;
    rendering = null;
    Sheet sheet = sheets.get(index);
    if (sheet != null) {
      sheet.finished = true;
      if (raster == null) {
        Label error = new Label("Page could not be previewed.");
        error.setWrapText(true);
        error.setTextFill(Color.DIMGRAY);
        sheet.frame.getChildren().setAll(error);
      } else {
        sheet.view.setImage(raster.image());
      }
    }
    renderNext();
  }

  private void cancelRender() {
    renderGeneration++;
    if (rendering != null) rendering.cancel(true);
    rendering = null;
  }

  private void clearSheets() {
    cancelRender();
    sheets.clear();
    surface.getChildren().clear();
  }

  static List<PageSize> read(Path path) throws IOException {
    try (PDDocument document = open(path)) {
      List<PageSize> sizes = new ArrayList<>();
      for (var page : document.getPages()) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("PDF preview cancelled");
        sizes.add(pageSize(page));
      }
      if (sizes.isEmpty()) throw new PreviewProblem("This PDF has no pages.");
      return sizes;
    }
  }

  private static PDDocument open(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("PDF is not available");
    if (Files.size(path) > MAX_PDF_BYTES) throw new PreviewProblem("This PDF is larger than 100 MiB.");
    return Loader.loadPDF(path.toFile(),
        () -> new ScratchFile(MemoryUsageSetting.setupTempFileOnly(3 * MAX_PDF_BYTES)));
  }

  private static Raster render(Path path, int index) throws IOException {
    try (PDDocument document = open(path)) {
      PageSize size = pageSize(document.getPage(index));
      if (!size.valid()) throw new IOException("Invalid PDF page dimensions");
      PDFRenderer renderer = new PDFRenderer(document);
      renderer.setSubsamplingAllowed(true);
      var raster = renderer.renderImage(index, (float) size.rasterScale(), ImageType.RGB);
      int width = raster.getWidth(), height = raster.getHeight();
      return new Raster(width, height, raster.getRGB(0, 0, width, height, null, 0, width));
    }
  }

  private static PageSize pageSize(PDPage page) {
    var box = page.getCropBox();
    double width = box.getWidth(), height = box.getHeight();
    if (width <= 0 || height <= 0 || !Double.isFinite(width * height)) return new PageSize(612, 792, false);
    boolean rotated = page.getRotation() % 180 != 0;
    return new PageSize(rotated ? height : width, rotated ? width : height, true);
  }

  record PageSize(double width, double height, boolean valid) {
    double longEdge() { return Math.max(width, height); }
    double rasterScale() {
      return Math.min(2, Math.min(MAX_RASTER_EDGE / longEdge(), Math.sqrt(MAX_PAGE_PIXELS / (width * height))));
    }
  }

  private record Raster(int width, int height, int[] pixels) {
    Image image() {
      WritableImage image = new WritableImage(width, height);
      image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
      return image;
    }
  }

  private static final class Sheet {
    final ImageView view;
    final StackPane frame;
    boolean finished;

    Sheet(ImageView view, StackPane frame) {
      this.view = view;
      this.frame = frame;
    }
  }
}
