package com.diyalamp.desktop.fx;

import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.Popover;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** Uses the real controls with a deterministic monotonic clock. */
public final class FxPomodoroTests {
  private static final long SECOND = 1_000_000_000L;
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<Throwable> failed = new AtomicReference<>();
    Platform.startup(() -> {
      try {
        FxTheme.setup();
        settingsAndCycles();
        toolbarGeometry();
      } catch (Throwable error) {
        failed.set(error);
      } finally { finished.countDown(); }
    });
    if (!finished.await(20, TimeUnit.SECONDS)) throw new AssertionError("Pomodoro UI checks timed out");
    Platform.exit();
    if (failed.get() != null) throw new AssertionError(failed.get());
    System.out.println("Passed " + checks + " Pomodoro UI checks.");
  }

  private static void settingsAndCycles() throws Exception {
    Preferences preferences = Preferences.userRoot().node("com/diyalamp/tests/" + UUID.randomUUID());
    AtomicLong now = new AtomicLong();
    PomodoroClock clock = new PomodoroClock(now::get);
    ArrayList<String> notices = new ArrayList<>();
    try {
      try (FxPomodoro timer = new FxPomodoro(null, notices::add, clock, preferences)) {
        Popover popup = timer.popover();
        check(popup.getStyleClass().contains("diyalamp-popover") && popup.isAutoHide() && popup.isHideOnEscape(),
            "Timer uses the shared anchored popover");
        check(timer.button().getGraphic() != null && timer.button().getText() == null
                && timer.button().getAccessibleText().equals("Pomodoro timer, Focus, Ready")
                && timer.button().getAccessibleHelp().contains("25:00"),
            "Ready timer exposes its phase and default duration");
        Button start = (Button) popup.getContentNode().lookup("#pomodoro-start");
        Button reset = (Button) popup.getContentNode().lookup("#pomodoro-reset");
        TextField focus = (TextField) popup.getContentNode().lookup("#pomodoro-focus-minutes");
        TextField rest = (TextField) popup.getContentNode().lookup("#pomodoro-break-minutes");
        check(focus.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY) instanceof Label focusLabel
                && "Focus minutes".equals(focusLabel.getAccessibleText())
                && rest.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY) instanceof Label breakLabel
                && "Break minutes".equals(breakLabel.getAccessibleText()),
            "Both editable durations must have distinct linked labels");
        for (String label : new String[] {"Focus", "Break", "One cycle", "Repeat"})
          check(!choice(popup, label).getGraphic().lookupAll(".icon-stroke").isEmpty(),
              label + " must have a visible icon");
        focus.setText("181");
        check(start.isDisabled() && clock.focusMinutes() == 25,
            "Out-of-range edits must prevent starting while preserving the prior duration");
        focus.setText("1"); rest.setText("2");
        check("1".equals(focus.queryAccessibleAttribute(AccessibleAttribute.TEXT))
                && "2".equals(rest.queryAccessibleAttribute(AccessibleAttribute.TEXT)),
            "Accessibility must expose the edited durations as their field values");
        check(!start.isDisabled() && clock.focusMinutes() == 1 && clock.breakMinutes() == 2,
            "Valid durations must be immediately available to Start");
        check(preferences.getInt("pomodoro.focusMinutes", -1) == 1
                && preferences.getInt("pomodoro.breakMinutes", -1) == 2,
            "Valid durations must be persisted");
        start.fire();
        check(clock.running() && start.getText().equals("Pause") && start.getGraphic() != null,
            "Start begins focus and presents a labeled pause control");
        now.addAndGet(10 * SECOND); timer.refresh();
        check(timer.button().getTooltip().getText().contains("Focus · 00:50 · Running"),
            "Toolbar reports the phase, countdown, and running state");
        focus.setText("0");
        check(!start.isDisabled(), "An invalid edit must not prevent pausing an active timer");
        start.fire();
        check(clock.paused() && start.getText().equals("Resume") && start.isDisabled()
                && timer.button().getAccessibleText().contains("Paused"),
            "Pause must retain remaining time and expose its state to accessibility");
        now.addAndGet(60 * SECOND); timer.refresh();
        check(timer.button().getTooltip().getText().contains("00:50"), "Paused UI does not count down");
        focus.setText("1"); start.fire();
        now.addAndGet(50 * SECOND); timer.refresh();
        check(clock.running() && clock.phase() == PomodoroClock.Phase.BREAK
                && timer.button().getAccessibleHelp().contains("Break · 02:00 · Running"),
            "One cycle must automatically start the configured break");
        check(notices.size() == 1 && notices.getFirst().contains("Break started"),
            "The focus boundary must issue one nonblocking notice");
        now.addAndGet(120 * SECOND); timer.refresh(); timer.refresh();
        check(!clock.running() && !clock.paused() && start.getText().equals("Start") && notices.size() == 2,
            "One cycle must stop after its break and report each boundary once");

        choice(popup, "Repeat").fire();
        check(clock.repeating() && preferences.getBoolean("pomodoro.repeating", false),
            "Repeat must change and persist the schedule mode");
        start.fire(); now.addAndGet(180 * SECOND); timer.refresh();
        check(clock.running() && clock.phase() == PomodoroClock.Phase.FOCUS && clock.seconds() == 60,
            "Repeat must continue to another focus phase");
        reset.fire();
        check(!clock.running() && !clock.paused() && timer.button().getTooltip().getText().contains("01:00"),
            "Reset stops and restores the configured duration");
        choice(popup, "Break").fire();
        check(clock.phase() == PomodoroClock.Phase.BREAK && clock.seconds() == 120,
            "Manual phase selection must retain the configured break duration");
      }
      PomodoroClock restored = new PomodoroClock(now::get);
      try (FxPomodoro reopened = new FxPomodoro(null, notices::add, restored, preferences)) {
        check(restored.focusMinutes() == 1 && restored.breakMinutes() == 2 && restored.repeating() && !restored.running(),
            "Reopening the app must restore preferences without restarting a timer");
      }
    } finally { preferences.removeNode(); }
  }

  private static RadioButton choice(Popover popup, String text) {
    return popup.getContentNode().lookupAll(".pomodoro-phase").stream().map(RadioButton.class::cast)
        .filter(choice -> choice.getText().equals(text)).findFirst().orElseThrow();
  }

  private static void toolbarGeometry() {
    Stage stage = new Stage();
    AtomicLong now = new AtomicLong();
    PomodoroClock clock = new PomodoroClock(now::get);
    clock.setDurations(180, 180);
    try (FxPomodoro timer = new FxPomodoro(stage, message -> {}, clock)) {
      StackPane root = new StackPane(timer.button());
      root.getStyleClass().add("workspace-root");
      root.getStylesheets().add(FxPomodoro.class.getResource("/fx-workspace.css").toExternalForm());
      FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
      stage.setScene(new Scene(root, 820, 560));
      stage.show(); root.applyCss(); root.layout();
      Label phase = (Label) timer.button().lookup(".pomodoro-toolbar-phase");
      Label countdown = (Label) timer.button().lookup(".pomodoro-toolbar-time");
      check(timer.button().getWidth() == 160 && phase.getText().equals("Focus")
              && countdown.getText().equals("180:00") && countdown.getFont().getSize() == 16,
          "Ready timer must show its phase and three-digit duration with the shared font");
      check(!timer.popover().isShowing(), "The phase and duration must remain visible with the popover closed");
      clock.start(); timer.refresh(); root.applyCss(); root.layout();
      check(timer.button().getWidth() == 160 && countdown.getText().equals("180:00"),
          "Starting the timer must preserve the width and duration");
      double activeWidth = timer.button().getWidth();
      now.addAndGet(89 * SECOND); timer.refresh(); clock.pause(); timer.refresh(); root.applyCss(); root.layout();
      check(timer.button().getWidth() == activeWidth && timer.button().getAccessibleText().contains("Paused"),
          "Countdown and pause must preserve the active button width");
      clock.select(PomodoroClock.Phase.BREAK); clock.start(); timer.refresh(); root.applyCss(); root.layout();
      check(timer.button().getWidth() == activeWidth && timer.button().getAccessibleText().contains("Break"),
          "Both phases must fit the same active width");
      clock.reset(); timer.refresh(); root.applyCss(); root.layout();
      check(timer.button().getWidth() == activeWidth && phase.getText().equals("Break")
              && timer.button().getAccessibleText().equals("Pomodoro timer, Break, Ready"),
          "A ready Break must remain visible and match the width of the running timer");
      Popover popup = timer.popover();
      popup.show(timer.button());
      popup.getRoot().applyCss(); popup.getRoot().layout();
      RadioButton focus = choice(popup, "Focus"), rest = choice(popup, "Break"),
          once = choice(popup, "One cycle"), repeat = choice(popup, "Repeat");
      check(focus.getWidth() > 0 && focus.getWidth() == rest.getWidth()
              && once.getWidth() == repeat.getWidth() && focus.getWidth() == once.getWidth()
              && focus.getHeight() == 36 && once.getHeight() == 36,
          "Phase and schedule options use matching columns and the shared control height");
      var focusField = (TextField) popup.getContentNode().lookup("#pomodoro-focus-minutes");
      var breakField = (TextField) popup.getContentNode().lookup("#pomodoro-break-minutes");
      check(focusField.getWidth() == focus.getWidth() && breakField.getWidth() == rest.getWidth(),
          "Duration fields must align with the phase and schedule columns");
      check(focusField instanceof CustomTextField focusInput && focusInput.getRight() instanceof Label focusUnit
              && focusUnit.getText().equals("min") && breakField instanceof CustomTextField breakInput
              && breakInput.getRight() instanceof Label breakUnit && breakUnit.getText().equals("min"),
          "The minutes unit must use the native AtlantaFX field slot instead of occupying editable text");
      Region phaseIndicator = (Region) focus.getParent().lookup(".segmented-indicator");
      Region scheduleIndicator = (Region) once.getParent().lookup(".segmented-indicator");
      check(phaseIndicator.getLayoutX() == rest.getLayoutX() && phaseIndicator.getBackground() != null
              && scheduleIndicator.getLayoutX() == once.getLayoutX() && scheduleIndicator.getBackground() != null,
          "Opening the popover must show the correct phase and schedule indicators in place");
      String preference = System.getProperty("diyalamp.reduceMotion");
      try {
        System.setProperty("diyalamp.reduceMotion", "true");
        focus.fire(); repeat.fire();
        check(focus.isSelected() && repeat.isSelected() && phaseIndicator.getLayoutX() == focus.getLayoutX()
                && scheduleIndicator.getLayoutX() == repeat.getLayoutX(),
            "Both indicators must follow the native radio selections immediately under reduced motion");
      } finally {
        if (preference == null) System.clearProperty("diyalamp.reduceMotion");
        else System.setProperty("diyalamp.reduceMotion", preference);
      }
    } finally { stage.close(); }
  }
}
