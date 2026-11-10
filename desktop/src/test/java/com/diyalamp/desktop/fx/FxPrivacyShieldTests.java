package com.diyalamp.desktop.fx;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Ensures the protected view has no sensitive descendants and can restore the same editor. */
public final class FxPrivacyShieldTests {
  public static void main(String[] arguments) throws Exception {
    FutureTask<Void> test = new FutureTask<>(() -> {
      FxWritingPane writing = new FxWritingPane("Private draft\n");
      var editor = writing.area();
      editor.setId("private-editor");
      VBox workspace = new VBox(new Label("Private path"), writing.node());
      Label footer = new Label("Private note metrics");
      footer.setId("private-footer");
      Button privacy = new Button("Privacy Mode");
      BorderPane root = new BorderPane(workspace, privacy, null, footer, null);
      SimpleBooleanProperty enabled = new SimpleBooleanProperty(false);
      privacy.setOnAction(event -> enabled.set(!enabled.get()));
      FxPrivacyShield.install(root, workspace, footer, enabled);
      Stage stage = new Stage();
      Scene scene = new Scene(root, 820, 560);
      stage.setScene(scene);
      stage.show();
      try {
        editor.requestFocus();
        check(scene.getFocusOwner() == editor, "The draft begins with keyboard focus");
        editor.selectRange(2, 7);
        privacy.requestFocus();
        check(scene.getFocusOwner() == privacy, "Activating the toolbar control can move focus before it is pressed");

        privacy.fire();
        var shield = root.getCenter();
        check(shield.getStyleClass().contains("privacy-shield") && root.getBottom() == null
                && root.getTop() != null, "Privacy mode replaces the center and removes the footer, leaving the toolbar");
        check(scene.lookup("#private-editor") == null && scene.lookup("#private-footer") == null
                && editor.getScene() == null && footer.getScene() == null,
            "Sensitive controls detach from the scene immediately");
        var accessibleChildren = root.queryAccessibleAttribute(AccessibleAttribute.CHILDREN);
        check(accessibleChildren instanceof List<?> children && children.contains(shield)
                && !children.contains(workspace) && !children.contains(footer),
            "The root accessibility tree contains the shield and omits the workspace and footer");
        check(scene.getFocusOwner() == shield && shield.queryAccessibleAttribute(AccessibleAttribute.TEXT)
                .toString().contains("Privacy Mode is on"),
            "Focus moves to a placeholder that announces privacy mode");
        editor.requestFocus();
        check(scene.getFocusOwner() == shield, "A detached editor cannot reclaim keyboard focus");

        privacy.requestFocus();
        privacy.fire();
        check(root.getCenter() == workspace && root.getBottom() == footer && editor.getScene() == scene
                && editor.getText().equals("Private draft") && shield.getScene() == null,
            "Turning privacy mode off restores the same workspace and editing state");
        check(scene.getFocusOwner() == editor && editor.getAnchor() == 2 && editor.getCaretPosition() == 7,
            "Leaving Privacy Mode returns keyboard focus and the selection to the draft");
      } finally {
        stage.hide();
        writing.dispose();
      }
      return null;
    });
    Platform.startup(test);
    try { test.get(10, TimeUnit.SECONDS); } finally { Platform.exit(); }
    System.out.println("Passed Privacy Mode scene and focus checks.");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
