package com.diyalamp.desktop.fx;

import java.util.concurrent.atomic.AtomicLong;

public final class PomodoroClockTests {
  private static int checks;
  private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
  public static void main(String[] args) {
    AtomicLong now = new AtomicLong(); PomodoroClock timer = new PomodoroClock(now::get);
    check(timer.seconds() == 1500 && !timer.running(), "Focus waits for an explicit start");
    timer.start(); now.set(999_000_000);
    check(timer.seconds() == 1500, "Partial seconds round up");
    now.set(60_000_000_000L); check(timer.seconds() == 1440, "Elapsed time follows monotonic time");
    timer.pause(); now.set(600_000_000_000L);
    check(timer.seconds() == 1440 && !timer.running(), "Pause excludes time away");
    timer.start(); now.set(660_000_000_000L);
    check(timer.seconds() == 1380, "Resume keeps the remaining duration");
    timer.reset(); check(timer.seconds() == 1500 && !timer.running(), "Reset stops the current session");
    timer.start(); now.addAndGet(1500_000_000_000L); timer.tick();
    check(timer.phase() == PomodoroClock.Phase.BREAK && timer.seconds() == 300 && !timer.running(),
        "Completed focus prepares a break without starting it");
    check(timer.completion().orElseThrow() == PomodoroClock.Phase.FOCUS, "Completion identifies the completed phase");
    timer.tick(); check(timer.completion().isEmpty(), "Completion is reported once");
    timer.start(); now.addAndGet(300_000_000_000L); timer.tick();
    check(timer.phase() == PomodoroClock.Phase.FOCUS && timer.seconds() == 1500 && !timer.running(),
        "Completed break prepares the next focus session");
    timer.select(PomodoroClock.Phase.BREAK); check(timer.seconds() == 300, "Manual break selection starts at five minutes");
    timer.start(); now.addAndGet(999_000_000_000_000L); timer.tick();
    check(timer.seconds() == 1500 && !timer.running(), "Delayed refresh cannot auto-run multiple sessions");
    timer.select(PomodoroClock.Phase.FOCUS); timer.start(); now.addAndGet(1500_000_000_000L); timer.pause();
    check(timer.phase() == PomodoroClock.Phase.BREAK && !timer.running(), "Pause at completion retains the completion transition");
    System.out.println("Passed " + checks + " Pomodoro clock checks.");
  }
}
