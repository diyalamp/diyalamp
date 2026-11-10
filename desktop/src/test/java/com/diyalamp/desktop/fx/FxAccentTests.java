package com.diyalamp.desktop.fx;

import atlantafx.base.controls.CustomTextField;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.AbstractPreferences;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Exercises actual JavaFX animation, interruption, and target-only preference writes. */
public final class FxAccentTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] arguments) throws Exception {
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      try { Platform.setImplicitExit(false); FxTheme.setup(); popupControls(); transition(finished, failure); }
      catch (Throwable error) { failure.set(error); finished.countDown(); }
    });
    if (!finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("Accent animation tests timed out");
    Platform.exit();
    if (failure.get() != null) throw new AssertionError("Accent animation regression", failure.get());
    System.out.println("Passed " + checks + " JavaFX accent animation checks.");
  }

  private static void popupControls() {
    Stage stage = new Stage();
    Button anchor = new Button("Accent colors");
    BorderPane root = new BorderPane(anchor);
    stage.setScene(new Scene(root, 500, 480));
    stage.show();
    var accent = new SimpleObjectProperty<>(FxTheme.DEFAULT_ACCENT);
    String preference = System.getProperty("diyalamp.reduceMotion");
    System.setProperty("diyalamp.reduceMotion", "true");
    try {
      var popup = FxAccentPopover.create(stage, accent);
      FxPopovers.toggle(popup, anchor);
      var content = popup.getContentNode();
      var presets = content.lookupAll(".accent-choice").stream().map(Button.class::cast).toList();
      check(presets.size() == 5, "The five accent presets must remain directly selectable");
      Button blue = presets.stream().filter(button -> button.getText().equals("Blue")).findFirst().orElseThrow();
      blue.fire();
      check(accent.get().equals(Color.web("#489CFF")) && blue.getAccessibleText().contains(", selected"),
          "Preset selection must update the workspace and accessible state");
      TitledPane custom = (TitledPane) content.lookup(".accent-custom");
      check(!custom.isExpanded() && !custom.isAnimated(), "The native custom disclosure must honor Reduce Motion");
      custom.setExpanded(true);
      CustomTextField hex = (CustomTextField) custom.getContent().lookup(".accent-hex");
      Button apply = (Button) custom.getContent().lookup(".popover-action");
      check(hex.getText().equals("#489CFF") && hex.getLeft() instanceof Region,
          "The hex input must start at the current accent with a native preview slot");
      hex.setText("#0");
      check(apply.isDisabled() && accent.get().equals(Color.web("#489CFF")),
          "Incomplete hex text must never change the workspace accent");
      hex.setText("AA66CC");
      check(hex.getLeft().getStyle().contains("#AA66CC") && !apply.isDisabled(),
          "A valid pending color must show a live preview without changing the workspace");
      apply.fire();
      check(accent.get().equals(Color.web("#AA66CC")) && custom.getAccessibleText().contains(", selected"),
          "Applying a custom value must announce the selected custom color");
      popup.hide(Duration.ZERO);
    } finally {
      if (preference == null) System.clearProperty("diyalamp.reduceMotion");
      else System.setProperty("diyalamp.reduceMotion", preference);
      stage.close();
    }
  }

  @SuppressWarnings("unchecked") private static void transition(CountDownLatch finished,
      AtomicReference<Throwable> failure) throws Exception {
    MemoryPreferences preferences = new MemoryPreferences(null, "");
    Color initial = Color.web("#7A9A85"), rose = Color.web("#BA929B"), blue = Color.web("#7B8FB7");
    preferences.put("accent", FxTheme.hex(initial));
    FxDiyalamp app = new FxDiyalamp(preferences);
    Method factory = FxDiyalamp.class.getDeclaredMethod("accentButton"); factory.setAccessible(true);
    Button button = (Button) factory.invoke(app);
    Region preview = (Region) button.getGraphic();
    call(app, "initializeAccent");
    ObjectProperty<Color> target = (ObjectProperty<Color>) field(app, "accent");
    ObjectProperty<Color> displayed = (ObjectProperty<Color>) field(app, "displayedAccent");
    BorderPane root = (BorderPane) field(app, "root");
    check(displayed.get().equals(initial) && target.get().equals(initial), "Restored color must apply immediately");
    check(field(app, "accentAnimation") == null, "Startup must not animate the restored accent");
    check(preferences.writes == 1, "Restoring an accent must not rewrite preferences");
    check(preview.getStyle().contains(FxTheme.hex(initial)), "Circular preview must show the restored color");
    String initialStyle = root.getStyle();

    target.set(rose);
    Timeline first = (Timeline) field(app, "accentAnimation");
    check(first.getStatus() == Animation.Status.RUNNING && first.getTotalDuration().toMillis() == 280,
        "A new selection must start the short color transition");
    check(displayed.get().equals(initial), "Starting the transition must not jump to the target");
    check(preferences.get("accent", "").equals(FxTheme.hex(rose)) && preferences.writes == 2,
        "The selected target must be persisted once, before its animation finishes");
    first.jumpTo(Duration.millis(140));
    Color middle = displayed.get();
    check(!middle.equals(initial) && !middle.equals(rose), "The transition must display an interpolated color");
    check(!root.getStyle().equals(initialStyle) && preview.getStyle().contains(FxTheme.hex(middle)),
        "Workspace colors and circular preview must follow the same animated value");
    check(preferences.writes == 2, "Animation frames must not write intermediate colors to preferences");

    target.set(blue);
    Timeline second = (Timeline) field(app, "accentAnimation");
    check(first != second && first.getStatus() == Animation.Status.STOPPED,
        "A newer choice must cancel the previous transition");
    check(displayed.get().equals(middle), "Retargeting must continue from the currently displayed color without a flash");
    check(preferences.writes == 3 && preferences.get("accent", "").equals(FxTheme.hex(blue)),
        "Retargeting must persist only the newly selected final color");
    second.jumpTo(Duration.millis(140));
    check(!displayed.get().equals(blue), "The folder-disposal check must occur while the color is still transitioning");
    call(app, "disposeAll");
    check(second.getStatus() == Animation.Status.RUNNING,
        "Disposing folder documents must not stop a workspace-wide accent transition");

    PauseTransition settle = new PauseTransition(Duration.millis(450));
    settle.setOnFinished(event -> {
      try {
        check(displayed.get().equals(blue) && target.get().equals(blue), "A naturally completed transition must reach the final selection");
        check(field(app, "accentAnimation") == null, "A completed transition must release its animation reference");
        check(preview.getStyle().contains(FxTheme.hex(blue)), "The circular preview must finish at the selected color");
        check(preferences.writes == 3, "Completion must not add preference writes");
        System.setProperty("diyalamp.reduceMotion", "true");
        try {
          target.set(rose);
          check(displayed.get().equals(rose) && field(app, "accentAnimation") == null,
              "Reduced motion applies a chosen accent immediately");
          check(preferences.writes == 4, "Applying an accent without motion persists only its final value");
        } finally {
          System.clearProperty("diyalamp.reduceMotion");
        }
        call(app, "disposeAll");
      } catch (Throwable error) { failure.set(error); }
      finally { finished.countDown(); }
    });
    settle.play();
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
  }
  private static void call(Object target, String name) throws Exception {
    Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
  }

  /** Isolated Preferences API provider: counts persistence, never touches the owner's settings. */
  private static final class MemoryPreferences extends AbstractPreferences {
    private final Map<String, String> values = new HashMap<>();
    int writes;
    MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
    @Override protected void putSpi(String key, String value) { values.put(key, value); writes++; }
    @Override protected String getSpi(String key) { return values.get(key); }
    @Override protected void removeSpi(String key) { values.remove(key); }
    @Override protected void removeNodeSpi() { values.clear(); }
    @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
    @Override protected String[] childrenNamesSpi() { return new String[0]; }
    @Override protected AbstractPreferences childSpi(String name) { return new MemoryPreferences(this, name); }
    @Override protected void syncSpi() { }
    @Override protected void flushSpi() { }
  }
}
