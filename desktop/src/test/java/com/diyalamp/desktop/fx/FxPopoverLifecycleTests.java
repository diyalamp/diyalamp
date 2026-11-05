package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Popover;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Real PopupWindow lifecycle, including colors whose rendered CSS hex is unchanged. */
public final class FxPopoverLifecycleTests {
  private static int checks;
  private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    CountDownLatch done = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      try { FxTheme.setup(); lifecycle(); }
      catch (Throwable error) { failure.set(error); }
      finally { done.countDown(); }
    });
    if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("Popover lifecycle tests timed out");
    Platform.exit(); if (failure.get() != null) throw new AssertionError("Popover lifecycle regression", failure.get());
    System.out.println("Passed " + checks + " JavaFX popover lifecycle checks.");
  }
  private static void lifecycle() {
    Button anchor = new Button("Fictional preview"); BorderPane root = new BorderPane(anchor);
    FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    Stage owner = new Stage(); owner.setScene(new Scene(root, 500, 300)); owner.show(); root.applyCss(); root.layout();
    Popover popup = new Popover(new Label("Fictional content")); FxPopovers.style(popup, owner); popup.setAnimated(false);
    popup.show(anchor);
    check(popup.isShowing() && FxTheme.accentOf(popup.getRoot()).equals(FxTheme.DEFAULT_ACCENT), "A shown popover must inherit its owner's current accent");
    Color first = Color.color(.55, .65, .75), second = Color.color(.5500002, .65, .75);
    FxTheme.accent(root, first); String style = root.getStyle();
    check(!first.equals(second), "The regression fixture must contain distinct actual colors");
    FxTheme.accent(root, second);
    check(style.equals(root.getStyle()), "The fixture must reproduce an unchanged rounded CSS hex string");
    check(FxTheme.accentOf(popup.getRoot()).equals(second), "The popup must track the exact final color even when CSS text does not change");
    popup.hide(Duration.ZERO);
    Color hidden = Color.web("#BA929B"); FxTheme.accent(root, hidden);
    check(FxTheme.accentOf(popup.getRoot()).equals(second), "A hidden popover must detach the owner listener");
    popup.show(anchor);
    check(FxTheme.accentOf(popup.getRoot()).equals(hidden), "Reopening must reconnect to the latest owner color");
    popup.hide(Duration.ZERO); popup.show(anchor);
    Color latest = Color.web("#7A9A85"); FxTheme.accent(root, latest);
    check(FxTheme.accentOf(popup.getRoot()).equals(latest), "Repeated reopen must preserve live color synchronization");
    popup.hide(Duration.ZERO); owner.close();
  }
}
