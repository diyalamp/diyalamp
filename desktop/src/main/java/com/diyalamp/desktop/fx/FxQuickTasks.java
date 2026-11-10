package com.diyalamp.desktop.fx;

import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.Popover;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

/** Small workspace-independent checklist, with a single bounded preference value. */
final class FxQuickTasks {
  private static final String TASKS = "quickTasks.v1";
  private static final int MAX_TASKS = 40;
  private static final int MAX_LENGTH = 160;
  private static final int PANEL_WIDTH = 328;
  private static final int MAX_VIEWPORT = 220;
  private static final String LIMIT_MESSAGE = MAX_TASKS + " tasks maximum.";
  private static final PseudoClass COMPLETED = PseudoClass.getPseudoClass("completed");
  private static final PseudoClass HAS_OPEN_TASKS = PseudoClass.getPseudoClass("has-open-tasks");

  private static final class Task {
    final String text;
    boolean complete;

    Task(String text, boolean complete) {
      this.text = text;
      this.complete = complete;
    }
  }

  private final Window owner;
  private final Preferences preferences;
  private final List<Task> tasks = new ArrayList<>();
  private final Button control = FxTheme.iconButton("list-checks", "Quick tasks", this::show);
  private final CustomTextField input = new CustomTextField();
  private final Button add = FxTheme.iconButton("plus", "Add task", this::add);
  private final Label summary = new Label(), empty = new Label("No tasks yet."), status = new Label();
  private final VBox rows = new VBox(4);
  private final ScrollPane checklist = new ScrollPane(rows);
  private Popover popup;

  FxQuickTasks(Window owner, Preferences preferences) {
    this.owner = owner;
    this.preferences = preferences;
    control.getStyleClass().add("quick-tasks-control");
    control.getStylesheets().add(stylesheet());
    read();
    refresh();
  }

  Button button() { return control; }

  void show() { FxPopovers.toggle(popover(), control); }

  void hide() {
    if (popup != null) popup.hide(Duration.ZERO);
  }

  Popover popover() {
    if (popup != null) return popup;
    Label title = new Label("Quick tasks");
    title.getStyleClass().add("popover-title");
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox header = new HBox(8, title, spacer, summary);
    header.setAlignment(Pos.CENTER_LEFT);
    summary.getStyleClass().add("quick-tasks-summary");
    input.setPromptText("Add a task…");
    input.setAccessibleText("New task");
    input.setAccessibleHelp("Type a task and press Enter to add it.");
    input.getStyleClass().addAll("note-search-field", "quick-task-input");
    input.setTextFormatter(new TextFormatter<>(change -> {
      String text = change.getControlNewText();
      return validText(text) ? change : null;
    }));
    input.setOnAction(event -> {
      if (!add.isDisabled()) add();
      event.consume();
    });
    input.textProperty().addListener(observable -> refreshAdd());
    add.getStyleClass().addAll("file-row-action", "quick-task-add");
    input.setRight(add);
    empty.getStyleClass().add("quick-tasks-empty");
    status.getStyleClass().add("quick-tasks-status");
    status.setWrapText(true);
    status.visibleProperty().bind(status.textProperty().isNotEmpty());
    status.managedProperty().bind(status.visibleProperty());
    checklist.getStyleClass().add("quick-tasks-list");
    checklist.setAccessibleText("Quick tasks checklist");
    checklist.setFitToWidth(true);
    checklist.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    checklist.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    checklist.setMaxHeight(MAX_VIEWPORT);
    checklist.managedProperty().bind(checklist.visibleProperty());
    empty.managedProperty().bind(empty.visibleProperty());
    rows.getStyleClass().add("quick-tasks-rows");
    for (Task task : tasks) rows.getChildren().add(row(task));
    VBox content = new VBox(12, header, input, empty, checklist, status);
    content.getStyleClass().addAll("popover-panel", "quick-tasks-panel");
    content.setPrefWidth(PANEL_WIDTH);
    popup = new Popover(content);
    popup.setTitle("Quick tasks");
    FxPopovers.style(popup, owner);
    popup.getRoot().getStylesheets().add(stylesheet());
    refresh();
    return popup;
  }

