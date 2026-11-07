package com.diyalamp.desktop.fx;

import java.util.concurrent.atomic.AtomicLong;

public final class PomodoroClockTests {
  private static final long SECOND = 1_000_000_000L, MINUTE = 60 * SECOND;
  private static int checks;

  private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

  public static void main(String[] args) {
    pauseResumeAndReset(); oneCycle(); repeatsAcrossDelays(); durationAndModeChanges(); monotonicWrap();
    System.out.println("Passed " + checks + " Pomodoro clock checks.");
  }

  private static void pauseResumeAndReset() {
    AtomicLong now = new AtomicLong(); PomodoroClock timer = new PomodoroClock(now::get);
    check(timer.focusMinutes() == 25 && timer.breakMinutes() == 5 && !timer.repeating(),
        "The default is 25 minutes of focus and five minutes of break, for one cycle");
    check(timer.seconds() == 1500 && !timer.running() && !timer.paused(), "Focus waits for an explicit start");
    timer.start(); now.set(999_000_000);
    check(timer.seconds() == 1500, "Partial seconds round up");
    now.set(MINUTE); check(timer.seconds() == 1440, "Elapsed time follows monotonic time");
    timer.pause(); now.set(10 * MINUTE);
    check(timer.seconds() == 1440 && !timer.running() && timer.paused(), "Pause excludes time away");
    timer.start(); now.set(11 * MINUTE);
    check(timer.seconds() == 1380 && timer.running() && !timer.paused(), "Resume keeps the remaining duration");
    timer.reset();
    check(timer.seconds() == 1500 && !timer.running() && !timer.paused(), "Reset restores the selected phase");
    timer.start(); now.addAndGet(25 * MINUTE); timer.pause();
    check(timer.phase() == PomodoroClock.Phase.BREAK && timer.paused() && timer.seconds() == 300,
        "Pause at the phase boundary pauses the ready break");
    check(timer.completion().orElseThrow() == PomodoroClock.Phase.FOCUS, "Pause retains the boundary completion");
  }

  private static void oneCycle() {
    AtomicLong now = new AtomicLong(); PomodoroClock timer = new PomodoroClock(now::get);
    timer.start(); now.addAndGet(25 * MINUTE + 10 * SECOND); timer.tick();
    check(timer.phase() == PomodoroClock.Phase.BREAK && timer.seconds() == 290 && timer.running(),
        "Focus automatically starts the break at the original boundary even after a delayed tick");
    check(timer.completion().orElseThrow() == PomodoroClock.Phase.FOCUS, "Completion identifies the completed phase");
    timer.tick(); check(timer.completion().isEmpty(), "Completion is reported once");
    now.addAndGet(290 * SECOND); timer.tick();
    check(timer.phase() == PomodoroClock.Phase.FOCUS && timer.seconds() == 1500 && !timer.running() && !timer.paused(),
        "A completed break stops with the next focus session ready");
    check(timer.completion().orElseThrow() == PomodoroClock.Phase.BREAK, "The full cycle reports its last phase");
    timer.start(); now.addAndGet(50 * MINUTE); timer.tick();
    check(timer.phase() == PomodoroClock.Phase.FOCUS && timer.seconds() == 1500 && !timer.running()
        && timer.completion().orElseThrow() == PomodoroClock.Phase.BREAK,
        "A delayed single cycle accounts for both phases and stops");
    timer.select(PomodoroClock.Phase.BREAK);
    check(timer.seconds() == 300 && !timer.running(), "A manual break waits for Start");
    timer.start(); now.addAndGet(5 * MINUTE); timer.tick();
    check(timer.phase() == PomodoroClock.Phase.FOCUS && !timer.running(), "A manual break ends on a fresh focus phase");
  }

  private static void repeatsAcrossDelays() {
    AtomicLong now = new AtomicLong(); PomodoroClock timer = new PomodoroClock(now::get);
    timer.setDurations(2, 1);
    timer.setRepeating(true);
    timer.start(); now.set(3_000_002 * MINUTE + 15 * SECOND); timer.tick();
    check(timer.phase() == PomodoroClock.Phase.BREAK && timer.seconds() == 45 && timer.running(),
        "Repeat skips whole elapsed cycles and applies the remaining time to the correct phase");
    check(timer.completion().orElseThrow() == PomodoroClock.Phase.FOCUS, "A delayed repeat reports the latest boundary");
    now.addAndGet(45 * SECOND); timer.tick();
    check(timer.phase() == PomodoroClock.Phase.FOCUS && timer.seconds() == 120 && timer.running()
        && timer.completion().orElseThrow() == PomodoroClock.Phase.BREAK,
        "An exact repeating break boundary starts focus");
    timer.select(PomodoroClock.Phase.BREAK); timer.start();
    now.addAndGet(4 * MINUTE + 20 * SECOND); timer.tick();
    check(timer.phase() == PomodoroClock.Phase.FOCUS && timer.seconds() == 100
        && timer.completion().orElseThrow() == PomodoroClock.Phase.BREAK,
        "Repeat cycles correctly when the starting phase is a break");
  }

  private static void durationAndModeChanges() {
    AtomicLong now = new AtomicLong(); PomodoroClock timer = new PomodoroClock(now::get);
    timer.setDurations(1, 180);
    check(timer.seconds() == 60 && timer.breakMinutes() == 180, "Custom lengths update an idle timer immediately");
    try {
      timer.setDurations(100, 181);
      throw new AssertionError("Unsupported durations must be rejected");
    } catch (IllegalArgumentException expected) {
      check(timer.focusMinutes() == 1 && timer.breakMinutes() == 180, "Invalid duration changes must be atomic");
    }
    timer.start(); now.addAndGet(20 * SECOND);
    timer.setDurations(4, 2);
    check(timer.seconds() == 40, "Updating durations must preserve a running phase");
    timer.pause(); timer.setDurations(3, 1);
    check(timer.seconds() == 40 && timer.paused(), "Updating durations must preserve a paused phase");
    timer.start(); now.addAndGet(40 * SECOND); timer.tick();
    check(timer.seconds() == 60 && timer.phase() == PomodoroClock.Phase.BREAK, "The next phase uses its new duration");
    timer.reset();
    check(timer.seconds() == 60 && timer.phase() == PomodoroClock.Phase.BREAK && timer.completion().isEmpty(),
        "Reset keeps the selected phase and clears prior completion");
    timer.setRepeating(true); timer.start();
    now.addAndGet(MINUTE + 10 * SECOND); timer.setRepeating(false);
    check(timer.phase() == PomodoroClock.Phase.FOCUS && timer.seconds() == 170 && timer.running(),
        "Disabling repeat settles elapsed phases before applying the new mode");
    now.addAndGet(4 * MINUTE); timer.tick();
    check(timer.phase() == PomodoroClock.Phase.FOCUS && timer.seconds() == 180 && !timer.running(),
        "Disabling repeat lets the active cycle finish once");
  }

  private static void monotonicWrap() {
    AtomicLong now = new AtomicLong(Long.MAX_VALUE - 45 * SECOND);
    PomodoroClock timer = new PomodoroClock(now::get);
    timer.setDurations(1, 1); timer.start();
    now.addAndGet(75 * SECOND); timer.tick();
    check(timer.phase() == PomodoroClock.Phase.BREAK && timer.seconds() == 45,
        "Elapsed differences remain correct when the monotonic counter wraps");
  }
}
