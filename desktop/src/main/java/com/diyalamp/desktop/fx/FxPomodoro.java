package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Popover;
import atlantafx.base.theme.Styles;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

/** A focus and break timer anchored to the native workspace toolbar. */
final class FxPomodoro implements AutoCloseable {
  private static final String FOCUS_MINUTES = "pomodoro.focusMinutes";
  private static final String BREAK_MINUTES = "pomodoro.breakMinutes";
  private static final String REPEATING = "pomodoro.repeating";
  private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("timer-active");
  private static final PseudoClass INVALID = PseudoClass.getPseudoClass("timer-invalid");

  private final Window owner;
  private final Consumer<String> notice;
  private final PomodoroClock clock;
  private final Preferences preferences;
  private final Button control = new Button();
  private final Node timerIcon = FxTheme.icon("timer", 16), playIcon = FxTheme.icon("play", 16),
      pauseIcon = FxTheme.icon("pause", 16), pausedIndicator = FxTheme.icon("pause", 12);
  private final Region runningIndicator = new Region();
  private final Label toolbarPhase = new Label(), toolbarTime = new Label();
  private final StackPane toolbarState = new StackPane();
  private final HBox activeTimer = new HBox(8, toolbarPhase, toolbarTime, toolbarState);
  private final Timeline ticker;
  private final Label time = new Label(), state = new Label(), detail = new Label(),
      settingsHelp = new Label(), durationError = new Label("Enter 1–180 minutes.");
  private final Button start = new Button(), reset = new Button("Reset", FxTheme.icon("refresh-cw", 16));
  private final RadioButton focus = new RadioButton("Focus"), rest = new RadioButton("Break"),
      once = new RadioButton("One cycle"), repeat = new RadioButton("Repeat");
  private final TextField focusMinutes = new TextField(), breakMinutes = new TextField();
  private boolean validDurations = true;
  private Popover popup;

  FxPomodoro(Window owner, Consumer<String> notice) {
    this(owner, notice, new PomodoroClock(System::nanoTime), null);
  }

  FxPomodoro(Window owner, Consumer<String> notice, Preferences preferences) {
    this(owner, notice, new PomodoroClock(System::nanoTime), preferences);
  }

  FxPomodoro(Window owner, Consumer<String> notice, PomodoroClock clock) {
    this(owner, notice, clock, null);
  }

