package com.diyalamp.desktop.fx;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/** Exercises native preview controls and their background readers with generated local files. */
public final class FxFilePreviewTests {
  public static void main(String[] args) throws Exception {
    Path folder = Files.createTempDirectory("diyalamp-preview-tests-");
    Platform.startup(() -> {
      FxTheme.setup();
      Platform.setImplicitExit(false);
    });
    try {
      textAndImages(folder);
      pdfNavigation(folder);
      previewErrors(folder);
      System.out.println("Passed JavaFX file preview checks.");
    } finally {
      Platform.exit();
      try (var paths = Files.walk(folder)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
      }
    }
  }

  private static void textAndImages(Path folder) throws Exception {
    String contents = "# Still plain text\n\n**Not formatted** and 👋";
    Path textFile = Files.writeString(folder.resolve("sample.txt"), "\uFEFF" + contents);
    FxFilePreview text = fx(() -> new FxFilePreview(textFile));
    await("the plain text preview", () -> text.getCenter() instanceof TextArea);
    fx(() -> {
      TextArea area = (TextArea) text.getCenter();
      check(!area.isEditable() && area.getText().equals(contents), "TXT retains syntax and Unicode in read-only text");
      text.dispose();
      return null;
    });

    BufferedImage fixture = new BufferedImage(66, 42, BufferedImage.TYPE_INT_RGB);
    for (String format : new String[] {"png", "jpeg"}) {
      Path imageFile = folder.resolve("photo." + format);
      check(ImageIO.write(fixture, format, imageFile.toFile()), "Cannot write the " + format + " fixture");
      FxFilePreview image = fx(() -> new FxFilePreview(imageFile));
      await("the " + format + " preview", () -> image.getCenter() instanceof ScrollPane);
      fx(() -> {
        Image displayed = image(image);
        check(displayed.getWidth() == 66 && displayed.getHeight() == 42, "Image dimensions survive decoding");
        image.dispose();
        return null;
      });
    }
  }

  private static void pdfNavigation(Path folder) throws Exception {
    Path source = folder.resolve("pages.pdf");
    writePdf(source, false);
    FxFilePreview pdf = fx(() -> new FxFilePreview(source));
    await("the first PDF page", () -> pageLabel(pdf).getText().equals("Page 1 of 2"));
    fx(() -> {
      check(previous(pdf).isDisabled() && !next(pdf).isDisabled(), "The first page has only forward navigation");
      check(colorAtCenter(pdf).getBlue() > .9, "The first page rendered its own contents");
      return null;
    });

    Path relocated = Files.move(source, folder.resolve("relocated.pdf"));
    fx(() -> {
      pdf.relocate(relocated);
      next(pdf).fire();
      return null;
    });
    await("the next page after relocating the open PDF", () -> pageLabel(pdf).getText().equals("Page 2 of 2"));
    fx(() -> {
      check(!previous(pdf).isDisabled() && next(pdf).isDisabled(), "The last page has only backward navigation");
      check(colorAtCenter(pdf).getRed() > .9, "The second page rendered from the new path");
      previous(pdf).fire();
      return null;
    });
    await("the previous PDF page", () -> pageLabel(pdf).getText().equals("Page 1 of 2"));

    FxFilePreview closed = fx(() -> {
      FxFilePreview pending = new FxFilePreview(relocated);
      pending.dispose();
      return pending;
    });
    fx(() -> {
      pdf.dispose();
      check(!(closed.getCenter() instanceof ScrollPane), "A cancelled request must leave no rendered page");
      return null;
    });
  }

  private static void previewErrors(Path folder) throws Exception {
    Path invalid = Files.write(folder.resolve("invalid.txt"), new byte[] {(byte) 0xC3, 0});
    Path corrupt = Files.writeString(folder.resolve("corrupt.pdf"), "Not a PDF");
    Path locked = folder.resolve("locked.pdf");
    writePdf(locked, true);
    expectError(invalid, "not valid UTF-8");
    expectError(corrupt, "could not be previewed");
    expectError(locked, "requires a password");
  }

  private static void expectError(Path source, String message) throws Exception {
    FxFilePreview preview = fx(() -> new FxFilePreview(source));
    await(message, () -> preview.getCenter() instanceof StackPane state
        && state.getChildren().getFirst() instanceof Label label && label.getText().contains(message));
    fx(() -> {
      preview.dispose();
      return null;
    });
  }

  private static void writePdf(Path path, boolean locked) throws Exception {
    try (PDDocument document = new PDDocument()) {
      for (int index = 0; index < 2; index++) {
        PDPage page = new PDPage(new PDRectangle(100, 120));
        document.addPage(page);
        try (var stream = new PDPageContentStream(document, page)) {
          stream.setNonStrokingColor(index, 0, 1 - index);
          stream.addRect(0, 0, 100, 120);
          stream.fill();
        }
      }
      if (locked) document.protect(new StandardProtectionPolicy("owner", "reader", new AccessPermission()));
      document.save(path.toFile());
    }
  }

  private static Image image(FxFilePreview preview) {
    StackPane sheet = (StackPane) ((ScrollPane) preview.getCenter()).getContent();
    return ((ImageView) sheet.getChildren().getFirst()).getImage();
  }

  private static Color colorAtCenter(FxFilePreview preview) {
    Image page = image(preview);
    return page.getPixelReader().getColor((int) (page.getWidth() / 2), (int) (page.getHeight() / 2));
  }

  private static Label pageLabel(FxFilePreview pdf) { return (Label) ((HBox) pdf.getTop()).getChildren().get(1); }
  private static Button previous(FxFilePreview pdf) { return (Button) ((HBox) pdf.getTop()).getChildren().getFirst(); }
  private static Button next(FxFilePreview pdf) { return (Button) ((HBox) pdf.getTop()).getChildren().getLast(); }

  private static void await(String description, Callable<Boolean> ready) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < end) {
      if (fx(ready)) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Timed out waiting for " + description);
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    Platform.runLater(task);
    return task.get(8, TimeUnit.SECONDS);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
