package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Popover;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
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
    Popover other = new Popover(new Label("Second preview"));
    FxPopovers.style(other, owner);
    FxPopovers.toggle(other, anchor);
    check(other.isShowing() && !popup.isShowing(), "Opening another toolbar popup must close the previous popup");
    other.hide(Duration.ZERO); owner.close();
  }

  private static void motion(CountDownLatch done, AtomicReference<Throwable> failure) {
    Button anchor = new Button("Fictional preview");
    BorderPane root = new BorderPane(); root.setTop(anchor);
    root.getStyleClass().add("workspace-root");
    Color chosenAccent = Color.web("#489CFF");
    FxTheme.accent(root, chosenAccent);
    Scene scene = new Scene(root, 500, 500);
    scene.getStylesheets().add(FxTheme.class.getResource("/fx-workspace.css").toExternalForm());
    Stage owner = new Stage(); owner.setScene(scene); owner.show(); root.applyCss(); root.layout();
    FxPomodoro timer = new FxPomodoro(owner, message -> {});
    Runnable close = () -> { timer.close(); owner.close(); done.countDown(); };
    Popover popup = timer.popover();
    var surface = popup.getRoot();
    try {
      FxPopovers.toggle(popup, anchor);
      check(popup.isShowing() && surface.isVisible() && surface.getOpacity() == 0 && surface.getTranslateY() == -8,
          "The actual skin must start transparent and offset toward its anchor");
      double initialY = surface.localToScreen(surface.getLayoutBounds()).getMinY();
      Timeline opening = timeline(popup);
      // Seek on the FX thread so a slow render pulse cannot skip the intermediate frame.
      opening.jumpTo(opening.getTotalDuration().divide(2));
      check(surface.getTranslateY() > -8 && surface.getTranslateY() < 0
              && surface.getOpacity() > 0 && surface.getOpacity() < 1,
          "The opening animation must visibly translate and fade the popup");
      await("opening", () -> surface.getOpacity() == 1, () -> {
        check(surface.getTranslateY() == 0 && Math.abs(surface.localToScreen(surface.getLayoutBounds()).getMinY() - initialY - 8) < 1,
            "The dropdown must travel eight pixels on screen and settle at its AtlantaFX anchor");
        surface.applyCss(); surface.layout();
        check(surface.getStyleClass().contains("diyalamp-popover") && surface.getEffect() == null,
            "The actual AtlantaFX skin must retain workspace styling and have no drop shadow");
        Path border = (Path) surface.lookup(".border");
        check(border.getStrokeWidth() == 1 && border.getStroke().equals(Color.web("#383533")),
            "The rounded popup must use the shared one-pixel dialog border");
        checkPopupSurface(popup, Color.web("#141312"), chosenAccent);
        checkPomodoroButtons(popup);
        FxTheme.focusSurface(root, 1);
        checkPopupSurface(popup, Color.BLACK, chosenAccent);
        FxTheme.focusSurface(root, 0);
        checkPopupSurface(popup, Color.web("#141312"), chosenAccent);
        for (var node = surface.getParent(); node != null; node = node.getParent())
          if (node instanceof javafx.scene.layout.Region region && region.getBackground() != null)
            check(region.getBackground().getFills().stream()
                .allMatch(fill -> Color.TRANSPARENT.equals(fill.getFill())),
                "Popup wrappers must stay transparent outside the rounded skin");
        FxPopovers.toggle(popup, anchor);
        check(popup.isShowing() && surface.isVisible() && surface.getOpacity() == 1,
            "Toolbar dismissal must keep the skin visible while the closing motion begins");
        await("dismissal", () -> !popup.isShowing(), () -> {
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
          close.run();
        }, close, failure);
      }, close, failure);
    } catch (Throwable error) { failure.set(error); close.run(); }
  }

  private static void checkPopupSurface(Popover popup, Color expected, Color chosenAccent) {
    Parent surface = popup.getRoot();
    surface.applyCss(); surface.layout();
    SnapshotParameters parameters = new SnapshotParameters(); parameters.setFill(Color.TRANSPARENT);
    var image = surface.snapshot(parameters, null);
    var pixels = image.getPixelReader();
    check(popup.isShowing() && pixels.getColor(0, 0).getOpacity() < .05
            && pixels.getColor((int) image.getWidth() / 2, 6).equals(expected),
        "The open popover must track the workspace surface and keep transparent corners: " + expected);
    Button start = (Button) popup.getContentNode().lookup("#pomodoro-start");
    check(FxTheme.accentOf(surface).equals(chosenAccent) && start.getBackground().getFills().stream()
            .anyMatch(fill -> chosenAccent.equals(fill.getFill())),
        "A live Focus surface change must preserve the chosen action accent");
  }

  private static void checkPomodoroButtons(Popover popup) {
    var actions = new HashSet<>(popup.getContentNode().lookupAll(".popover-action"));
    actions.addAll(popup.getContentNode().lookupAll(".pomodoro-phase"));
    check(actions.size() == 6, "The Pomodoro popover must have six actions and choices");
    for (Node action : actions) {
      ButtonBase button = (ButtonBase) action;
      Bounds control = button.localToScene(button.getLayoutBounds());
      Node graphic = button.getGraphic(), text = button.lookup(".text");
      Bounds icon = graphic.localToScene(graphic.getLayoutBounds());
      Bounds label = text.localToScene(text.getLayoutBounds());
      double centerX = (Math.min(icon.getMinX(), label.getMinX()) + Math.max(icon.getMaxX(), label.getMaxX())) / 2;
      double centerY = (Math.min(icon.getMinY(), label.getMinY()) + Math.max(icon.getMaxY(), label.getMaxY())) / 2;
      check(Math.abs(centerX - control.getCenterX()) <= 1 && Math.abs(centerY - control.getCenterY()) <= 1,
          "The icon and label must be centered together in the " + button.getText() + " control");
    }
  }

  private static Timeline timeline(Popover popup) throws ReflectiveOperationException {
    Object motion = popup.getProperties().get("diyalamp.popover.motion");
    var animation = motion.getClass().getDeclaredField("animation");
    animation.setAccessible(true);
    return (Timeline) animation.get(motion);
  }

  private static void await(String name, BooleanSupplier condition, Runnable next, Runnable close,
      AtomicReference<Throwable> failure) {
    poll(name, condition, next, close, failure, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
  }

  private static void poll(String name, BooleanSupplier condition, Runnable next, Runnable close,
      AtomicReference<Throwable> failure, long deadline) {
    try {
      if (condition.getAsBoolean()) { next.run(); return; }
      if (System.nanoTime() >= deadline) throw new AssertionError("Popup animation did not finish " + name);
      PauseTransition pulse = new PauseTransition(Duration.millis(15));
      pulse.setOnFinished(event -> poll(name, condition, next, close, failure, deadline)); pulse.play();
    } catch (Throwable error) { failure.set(error); close.run(); }
  }
}
