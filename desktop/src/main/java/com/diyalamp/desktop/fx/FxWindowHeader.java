package com.diyalamp.desktop.fx;

import javafx.application.ColorScheme;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.MenuBar;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/** Places the workspace toolbar beside the system window buttons on macOS. */
final class FxWindowHeader {
  private FxWindowHeader() {}

  static Region create(Stage stage, MenuBar menu, Region toolbar, Node dragHandle) {
    if (!System.getProperty("os.name", "").startsWith("Mac")
        || !Platform.isSupported(ConditionalFeature.EXTENDED_WINDOW)) {
      return toolbar;
    }

    stage.initStyle(StageStyle.EXTENDED);
    HeaderBar.setSystemColorScheme(stage, ColorScheme.DARK);
    // Use macOS's large native toolbar style to center its buttons in the workspace row.
    HeaderBar.setSystemButtonHeight(stage, 52);
    // Keep the system menu registered without the empty JavaFX menu strip above the toolbar.
    menu.setMinHeight(0);
    menu.setPrefHeight(0);
    menu.setMaxHeight(0);

    HeaderBar header = new HeaderBar(null, toolbar, null);
    header.getStyleClass().add("workspace-window-header");
    header.getStylesheets().add(FxWindowHeader.class.getResource("/fx-window-header.css").toExternalForm());
    HeaderBar.setAlignment(toolbar, Pos.CENTER_LEFT);
    HeaderBar.setDragType(dragHandle, HeaderDragType.DRAGGABLE);
    return header;
  }
}
