package com.diyalamp.desktop.fx;

import atlantafx.base.theme.Styles;
import java.util.Locale;
import java.util.function.Consumer;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;

/** Small in-app Pomodoro. Closing its dialog leaves a running session in the status bar. */
final class FxPomodoro implements AutoCloseable {
  private final Window owner;
  private final Consumer<String> notice;
  private final PomodoroClock clock;
  private final Button control = new Button();
  private final Timeline ticker;
  private final Label time = new Label(), phase = new Label(), detail = new Label();
  private final Button start = new Button(), reset = new Button("Reset");
  private final ToggleButton focus = new ToggleButton("Focus · 25 min"), rest = new ToggleButton("Break · 5 min");
  private Dialog<Void> dialog;

  FxPomodoro(Window owner, Consumer<String> notice) {
    this(owner, notice, new PomodoroClock(System::nanoTime));
  }

  FxPomodoro(Window owner, Consumer<String> notice, PomodoroClock clock) {
    this.owner = owner; this.notice = notice; this.clock = clock;
    control.setGraphic(FxTheme.icon("timer", 15));
    control.getStyleClass().addAll("flat", "pomodoro-control");
    control.setAccessibleText("Pomodoro focus timer");
    control.setTooltip(new Tooltip("Pomodoro · 25 minutes of focus, 5 minutes of rest"));
    control.setOnAction(event -> show());
    ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refresh()));
    ticker.setCycleCount(Animation.INDEFINITE);
    start.getStyleClass().add(Styles.ACCENT); start.setId("pomodoro-start");
    reset.setId("pomodoro-reset");
    start.setOnAction(event -> {
      if (clock.running()) clock.pause(); else clock.start();
      refresh();
    });
    reset.setOnAction(event -> { clock.reset(); detail.setText("Choose when to start. Sessions stop when the app quits."); refresh(); });
    ToggleGroup phases = new ToggleGroup(); focus.setToggleGroup(phases); rest.setToggleGroup(phases);
    focus.setOnAction(event -> { clock.select(PomodoroClock.Phase.FOCUS); refresh(); });
    rest.setOnAction(event -> { clock.select(PomodoroClock.Phase.BREAK); refresh(); });
    refresh();
  }

  Button button() { return control; }

  void show() {
    Dialog<Void> current = dialog();
    if (current.isShowing()) current.getDialogPane().getScene().getWindow().requestFocus();
    else current.show();
  }

  Dialog<Void> dialog() {
    if (dialog != null) return dialog;
    phase.getStyleClass().add("pomodoro-phase"); time.getStyleClass().add("pomodoro-time");
    detail.getStyleClass().add("pomodoro-detail"); detail.setWrapText(true);
    detail.setText("Choose when to start. Sessions stop when the app quits.");
    HBox choices = new HBox(8, focus, rest); choices.setAlignment(Pos.CENTER);
    HBox actions = new HBox(8, start, reset); actions.setAlignment(Pos.CENTER);
    VBox content = new VBox(16, choices, phase, time, actions, detail);
    content.setAlignment(Pos.CENTER); content.setPrefWidth(360);
    dialog = new Dialog<>(); dialog.setTitle("Pomodoro"); dialog.initModality(Modality.NONE);
    dialog.getDialogPane().getStyleClass().add("pomodoro-dialog");
    dialog.getDialogPane().setContent(content); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    FxDialogs.style(dialog, owner); refresh(); return dialog;
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
    time.setText(remaining); phase.setText(clock.phase().label);
    control.setText(clock.phase().label + " · " + remaining);
    start.setText(clock.running() ? "Pause" : "Start");
    focus.setSelected(clock.phase() == PomodoroClock.Phase.FOCUS);
    rest.setSelected(clock.phase() == PomodoroClock.Phase.BREAK);
    if (clock.running()) ticker.play(); else ticker.stop();
  }

  @Override public void close() { ticker.stop(); if (dialog != null) dialog.close(); }
}
