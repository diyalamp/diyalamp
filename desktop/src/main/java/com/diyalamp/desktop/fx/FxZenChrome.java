package com.diyalamp.desktop.fx;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.scene.Node;
import javafx.scene.effect.ColorAdjust;

/** Dims workspace chrome with the editor's focus transition, without changing its geometry. */
final class FxZenChrome {
  private static final double DIM = 0.36;

  static void apply(ReadOnlyDoubleProperty progress, Node... regions) {
    for (Node region : regions) {
      ColorAdjust dim = new ColorAdjust();
      progress.addListener((observable, before, value) -> paint(region, dim, value.doubleValue()));
      paint(region, dim, progress.get());
    }
  }

  private static void paint(Node region, ColorAdjust dim, double progress) {
    double brightness = -intensity(progress);
    if (brightness == 0) {
      if (region.getEffect() == dim) region.setEffect(dim.getInput());
      dim.setInput(null);
      return;
    }
    dim.setBrightness(brightness);
    if (region.getEffect() != dim) {
      dim.setInput(region.getEffect());
      region.setEffect(dim);
    }
  }

  static double intensity(double progress) {
    return Double.isFinite(progress) ? DIM * Math.clamp(progress, 0, 1) : 0;
  }

  private FxZenChrome() {}
}
