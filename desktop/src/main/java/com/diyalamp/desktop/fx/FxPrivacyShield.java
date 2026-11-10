package com.diyalamp.desktop.fx;

import java.lang.ref.WeakReference;
import javafx.beans.value.ChangeListener;
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
    new FxPrivacyShield(root, workspace, footer, enabled);
  }

  private WeakReference<Node> lastWorkspaceFocus = new WeakReference<>(null);

  private FxPrivacyShield(BorderPane root, Node workspace, Node footer, ObservableBooleanValue enabled) {
    Label title = new Label("Privacy Mode is on");
    title.getStyleClass().add("workspace-empty-title");
    Label description = new Label("Notes, folders, and tabs are hidden. Use the toolbar shield to return.");
    description.getStyleClass().add("empty-detail");
    description.setWrapText(true);
    description.setTextAlignment(TextAlignment.CENTER);
    description.setMaxWidth(340);
    VBox shield = new VBox(8, FxTheme.icon("shield", 24), title, description);
    shield.getStyleClass().addAll("workspace-empty", "privacy-shield");
    shield.setAlignment(Pos.CENTER);
    shield.setAccessibleRole(AccessibleRole.PARENT);
    shield.setAccessibleText(title.getText() + ". " + description.getText());

    // Opening Privacy Mode from the toolbar may already have moved focus out of the editor.
    ChangeListener<Node> trackFocus = (observable, previous, focused) -> {
      if (within(focused, workspace)) lastWorkspaceFocus = new WeakReference<>(focused);
    };
    root.sceneProperty().addListener((observable, previous, scene) -> {
      if (previous != null) previous.focusOwnerProperty().removeListener(trackFocus);
      if (scene != null) {
        scene.focusOwnerProperty().addListener(trackFocus);
        trackFocus.changed(scene.focusOwnerProperty(), null, scene.getFocusOwner());
      }
    });
    if (root.getScene() != null) {
      var focusOwner = root.getScene().focusOwnerProperty();
      focusOwner.addListener(trackFocus);
      trackFocus.changed(focusOwner, null, focusOwner.get());
    }

    enabled.addListener((observable, previous, privateMode) -> display(root, workspace, footer, shield, privateMode));
    display(root, workspace, footer, shield, enabled.get());
  }

  private void display(BorderPane root, Node workspace, Node footer, Node shield, boolean enabled) {
    Node restore = lastWorkspaceFocus.get();
    // Removing the content also removes its controls from focus and accessibility traversal.
    root.setCenter(enabled ? shield : workspace);
    root.setBottom(enabled ? null : footer);
    if (enabled) shield.requestFocus();
    else if (within(restore, workspace)) restore.requestFocus();
  }

  private static boolean within(Node node, Node ancestor) {
    for (Node current = node; current != null; current = current.getParent())
      if (current == ancestor) return true;
    return false;
  }
}
