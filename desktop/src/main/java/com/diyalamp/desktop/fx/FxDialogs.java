package com.diyalamp.desktop.fx;

import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

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
    configureSurface(dialog, pane);
    if (dialog instanceof TextInputDialog input) arrangeInput(input);
    applyOwnerTheme(pane, owner == null || owner.getScene() == null ? null : owner.getScene().getRoot());
    if (owner != null) {
      class OwnerTheme {
        Parent source;
        final MapChangeListener<Object, Object> listener = change -> {
          if (FxTheme.themeChanged(change.getKey())) applyOwnerTheme(pane, source);
        };
        void unbind() { if (source != null) source.getProperties().removeListener(listener); source = null; }
        void bind() {
          unbind();
          if (owner.getScene() == null) return;
          source = owner.getScene().getRoot();
          applyOwnerTheme(pane, source);
          source.getProperties().addListener(listener);
        }
      }
      OwnerTheme link = new OwnerTheme();
      dialog.showingProperty().addListener((observable, previous, showing) -> { if (showing) link.bind(); else link.unbind(); });
      if (dialog.isShowing()) link.bind();
    }
    decorateButtons(pane);
    pane.getButtonTypes().addListener((ListChangeListener<ButtonType>) change -> decorateButtons(pane));
  }

  private static void applyOwnerTheme(DialogPane pane, Parent source) {
    FxTheme.inherit(pane, source);
    if (!Platform.isSupported(ConditionalFeature.TRANSPARENT_WINDOW)) pane.getScene().setFill(FxTheme.surfaceOf(source));
  }

  private static void configureSurface(Dialog<?> dialog, DialogPane pane) {
    boolean transparent = Platform.isSupported(ConditionalFeature.TRANSPARENT_WINDOW);
    dialog.initStyle(transparent ? StageStyle.TRANSPARENT : StageStyle.UNDECORATED);
    if (transparent) pane.getScene().setFill(Color.TRANSPARENT);
    if (pane.getHeader() == null && (dialog.getHeaderText() == null || dialog.getHeaderText().isBlank()))
      dialog.setHeaderText(dialog.getTitle());

    if (!transparent) return;
    FadeTransition entrance = new FadeTransition(Duration.millis(180), pane);
    entrance.setFromValue(0);
    entrance.setToValue(1);
    entrance.setInterpolator(Interpolator.EASE_OUT);
    dialog.addEventHandler(DialogEvent.DIALOG_SHOWING, event ->
        pane.setOpacity(FxMotion.reduced() ? 1 : 0));
    dialog.showingProperty().addListener((observable, previous, showing) -> {
      if (showing) {
        if (pane.getOpacity() == 0) entrance.playFromStart();
      } else {
        entrance.stop();
        pane.setOpacity(1);
      }
    });
  }

  private static void arrangeInput(TextInputDialog input) {
    // Keep TextInputDialog's field, result converter and default action.
    DialogPane pane = input.getDialogPane();
    Runnable arrange = () -> {
      if (!(pane.getContent() instanceof GridPane grid)) return;
      grid.setVgap(8);
      GridPane.setColumnIndex(input.getEditor(), 0);
      GridPane.setRowIndex(input.getEditor(), 1);
      for (var child : grid.getChildren())
        if (child instanceof Label label) label.setLabelFor(input.getEditor());
    };
    arrange.run();
    pane.contentTextProperty().addListener(observable -> arrange.run());
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
