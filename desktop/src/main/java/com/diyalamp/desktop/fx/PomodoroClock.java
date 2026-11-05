package com.diyalamp.desktop.fx;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/** A foreground focus timer, independent of wall-clock changes and UI refresh frequency. */
final class PomodoroClock {
  enum Phase {
    FOCUS("Focus", 25), BREAK("Break", 5);
    final String label;
    final int minutes;
    Phase(String label, int minutes) { this.label = label; this.minutes = minutes; }
    long duration() { return minutes * 60L * 1_000_000_000L; }
  }

  private final LongSupplier nanos;
  private Phase phase = Phase.FOCUS;
  private long remaining = phase.duration(), started;
  private boolean running;
  private Phase completed;

  PomodoroClock(LongSupplier nanos) { this.nanos = Objects.requireNonNull(nanos); }
  Phase phase() { return phase; }
  boolean running() { return running; }

  long seconds() {
    long value = running ? Math.max(0, remaining - Math.max(0, nanos.getAsLong() - started)) : remaining;
    return (value + 999_999_999L) / 1_000_000_000L;
  }

  void start() {
    if (running) return;
    started = nanos.getAsLong(); running = true;
  }

  void pause() {
    tick();
    if (!running) return;
    remaining = Math.max(0, remaining - Math.max(0, nanos.getAsLong() - started));
    running = false;
  }

  void reset() { running = false; remaining = phase.duration(); completed = null; }
  void select(Phase selected) { phase = Objects.requireNonNull(selected); reset(); }

  void tick() {
    if (!running || seconds() > 0) return;
    completed = phase;
    phase = phase == Phase.FOCUS ? Phase.BREAK : Phase.FOCUS;
    running = false; remaining = phase.duration();
  }

  Optional<Phase> completion() {
    Phase result = completed; completed = null; return Optional.ofNullable(result);
  }
}
