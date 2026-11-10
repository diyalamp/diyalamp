package com.diyalamp.desktop.fx;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** A real checklist popover with an isolated preferences node. */
public final class FxQuickTasksTests {
  private static int checks;

  public static void main(String[] args) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      try {
        FxTheme.setup();
        tasks();
      } catch (Throwable error) { failure.set(error); }
      finally { done.countDown(); }
    });
    if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("Quick task tests timed out");
    Platform.exit();
    if (failure.get() != null) throw new AssertionError("Quick tasks regression", failure.get());
    System.out.println("Passed " + checks + " JavaFX quick task checks.");
  }

  private static void tasks() throws Exception {
    System.setProperty("diyalamp.reduceMotion", "true");
    var preferences = Preferences.userRoot().node("com/diyalamp/tests/" + UUID.randomUUID());
    Stage stage = new Stage();
    boolean removed = false;
    try {
      FxQuickTasks tasks = new FxQuickTasks(stage, preferences);
      BorderPane root = new BorderPane(tasks.button());
      root.getStyleClass().add("workspace-root");
      root.getStylesheets().add(FxTheme.class.getResource("/fx-workspace.css").toExternalForm());
      stage.setScene(new Scene(root, 820, 600));
      stage.show();
      tasks.button().fire();
      var popup = tasks.popover();
      Parent surface = popup.getRoot();
      Parent content = (Parent) popup.getContentNode();
      layout(surface);
      var entry = (HBox) content.lookup(".quick-task-entry");
      var input = (TextField) entry.lookup(".quick-task-input");
      var add = (Button) entry.lookup(".quick-task-add");
      var empty = (Label) content.lookup(".quick-tasks-empty");
      var summary = (Label) content.lookup(".quick-tasks-summary");
      var status = (Label) content.lookup(".quick-tasks-status");
      var list = (ScrollPane) content.lookup(".quick-tasks-list");
      check(popup.isShowing() && popup.isAutoHide() && popup.isHideOnEscape(),
          "The toolbar opens the shared dismissible AtlantaFX popover");
      check(empty.isVisible() && !list.isManaged() && add.isDisabled(),
          "An empty checklist must show its empty state without a blank viewport");
      check(entry.getHeight() == 36 && input.getParent() == entry && add.getParent() == entry
              && add.getBoundsInParent().getMaxX() <= entry.getWidth(),
          "The editable field and Add button share a compact surface as separate controls");

      input.setText("  ");
      input.fireEvent(new ActionEvent());
      check(rows(list).isEmpty(), "Blank text cannot create a task");
      String title = "Review résumé λ 🗒";
      input.setText(title);
      check(title.equals(input.queryAccessibleAttribute(AccessibleAttribute.TEXT))
              && "Add task".equals(add.getAccessibleText()),
          "Accessibility exposes the typed task and Add button independently");
      input.fireEvent(new ActionEvent());
      input.setText(title);
      add.fire();
      check(rows(list).size() == 2 && !empty.isVisible() && input.getText().isEmpty()
              && summary.getText().equals("2 remaining"),
          "Enter and the add button create distinct rows with the same task text");
      layout(surface);
      HBox firstRow = rows(list).getFirst();
      double addInset = entry.getWidth() - add.getBoundsInParent().getMaxX();
      double removeInset = firstRow.getWidth() - remove(firstRow).getBoundsInParent().getMaxX();
      check(addInset == removeInset, "The Add and remove buttons use the same right inset");
      CheckBox completed = checkbox(rows(list).getFirst());
      completed.fire();
      check(completed.isSelected() && completed.getText().equals(title) && summary.getText().equals("1 remaining"),
          "Completing a task must retain its text and update the visible count");
      remove(rows(list).get(1)).fire();
      check(rows(list).size() == 1 && checkbox(rows(list).getFirst()) == completed,
          "Removing a duplicate must keep the correct row and its completion state");
      var reopened = new FxQuickTasks(stage, preferences);
      var savedList = (ScrollPane) ((Parent) reopened.popover().getContentNode()).lookup(".quick-tasks-list");
      check(rows(savedList).size() == 1 && checkbox(rows(savedList).getFirst()).isSelected()
              && checkbox(rows(savedList).getFirst()).getText().equals(title),
          "A reopened checklist must restore exact Unicode text and completion");

      String longTask = "界".repeat(160);
      for (int i = 1; i < 40; i++) {
        input.setText(longTask);
        add.fire();
      }
      input.setText("One more");
      input.fireEvent(new ActionEvent());
      layout(surface);
      check(rows(list).size() == 40 && add.isDisabled() && status.getText().contains("40"),
          "The bounded checklist must keep previous tasks when the limit is reached");
      check(list.getHeight() > 0 && list.getHeight() <= 220 && list.getHbarPolicy() == ScrollPane.ScrollBarPolicy.NEVER,
          "Long lists scroll vertically within the compact popover");
      tasks.hide();
      check(!popup.isShowing(), "The shell must be able to close the list immediately");

      preferences.removeNode();
      removed = true;
      completed.fire();
      check(completed.isSelected() && status.getText().equals("Tasks could not be saved."),
          "A rejected preferences write must restore the task's previous state");
    } finally {
      stage.close();
      if (!removed) preferences.removeNode();
      System.clearProperty("diyalamp.reduceMotion");
    }
  }

  private static List<HBox> rows(ScrollPane list) {
    return ((VBox) list.getContent()).getChildren().stream().map(HBox.class::cast).toList();
  }

  private static CheckBox checkbox(HBox row) { return (CheckBox) row.getChildren().getFirst(); }
  private static Button remove(HBox row) { return (Button) row.getChildren().getLast(); }
  private static void layout(Parent root) { root.applyCss(); root.layout(); }
  private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
