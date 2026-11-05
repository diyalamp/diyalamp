package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Popover;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Actual Popup scenes: preferred opening focus, normal keyboard traversal, and dismissal. */
public final class FxPopoverFocusTests {
  private static int checks;
  private static final CountDownLatch done = new CountDownLatch(1);
  private static final AtomicReference<Throwable> failure = new AtomicReference<>();
  private static Stage stage;
  private static FxPomodoro timer;
  private static Preferences preferences;
  private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    Platform.startup(() -> { try { start(); } catch (Throwable error) { finish(error); } });
    if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("Popover keyboard tests timed out");
    Platform.exit(); if (failure.get() != null) throw new AssertionError("Popover keyboard regression", failure.get());
    System.out.println("Passed " + checks + " JavaFX popover keyboard checks.");
  }
  private static void start() throws Exception {
    FxTheme.setup(); preferences = Preferences.userRoot().node("com/diyalamp/tests/" + UUID.randomUUID());
    FxDiyalamp app = new FxDiyalamp(preferences); stage = new Stage();
    Field owner = FxDiyalamp.class.getDeclaredField("stage"); owner.setAccessible(true); owner.set(app, stage);
    Button accent = (Button) call(app, "accentButton"); timer = new FxPomodoro(stage, message -> {});
    BorderPane root = (BorderPane) field(app, "root"); root.setCenter(new HBox(timer.button(), accent));
    stage.setScene(new Scene(root, 600, 400)); call(app, "initializeAccent"); stage.show(); root.applyCss(); root.layout();
    timer.show();
    later(320, () -> {
      Popover popup = timer.popover(); Button start = (Button) popup.getContentNode().lookup("#pomodoro-start");
      Button reset = (Button) popup.getContentNode().lookup("#pomodoro-reset");
      check(popup.isFocused() && popup.getScene().getFocusOwner() == start, "Pomodoro must open with keyboard focus on Start");
      key(start, KeyCode.TAB, false); check(popup.getScene().getFocusOwner() == reset, "Tab must traverse actual popup controls");
      key(reset, KeyCode.TAB, true); check(popup.getScene().getFocusOwner() == start, "Shift-Tab must traverse back inside the popup");
      key(start, KeyCode.ESCAPE, false);
      later(220, () -> {
        check(!popup.isShowing(), "Escape must dismiss the actual Pomodoro popup");
        accent.fire();
        later(320, () -> {
          Popover colors = (Popover) field(app, "accentMenu");
          Button selected = colors.getContentNode().lookupAll(".accent-choice").stream().map(node -> (Button) node)
              .filter(button -> button.getAccessibleText().contains(", selected")).findFirst().orElseThrow();
          check(colors.isFocused() && colors.getScene().getFocusOwner() == selected, "Accent must open focused on the selected preset");
          key(selected, KeyCode.TAB, false);
          check(colors.getScene().getFocusOwner() != selected && colors.getScene().getFocusOwner().getScene() == colors.getScene(),
              "Tab must stay in the actual Accent popup controls");
          key(colors.getScene().getFocusOwner(), KeyCode.ESCAPE, false);
          later(220, () -> {
            check(!colors.isShowing(), "Escape must dismiss the actual Accent popup");
            timer.show(); timer.popover().hide(Duration.ZERO); accent.requestFocus();
            later(320, () -> {
              check(!timer.popover().isShowing() && stage.getScene().getFocusOwner() == accent,
                  "Hiding before the entrance finishes must cancel pending popup focus and preserve the owner's focus");
              finish(null);
            });
          });
        });
      });
    });
  }
  private static void key(javafx.scene.Node node, KeyCode code, boolean shift) {
    node.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
  }
  private interface Checked { void run() throws Exception; }
  private static void later(double milliseconds, Checked check) {
    PauseTransition wait = new PauseTransition(Duration.millis(milliseconds));
    wait.setOnFinished(event -> { try { check.run(); } catch (Throwable error) { finish(error); } }); wait.play();
  }
  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
  }
  private static Object call(Object target, String name) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
  }
  private static void finish(Throwable error) {
    if (error != null) failure.set(error);
    if (timer != null) timer.close(); if (stage != null) stage.close();
    try { if (preferences != null) preferences.removeNode(); } catch (Exception cleanup) { if (failure.get() == null) failure.set(cleanup); }
    done.countDown();
  }
}