  private void add() {
    String text = input.getText().strip();
    if (text.isEmpty() || tasks.size() >= MAX_TASKS) return;
    Task task = new Task(text, false);
    tasks.add(task);
    if (!save()) {
      tasks.removeLast();
      return;
    }
    rows.getChildren().add(row(task));
    input.clear();
    refresh();
    checklist.setVvalue(checklist.getVmax());
    input.requestFocus();
  }

  private HBox row(Task task) {
    CheckBox done = new CheckBox(task.text);
    done.setSelected(task.complete);
    done.setWrapText(true);
    done.setMinWidth(0);
    done.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(done, Priority.ALWAYS);
    HBox row = new HBox(8);
    row.getStyleClass().add("quick-task-row");
    row.setAlignment(Pos.CENTER_LEFT);
    row.pseudoClassStateChanged(COMPLETED, task.complete);
    done.setOnAction(event -> {
      boolean previous = task.complete;
      task.complete = done.isSelected();
      if (!save()) {
        task.complete = previous;
        done.setSelected(previous);
        return;
      }
      row.pseudoClassStateChanged(COMPLETED, task.complete);
      refresh();
    });
    Button remove = FxTheme.iconButton("x", "Remove task", () -> remove(task, row));
    remove.setAccessibleText("Remove task: " + task.text);
    remove.getStyleClass().add("file-row-action");
    row.getChildren().setAll(done, remove);
    return row;
  }

  private void remove(Task task, HBox row) {
    int position = tasks.indexOf(task);
    if (position < 0) return;
    tasks.remove(position);
    if (!save()) {
      tasks.add(position, task);
      return;
    }
    rows.getChildren().remove(row);
    refresh();
    input.requestFocus();
  }

  private void refresh() {
    long remaining = tasks.stream().filter(task -> !task.complete).count();
    boolean hasTasks = !tasks.isEmpty();
    String count = remaining + " remaining";
    summary.setText(hasTasks ? count : "");
    control.setAccessibleHelp(hasTasks ? count : "No tasks.");
    control.pseudoClassStateChanged(HAS_OPEN_TASKS, remaining > 0);
    empty.setVisible(!hasTasks);
    checklist.setVisible(hasTasks);
    if (hasTasks) {
      rows.applyCss();
      checklist.setPrefViewportHeight(Math.min(MAX_VIEWPORT, rows.prefHeight(PANEL_WIDTH - 44)));
    }
    refreshAdd();
  }

  private void refreshAdd() {
    boolean full = tasks.size() >= MAX_TASKS;
    add.setDisable(input.getText().isBlank() || full);
    if (full) status.setText(LIMIT_MESSAGE);
    else if (status.getText().equals(LIMIT_MESSAGE)) status.setText("");
  }

  private void read() {
    for (String record : preferences.get(TASKS, "").split("\n")) {
      if (tasks.size() == MAX_TASKS) break;
      if (record.length() < 2 || (record.charAt(0) != '0' && record.charAt(0) != '1')) continue;
      String text = record.substring(1);
      if (validText(text) && !text.isBlank()) tasks.add(new Task(text, record.charAt(0) == '1'));
    }
  }

  private boolean save() {
    // A TextField accepts one line. A prefix records completion, and the limits keep one
    // snapshot within Preferences.MAX_VALUE_LENGTH even with the longest supported tasks.
    StringBuilder stored = new StringBuilder();
    for (Task task : tasks) stored.append(task.complete ? '1' : '0').append(task.text).append('\n');
    try {
      preferences.put(TASKS, stored.toString());
      status.setText("");
      return true;
    } catch (IllegalStateException | SecurityException error) {
      status.setText("Tasks could not be saved.");
      return false;
    }
  }

  private static boolean validText(String text) {
    return text.length() <= MAX_LENGTH && text.chars().noneMatch(Character::isISOControl);
  }

  private static String stylesheet() {
    return FxQuickTasks.class.getResource("/fx-quick-tasks.css").toExternalForm();
  }
}
