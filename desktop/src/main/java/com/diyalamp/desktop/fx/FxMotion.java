package com.diyalamp.desktop.fx;

import javafx.animation.Interpolator;
import javafx.application.Platform;
import javafx.util.Duration;

/** Workbench motion follows the platform setting, with an override for controlled launches. */
final class FxMotion {
  static final Duration SIDEBAR_TIME = Duration.millis(180);
  static final Interpolator SIDEBAR_EASE = Interpolator.ofSpline(.22, .61, .36, 1);

  static boolean reduced() {
    return Boolean.getBoolean("diyalamp.reduceMotion") || Platform.getPreferences().isReducedMotion();
  }

  private FxMotion() {}
}
