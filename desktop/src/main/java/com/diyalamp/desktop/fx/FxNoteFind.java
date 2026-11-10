package com.diyalamp.desktop.fx;

import atlantafx.base.controls.CustomTextField;
import java.util.regex.Pattern;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;
import org.fxmisc.richtext.StyledTextArea;

/** A nonmodal Find row using AtlantaFX's text input and the editor's native selection. */
final class FxNoteFind extends GridPane {
  private static final double SINGLE_ROW_WIDTH = 360;
  private enum Step { COUNT, CURRENT, NEXT, PREVIOUS }

  private final StyledTextArea<?, ?> editor;
  private final CustomTextField query = new CustomTextField();
  private final Label status = new Label();
  private final Button previous = FxTheme.iconButton("chevron-down", "Previous match", () -> navigate(true));
  private final Button next = FxTheme.iconButton("chevron-down", "Next match", () -> navigate(false));
  private final Button close = FxTheme.iconButton("x", "Close find", this::close);
  private final HBox actions = new HBox(4, status, previous, next, close);
  private final FadeTransition fade = new FadeTransition(Duration.millis(140), this);
  private final PauseTransition recount = new PauseTransition(Duration.millis(85));
  private boolean open;

  FxNoteFind(StyledTextArea<?, ?> editor) {
    this.editor = editor;
    getStyleClass().add("note-find");
    setAlignment(Pos.CENTER_RIGHT);
    setHgap(8);
    setVgap(4);
    setMinWidth(0);
    setVisible(false);
    setOpacity(0);
    managedProperty().bind(visibleProperty());
    fade.setInterpolator(FxMotion.SIDEBAR_EASE);
    recount.setOnFinished(event -> update(Step.COUNT));

    query.getStyleClass().addAll("note-search-field", "note-find-query");
    query.setPromptText("Find in note…");
    query.setAccessibleText("Find in note");
    query.setAccessibleHelp("Enter finds the next match, Shift Enter finds the previous match, and Escape closes Find.");
    query.setMinWidth(0);
    Node magnifier = FxTheme.icon("search");
    magnifier.setMouseTransparent(true);
    query.setLeft(magnifier);
    query.textProperty().addListener((observable, before, after) -> {
      if (open) update(Step.CURRENT);
    });
    query.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
      if (event.getCode() == KeyCode.ENTER) {
        navigate(event.isShiftDown());
        event.consume();
      }
    });
    addEventFilter(KeyEvent.KEY_PRESSED, event -> {
      if (event.getCode() == KeyCode.ESCAPE && open) {
        close();
        event.consume();
      }
    });

    previous.getGraphic().setRotate(180);
    for (Button button : new Button[] {previous, next, close}) button.getStyleClass().add("file-row-action");
    status.getStyleClass().add("note-find-status");
    status.setMinWidth(0);
    actions.setAlignment(Pos.CENTER_RIGHT);
    actions.setMinWidth(0);
    GridPane.setHgrow(query, Priority.ALWAYS);
    GridPane.setHalignment(query, HPos.RIGHT);
    GridPane.setHalignment(actions, HPos.RIGHT);
    add(query, 0, 0);
    add(actions, 1, 0);
    widthProperty().addListener((observable, before, width) -> arrange(width.doubleValue()));
    arrange(getWidth());
    update(Step.COUNT);
  }

  private void arrange(double width) {
    boolean compact = width < SINGLE_ROW_WIDTH;
    GridPane.setColumnSpan(query, compact ? 2 : 1);
    GridPane.setColumnSpan(actions, compact ? 2 : 1);
    GridPane.setColumnIndex(actions, compact ? 0 : 1);
    GridPane.setRowIndex(actions, compact ? 1 : 0);
  }

  boolean isOpen() { return open; }

  void show() {
    if (!open) {
      String selected = editor.getSelectedText();
      if (!selected.isBlank() && selected.length() <= 160 && selected.indexOf('\n') < 0) query.setText(selected);
      open = true;
      setMouseTransparent(false);
      setVisible(true);
      animate(1);
      update(Step.CURRENT);
    }
    query.requestFocus();
    query.selectAll();
  }

  void close() {
    if (!open) return;
    open = false;
    recount.stop();
    setMouseTransparent(true);
    editor.requestFocus();
    animate(0);
  }

  void dispose() {
    fade.stop();
    recount.stop();
  }

  void textChanged() {
    if (open) recount.playFromStart();
  }

  private void animate(double target) {
    double opacity = getOpacity();
    fade.stop();
    if (FxMotion.reduced() || getScene() == null || opacity == target) {
      setOpacity(target);
      setVisible(open);
      return;
    }
    fade.setFromValue(opacity);
    fade.setToValue(target);
    fade.setOnFinished(event -> setVisible(open));
    fade.playFromStart();
  }

  private void navigate(boolean backward) { update(backward ? Step.PREVIOUS : Step.NEXT); }

  private void update(Step step) {
    recount.stop();
    String term = query.getText();
    if (term.isBlank()) {
      showCount(0, 0, false);
      return;
    }
    IndexRange selection = editor.getSelection();
    int anchor = step == Step.NEXT ? selection.getEnd() : selection.getStart();
    var matches = literal(term).matcher(editor.getText());
    int count = 0, current = 0;
    int firstStart = 0, firstEnd = 0, lastStart = 0, lastEnd = 0;
    int chosenStart = 0, chosenEnd = 0;
    // Match offsets refer to the original rendered text, including Unicode.
    // Keep only the active and wraparound ranges even when a long note has many hits.
    while (matches.find()) {
      if (++count == 1) {
        firstStart = matches.start();
        firstEnd = matches.end();
      }
      lastStart = matches.start();
      lastEnd = matches.end();
      boolean choose = switch (step) {
        case COUNT -> lastStart == selection.getStart() && lastEnd == selection.getEnd();
        case PREVIOUS -> lastEnd <= anchor;
        case CURRENT, NEXT -> current == 0 && lastStart >= anchor;
      };
      if (choose) {
        chosenStart = lastStart;
        chosenEnd = lastEnd;
        current = count;
      }
    }
    if (step != Step.COUNT && count > 0) {
      if (current == 0) {
        boolean backward = step == Step.PREVIOUS;
        current = backward ? count : 1;
        chosenStart = backward ? lastStart : firstStart;
        chosenEnd = backward ? lastEnd : firstEnd;
      }
      editor.selectRange(chosenStart, chosenEnd);
      editor.requestFollowCaret();
    }
    showCount(current, count, true);
  }

  private void showCount(int current, int count, boolean searched) {
    status.setText(!searched ? "" : count == 0 ? "No matches" : current == 0
        ? count + (count == 1 ? " match" : " matches") : current + " of " + count);
    status.setAccessibleText(current == 0 ? status.getText() : "Match " + status.getText());
    previous.setDisable(count == 0);
    next.setDisable(count == 0);
  }

  static Pattern literal(String term) {
    return Pattern.compile(term, Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }
}
