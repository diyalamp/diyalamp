package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Breadcrumbs;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

/** A pane's note and enclosing folder, using AtlantaFX's keyboard-accessible breadcrumbs. */
final class FxDocumentLocation extends Breadcrumbs<Path> {
  private final Tooltip location = new Tooltip();
  private Path workspace, note;
  private boolean showWorkspace;

  FxDocumentLocation(Consumer<Path> navigate) {
    getStyleClass().add("document-breadcrumb");
    setMinWidth(0);
    setMaxWidth(Double.MAX_VALUE);
    setTooltip(location);
    setAutoNavigationEnabled(false);
    setOnCrumbAction(event -> navigate.accept(event.getSelectedCrumb().getValue()));
    setDividerFactory(crumb -> {
      if (crumb == null || crumb.isLast()) return null;
      StackPane divider = new StackPane(FxTheme.icon("chevron-right", 12));
      divider.setPrefWidth(16);
      return divider;
    });
    setCrumbFactory(crumb -> {
      Path path = crumb.getValue();
      String title = path.getFileName() == null ? path.toString() : path.getFileName().toString();
      if (showWorkspace && !crumb.isLast() && !path.equals(workspace) && !workspace.equals(path.getParent()))
        title = "… " + title;
      final double share = showWorkspace ? (crumb.isLast() ? .50 : .25) : (crumb.isLast() ? .64 : .36);
      Button button = new Button(title);
      button.getStyleClass().addAll("flat", "document-crumb");
      button.setAlignment(Pos.CENTER_LEFT);
      button.setTextOverrun(OverrunStyle.ELLIPSIS);
      button.setTooltip(new Tooltip(path.toString()));
      button.setAccessibleText((crumb.isLast() ? "Current note, " : "Reveal folder, ") + path);
      // The stock skin lays out children at their preferred sizes.
      Text measurement = new Text(title);
      measurement.fontProperty().bind(button.fontProperty());
      button.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> {
        double available = Math.max(0, getWidth() - (showWorkspace ? 34 : 18));
        double insets = button.getInsets().getLeft() + button.getInsets().getRight();
        return Math.min(Math.ceil(measurement.getLayoutBounds().getWidth() + insets) + 2,
            Math.max(28, Math.floor(available * share)));
      }, widthProperty(), measurement.layoutBoundsProperty(), button.insetsProperty()));
      return button;
    });
    widthProperty().addListener((observable, previous, width) -> updateCompactMode());
  }

  void show(Path workspace, Path note) {
    if (Objects.equals(this.workspace, workspace) && Objects.equals(this.note, note)) return;
    this.workspace = workspace;
    this.note = note;
    showWorkspace = usesWorkspaceCrumb();
    location.setText(note == null ? "" : note.toString());
    setAccessibleText(note == null ? "Document location" : "Document location, " + note);
    rebuild();
  }

  private boolean usesWorkspaceCrumb() {
    return note != null && !Objects.equals(workspace, note.getParent()) && getWidth() >= 420;
  }

  private void updateCompactMode() {
    boolean next = usesWorkspaceCrumb();
    if (next != showWorkspace) {
      showWorkspace = next;
      rebuild();
    }
  }

  private void rebuild() {
    if (note == null) setSelectedCrumb(null);
    else if (showWorkspace) setSelectedCrumb(buildTreeModel(workspace, note.getParent(), note));
    else setSelectedCrumb(buildTreeModel(note.getParent(), note));
  }
}
