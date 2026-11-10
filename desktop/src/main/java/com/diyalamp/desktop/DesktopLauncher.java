package com.diyalamp.desktop;

import com.diyalamp.desktop.fx.FxDiyalamp;
import javafx.application.Application;

/** Separate launcher lets the packaged JavaFX runtime start through the normal Java entry point. */
public final class DesktopLauncher {
  public static final String VERSION = "1.4.0";

  public static void main(String[] args) {
    System.setProperty("apple.awt.application.name", "diyalamp");
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
    Application.launch(FxDiyalamp.class, args);
  }

  private DesktopLauncher() {}
}
