package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Popover;
import javafx.collections.MapChangeListener;
import javafx.scene.Parent;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

/** Shared styling and lifecycle for AtlantaFX's animated, anchored popover component. */
public final class FxPopovers {
  public static void style(Popover popup, Window owner) {
    if (popup.getProperties().putIfAbsent("diyalamp.popover.styled", true) != null) return;
    popup.setDetachable(false); popup.setHeaderAlwaysVisible(false); popup.setCloseButtonEnabled(false);
    popup.setCornerRadius(12); popup.setArrowSize(8);
    popup.setArrowLocation(Popover.ArrowLocation.TOP_RIGHT);
    popup.setFadeInDuration(Duration.millis(220)); popup.setFadeOutDuration(Duration.millis(160));
    popup.setAnimated(!Boolean.getBoolean("diyalamp.reduceMotion"));
    popup.setAutoHide(true); popup.setHideOnEscape(true); popup.setConsumeAutoHidingEvents(false);
    Parent root = popup.getRoot();
    root.getStyleClass().addAll("workspace-root", "diyalamp-popover");
    popup.getRoot().getStylesheets().add(FxPopovers.class.getResource("/fx-workspace.css").toExternalForm());
    popup.getRoot().getStylesheets().add(FxPopovers.class.getResource("/fx-popovers.css").toExternalForm());
    Parent[] source = {null};
    MapChangeListener<Object, Object> accent = change -> {
      if (source[0] != null && FxTheme.DISPLAYED_ACCENT.equals(change.getKey()))
        FxTheme.accent(root, FxTheme.accentOf(source[0]));
    };
    Runnable detach = () -> {
      if (source[0] != null) source[0].getProperties().removeListener(accent);
      source[0] = null;
    };
    popup.addEventHandler(WindowEvent.WINDOW_SHOWING, event -> {
      detach.run();
      if (owner != null && owner.getScene() != null) {
        source[0] = owner.getScene().getRoot();
        source[0].getProperties().addListener(accent);
        FxTheme.accent(root, FxTheme.accentOf(source[0]));
      } else FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    });
    popup.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> detach.run());
  }

  private FxPopovers() { }
}
