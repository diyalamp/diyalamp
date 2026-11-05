package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Popover;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import java.util.function.BooleanSupplier;
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

/** Actual Popup scenes use the component’s default focus and standard traversal. */
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
    Popover popup = timer.popover();
    Button start = (Button) popup.getContentNode().lookup("#pomodoro-start");
    Button reset = (Button) popup.getContentNode().lookup("#pomodoro-reset");
    await(() -> popup.isFocused() && popup.getScene().getFocusOwner() == start,
        "Pomodoro's natural first control must receive focus", () -> {
      check(popup.getScene().getFocusOwner() == start, "Start must be the component's natural first focusable control");
      key(start, KeyCode.TAB, false); check(popup.getScene().getFocusOwner() == reset, "Tab must traverse actual popup controls");
      key(reset, KeyCode.TAB, true); check(popup.getScene().getFocusOwner() == start, "Shift-Tab must traverse back inside the popup");
      check(popup.isHideOnEscape() && popup.isAutoHide(), "The maintained component must retain native Escape and outside-click dismissal");
      popup.hide(Duration.ZERO); accent.fire();
      Popover colors = (Popover) field(app, "accentMenu");
      await(() -> colors.isFocused() && colors.getScene().getFocusOwner() instanceof Button button
          && button.getStyleClass().contains("accent-choice"), "Accent's component-default control must receive focus", () -> {
        Button selected = colors.getContentNode().lookupAll(".accent-choice").stream().map(node -> (Button) node)
            .filter(button -> button.getAccessibleText().contains(", selected")).findFirst().orElseThrow();
        check(selected.getText().contains("✓"), "The selected preset indicator must remain independent of default keyboard focus");
        var focused = colors.getScene().getFocusOwner(); key(focused, KeyCode.TAB, false);
        check(colors.getScene().getFocusOwner() != focused && colors.getScene().getFocusOwner().getScene() == colors.getScene(),
            "Tab must stay in the actual Accent popup controls");
        check(colors.isHideOnEscape() && colors.isAutoHide(), "Accent must retain the maintained component's native dismissal behavior");
        colors.hide(Duration.ZERO); timer.show(); timer.popover().hide(Duration.ZERO); accent.requestFocus();
        await(() -> !timer.popover().isShowing() && stage.getScene().getFocusOwner() == accent,
            "Closing a popup must preserve the owner's chosen focus", () -> {
          check(stage.getScene().getFocusOwner() == accent, "Closing a popup must not schedule a later preferred-focus override");
          finish(null);
        });
      });
    });
  }
  private static void await(BooleanSupplier condition, String description, Checked next) {
    poll(condition, description, next, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
  }
  private static void poll(BooleanSupplier condition, String description, Checked next, long deadline) {
    try {
      if (condition.getAsBoolean()) { next.run(); return; }
      if (System.nanoTime() >= deadline) throw new AssertionError(description);
      PauseTransition pulse = new PauseTransition(Duration.millis(25));
      pulse.setOnFinished(event -> poll(condition, description, next, deadline)); pulse.play();
    } catch (Throwable error) { finish(error); }
  }
  private static void key(javafx.scene.Node node, KeyCode code, boolean shift) {
    node.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
  }
  private interface Checked { void run() throws Exception; }
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
