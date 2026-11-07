package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Popover;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Path;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Real PopupWindow lifecycle, rendered shape and motion, and live owner accent. */
public final class FxPopoverLifecycleTests {
  private static int checks;
  private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    CountDownLatch done = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      try {
        Platform.setImplicitExit(false);
        FxTheme.setup(); lifecycle();
        motion(done, failure);
      } catch (Throwable error) { failure.set(error); done.countDown(); }
    });
    if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("Popover lifecycle tests timed out");
    Platform.exit(); if (failure.get() != null) throw new AssertionError("Popover lifecycle regression", failure.get());
    System.out.println("Passed " + checks + " JavaFX popover lifecycle checks.");
  }
  private static void lifecycle() {
    Button anchor = new Button("Fictional preview"); BorderPane root = new BorderPane(anchor);
    FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    Stage owner = new Stage(); owner.setScene(new Scene(root, 500, 300)); owner.show(); root.applyCss(); root.layout();
    Popover popup = new Popover(new Label("Fictional content")); FxPopovers.style(popup, owner); popup.setAnimated(false);
    popup.show(anchor);
    check(popup.isShowing() && FxTheme.accentOf(popup.getRoot()).equals(FxTheme.DEFAULT_ACCENT), "A shown popover must inherit its owner's current accent");
    Color first = Color.color(.55, .65, .75), second = Color.color(.5500002, .65, .75);
    FxTheme.accent(root, first); String style = root.getStyle();
    check(!first.equals(second), "The regression fixture must contain distinct actual colors");
    FxTheme.accent(root, second);
    check(style.equals(root.getStyle()), "The fixture must reproduce an unchanged rounded CSS hex string");
    check(FxTheme.accentOf(popup.getRoot()).equals(second), "The popup must track the exact final color even when CSS text does not change");
    popup.hide(Duration.ZERO);
    Color hidden = Color.web("#BA929B"); FxTheme.accent(root, hidden);
    check(FxTheme.accentOf(popup.getRoot()).equals(second), "A hidden popover must detach the owner listener");
    popup.show(anchor);
    check(FxTheme.accentOf(popup.getRoot()).equals(hidden), "Reopening must reconnect to the latest owner color");
    popup.hide(Duration.ZERO); popup.show(anchor);
    Color latest = Color.web("#7A9A85"); FxTheme.accent(root, latest);
    check(FxTheme.accentOf(popup.getRoot()).equals(latest), "Repeated reopen must preserve live color synchronization");
    popup.hide(Duration.ZERO); owner.close();
  }

  private static void motion(CountDownLatch done, AtomicReference<Throwable> failure) {
    Button anchor = new Button("Fictional preview");
    BorderPane root = new BorderPane(); root.setTop(anchor);
    Stage owner = new Stage(); owner.setScene(new Scene(root, 500, 300)); owner.show(); root.applyCss(); root.layout();
    FxPomodoro timer = new FxPomodoro(owner, message -> {});
    Popover popup = timer.popover();
    var surface = popup.getRoot();
    FxPopovers.toggle(popup, anchor);
    check(popup.isShowing() && surface.isVisible() && surface.getOpacity() == 0 && surface.getTranslateY() == -8,
        "The actual skin must start transparent and offset toward its anchor");
    double initialY = surface.localToScreen(surface.getLayoutBounds()).getMinY();
    await(() -> surface.getOpacity() > 0 && surface.getOpacity() < 1, () -> {
      check(surface.getTranslateY() > -8 && surface.getTranslateY() < 0,
          "An animation pulse must visibly translate and fade the popup");
      await(() -> surface.getOpacity() == 1, () -> {
        check(surface.getTranslateY() == 0 && Math.abs(surface.localToScreen(surface.getLayoutBounds()).getMinY() - initialY - 8) < 1,
            "The dropdown must travel eight pixels on screen and settle at its AtlantaFX anchor");
        surface.applyCss(); surface.layout();
        check(surface.getStyleClass().contains("diyalamp-popover") && surface.getEffect() == null,
            "The actual AtlantaFX skin must retain workspace styling and have no drop shadow");
        Path border = (Path) surface.lookup(".border");
        check(border.getStrokeWidth() == 1 && border.getStroke().equals(Color.web("#2A2B2C")),
            "The rounded popup must use the shared one-pixel dialog border");
        SnapshotParameters parameters = new SnapshotParameters(); parameters.setFill(Color.TRANSPARENT);
        var image = surface.snapshot(parameters, null);
        var pixels = image.getPixelReader();
        check(pixels.getColor(0, 0).getOpacity() < .05
            && pixels.getColor((int) image.getWidth() / 2, 6).equals(Color.web("#202122")),
            "Rendered popup pixels must have a transparent corner and the dark dialog surface");
        FxPopovers.toggle(popup, anchor);
        check(popup.isShowing() && surface.isVisible() && surface.getOpacity() == 1,
            "Toolbar dismissal must keep the skin visible while the closing motion begins");
        await(() -> !popup.isShowing(), () -> {
          String previous = System.getProperty("diyalamp.reduceMotion");
          try {
            System.setProperty("diyalamp.reduceMotion", "true");
            FxPopovers.toggle(popup, anchor);
            check(popup.isShowing() && surface.isVisible() && surface.getOpacity() == 1 && surface.getTranslateY() == 0,
                "Reduced motion must open at full opacity and the final position");
            FxPopovers.toggle(popup, anchor);
            check(!popup.isShowing(), "Reduced-motion dismissal must be immediate");
          } finally {
            if (previous == null) System.clearProperty("diyalamp.reduceMotion");
            else System.setProperty("diyalamp.reduceMotion", previous);
          }
          timer.close(); owner.close(); done.countDown();
        }, done, failure);
      }, done, failure);
    }, done, failure);
  }

  private static void await(BooleanSupplier condition, Runnable next, CountDownLatch done,
      AtomicReference<Throwable> failure) {
    poll(condition, next, done, failure, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
  }

  private static void poll(BooleanSupplier condition, Runnable next, CountDownLatch done,
      AtomicReference<Throwable> failure, long deadline) {
    try {
      if (condition.getAsBoolean()) { next.run(); return; }
      if (System.nanoTime() >= deadline) throw new AssertionError("Popup animation did not reach its expected state");
      PauseTransition pulse = new PauseTransition(Duration.millis(15));
      pulse.setOnFinished(event -> poll(condition, next, done, failure, deadline)); pulse.play();
    } catch (Throwable error) { failure.set(error); done.countDown(); }
  }
}
