package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Tab;
import atlantafx.base.controls.TabLine;
import atlantafx.base.controls.TabSkin;
import atlantafx.base.theme.Styles;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.css.TransitionEvent;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

/** Native JavaFX CSS transitions change paint, without changing control geometry. */
public final class FxHoverThemeTests {
  private static int checks;
  private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    if (!"Headless".equals(System.getProperty("glass.platform"))) {
      throw new IllegalStateException("Hover checks require the headless JavaFX pointer");
    }
    CountDownLatch done = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      Stage stage = new Stage();
      try {
        FxTheme.setup();
        Button tool = FxTheme.iconButton("folder-open", "Fictional action", () -> {});
        Label metric = new Label("12 words"); metric.getStyleClass().add("note-metric");
        TabLine tabs = new TabLine(); tabs.getStyleClass().addAll(Styles.DENSE, Styles.TABS_FLOATING, "document-tabs");
        tabs.setAnimated(false); tabs.setTabClosingPolicy(Tab.ClosingPolicy.ALL_TABS);
        Tab first = new Tab("first", "First.md"), second = new Tab("second", "Second.md");
        tabs.getTabs().addAll(first, second); tabs.getSelectionModel().select(first);
        HBox root = new HBox(8, tool, metric, tabs); root.setFillHeight(false);
        root.getStyleClass().add("workspace-root");
        root.getStylesheets().add(FxTheme.class.getResource("/fx-workspace.css").toExternalForm());
        FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
        Scene scene = new Scene(root, 400, 200);
        stage.setScene(scene); stage.show(); root.applyCss(); root.layout();
        Bounds originalBounds = tool.getBoundsInParent();
        TabSkin inactive = tabs.lookupAll(".tab").stream().filter(TabSkin.class::isInstance).map(TabSkin.class::cast)
            .filter(header -> header.getTab() == second).findFirst().orElseThrow();
        Region close = (Region) inactive.lookup(".close-button");
        Bounds originalTabBounds = inactive.getBoundsInParent(), originalCloseBounds = close.getBoundsInParent();
        AtomicInteger starts = new AtomicInteger();
        tool.addEventHandler(TransitionEvent.START, event -> starts.incrementAndGet());
        Robot pointer = new Robot();
        pointer.mouseMove(root.localToScreen(root.getWidth() / 2, root.getHeight() / 2));
        Color target = Color.web("#141312"), outline = Color.web("#383533");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        scene.addPostLayoutPulseListener(new Runnable() {
          private boolean hoverStarted;
          private boolean checkingReducedMotion;
          private boolean checkingTabHover;
          private boolean geometryUnchanged = true;
          private int startsBeforeReducedMotion;

          @Override public void run() {
            boolean complete = false;
            try {
              if (System.nanoTime() >= deadline) {
                throw new AssertionError("Hover did not settle. Toolbar hovered=" + tool.isHover()
                    + " paint=" + paint(tool) + " starts=" + starts.get() + ", metric hovered=" + metric.isHover()
                    + ", tab hovered=" + inactive.isHover() + ", close hovered=" + close.isHover());
              }
              if (!hoverStarted) {
                // The headless Robot uses JavaFX picking with one pointer and one hovered sibling.
                moveToCenter(pointer, tool);
                hoverStarted = true;
              } else if (!checkingReducedMotion) {
                geometryUnchanged &= originalBounds.equals(tool.getBoundsInParent());
                if (tool.isHover() && target.equals(paint(tool)) && starts.get() > 0
                    && outline.equals(tool.getBackground().getFills().getFirst().getFill())) {
                  check(starts.get() > 0, "Hover must start actual native CSS transitions");
                  check(target.equals(paint(tool)) && tool.getBackground().getFills().size() == 2,
                      "Tool hover must use an outline over the shared workbench surface");
                  check(geometryUnchanged, "Hover must never lift, scale, or resize the workbench controls");
                  startsBeforeReducedMotion = starts.get();
                  System.setProperty("diyalamp.reduceMotion", "true");
                  FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
                  root.applyCss();
                  moveToCenter(pointer, metric);
                  checkingReducedMotion = true;
                }
              } else if (!checkingTabHover && metric.isHover() && !tool.isHover()) {
                check(target.equals(paint(metric)), "Metric hover immediately reaches the subdued surface: " + paint(metric));
                check(paint(tool).getOpacity() == 0, "Reduced motion applies the final toolbar paint immediately");
                check(startsBeforeReducedMotion == starts.get(), "Reduced motion must not start new CSS transitions");
                moveToCenter(pointer, close);
                checkingTabHover = true;
              } else if (checkingTabHover && inactive.isHover() && close.isHover()) {
                check(paint(inactive).equals(Color.web("#FFFFFF0F"))
                    && inactive.getBackground().getFills().stream().allMatch(fill ->
                        fill.getInsets().equals(Insets.EMPTY) && fill.getRadii().equals(CornerRadii.EMPTY)),
                    "The hovered tab must have a flat, full-width tint: " + paint(inactive) + " / "
                        + inactive.getBackground().getFills().stream().map(fill -> fill.getInsets() + " " + fill.getRadii()).toList());
                check(paint(close).equals(Color.web("#FFFFFF0F"))
                        && close.getBackground().getFills().size() == 1
                        && close.getBackground().getFills().getFirst().getInsets().equals(new Insets(2))
                        && close.getBorder() == null && paint((Region) close.lookup(".graphic")).equals(Color.WHITE),
                    "Hovering close adds one quiet inset fill and brightens its glyph");
                check(originalTabBounds.equals(inactive.getBoundsInParent()) && originalCloseBounds.equals(close.getBoundsInParent()),
                    "Tab hover must keep both hit areas in place");
                Region active = (Region) tabs.lookup(".tab:selected");
                check(Color.web("#FF6B6B").equals(active.getBorder().getStrokes().getFirst().getTopStroke()),
                    "Hovering a different tab must leave the selected tab's accent in place");
                check(close.getAccessibleRole() == AccessibleRole.BUTTON, "The close action remains an accessible button");
                close.executeAccessibleAction(AccessibleAction.FIRE);
                check(tabs.getTabs().size() == 1 && tabs.getTabs().getFirst() == first,
                    "The accessible close action must close only its own tab");
                complete = true;
              }
            } catch (Throwable error) {
              failure.set(error);
              complete = true;
            } finally {
              if (complete) {
                scene.removePostLayoutPulseListener(this);
                System.clearProperty("diyalamp.reduceMotion"); stage.close(); done.countDown();
              } else {
                Platform.requestNextPulse();
              }
            }
          }
        });
        Platform.requestNextPulse();
      } catch (Throwable error) { failure.set(error); stage.close(); done.countDown(); }
    });
    if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("Hover transition checks timed out");
    Platform.exit(); if (failure.get() != null) throw new AssertionError("Hover regression", failure.get());
    System.out.println("Passed " + checks + " native CSS hover checks.");
  }

  private static void moveToCenter(Robot pointer, Region node) {
    pointer.mouseMove(node.localToScreen(node.getWidth() / 2, node.getHeight() / 2));
  }

  private static Color paint(Region node) {
    return (Color) node.getBackground().getFills().getLast().getFill();
  }
}
