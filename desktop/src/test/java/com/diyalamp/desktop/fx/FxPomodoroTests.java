package com.diyalamp.desktop.fx;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import atlantafx.base.controls.Popover;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;

public final class FxPomodoroTests {
  private static int checks;
  private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    CountDownLatch finished = new CountDownLatch(1); AtomicReference<Throwable> failed = new AtomicReference<>();
    Platform.startup(() -> {
      try {
        FxTheme.setup(); AtomicLong now = new AtomicLong(); PomodoroClock clock = new PomodoroClock(now::get);
        ArrayList<String> notices = new ArrayList<>();
        try (FxPomodoro timer = new FxPomodoro(null, notices::add, clock)) {
          Popover popup = timer.popover();
          check(popup.getRoot().getStyleClass().contains("diyalamp-popover") && popup.isAutoHide()
              && popup.isHideOnEscape() && popup.isAnimated(), "Timer uses the shared animated popover");
          check(timer.button().getGraphic() != null, "Timer has a flat Lucide graphic");
          check(timer.button().getText().contains("25:00"), "Status starts with the focus duration");
          Button start = (Button) popup.getContentNode().lookup("#pomodoro-start");
          Button reset = (Button) popup.getContentNode().lookup("#pomodoro-reset");
          start.fire(); check(clock.running() && start.getText().equals("Stop"), "Start begins the focus session");
          now.addAndGet(60_000_000_000L); timer.refresh();
          check(timer.button().getText().contains("24:00"), "Status tracks elapsed time");
          start.fire(); check(!clock.running() && start.getText().equals("Start"), "Pause is reversible");
          now.addAndGet(60_000_000_000L); timer.refresh();
          check(timer.button().getText().contains("24:00"), "Paused UI does not count down");
          start.fire(); now.addAndGet(1440_000_000_000L); timer.refresh();
          check(!clock.running() && timer.button().getText().equals("Break · 05:00"), "Completion prepares a manual break");
          check(notices.size() == 1 && notices.getFirst().contains("Focus session complete"), "Completion uses a single non-blocking notice");
          timer.refresh(); check(notices.size() == 1, "Further refreshes do not repeat the notice");
          var choices = popup.getContentNode().lookupAll(".toggle-button").stream().map(node -> (ToggleButton) node).toList();
          choices.stream().filter(node -> node.getText().startsWith("Focus")).findFirst().orElseThrow().fire();
          check(clock.phase() == PomodoroClock.Phase.FOCUS && clock.seconds() == 1500, "Mode choices select their fixed duration");
          start.fire(); now.addAndGet(20_000_000_000L); reset.fire();
          check(!clock.running() && timer.button().getText().contains("25:00"), "Reset stops and restores the selected phase");
        }
      } catch (Throwable error) { failed.set(error); } finally { finished.countDown(); }
    });
    if (!finished.await(20, TimeUnit.SECONDS)) throw new AssertionError("Pomodoro UI checks timed out");
    Platform.exit(); if (failed.get() != null) throw new AssertionError(failed.get());
    System.out.println("Passed " + checks + " Pomodoro UI checks.");
  }
}
