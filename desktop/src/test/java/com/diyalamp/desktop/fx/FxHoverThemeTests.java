package com.diyalamp.desktop.fx;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.css.TransitionEvent;
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
        stage.setScene(new Scene(root, 400, 200)); stage.show(); root.applyCss(); root.layout();
        double x = tool.getLayoutX(), y = tool.getLayoutY(), width = tool.getWidth(), height = tool.getHeight();
        AtomicInteger starts = new AtomicInteger();
        tool.addEventHandler(TransitionEvent.START, event -> starts.incrementAndGet());
        PseudoClass hover = PseudoClass.getPseudoClass("hover");
        PauseTransition end = new PauseTransition(Duration.millis(250));
        end.setOnFinished(event -> {
          try {
            check(starts.get() > 0, "Hover must start actual native CSS transitions");
            check(tool.getBackground().getFills().getLast().getFill().equals(Color.web("#242526")), "Tool hover reaches the subdued dark surface");
            check(metric.getBackground().getFills().getLast().getFill().equals(Color.web("#242526")),
                "Metric hover reaches the same subdued surface: " + metric.getBackground().getFills().getLast().getFill()
                    + " states=" + metric.getPseudoClassStates());
            check(x == tool.getLayoutX() && y == tool.getLayoutY() && width == tool.getWidth() && height == tool.getHeight(),
                "Hover must never lift, scale, or resize the workbench controls");
            int previous = starts.get();
            System.setProperty("diyalamp.reduceMotion", "true"); FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
            root.applyCss(); tool.pseudoClassStateChanged(hover, false); root.applyCss();
            check(((Color) tool.getBackground().getFills().getLast().getFill()).getOpacity() == 0,
                "Reduced motion applies the final paint immediately");
            check(previous == starts.get(), "Reduced motion must not start new CSS transitions");
          } catch (Throwable error) { failure.set(error); }
          finally { System.clearProperty("diyalamp.reduceMotion"); stage.close(); done.countDown(); }
        });
        // Let the stage's initial pointer-state synchronization finish before the fixture's hover.
        PauseTransition begin = new PauseTransition(Duration.millis(100));
        begin.setOnFinished(event -> {
          tool.pseudoClassStateChanged(hover, true); metric.pseudoClassStateChanged(hover, true);
          root.applyCss(); end.play();
        });
        begin.play();
      } catch (Throwable error) { failure.set(error); stage.close(); done.countDown(); }
    });
    if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("Hover transition checks timed out");
    Platform.exit(); if (failure.get() != null) throw new AssertionError("Hover regression", failure.get());
    System.out.println("Passed " + checks + " native CSS hover checks.");
  }
}
