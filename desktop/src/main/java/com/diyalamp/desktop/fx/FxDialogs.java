package com.diyalamp.desktop.fx;

import atlantafx.base.theme.Styles;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

/** Consistent AtlantaFX styling for standard dialogs, preserving native dialog behavior. */
public final class FxDialogs {
  private static final String STYLED = "diyalamp.dialog.styled";

  /** Call after supplying content and actions, before the dialog is first shown. */
  public static void style(Dialog<?> dialog, Window owner) {
    if (owner != null && dialog.getOwner() == null) dialog.initOwner(owner);
    DialogPane pane = dialog.getDialogPane();
    if (pane.getProperties().putIfAbsent(STYLED, Boolean.TRUE) != null) return;
    pane.getStyleClass().addAll("workspace-root", "diyalamp-dialog");
    pane.getStylesheets().add(FxDialogs.class.getResource("/fx-workspace.css").toExternalForm());
    pane.getStylesheets().add(FxDialogs.class.getResource("/fx-dialogs.css").toExternalForm());
    pane.setGraphic(null);
    FxTheme.accent(pane, owner == null || owner.getScene() == null
        ? FxTheme.DEFAULT_ACCENT : FxTheme.accentOf(owner.getScene().getRoot()));
    if (owner != null) {
      class OwnerAccent {
        Parent source;
        final MapChangeListener<Object, Object> listener = change -> {
          if (FxTheme.DISPLAYED_ACCENT.equals(change.getKey())) FxTheme.accent(pane, FxTheme.accentOf(source));
        };
        void unbind() { if (source != null) source.getProperties().removeListener(listener); source = null; }
        void bind() {
          unbind();
          if (owner.getScene() == null) return;
          source = owner.getScene().getRoot();
          FxTheme.accent(pane, FxTheme.accentOf(source));
          source.getProperties().addListener(listener);
        }
      }
      OwnerAccent link = new OwnerAccent();
      dialog.showingProperty().addListener((observable, previous, showing) -> { if (showing) link.bind(); else link.unbind(); });
      if (dialog.isShowing()) link.bind();
    }
    decorateButtons(pane);
    pane.getButtonTypes().addListener((ListChangeListener<ButtonType>) change -> decorateButtons(pane));
  }

  private static void decorateButtons(DialogPane pane) {
    for (ButtonType type : pane.getButtonTypes()) {
      if (!(pane.lookupButton(type) instanceof Button button)) continue;
      if (!button.getStyleClass().contains("dialog-action")) button.getStyleClass().add("dialog-action");
      ButtonBar.ButtonData action = type.getButtonData();
      if ((action == ButtonBar.ButtonData.OK_DONE || action == ButtonBar.ButtonData.APPLY
          || action == ButtonBar.ButtonData.YES || action == ButtonBar.ButtonData.FINISH
          || action == ButtonBar.ButtonData.NEXT_FORWARD) && !button.getStyleClass().contains(Styles.ACCENT))
        button.getStyleClass().add(Styles.ACCENT);
    }
  }

  private FxDialogs() { }
}
