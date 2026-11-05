package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Popover;
import java.util.function.Supplier;
import javafx.animation.PauseTransition;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.collections.MapChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

/** Shared styling and lifecycle for AtlantaFX's animated, anchored popover component. */
public final class FxPopovers {
  /** Choose the opening keyboard target once the maintained popover's entrance animation is ready. */
  public static void focusOnShow(Popover popup, Supplier<? extends Node> preferred) {
    PauseTransition pending = new PauseTransition();
    pending.setOnFinished(event -> {
      if (!popup.isShowing() || !popup.isFocused()) return;
      Node target = preferred.get();
      if (target != null && !target.isDisabled() && target.isVisible()
          && popup.getScene().getFocusOwner() != target) target.requestFocus();
    });
    // Popover.show installs its own onShown handler; preserve it and the component's fade behavior.
    popup.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> {
      if (!popup.isFocused()) popup.requestFocus();
      pending.setDuration(popup.isAnimated() ? popup.getFadeInDuration() : Duration.ZERO);
      pending.playFromStart();
    });
    // Opening focus never overrides an interaction the user has already made inside the bubble.
    popup.getRoot().addEventFilter(KeyEvent.KEY_PRESSED, event -> pending.stop());
    popup.getRoot().addEventFilter(MouseEvent.MOUSE_PRESSED, event -> pending.stop());
    popup.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> pending.stop());
  }

  public static void style(Popover popup, Window owner) {
    if (popup.getProperties().putIfAbsent("diyalamp.popover.styled", true) != null) return;
    popup.setDetachable(false); popup.setHeaderAlwaysVisible(false); popup.setCloseButtonEnabled(false);
    popup.setCornerRadius(12); popup.setArrowSize(8);
    popup.setArrowLocation(Popover.ArrowLocation.TOP_RIGHT);
    popup.setFadeInDuration(Duration.millis(220)); popup.setFadeOutDuration(Duration.millis(160));
    popup.setAnimated(!Boolean.getBoolean("diyalamp.reduceMotion"));
    popup.setAutoHide(true); popup.setHideOnEscape(true); popup.setConsumeAutoHidingEvents(false);
    Parent root = popup.getRoot();
    root.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
      if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && popup.isHideOnEscape()) {
        popup.hide(); event.consume();
      }
    });
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
