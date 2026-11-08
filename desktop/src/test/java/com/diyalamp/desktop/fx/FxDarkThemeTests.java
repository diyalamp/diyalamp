package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Tab;
import atlantafx.base.controls.TabLine;
import atlantafx.base.theme.Styles;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Shape;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Rendered control surfaces, real popup windows, and dark-aware accent colors. */
public final class FxDarkThemeTests {
  private static int checks;
  private static void check(boolean value, String message) {
    checks++; if (!value) throw new AssertionError(message);
  }
  public static void main(String[] args) throws Exception {
    CountDownLatch done = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      try { surfaces(); }
      catch (Throwable error) { failure.set(error); }
      finally { done.countDown(); }
    });
    if (!done.await(20, TimeUnit.SECONDS)) throw new AssertionError("Dark component checks timed out");
    Platform.exit();
    if (failure.get() != null) throw new AssertionError("Dark component regression", failure.get());
    System.out.println("Passed " + checks + " dark-theme component checks.");
  }

  private static void surfaces() {
    FxTheme.setup();
    check(Application.getUserAgentStylesheet().contains("cupertino-dark"), "Maintained CupertinoDark supplies controls");
    Node firstIcon = FxTheme.icon("search"), secondIcon = FxTheme.icon("search");
    HBox independentIcons = new HBox(firstIcon, secondIcon);
    var firstStroke = (Shape) firstIcon.lookup(".icon-stroke");
    var secondStroke = (Shape) secondIcon.lookup(".icon-stroke");
    firstStroke.setStroke(Color.WHITE);
    check(independentIcons.getChildren().size() == 2 && firstStroke != secondStroke
        && secondStroke.getStroke().equals(Color.web("#8C8C8C")),
        "Cached icon geometry must still give each control its own rendered shapes");
    BorderPane root = new BorderPane(); root.getStyleClass().add("workspace-root");
    root.getStylesheets().add(FxTheme.class.getResource("/fx-workspace.css").toExternalForm());
    FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    Label brand = new Label("Fictional workspace"); brand.getStyleClass().add("workspace-brand");
    Button tools = FxTheme.iconButton("folder-open", "Open fictional folder", () -> {});
    HBox toolbar = new HBox(brand, tools); toolbar.getStyleClass().add("workspace-toolbar"); root.setTop(toolbar);
    TextField input = new TextField(); input.setPromptText("Find a note…"); input.getStyleClass().add("note-search-field");
    VBox sidebar = new VBox(input); sidebar.getStyleClass().add("file-explorer"); sidebar.setPrefWidth(240); root.setLeft(sidebar);
    Label heading = new Label("Fictional note"); heading.getStyleClass().add("workspace-empty-title");
    Label paragraph = new Label("Ordinary writing");
    VBox writing = new VBox(heading, paragraph); writing.getStyleClass().add("note-page");
    TabLine tabs = new TabLine(); tabs.getStyleClass().addAll(Styles.DENSE, Styles.TABS_FLOATING, "document-tabs");
    Tab first = new Tab("first", "First.md"), second = new Tab("second", "Second.md");
    tabs.getTabs().addAll(first, second); tabs.getSelectionModel().select(first);
    BorderPane document = new BorderPane(writing); document.setTop(tabs); root.setCenter(document);
    Label status = new Label("All changes saved"); HBox footer = new HBox(status); footer.getStyleClass().add("workspace-status"); root.setBottom(footer);
    Stage stage = new Stage(); stage.setScene(new Scene(root, 1000, 650)); stage.show(); layout(root);
    try (FxPomodoro timer = new FxPomodoro(stage, ignored -> {})) {
      color(background(root), "#141312", "Workspace");
      color(background(toolbar), "#141312", "Toolbar"); color(background(sidebar), "#141312", "Sidebar");
      color(background(writing), "#141312", "Editor"); color(background(footer), "#141312", "Status bar");
      color(background(input), "#141312", "Search field"); color((Color) css(input, "-fx-text-fill"), "#CEC9C2", "Input text");
      color((Color) css(input, "-fx-prompt-text-fill"), "#A49D95", "Placeholder");
      color((Color) paragraph.getTextFill(), "#CEC9C2", "UI text");
      color((Color) input.getBorder().getStrokes().getFirst().getTopStroke(), "#383533", "Input border");
      input.requestFocus(); layout(root);
      color((Color) input.getBorder().getStrokes().getFirst().getTopStroke(), "#FF5555B3", "Input focus ring");
      tools.requestFocus(); layout(root);
      color((Color) status.getTextFill(), "#A49D95", "Secondary text");
      for (Labeled label : List.of(brand, heading, paragraph, status)) font(label.getFont().getSize(), label.getText());
      font(input.getFont().getSize(), "Input");
      for (Node node : tabs.lookupAll(".tab .label")) font(((Label) node).getFont().getSize(), "Tab label");
      for (Color choice : List.of(Color.web("#EE0000"), Color.web("#489CFF"), Color.web("#F7C843"),
          Color.web("#4CD980"), Color.WHITE, Color.web("#7B8FB7"), Color.BLACK)) {
        FxTheme.accent(root, choice);
        Hyperlink link = new Hyperlink("Local link"); link.setStyle("-fx-text-fill: -color-accent-fg;"); writing.getChildren().add(link);
        Button action = new Button("Action"); action.getStyleClass().add(Styles.ACCENT); writing.getChildren().add(action); layout(root);
        Color linkInk = (Color) link.getTextFill();
        check(FxTheme.contrast(linkInk, FxTheme.EDITOR_BACKGROUND) >= 4.5, "Each accent remains readable as dark-surface link text");
        check(FxTheme.contrast((Color) action.getTextFill(), background(action)) >= 4.5, "Each accent action has readable text");
        if (choice.equals(FxTheme.DEFAULT_ACCENT)) {
          color(linkInk, "#FF6B6B", "Default link"); color(background(action), "#EE0000", "Default action");
        }
        writing.getChildren().removeAll(link, action);
      }
      FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
      TextInputDialog dialog = new TextInputDialog("Fictional note"); dialog.setHeaderText("Rename note");
      FxDialogs.style(dialog, stage); dialog.show(); layout(dialog.getDialogPane());
      color(background(dialog.getDialogPane()), "#141312", "Dialog");
      color(background(dialog.getEditor()), "#141312", "Dialog input");
      for (Node node : dialog.getDialogPane().lookupAll(".label")) font(((Label) node).getFont().getSize(), "Dialog label/header");
      for (ButtonType type : dialog.getDialogPane().getButtonTypes()) font(((Button) dialog.getDialogPane().lookupButton(type)).getFont().getSize(), "Dialog action");
      FxTheme.focusSurface(root, 1); layout(dialog.getDialogPane());
      color(background(dialog.getDialogPane()), "#000000", "Focus dialog");
      color(background(dialog.getEditor()), "#000000", "Focus dialog input");
      FxTheme.focusSurface(root, 0);
      dialog.close();
      toolbar.getChildren().add(timer.button()); layout(root); timer.popover().setAnimated(false); timer.show();
      layout(timer.popover().getRoot());
      Label phase = (Label) timer.button().lookup(".pomodoro-toolbar-phase");
      check(phase != null && phase.isVisible() && phase.getText().equals("Focus")
              && timer.button().getAccessibleText().equals("Pomodoro timer, Focus, Ready")
              && timer.button().getAccessibleHelp().contains("25:00"),
          "The ready timer must display its selected phase and expose its phase, status, and remaining time");
      for (Node node : timer.popover().getRoot().lookupAll(".label")) font(((Label) node).getFont().getSize(), "Timer label/digits");
      for (Node node : timer.popover().getRoot().lookupAll(".button")) font(((Button) node).getFont().getSize(), "Timer action");
      for (Node node : timer.popover().getRoot().lookupAll(".toggle-button")) font(((ToggleButton) node).getFont().getSize(), "Timer phase");
      var border = (javafx.scene.shape.Shape) timer.popover().getRoot().lookup(".border");
      color((Color) border.getFill(), "#141312", "Popover"); timer.popover().hide(Duration.ZERO);
      Tooltip tip = tools.getTooltip(); tip.show(stage, 100, 100); tip.getScene().getRoot().applyCss();
      font(tip.getFont().getSize(), "Tooltip"); color((Color) ((Label) tip.getSkin().getNode()).getTextFill(), "#CEC9C2", "Tooltip ink"); tip.hide();
      ContextMenu menu = FxTheme.contextMenu();
      menu.getItems().add(new MenuItem("Copy"));
      menu.show(tools, Side.BOTTOM, 0, 0);
      layout(menu.getScene().getRoot());
      for (Node node : menu.getScene().getRoot().lookupAll(".menu-item .label")) font(((Label) node).getFont().getSize(), "Context menu");
      Region menuSurface = (Region) menu.getSkin().getNode();
      color(background(menuSurface), "#141312", "Context menu");
      FxTheme.focusSurface(root, 1); layout(menu.getScene().getRoot());
      color(background(menuSurface), "#000000", "Focus context menu");
      FxTheme.focusSurface(root, 0); menu.hide();
    } finally { stage.close(); }
  }
  private static void font(double size, String label) { check(size == FxTheme.TEXT_SIZE, label + " must use one 16px size, got " + size); }
  private static void color(Color actual, String expected, String label) { check(actual.equals(Color.web(expected)), label + " color was " + actual); }
  private static Color background(Region node) {
    var fills = node.getBackground().getFills(); return (Color) fills.getLast().getFill();
  }
  private static void layout(javafx.scene.Parent node) { node.applyCss(); node.layout(); }
  @SuppressWarnings("unchecked") private static Object css(javafx.css.Styleable node, String property) {
    var metadata = (javafx.css.CssMetaData<javafx.css.Styleable, Object>) node.getCssMetaData().stream()
        .filter(item -> item.getProperty().equals(property)).findFirst().orElseThrow();
    return metadata.getStyleableProperty(node).getValue();
  }
}
