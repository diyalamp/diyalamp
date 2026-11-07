package com.diyalamp.desktop.fx;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.css.TransitionEvent;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Native JavaFX CSS transitions change paint, without changing control geometry. */
public final class FxHoverThemeTests {
  private static int checks;
  private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    CountDownLatch done = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      Stage stage = new Stage();
      try {
        FxTheme.setup();
        Button tool = FxTheme.iconButton("folder-open", "Fictional action", () -> {});
        Label metric = new Label("12 words"); metric.getStyleClass().add("note-metric");
        HBox root = new HBox(8, tool, metric); root.getStyleClass().add("workspace-root");
        root.getStylesheets().add(FxTheme.class.getResource("/fx-workspace.css").toExternalForm());
        FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
        Scene scene = new Scene(root, 400, 200);
        stage.setScene(scene); stage.show(); root.applyCss(); root.layout();
        Bounds originalBounds = tool.getBoundsInParent();
        AtomicInteger starts = new AtomicInteger();
        tool.addEventHandler(TransitionEvent.START, event -> starts.incrementAndGet());
        PseudoClass hover = PseudoClass.getPseudoClass("hover");
        // Let the stage's initial pointer-state synchronization finish before the fixture's hover.
        PauseTransition begin = new PauseTransition(Duration.millis(100));
        begin.setOnFinished(event -> {
          tool.pseudoClassStateChanged(hover, true); metric.pseudoClassStateChanged(hover, true);
          root.applyCss();
          Color target = Color.web("#242526");
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
          scene.addPostLayoutPulseListener(new Runnable() {
            private boolean geometryUnchanged = true;

            @Override public void run() {
              boolean complete = false;
              try {
                // Sample real CSS pulses, including intermediate frames, without assuming
                // the paint starts on the same pulse as the synthetic hover state.
                geometryUnchanged &= originalBounds.equals(tool.getBoundsInParent());
                var toolPaint = tool.getBackground().getFills().getLast().getFill();
                var metricPaint = metric.getBackground().getFills().getLast().getFill();
                if ((starts.get() == 0 || !target.equals(toolPaint) || !target.equals(metricPaint))
                    && System.nanoTime() < deadline) {
                  Platform.requestNextPulse();
                  return;
                }
                complete = true;
                check(starts.get() > 0, "Hover must start actual native CSS transitions");
                check(target.equals(toolPaint), "Tool hover reaches the subdued dark surface: " + toolPaint);
                check(target.equals(metricPaint), "Metric hover reaches the same subdued surface: " + metricPaint
                    + " states=" + metric.getPseudoClassStates());
                check(geometryUnchanged, "Hover must never lift, scale, or resize the workbench controls");
                int previous = starts.get();
                System.setProperty("diyalamp.reduceMotion", "true"); FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
                root.applyCss(); tool.pseudoClassStateChanged(hover, false); root.applyCss();
                check(((Color) tool.getBackground().getFills().getLast().getFill()).getOpacity() == 0,
                    "Reduced motion applies the final paint immediately");
                check(previous == starts.get(), "Reduced motion must not start new CSS transitions");
              } catch (Throwable error) {
                failure.set(error);
                complete = true;
              } finally {
                if (complete) {
                  scene.removePostLayoutPulseListener(this);
                  System.clearProperty("diyalamp.reduceMotion"); stage.close(); done.countDown();
                }
              }
            }
          });
          Platform.requestNextPulse();
        });
        begin.play();
      } catch (Throwable error) { failure.set(error); stage.close(); done.countDown(); }
    });
    if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("Hover transition checks timed out");
    Platform.exit(); if (failure.get() != null) throw new AssertionError("Hover regression", failure.get());
    System.out.println("Passed " + checks + " native CSS hover checks.");
  }
}
