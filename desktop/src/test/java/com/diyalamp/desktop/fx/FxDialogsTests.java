package com.diyalamp.desktop.fx;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
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
    check(input.getEditor().getText().equals("Original note") && input.getHeaderText() == null,
        "Styling must not rewrite input or add a decorative heading");
    input.show(); input.getDialogPane().applyCss(); input.getDialogPane().layout();
    Button accept = (Button) input.getDialogPane().lookupButton(ButtonType.OK);
    Button cancel = (Button) input.getDialogPane().lookupButton(ButtonType.CANCEL);
    check(accept.isDefaultButton() && cancel.isCancelButton(), "Standard Enter and Escape roles must remain intact");
    check(accept.getStyleClass().contains("accent"), "Affirmative actions must use the maintained AtlantaFX accent style");
    check(accept.getFont().getFamily().equals(Font.getDefault().getFamily()) && accept.getFont().getSize() == 14,
        "Action text must resolve the native system family at the shared 14-point size");
    check(input.getEditor().getFont().getSize() == 14 && input.getEditor().getHeight() >= 38,
        "Text fields must use the shared readable size and control height");
    check(input.getDialogPane().getGraphic() == null, "The dialog must not add an oversized icon above its content");
    check(input.getDialogPane().getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius() == 14,
        "Actual laid-out dialog surfaces must have the shared soft rounding");
    input.getEditor().setText("Renamed note"); accept.fire();
    check(input.getResult().equals("Renamed note"), "Standard input acceptance must still return the edited value");

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
}
