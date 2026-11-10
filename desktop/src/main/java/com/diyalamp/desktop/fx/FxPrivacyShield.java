package com.diyalamp.desktop.fx;

import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

/** Replaces sensitive scene content immediately while retaining its editing state. */
final class FxPrivacyShield {
  static void install(BorderPane root, Node workspace, Node footer, ObservableBooleanValue enabled) {
    Label title = new Label("Privacy Mode is on");
    title.getStyleClass().add("workspace-empty-title");
    Label description = new Label("Notes and workspace details are hidden. Use the Privacy Mode control to return to your workspace.");
    description.getStyleClass().add("empty-detail");
    description.setWrapText(true);
    description.setTextAlignment(TextAlignment.CENTER);
    description.setMaxWidth(340);
    VBox shield = new VBox(8, title, description);
    shield.getStyleClass().addAll("workspace-empty", "privacy-shield");
    shield.setAlignment(Pos.CENTER);
    shield.setAccessibleRole(AccessibleRole.PARENT);
    shield.setAccessibleText(title.getText() + ". " + description.getText());

    enabled.addListener((observable, previous, privateMode) -> display(root, workspace, footer, shield, privateMode));
    display(root, workspace, footer, shield, enabled.get());
  }

  private static void display(BorderPane root, Node workspace, Node footer, Node shield, boolean enabled) {
    // Removing the content also removes its controls from focus and accessibility traversal.
    root.setCenter(enabled ? shield : workspace);
    root.setBottom(enabled ? null : footer);
    if (enabled) shield.requestFocus();
  }

  private FxPrivacyShield() {}
}
