package com.diyalamp.desktop.fx;

import atlantafx.base.controls.TabLine;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.prefs.AbstractPreferences;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Native divider resizing/reset/persistence with an isolated preference store and fictional note. */
public final class FxSidebarLayoutTests {
  private static int checks;
  private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

  public static void main(String[] args) throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
    if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("Toolkit startup timed out");
    Path root = Files.createTempDirectory("diyalamp-resizable-sidebar-").toRealPath();
    Path note = Files.writeString(root.resolve("Fictional.md"), "Original fictional note\n");
    MemoryPreferences preferences = new MemoryPreferences();
    preferences.put("folder", root.toString()); preferences.putBoolean("connectionsVisible", true);
    preferences.putDouble("filesRatio", .30);
    Fixture fixture = null;
    try {
      fixture = fx(() -> new Fixture(preferences, note)); Fixture current = fixture;
      await(() -> fx(() -> current.stable() && current.body.getItems().size() == 3));
      fx(() -> {
        current.layout();
        check(current.body.getDividers().size() == 2, "Both sidebars use real native SplitPane dividers");
        near(current.left.getWidth(), current.body.getWidth() * .20, "Migrate the previous default files width");
        near(current.right.getWidth(), current.body.getWidth() * .20, "Default connections width");
        current.body.setDividerPositions(.35, .65); current.layout();
        check(current.left.getWidth() > 320 && current.right.getWidth() > 320, "Native divider positions resize both sidebars");
        return null;
      });
      await(() -> fx(() -> preferences.getDouble("filesRatio", 0) > .32 && preferences.getDouble("connectionsRatio", 0) > .32));
      fx(() -> {
        near(preferences.getDouble("filesRatio", 0) * current.body.getWidth(), current.left.getWidth(), "Persisted native left share");
        near(preferences.getDouble("connectionsRatio", 0) * current.body.getWidth(), current.right.getWidth(), "Persisted native right share");
        current.body.setDividerPositions(.01, .99); current.layout();
        check(current.left.getWidth() >= 199 && current.right.getWidth() >= 199,
            "Native dividers enforce usable sidebar minimums");
        current.body.setDividerPositions(.80, .85); current.layout();
        check(((Region) field(current.app, "split")).getWidth() >= 239,
            "Native dividers preserve the writing pane minimum");
        current.body.setDividerPositions(.35, .65); current.layout();
        current.editor.area().appendText("Preserved through layout");
        current.editor.area().selectRange(2, 8);
        current.text = current.editor.area().getText(); current.active = field(current.app, "active");
        ((BooleanProperty) field(current.app, "zenMode")).set(true);
        current.reset().fire(); return null;
      });
      await(() -> fx(current::stable));
      fx(() -> {
        near(current.left.getWidth(), current.body.getWidth() * .20, "Reset files width"); near(current.right.getWidth(), current.body.getWidth() * .20, "Reset connections width");
        check(field(current.app, "active") == current.active && current.editor.area().getText().equals(current.text), "Reset keeps the same open editor and note content");
        check(current.editor.area().getAnchor() == 2 && current.editor.area().getCaretPosition() == 8, "Reset keeps the editor selection");
        check(((BooleanProperty) field(current.app, "zenMode")).get(), "Reset preserves current-line Zen");
        call(current.app, "toggleSidebar");
        current.graph.showingProperty().set(false);
        Timeline closing = (Timeline) field(current.app, "sidebarAnimation");
        check(closing != null, "The sidebar transition begins while motion is enabled");
        closing.jumpTo(Duration.millis(65));
        current.layout();
        check(current.body.getItems().size() == 3, "Both sidebars stay mounted while the workspace shrinks them");
        System.setProperty("diyalamp.reduceMotion", "true");
        try {
          call(current.app, "applyMotionPreference");
          check(field(current.app, "sidebarAnimation") == null && !current.right.isVisible()
              && current.body.getItems().size() == 1,
              "Enabling reduced motion must settle the divider and the inspector immediately");
          check(!((TabLine) field(field(current.app, "primary"), "tabs")).getAnimated(),
              "Reduced motion must disable AtlantaFX tab reveal as well as workspace motion");
        } finally {
          System.clearProperty("diyalamp.reduceMotion");
          call(current.app, "applyMotionPreference");
        }
        return null;
      });
      await(() -> fx(() -> current.stable() && current.body.getItems().size() == 1));
      fx(() -> {
        check(!preferences.getBoolean("filesVisible", true) && !preferences.getBoolean("connectionsVisible", true), "Both independent closed states persist");
        current.reset().fire();
        check(current.body.getItems().size() == 1, "Reset never opens a hidden sidebar");
        call(current.app, "toggleSidebar"); current.graph.showingProperty().set(true); return null;
      });
      await(() -> fx(() -> current.stable() && current.body.getItems().size() == 3));
      fx(() -> {
        near(current.left.getWidth(), current.body.getWidth() * .20, "Reopened default files width"); near(current.right.getWidth(), current.body.getWidth() * .20, "Reopened default connections width");
        current.stage.setWidth(820); current.layout(); return null;
      });
      await(() -> fx(current::stable));
      fx(() -> {
        current.layout();
        Region writer = (Region) field(current.app, "split");
        check(writer.getWidth() >= 239, "The native writing pane keeps useful width when both sidebars are open");
        check(current.right.localToScene(current.right.getWidth(), 0).getX() <= current.stage.getScene().getWidth() + 1,
            "Sidebars remain inside a narrow window");
        call(current.app, "open", new Class<?>[] {Path.class, boolean.class}, note, true); return null;
      });
      await(() -> fx(current::stable));
      fx(() -> {
        current.layout(); SplitPane writer = (SplitPane) field(current.app, "split");
        check(writer.getItems().size() == 2 && writer.getWidth() >= 400,
            "Two writing panes receive their combined native minimum at a narrow window width");
        var bounds = writer.localToScene(writer.getBoundsInLocal());
        var first = writer.getItems().getFirst().localToScene(writer.getItems().getFirst().getBoundsInLocal());
        var second = writer.getItems().getLast().localToScene(writer.getItems().getLast().getBoundsInLocal());
        check(first.getMinX() >= bounds.getMinX() - 1 && second.getMaxX() <= bounds.getMaxX() + 1
            && second.getMinX() >= first.getMaxX(), "Nested writing panes neither overlap nor extend into sidebars");
        call(current.app, "singlePane");
        current.body.setDividerPositions(.23, .72); current.layout();
        call(current.app, "toggleSidebar"); return null;
      });
      await(() -> fx(() -> current.stable() && current.body.getItems().size() == 2));
      double[] saved = fx(() -> new double[] {preferences.getDouble("filesRatio", 0), preferences.getDouble("connectionsRatio", 0)});
      fx(() -> { current.close(); return null; }); fixture = null;
      fixture = fx(() -> new Fixture(preferences, note)); Fixture restored = fixture;
      await(() -> fx(() -> restored.stable() && restored.body.getItems().size() == 2));
      fx(() -> {
        check(!restored.body.getItems().contains(restored.left) && restored.body.getItems().contains(restored.right), "A new app restores sidebars independently");
        near(restored.right.getWidth(), saved[1] * restored.body.getWidth(), "Restored right share");
        call(restored.app, "toggleSidebar"); return null;
      });
      await(() -> fx(restored::stable));
      fx(() -> { near(restored.left.getWidth(), saved[0] * restored.body.getWidth(), "Restored hidden files share"); restored.close(); return null; }); fixture = null;
      preferences.putDouble("filesRatio", Double.NaN); preferences.putDouble("connectionsRatio", Double.POSITIVE_INFINITY);
      fixture = fx(() -> new Fixture(preferences, note)); Fixture invalid = fixture;
      await(() -> fx(invalid::stable));
      fx(() -> {
        near(invalid.left.getWidth(), invalid.body.getWidth() * .20, "Invalid files width falls back safely"); near(invalid.right.getWidth(), invalid.body.getWidth() * .20, "Invalid connections width falls back safely");
        return null;
      });
      check(Files.readString(note).contains("Preserved through layout"), "Closing retains edits through all layout changes");
    } finally {
      if (fixture != null) { Fixture last = fixture; fx(() -> { last.close(); return null; }); }
      Platform.exit();
      try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    System.out.println("Passed " + checks + " native sidebar layout checks.");
  }
  private static void near(double actual, double expected, String name) { check(Math.abs(actual - expected) <= 3, name + ": " + actual + " expected " + expected); }
  private static final class Fixture implements AutoCloseable {
    final FxDiyalamp app;
    final Stage stage = new Stage();
    final SplitPane body;
    final Region left, right;
    final FxNoteGraph graph;
    final FxWritingPane editor;
    Object active;
    String text;
    Fixture(MemoryPreferences preferences, Path note) throws Exception {
      app = new FxDiyalamp(preferences); app.start(stage); call(app, "open", new Class<?>[] {Path.class, boolean.class}, note, false);
      body = (SplitPane) field(app, "body"); left = (Region) field(app, "explorerClip");
      graph = (FxNoteGraph) field(app, "noteGraph"); right = (Region) graph.node();
      editor = (FxWritingPane) field(field(app, "active"), "editor"); layout();
    }
    boolean stable() throws Exception { return !((boolean) field(app, "sidebarLayoutChanging")) && !((boolean) field(app, "sidebarLayoutQueued")); }
    void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); body.layout(); }
    Button reset() throws Exception { return ((ToolBar) field(app, "toolbar")).getItems().stream()
        .filter(node -> "Reset sidebar widths".equals(node.getAccessibleText())).map(node -> (Button) node).findFirst().orElseThrow(); }
    @Override public void close() { app.stop(); stage.hide(); }
  }
  private static Object field(Object target, String name) throws Exception { Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target); }
  private static Object call(Object target, String name) throws Exception { return call(target,name,new Class<?>[0]); }
  private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception { Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args); }
  private static <T> T fx(Callable<T> action) throws Exception { FutureTask<T> result=new FutureTask<>(action);Platform.runLater(result);return result.get(10,TimeUnit.SECONDS); }
  private static void await(Callable<Boolean> ready) throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!ready.call()){if(System.nanoTime()>end)throw new AssertionError("Sidebar layout timed out");Thread.sleep(25);} }
  private static final class MemoryPreferences extends AbstractPreferences {
    private final Map<String,String> values = new HashMap<>();
    MemoryPreferences(){super(null,"");}
    @Override protected void putSpi(String k,String v){values.put(k,v);} @Override protected String getSpi(String k){return values.get(k);}
    @Override protected void removeSpi(String k){values.remove(k);} @Override protected void removeNodeSpi(){values.clear();}
    @Override protected String[] keysSpi(){return values.keySet().toArray(String[]::new);} @Override protected String[] childrenNamesSpi(){return new String[0];}
    @Override protected AbstractPreferences childSpi(String name){throw new UnsupportedOperationException();}
    @Override protected void syncSpi(){} @Override protected void flushSpi(){}
  }
}
