package com.diyalamp.desktop.fx;

import atlantafx.base.theme.Styles;
import atlantafx.base.controls.Popover;
import java.util.Locale;
import java.util.function.Consumer;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;

/** Small in-app Pomodoro using AtlantaFX's anchored, animated popover. */
final class FxPomodoro implements AutoCloseable {
  private final Window owner;
  private final Consumer<String> notice;
  private final PomodoroClock clock;
  private final Button control = new Button();
  private final Timeline ticker;
  private final Label time = new Label(), detail = new Label();
  private final Button start = new Button(), reset = new Button("Reset");
  private final ToggleButton focus = new ToggleButton("Focus · 25 min"), rest = new ToggleButton("Break · 5 min");
  private Popover popup;

  FxPomodoro(Window owner, Consumer<String> notice) {
    this(owner, notice, new PomodoroClock(System::nanoTime));
  }

  FxPomodoro(Window owner, Consumer<String> notice, PomodoroClock clock) {
    this.owner = owner; this.notice = notice; this.clock = clock;
    control.setGraphic(FxTheme.icon("timer", FxTheme.TEXT_SIZE));
    control.getStyleClass().addAll("flat", "workspace-tool", "pomodoro-control");
    control.setAccessibleText("Pomodoro focus timer");
    control.setTooltip(new Tooltip("Focus timer"));
    control.setOnAction(event -> show());
    ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refresh()));
    ticker.setCycleCount(Animation.INDEFINITE);
    start.getStyleClass().addAll("popover-action", Styles.ACCENT); start.setId("pomodoro-start");
    reset.getStyleClass().add("popover-action");
    reset.setId("pomodoro-reset");
    start.setOnAction(event -> {
      if (clock.running()) clock.pause(); else clock.start();
      refresh();
    });
    reset.setOnAction(event -> { clock.reset(); detail.setText(""); refresh(); });
    ToggleGroup phases = new ToggleGroup(); focus.setToggleGroup(phases); rest.setToggleGroup(phases);
    focus.getStyleClass().add("pomodoro-phase"); rest.getStyleClass().add("pomodoro-phase");
    focus.setOnAction(event -> { clock.select(PomodoroClock.Phase.FOCUS); refresh(); });
    rest.setOnAction(event -> { clock.select(PomodoroClock.Phase.BREAK); refresh(); });
    refresh();
  }

  Button button() { return control; }

  void show() { FxPopovers.toggle(popover(), control); }

  Popover popover() {
    if (popup != null) return popup;
    time.getStyleClass().add("pomodoro-time");
    detail.getStyleClass().add("pomodoro-detail"); detail.setWrapText(true);
    detail.managedProperty().bind(detail.visibleProperty()); detail.setVisible(false);
    detail.textProperty().addListener((observable, previous, value) -> detail.setVisible(!value.isBlank()));
    Label title = new Label("Pomodoro");
    title.getStyleClass().add("popover-title");
    Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox header = new HBox(8, title, spacer, time); header.setAlignment(Pos.CENTER_LEFT);
    HBox choices = row(focus, rest);
    HBox actions = row(start, reset);
    VBox content = new VBox(8, header, actions, choices, detail);
    content.getStyleClass().add("pomodoro-bubble");
    content.setPrefWidth(304);
    popup = new Popover(content); FxPopovers.style(popup, owner);
    refresh(); return popup;
  }

  private static HBox row(ButtonBase first, ButtonBase second) {
    first.setMaxWidth(Double.MAX_VALUE); second.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(first, Priority.ALWAYS); HBox.setHgrow(second, Priority.ALWAYS);
    return new HBox(8, first, second);
  }

  void refresh() {
    clock.tick();
    clock.completion().ifPresent(done -> {
      String message = done == PomodoroClock.Phase.FOCUS
          ? "Focus session complete. Your five-minute break is ready."
          : "Break complete. Start the next focus session when you are ready.";
      detail.setText(message); notice.accept(message);
    });
    long seconds = clock.seconds();
    String remaining = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    time.setText(remaining);
    control.setText(null);
    String state = clock.phase().label + " · " + remaining + (clock.running() ? " · Running" : " · Stopped");
    control.getTooltip().setText(state);
    control.setAccessibleHelp(state);
    start.setText(clock.running() ? "Stop" : "Start");
    focus.setSelected(clock.phase() == PomodoroClock.Phase.FOCUS);
    rest.setSelected(clock.phase() == PomodoroClock.Phase.BREAK);
    if (clock.running()) ticker.play(); else ticker.stop();
  }

  @Override public void close() { ticker.stop(); if (popup != null) popup.hide(Duration.ZERO); }
}
