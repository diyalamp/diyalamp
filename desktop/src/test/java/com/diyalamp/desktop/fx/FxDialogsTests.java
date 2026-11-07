package com.diyalamp.desktop.fx;

import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Standard JavaFX dialogs keep their results, cancel behavior, and action filters. */
public final class FxDialogsTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      try { FxTheme.setup(); dialogs(); }
      catch (Throwable error) { failure.set(error); }
      finally { done.countDown(); }
    });
    if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("Dialog tests timed out");
    Platform.exit();
    if (failure.get() != null) throw new AssertionError("Dialog regression", failure.get());
    System.out.println("Passed " + checks + " JavaFX dialog checks.");
  }

  private static void dialogs() {
    System.setProperty("diyalamp.reduceMotion", "true");
    BorderPane ownerRoot = new BorderPane();
    Color accent = Color.web("#BA929B"); FxTheme.accent(ownerRoot, accent);
    Stage owner = new Stage(); owner.setScene(new Scene(ownerRoot, 600, 400)); owner.show();
    TextInputDialog input = new TextInputDialog("Original note");
    input.setTitle("Rename note"); input.setHeaderText(null); input.setContentText("Note name");
    FxDialogs.style(input, owner); FxDialogs.style(input, owner);
    check(input.getOwner() == owner, "Styled dialogs must retain their owner window");
    check(input.getDialogPane().getStyleClass().stream().filter("diyalamp-dialog"::equals).count() == 1,
        "Styling must be idempotent");
    check(FxTheme.accentOf(input.getDialogPane()).equals(accent), "Dialog controls must use the owner's current displayed accent");
    check(input.getEditor().getText().equals("Original note") && input.getHeaderText().equals("Rename note"),
        "The window title moves into the surface while preserving the initial input");
    input.show(); input.getDialogPane().applyCss(); input.getDialogPane().layout();
    Button accept = (Button) input.getDialogPane().lookupButton(ButtonType.OK);
    Button cancel = (Button) input.getDialogPane().lookupButton(ButtonType.CANCEL);
    check(accept.isDefaultButton() && cancel.isCancelButton(), "Standard Enter and Escape roles must remain intact");
    check(accept.getStyleClass().contains("accent"), "Affirmative actions must use the maintained AtlantaFX accent style");
    check(accept.getFont().getName().equals("Inter Regular") && accept.getFont().getSize() == 16,
        "Action text must use the shared Inter font and 16-point size");
    check(input.getEditor().getFont().getSize() == 16 && input.getEditor().getHeight() == 36
        && accept.getHeight() == 36,
        "Dialog fields and actions must use the shared 36-pixel control height");
    check(input.getDialogPane().getGraphic() == null, "The dialog must not add an oversized icon above its content");
    check(input.getDialogPane().getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius() == 12,
        "Actual laid-out dialog surfaces must have the shared soft rounding");
    Stage window = (Stage) input.getDialogPane().getScene().getWindow();
    if (Platform.isSupported(ConditionalFeature.TRANSPARENT_WINDOW)) {
      check(window.getStyle() == StageStyle.TRANSPARENT,
          "The dialog must drop native window chrome when transparent windows are supported");
      var rendered = window.getScene().snapshot(null).getPixelReader();
      check(rendered.getColor(0, 0).getOpacity() == 0 && rendered.getColor(210, 12).isOpaque(),
          "The scene must render transparent corners around its opaque dark surface");
    } else check(window.getStyle() == StageStyle.UNDECORATED,
        "Platforms without window transparency must still use an undecorated dark surface");
    Label prompt = (Label) input.getDialogPane().getContent().lookup(".label");
    check(prompt != null && prompt.getLabelFor() == input.getEditor()
            && prompt.getBoundsInParent().getMaxY() < input.getEditor().getBoundsInParent().getMinY(),
        "The visible name label must identify the field and sit above it");
    check(input.getEditor().getBackground().getFills().size() == 1 && input.getEditor().getEffect() == null,
        "The input must render one flat fill without an inset effect");
    check(input.getDialogPane().getOpacity() == 1,
        "The dialog must open fully visible when reduced motion is enabled");
    input.getEditor().setText("Renamed note"); press(input, KeyCode.ENTER);
    check(input.getResult().equals("Renamed note"), "Standard input acceptance must still return the edited value");
    input.setResult(null); input.show(); press(input, KeyCode.ESCAPE);
    check(!input.isShowing() && input.getResult() == null,
        "Escape must dismiss the styled input without accepting its text");

    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "A fictional confirmation", ButtonType.CANCEL, ButtonType.OK);
    alert.setHeaderText(null); FxDialogs.style(alert, owner);
    alert.show(); alert.getDialogPane().applyCss(); alert.getDialogPane().layout();
    check(alert.getDialogPane().getGraphic() == null, "Alert theme graphics must not reappear after CSS is applied");
    ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
    check(alert.getResult() == ButtonType.CANCEL, "Cancel must preserve the original confirmation result");

    Dialog<ButtonType> guarded = new Dialog<>(); guarded.initModality(Modality.NONE);
    guarded.getDialogPane().setContent(new Label("Fictional update"));
    FxDialogs.style(guarded, owner);
    guarded.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.APPLY);
    Button apply = (Button) guarded.getDialogPane().lookupButton(ButtonType.APPLY);
    check(apply.getStyleClass().contains("accent"), "Actions added after styling must receive the same treatment");
    check(guarded.getModality() == Modality.NONE, "Styling cannot change an updater's nonmodal behavior");
    EventHandler<ActionEvent> guard = ActionEvent::consume;
    apply.addEventFilter(ActionEvent.ACTION, guard);
    guarded.show(); apply.fire();
    check(guarded.isShowing() && guarded.getResult() == null,
        "An existing save/install safety filter must still be able to prevent dialog acceptance");
    apply.removeEventFilter(ActionEvent.ACTION, guard); apply.fire();
    check(guarded.getResult() == ButtonType.APPLY, "The original action must complete after its safety condition succeeds");
    Color hiddenColor = FxTheme.accentOf(guarded.getDialogPane());
    Color nextColor = Color.web("#A497BD"); FxTheme.accent(ownerRoot, nextColor);
    check(FxTheme.accentOf(guarded.getDialogPane()).equals(hiddenColor), "A hidden dialog must detach its owner-accent listener");
    guarded.show();
    check(FxTheme.accentOf(guarded.getDialogPane()).equals(nextColor), "A reused modeless dialog must refresh its accent when reopened");
    Color liveColor = Color.web("#7A9A85"); FxTheme.accent(ownerRoot, liveColor);
    check(FxTheme.accentOf(guarded.getDialogPane()).equals(liveColor), "Visible modeless dialogs must follow live owner accent changes");
    ((Button) guarded.getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
    owner.close();
  }

  private static void press(TextInputDialog input, KeyCode key) {
    Event.fireEvent(input.getEditor(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
  }
}
