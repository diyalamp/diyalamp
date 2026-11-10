package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Popover;
import java.util.List;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.collections.MapChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

/** Shared styling and motion for AtlantaFX's anchored popover component. */
public final class FxPopovers {
  private static final String MOTION = "diyalamp.popover.motion";
  private static final double GAP = 8;

  public static void style(Popover popup, Window owner) {
    if (popup.getProperties().containsKey(MOTION)) return;
    Motion motion = new Motion(popup);
    popup.getProperties().put(MOTION, motion);
    popup.setDetachable(false); popup.setHeaderAlwaysVisible(false); popup.setCloseButtonEnabled(false);
    popup.setCornerRadius(12); popup.setArrowSize(0); popup.setArrowIndent(6);
    popup.setArrowLocation(Popover.ArrowLocation.TOP_RIGHT);
    // Hold the layout anchor steady while the rendered skin moves within the popup.
    popup.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_TOP_LEFT);
    // AtlantaFX hides its skin before its fade finishes. Own the visible motion while
    // keeping its anchoring and native keyboard and outside-click dismissal.
    popup.setAnimated(false);
    popup.setFadeInDuration(Duration.ZERO);
    popup.setAutoHide(true); popup.setHideOnEscape(true); popup.setConsumeAutoHidingEvents(false);
    Parent root = popup.getRoot();
    // The skin copies the control's style classes onto its root when first shown.
    popup.getStyleClass().addAll("workspace-root", "diyalamp-popover");
    root.getStylesheets().add(FxPopovers.class.getResource("/fx-workspace.css").toExternalForm());
    root.getStylesheets().add(FxPopovers.class.getResource("/fx-popovers.css").toExternalForm());
    Parent[] source = {null};
    MapChangeListener<Object, Object> theme = change -> {
      if (source[0] != null && FxTheme.themeChanged(change.getKey())) {
        FxTheme.inherit(root, source[0]);
        if (FxMotion.reduced()) motion.finish();
      }
    };
    Runnable detach = () -> {
      if (source[0] != null) source[0].getProperties().removeListener(theme);
      source[0] = null;
    };
    popup.addEventHandler(WindowEvent.WINDOW_SHOWING, event -> {
      detach.run();
      if (owner != null && owner.getScene() != null) {
        source[0] = owner.getScene().getRoot();
        source[0].getProperties().addListener(theme);
      }
      FxTheme.inherit(root, source[0]);
    });
    popup.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> root.setVisible(true));
    popup.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> {
      motion.reset();
      detach.run();
    });
  }

  static void toggle(Popover popup, Node anchor) {
    Motion motion = (Motion) popup.getProperties().get(MOTION);
    boolean animate = !FxMotion.reduced();
    if (!popup.isShowing()) {
      Window owner = anchor.getScene().getWindow();
      for (Window window : List.copyOf(Window.getWindows())) {
        if (window instanceof Popover other && other != popup && other.getOwnerWindow() == owner
            && other.getProperties().containsKey(MOTION)) other.hide(Duration.ZERO);
      }
      if (animate) popup.getRoot().setOpacity(0);
      popup.show(anchor, -GAP);
      // Apply the slide after AtlantaFX has calculated the anchored window position.
      if (animate) {
        popup.getRoot().setTranslateY(-GAP);
        motion.play(true);
      }
    } else if (animate) {
      motion.play(motion.closing);
    } else {
      popup.hide(Duration.ZERO);
    }
  }

  private static final class Motion {
    private final Popover popup;
    private Timeline animation;
    private boolean closing;

    Motion(Popover popup) { this.popup = popup; }

    void play(boolean opening) {
      Node root = popup.getRoot();
      if (animation != null) animation.stop();
      closing = !opening;
      animation = new Timeline(new KeyFrame(Duration.millis(opening ? 180 : 120),
          new KeyValue(root.opacityProperty(), opening ? 1 : 0, FxMotion.SIDEBAR_EASE),
          new KeyValue(root.translateYProperty(), opening ? 0 : -GAP, FxMotion.SIDEBAR_EASE)));
      animation.setOnFinished(event -> {
        animation = null;
        if (closing) popup.hide(Duration.ZERO);
      });
      animation.play();
    }

    void reset() {
      if (animation != null) animation.stop();
      animation = null;
      closing = false;
      popup.getRoot().setOpacity(1);
      popup.getRoot().setTranslateY(0);
    }

    void finish() {
      if (animation == null) return;
      if (closing) popup.hide(Duration.ZERO);
      else reset();
    }
  }

  private FxPopovers() { }
}
