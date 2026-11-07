package com.diyalamp.desktop.fx;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.stage.Stage;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Keeps AppKit's window controls in the same Focus transition as the JavaFX toolbar. */
final class MacWindowButtons {
  private static final System.Logger LOG = System.getLogger(MacWindowButtons.class.getName());
  private final Arena arena = Arena.ofAuto();
  private final MethodHandle getObject, getButton, setAlpha;
  private final MemorySegment application, keyWindow, title, utf8String, standardWindowButton, setAlphaValue;
  private boolean failed;

  private MacWindowButtons() throws Throwable {
    Linker linker = Linker.nativeLinker();
    SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena);
    FunctionDescriptor lookup = FunctionDescriptor.of(ADDRESS, ADDRESS);
    MethodHandle objcClass = linker.downcallHandle(objc.findOrThrow("objc_getClass"), lookup);
    MethodHandle selector = linker.downcallHandle(objc.findOrThrow("sel_registerName"), lookup);
    MemorySegment send = objc.findOrThrow("objc_msgSend");
    getObject = linker.downcallHandle(send, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    getButton = linker.downcallHandle(send, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    setAlpha = linker.downcallHandle(send, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE));
    MemorySegment appClass = (MemorySegment) objcClass.invokeExact(arena.allocateFrom("NSApplication"));
    application = (MemorySegment) getObject.invokeExact(appClass, selector(selector, "sharedApplication"));
    keyWindow = selector(selector, "keyWindow");
    title = selector(selector, "title");
    utf8String = selector(selector, "UTF8String");
    standardWindowButton = selector(selector, "standardWindowButton:");
    setAlphaValue = selector(selector, "setAlphaValue:");
  }

  private MemorySegment selector(MethodHandle lookup, String name) throws Throwable {
    return (MemorySegment) lookup.invokeExact(arena.allocateFrom(name));
  }

  static void bind(Stage stage, ReadOnlyDoubleProperty progress) {
    try {
      MacWindowButtons bridge = new MacWindowButtons();
      Runnable repaint = () -> bridge.paint(stage, 1 - FxZenChrome.intensity(progress.get()));
      progress.addListener((observable, previous, value) -> repaint.run());
      stage.focusedProperty().addListener((observable, previous, focused) -> {
        if (focused) Platform.runLater(repaint);
      });
      stage.fullScreenProperty().addListener((observable, previous, fullscreen) -> Platform.runLater(repaint));
    } catch (VirtualMachineError failure) {
      throw failure;
    } catch (Throwable failure) {
      LOG.log(System.Logger.Level.WARNING, "Could not connect Focus mode to the macOS window buttons", failure);
    }
  }

  private void paint(Stage stage, double alpha) {
    if (failed || !stage.isShowing() || !stage.isFocused()) return;
    try {
      MemorySegment window = (MemorySegment) getObject.invokeExact(application, keyWindow);
      if (window.equals(MemorySegment.NULL)) return;
      // A native chooser or dialog may temporarily own the key window.
      MemorySegment name = (MemorySegment) getObject.invokeExact(window, title);
      MemorySegment text = (MemorySegment) getObject.invokeExact(name, utf8String);
      if (text.equals(MemorySegment.NULL) || !stage.getTitle().equals(text.reinterpret(4096).getString(0))) return;
      // NSWindowCloseButton, NSWindowMiniaturizeButton, NSWindowZoomButton.
      for (long type = 0; type < 3; type++) {
        MemorySegment button = (MemorySegment) getButton.invokeExact(window, standardWindowButton, type);
        if (!button.equals(MemorySegment.NULL)) setAlpha.invokeExact(button, setAlphaValue, alpha);
      }
    } catch (VirtualMachineError failure) {
      throw failure;
    } catch (Throwable failure) {
      failed = true;
      LOG.log(System.Logger.Level.WARNING, "Could not dim the macOS window buttons", failure);
    }
  }
}