  FxPomodoro(Window owner, Consumer<String> notice, PomodoroClock clock, Preferences preferences) {
    this.owner = owner;
    this.notice = notice;
    this.clock = clock;
    this.preferences = preferences;
    if (preferences != null) {
      clock.setDurations(savedMinutes(FOCUS_MINUTES, clock.focusMinutes()),
          savedMinutes(BREAK_MINUTES, clock.breakMinutes()));
      clock.setRepeating(preferences.getBoolean(REPEATING, clock.repeating()));
    }
    buildToolbarButton();
    ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refresh()));
    ticker.setCycleCount(Animation.INDEFINITE);

    start.getStyleClass().addAll("popover-action", Styles.ACCENT);
    start.setId("pomodoro-start");
    reset.getStyleClass().add("popover-action");
    reset.setId("pomodoro-reset");
    start.setOnAction(event -> {
      if (clock.running()) clock.pause();
      else if (validDurations) {
        detail.setText("");
        clock.start();
      }
      refresh();
    });
    reset.setOnAction(event -> {
      clock.reset();
      detail.setText("");
      focusMinutes.setText(Integer.toString(clock.focusMinutes()));
      breakMinutes.setText(Integer.toString(clock.breakMinutes()));
      refresh();
    });
    choices(focus, rest);
    choices(once, repeat);
    focus.setOnAction(event -> select(PomodoroClock.Phase.FOCUS));
    rest.setOnAction(event -> select(PomodoroClock.Phase.BREAK));
    once.setOnAction(event -> repeat(false));
    repeat.setOnAction(event -> repeat(true));
    configureMinutes(focusMinutes, "pomodoro-focus-minutes", clock.focusMinutes());
    configureMinutes(breakMinutes, "pomodoro-break-minutes", clock.breakMinutes());
    durationError.getStyleClass().add("pomodoro-error");
    durationError.setVisible(false);
    durationError.managedProperty().bind(durationError.visibleProperty());
    refresh();
  }

  Button button() { return control; }

  void show() { FxPopovers.toggle(popover(), control); }

  Popover popover() {
    if (popup != null) return popup;
    time.getStyleClass().add("pomodoro-time");
    state.getStyleClass().add("pomodoro-detail");
    settingsHelp.getStyleClass().add("pomodoro-detail");
    settingsHelp.setWrapText(true);
    detail.getStyleClass().add("pomodoro-detail");
    detail.setWrapText(true);
    detail.visibleProperty().bind(detail.textProperty().isNotEmpty());
    detail.managedProperty().bind(detail.visibleProperty());
    Label title = new Label("Pomodoro");
    title.getStyleClass().add("popover-title");
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox header = new HBox(8, new VBox(8, title, state), spacer, time);
    header.setAlignment(Pos.TOP_LEFT);
    VBox focusSetting = labeledMinutes("Focus minutes", focusMinutes);
    VBox breakSetting = labeledMinutes("Break minutes", breakMinutes);
    HBox.setHgrow(focusSetting, Priority.ALWAYS);
    HBox.setHgrow(breakSetting, Priority.ALWAYS);
    Label schedule = new Label("Schedule");
    schedule.getStyleClass().add("pomodoro-detail");
    VBox content = new VBox(8, header, row(start, reset), row(focus, rest), new Separator(),
        new HBox(8, focusSetting, breakSetting), schedule, row(once, repeat), settingsHelp, durationError, detail);
    content.getStyleClass().add("pomodoro-bubble");
    content.setPrefWidth(328);
    popup = new Popover(content);
    FxPopovers.style(popup, owner);
    refresh();
    return popup;
  }

  private void buildToolbarButton() {
    control.setText(null);
    control.setGraphic(timerIcon);
    control.getStyleClass().addAll("flat", "workspace-tool", "pomodoro-control");
    control.getStylesheets().add(FxPomodoro.class.getResource("/fx-popovers.css").toExternalForm());
    control.setTooltip(new Tooltip());
    control.setOnAction(event -> show());
    activeTimer.setAlignment(Pos.CENTER);
    toolbarPhase.getStyleClass().add("pomodoro-toolbar-phase");
    toolbarTime.getStyleClass().add("pomodoro-toolbar-time");
    toolbarTime.setAlignment(Pos.CENTER_RIGHT);
    toolbarState.getStyleClass().add("pomodoro-toolbar-state");
    runningIndicator.getStyleClass().add("pomodoro-running-dot");
    activeTimer.setMouseTransparent(true);
  }

  private static void choices(RadioButton first, RadioButton second) {
    ToggleGroup group = new ToggleGroup();
    first.setToggleGroup(group);
    second.setToggleGroup(group);
    first.getStyleClass().add("pomodoro-phase");
    second.getStyleClass().add("pomodoro-phase");
  }

  private static HBox row(ButtonBase first, ButtonBase second) {
    first.setMaxWidth(Double.MAX_VALUE);
    second.setMaxWidth(Double.MAX_VALUE);
    first.setPrefWidth(128);
    second.setPrefWidth(128);
    HBox.setHgrow(first, Priority.ALWAYS);
    HBox.setHgrow(second, Priority.ALWAYS);
    return new HBox(8, first, second);
  }

  private void configureMinutes(TextField field, String id, int initial) {
    field.setId(id);
    field.getStyleClass().addAll("note-search-field", "pomodoro-minutes");
    field.setAccessibleHelp("Enter a whole number from 1 to 180 minutes.");
    field.setTextFormatter(new TextFormatter<>(change ->
        change.getControlNewText().matches("[0-9]{0,3}") ? change : null));
    field.setText(Integer.toString(initial));
    field.setMinWidth(0);
    field.setPrefWidth(0);
    field.setMaxWidth(Double.MAX_VALUE);
    field.textProperty().addListener(observable -> applyDurations());
    field.setOnAction(event -> {
      int value = minutes(field);
      if (value != 0) field.setText(Integer.toString(value));
      event.consume();
    });
  }

  private static VBox labeledMinutes(String title, TextField field) {
    Label label = new Label(title);
    label.setLabelFor(field);
    field.setAccessibleText(title);
    return new VBox(8, label, field);
  }

  private void applyDurations() {
    int focusValue = minutes(focusMinutes), breakValue = minutes(breakMinutes);
    validDurations = focusValue != 0 && breakValue != 0;
    focusMinutes.pseudoClassStateChanged(INVALID, focusValue == 0);
    breakMinutes.pseudoClassStateChanged(INVALID, breakValue == 0);
    durationError.setVisible(!validDurations);
    if (validDurations && (focusValue != clock.focusMinutes() || breakValue != clock.breakMinutes())) {
      clock.setDurations(focusValue, breakValue);
      if (preferences != null) {
        preferences.putInt(FOCUS_MINUTES, focusValue);
        preferences.putInt(BREAK_MINUTES, breakValue);
      }
    }
    refresh();
  }

  private static int minutes(TextField field) {
    String text = field.getText();
    if (text.isEmpty()) return 0;
    int value = Integer.parseInt(text);
    return value >= PomodoroClock.MIN_MINUTES && value <= PomodoroClock.MAX_MINUTES ? value : 0;
  }

  private int savedMinutes(String key, int fallback) {
    int value = preferences.getInt(key, fallback);
    return value >= PomodoroClock.MIN_MINUTES && value <= PomodoroClock.MAX_MINUTES ? value : fallback;
  }

  private void select(PomodoroClock.Phase phase) {
    if (clock.phase() != phase) {
      clock.select(phase);
      detail.setText("");
    }
    refresh();
  }

  private void repeat(boolean enabled) {
    clock.setRepeating(enabled);
    if (preferences != null) preferences.putBoolean(REPEATING, enabled);
    refresh();
  }

  void refresh() {
    clock.tick();
    clock.completion().ifPresent(done -> {
      String message = done == PomodoroClock.Phase.FOCUS ? "Focus session complete. Break started."
          : clock.running() ? "Break complete. Focus started." : "Break complete. Timer stopped.";
      detail.setText(message);
      notice.accept(message);
    });
    long seconds = clock.seconds();
    String remaining = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    String phase = clock.phase().label;
    String activity = clock.running() ? "Running" : clock.paused() ? "Paused" : "Ready";
    String summary = phase + " · " + remaining + " · " + activity;
    boolean active = clock.running() || clock.paused();
    time.setText(remaining);
    state.setText(phase + " · " + activity);
    toolbarPhase.setText(phase);
    toolbarTime.setText(remaining);
    toolbarState.setAccessibleText(activity);
    Node indicator = clock.running() ? runningIndicator : pausedIndicator;
    if (toolbarState.getChildren().isEmpty() || toolbarState.getChildren().getFirst() != indicator)
      toolbarState.getChildren().setAll(indicator);
    Node graphic = active ? activeTimer : timerIcon;
    if (control.getGraphic() != graphic) control.setGraphic(graphic);
    control.pseudoClassStateChanged(ACTIVE, active);
    control.setAccessibleText(active ? "Pomodoro timer, " + phase + ", " + activity : "Pomodoro focus timer");
    control.setAccessibleHelp(summary);
    control.getTooltip().setText(summary);

    start.setText(clock.running() ? "Pause" : clock.paused() ? "Resume" : "Start");
    start.setGraphic(clock.running() ? pauseIcon : playIcon);
    start.setDisable(!clock.running() && !validDurations);
    focus.setSelected(clock.phase() == PomodoroClock.Phase.FOCUS);
    rest.setSelected(clock.phase() == PomodoroClock.Phase.BREAK);
    once.setSelected(!clock.repeating());
    repeat.setSelected(clock.repeating());
    settingsHelp.setText(clock.repeating() ? "Alternate focus and break continuously."
        : "Focus, then a break, then stop.");
    if (clock.running()) ticker.play(); else ticker.stop();
  }

  @Override public void close() {
    ticker.stop();
    if (popup != null) popup.hide(Duration.ZERO);
  }
}
