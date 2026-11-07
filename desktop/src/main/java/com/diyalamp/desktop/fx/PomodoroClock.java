package com.diyalamp.desktop.fx;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/** A foreground focus timer, independent of wall-clock changes and UI refresh frequency. */
final class PomodoroClock {
  static final int MIN_MINUTES = 1, MAX_MINUTES = 180;
  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  enum Phase {
    FOCUS("Focus"), BREAK("Break");
    final String label;
    Phase(String label) { this.label = label; }
    Phase next() { return this == FOCUS ? BREAK : FOCUS; }
  }

  private final LongSupplier nanos;
  private int focusMinutes = 25, breakMinutes = 5;
  private Phase phase = Phase.FOCUS;
  private long remaining = duration(phase), started;
  private boolean running, paused, repeating;
  private Phase completed;

  PomodoroClock(LongSupplier nanos) { this.nanos = Objects.requireNonNull(nanos); }
  Phase phase() { return phase; }
  boolean running() { return running; }
  boolean paused() { return paused; }
  boolean repeating() { return repeating; }
  int focusMinutes() { return focusMinutes; }
  int breakMinutes() { return breakMinutes; }

  void setDurations(int focusMinutes, int breakMinutes) {
    if (focusMinutes < MIN_MINUTES || focusMinutes > MAX_MINUTES
        || breakMinutes < MIN_MINUTES || breakMinutes > MAX_MINUTES)
      throw new IllegalArgumentException("Pomodoro durations must be 1–180 minutes.");
    tick();
    this.focusMinutes = focusMinutes;
    this.breakMinutes = breakMinutes;
    if (!running && !paused) remaining = duration(phase);
  }

  void setRepeating(boolean repeating) {
    tick();
    this.repeating = repeating;
  }

  long seconds() {
    long value = running ? Math.max(0, remaining - elapsed(nanos.getAsLong())) : remaining;
    return (value + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND;
  }

  void start() {
    if (running) return;
    started = nanos.getAsLong(); running = true; paused = false;
  }

  void pause() {
    long now = nanos.getAsLong();
    advance(now);
    if (!running) return;
    remaining -= elapsed(now);
    running = false; paused = true;
  }

  void reset() { running = false; paused = false; remaining = duration(phase); completed = null; }
  void select(Phase selected) { phase = Objects.requireNonNull(selected); reset(); }

  void tick() { if (running) advance(nanos.getAsLong()); }

  private void advance(long now) {
    if (!running) return;
    long overdue = elapsed(now) - remaining;
    if (overdue < 0) return;
    if (!repeating && (phase == Phase.BREAK || overdue >= duration(Phase.BREAK))) {
      completed = Phase.BREAK;
      phase = Phase.FOCUS;
      running = false;
      remaining = duration(phase);
      return;
    }

    completed = phase;
    phase = phase.next();
    long phaseDuration = duration(phase);
    if (repeating) {
      // Starting at a phase boundary, whole cycles return to the same phase and
      // last completion. Account for at most one extra boundary in the remainder.
      overdue %= duration(Phase.FOCUS) + duration(Phase.BREAK);
      if (overdue >= phaseDuration) {
        overdue -= phaseDuration;
        completed = phase;
        phase = phase.next();
        phaseDuration = duration(phase);
      }
    }
    remaining = phaseDuration - overdue;
    started = now;
  }

  private long elapsed(long now) { return Math.max(0, now - started); }

  private long duration(Phase phase) {
    return (phase == Phase.FOCUS ? focusMinutes : breakMinutes) * 60L * NANOS_PER_SECOND;
  }

  /** Returns the latest elapsed phase once, including after a delayed refresh. */
  Optional<Phase> completion() {
    Phase result = completed; completed = null; return Optional.ofNullable(result);
  }
}
