package com.diyalamp.desktop.fx;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.css.TransitionEvent;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

/** Native JavaFX CSS transitions change paint, without changing control geometry. */
public final class FxHoverThemeTests {
  private static int checks;
  private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    if (!"Headless".equals(System.getProperty("glass.platform"))) {
      throw new IllegalStateException("Hover checks require the headless JavaFX pointer");
    }
    CountDownLatch done = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      Stage stage = new Stage();
      try {
        FxTheme.setup();
        Button tool = FxTheme.iconButton("folder-open", "Fictional action", () -> {});
        Label metric = new Label("12 words"); metric.getStyleClass().add("note-metric");
        HBox root = new HBox(8, tool, metric); root.setFillHeight(false);
        root.getStyleClass().add("workspace-root");
        root.getStylesheets().add(FxTheme.class.getResource("/fx-workspace.css").toExternalForm());
        FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
        Scene scene = new Scene(root, 400, 200);
        stage.setScene(scene); stage.show(); root.applyCss(); root.layout();
        Bounds originalBounds = tool.getBoundsInParent();
        AtomicInteger starts = new AtomicInteger();
        tool.addEventHandler(TransitionEvent.START, event -> starts.incrementAndGet());
        Robot pointer = new Robot();
        pointer.mouseMove(root.localToScreen(root.getWidth() / 2, root.getHeight() / 2));
        Color target = Color.web("#202122");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        scene.addPostLayoutPulseListener(new Runnable() {
          private boolean hoverStarted;
          private boolean checkingReducedMotion;
          private boolean geometryUnchanged = true;
          private int startsBeforeReducedMotion;

          @Override public void run() {
            boolean complete = false;
            try {
              if (System.nanoTime() >= deadline) {
                throw new AssertionError("Hover did not settle. Toolbar hovered=" + tool.isHover()
                    + " paint=" + paint(tool) + " starts=" + starts.get() + ", metric hovered=" + metric.isHover());
              }
              if (!hoverStarted) {
                // The headless Robot uses JavaFX picking with one pointer and one hovered sibling.
                moveToCenter(pointer, tool);
                hoverStarted = true;
              } else if (!checkingReducedMotion) {
                geometryUnchanged &= originalBounds.equals(tool.getBoundsInParent());
                if (tool.isHover() && target.equals(paint(tool)) && starts.get() > 0) {
                  check(starts.get() > 0, "Hover must start actual native CSS transitions");
                  check(target.equals(paint(tool)), "Tool hover reaches the subdued dark surface");
                  check(geometryUnchanged, "Hover must never lift, scale, or resize the workbench controls");
                  startsBeforeReducedMotion = starts.get();
                  System.setProperty("diyalamp.reduceMotion", "true");
                  FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
                  root.applyCss();
                  moveToCenter(pointer, metric);
                  checkingReducedMotion = true;
                }
              } else if (metric.isHover() && !tool.isHover()) {
                check(target.equals(paint(metric)), "Metric hover immediately reaches the subdued surface: " + paint(metric));
                check(paint(tool).getOpacity() == 0, "Reduced motion applies the final toolbar paint immediately");
                check(startsBeforeReducedMotion == starts.get(), "Reduced motion must not start new CSS transitions");
                complete = true;
              }
            } catch (Throwable error) {
              failure.set(error);
              complete = true;
            } finally {
              if (complete) {
                scene.removePostLayoutPulseListener(this);
                System.clearProperty("diyalamp.reduceMotion"); stage.close(); done.countDown();
              } else {
                Platform.requestNextPulse();
              }
            }
          }
        });
        Platform.requestNextPulse();
      } catch (Throwable error) { failure.set(error); stage.close(); done.countDown(); }
    });
    if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("Hover transition checks timed out");
    Platform.exit(); if (failure.get() != null) throw new AssertionError("Hover regression", failure.get());
    System.out.println("Passed " + checks + " native CSS hover checks.");
  }

  private static void moveToCenter(Robot pointer, Region node) {
    pointer.mouseMove(node.localToScreen(node.getWidth() / 2, node.getHeight() / 2));
  }

  private static Color paint(Region node) {
    return (Color) node.getBackground().getFills().getLast().getFill();
  }
}
