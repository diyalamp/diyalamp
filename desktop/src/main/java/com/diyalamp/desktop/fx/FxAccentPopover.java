package com.diyalamp.desktop.fx;

import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.Popover;
import atlantafx.base.theme.Styles;
import java.util.List;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/** Workspace accent presets and a native disclosure for custom hex colors. */
final class FxAccentPopover {
  private record Preset(String name, Color color) {
    Preset(String name, String hex) { this(name, Color.web(hex)); }
  }
  private static final List<Preset> PRESETS = List.of(
      new Preset("Red", "#EE0000"), new Preset("Blue", "#489CFF"), new Preset("Yellow", "#F7C843"),
      new Preset("Green", "#4CD980"), new Preset("White", "#FFFFFF"));
  private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{6}");
  private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

  static Popover create(Window owner, ObjectProperty<Color> accent) {
    Label title = new Label("Accent color");
    title.getStyleClass().add("popover-title");
    GridPane choices = new GridPane();
    choices.setHgap(4);
    choices.setVgap(4);
    for (int i = 0; i < 2; i++) {
      ColumnConstraints column = new ColumnConstraints();
      column.setPercentWidth(50);
      choices.getColumnConstraints().add(column);
    }
    for (int i = 0; i < PRESETS.size(); i++) {
      Preset preset = PRESETS.get(i);
      Region dot = swatch();
      paint(dot, preset.color());
      Button choice = new Button(preset.name(), dot);
      choice.getStyleClass().addAll(Styles.FLAT, "accent-choice");
      choice.setMinWidth(0);
      choice.setMaxWidth(Double.MAX_VALUE);
      Runnable select = () -> {
        boolean selected = preset.color().equals(accent.get());
        choice.pseudoClassStateChanged(SELECTED, selected);
        choice.setAccessibleText(preset.name() + " accent color" + (selected ? ", selected" : ""));
      };
      select.run();
      accent.addListener((observable, previous, next) -> select.run());
      choice.setOnAction(event -> accent.set(preset.color()));
      choices.add(choice, i % 2, i / 2);
    }

    CustomTextField hex = new CustomTextField();
    hex.setAccessibleText("Custom accent hex");
    hex.setAccessibleHelp("Enter six hexadecimal digits, with or without the leading number sign.");
    hex.setPromptText("#RRGGBB");
    hex.getStyleClass().add("accent-hex");
    hex.setMinWidth(0);
    HBox.setHgrow(hex, Priority.ALWAYS);
    Region preview = swatch();
    hex.setLeft(preview);
    hex.textProperty().addListener((observable, previous, text) -> {
      String value = text.strip();
      paint(preview, HEX.matcher(value).matches() ? color(value) : Color.TRANSPARENT);
    });
    Button apply = new Button("Apply");
    apply.setMinWidth(Region.USE_PREF_SIZE);
    apply.getStyleClass().addAll("popover-action", Styles.ACCENT);
    apply.disableProperty().bind(Bindings.createBooleanBinding(
        () -> !HEX.matcher(hex.getText().strip()).matches(), hex.textProperty()));
    apply.setOnAction(event -> accent.set(color(hex.getText().strip())));
    hex.setOnAction(event -> apply.fire());
    HBox customEntry = new HBox(8, hex, apply);
    customEntry.setAlignment(Pos.CENTER_LEFT);
    TitledPane custom = new TitledPane("Custom color", customEntry);
    custom.getStyleClass().add("accent-custom");
    custom.setExpanded(false);
    custom.animatedProperty().bind(Bindings.createBooleanBinding(
        () -> !FxMotion.reduced(), Platform.getPreferences().reducedMotionProperty()));
    Runnable customSelection = () -> {
      boolean selected = PRESETS.stream().noneMatch(preset -> preset.color().equals(accent.get()));
      custom.pseudoClassStateChanged(SELECTED, selected);
      custom.setAccessibleText(selected ? "Custom accent color, selected" : "Custom accent color");
    };
    customSelection.run();
    accent.addListener((observable, previous, next) -> customSelection.run());
    custom.expandedProperty().addListener((observable, previous, expanded) -> {
      if (!expanded) return;
      hex.setText(FxTheme.hex(accent.get()));
      Platform.runLater(() -> {
        if (custom.isExpanded() && hex.getScene() != null && hex.getScene().getWindow().isShowing()) {
          hex.requestFocus();
          hex.selectAll();
        }
      });
    });
    VBox content = new VBox(8, title, choices, custom);
    content.getStyleClass().addAll("popover-panel", "accent-bubble");
    content.setPrefWidth(304);
    Popover popup = new Popover(content);
    popup.setTitle("Accent color");
    FxPopovers.style(popup, owner);
    return popup;
  }

  private static Region swatch() {
    Region dot = new Region();
    dot.getStyleClass().add("accent-swatch");
    dot.setMouseTransparent(true);
    return dot;
  }

  private static void paint(Region dot, Color color) {
    dot.setStyle("-fx-background-color: " + (color.equals(Color.TRANSPARENT) ? "transparent" : FxTheme.hex(color)) + ";");
  }

  private static Color color(String value) {
    return Color.web(value.startsWith("#") ? value : "#" + value);
  }

  private FxAccentPopover() {}
}
