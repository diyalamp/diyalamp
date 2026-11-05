package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.DesktopLauncher;
import com.diyalamp.desktop.update.AppUpdater;
import java.io.IOException;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** Explicit update workflow. Checking never downloads or installs an application. */
final class FxUpdates {
  private final Stage owner;
  private final BooleanSupplier save;
  private final Runnable quit;
  private final Consumer<String> status;
  private Task<?> worker;
  private Dialog<?> progress, ready;
  private AppUpdater updater;
  private AppUpdater.PreparedUpdate prepared;
  private boolean installing;

  FxUpdates(Stage owner, BooleanSupplier save, Runnable quit, Consumer<String> status) {
    this.owner = owner; this.save = save; this.quit = quit; this.status = status;
  }

  void check() {
    if (ready != null && ready.isShowing()) {
      ready.getDialogPane().getScene().getWindow().requestFocus(); return;
    }
    if (worker != null && !worker.isDone()) {
      if (progress != null) progress.getDialogPane().getScene().getWindow().requestFocus();
      return;
    }
    Task<Offer> task = new Task<>() {
      @Override protected Offer call() throws Exception {
        updateMessage("Checking the latest stable release…");
        AppUpdater service = AppUpdater.create();
        return new Offer(service, service.check(DesktopLauncher.VERSION));
      }
    };
    task.setOnSucceeded(event -> {
      closeProgress();
      Offer offer = task.getValue(); updater = offer.updater();
      if (offer.release().isEmpty()) {
        information("diyalamp is up to date", "You are using version " + DesktopLauncher.VERSION + ".");
        status.accept("Up to date · " + DesktopLauncher.VERSION); return;
      }
      AppUpdater.Release release = offer.release().get();
      ButtonType download = new ButtonType("Download update", ButtonBar.ButtonData.OK_DONE);
      Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
          "Version " + release.version() + " is available. Download its verified Mac installer? "
              + "The running app and your notes stay unchanged until you choose Install and Restart.",
          ButtonType.CANCEL, download);
      alert.setTitle("Update available"); alert.setHeaderText(null); FxDialogs.style(alert, owner);
      alert.showAndWait().filter(download::equals).ifPresent(ignored -> prepare(release));
    });
    run(task, "Check for updates");
  }

  private record Offer(AppUpdater updater, Optional<AppUpdater.Release> release) {}

  private void prepare(AppUpdater.Release release) {
    Task<AppUpdater.PreparedUpdate> task = new Task<>() {
      @Override protected AppUpdater.PreparedUpdate call() throws Exception {
        return updater.prepare(release, this::updateMessage);
      }
    };
    task.setOnSucceeded(event -> {
      closeProgress(); prepared = task.getValue(); showReady();
    });
    run(task, "Downloading update");
  }

  private void run(Task<?> task, String title) {
    worker = task;
    Label message = new Label(); message.textProperty().bind(task.messageProperty());
    message.setWrapText(true); message.setMaxWidth(320);
    ProgressIndicator indicator = new ProgressIndicator(); indicator.setPrefSize(26, 26);
    HBox line = new HBox(14, indicator, message); line.setAlignment(Pos.CENTER_LEFT);
    VBox content = new VBox(line); content.setPrefWidth(390);
    Dialog<ButtonType> dialog = new Dialog<>(); dialog.initModality(Modality.NONE);
    dialog.setTitle(title); dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
    FxDialogs.style(dialog, owner);
    dialog.setOnCloseRequest(event -> { if (!task.isDone()) task.cancel(true); });
    progress = dialog;
    task.setOnFailed(event -> {
      closeProgress();
      Throwable error = task.getException();
      information("Update could not be completed", error == null ? "Please try again later." : error.getMessage());
      status.accept("Update not installed");
    });
    task.setOnCancelled(event -> { closeProgress(); status.accept("Update canceled · current app unchanged"); });
    dialog.show(); Thread.ofVirtual().name("diyalamp-update").start(task);
  }

  private void showReady() {
    ButtonType install = new ButtonType("Install and Restart", ButtonBar.ButtonData.OK_DONE);
    Dialog<ButtonType> dialog = new Dialog<>(); dialog.initModality(Modality.NONE);
    dialog.setTitle("Ready to update");
    Label description = new Label("Version " + prepared.version()
        + " is downloaded and verified. Your notes will be saved before the app restarts. "
        + "If a note has a save conflict, resolve it first and try again.");
    description.setWrapText(true);
    VBox content = new VBox(description); content.setPrefWidth(390);
    dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, install);
    FxDialogs.style(dialog, owner);
    Button installButton = (Button) dialog.getDialogPane().lookupButton(install);
    installButton.addEventFilter(ActionEvent.ACTION, event -> {
      try {
        installing = updater.installAndRestart(prepared, save, () -> {
          installing = true; quit.run();
        });
        if (!installing) event.consume();
      } catch (IOException error) {
        event.consume(); information("Update was not installed", error.getMessage());
      }
    });
    dialog.setOnHidden(event -> { ready = null; if (!installing) discardPrepared(); });
    ready = dialog; dialog.show();
  }

  private void discardPrepared() {
    AppUpdater.PreparedUpdate staged = prepared; AppUpdater service = updater; prepared = null;
    if (staged == null || service == null) return;
    Thread.ofVirtual().name("diyalamp-update-cleanup").start(() -> {
      try { service.discard(staged); }
      catch (IOException error) { System.err.println("Could not remove a staged update: " + error.getMessage()); }
    });
  }

  void close() {
    if (worker != null && !worker.isDone()) worker.cancel(true);
    if (!installing) discardPrepared();
  }

  private void closeProgress() {
    Dialog<?> current = progress; progress = null; if (current != null) current.close();
  }

  private void information(String title, String message) {
    Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
    alert.setTitle(title); alert.setHeaderText(null); FxDialogs.style(alert, owner); alert.showAndWait();
  }
}
