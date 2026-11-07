package com.diyalamp.desktop.fx;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import javafx.application.Platform;

/** Restores AppKit's bundled Dock icon after JavaFX has started. */
final class MacDockIcon {
  private static final System.Logger LOG = System.getLogger(MacDockIcon.class.getName());

  private MacDockIcon() {}

  static void restoreBundledIcon() {
    if (!System.getProperty("os.name", "").startsWith("Mac")
        || System.getProperty("jpackage.app-path", "").isBlank()
        || !Platform.isFxApplicationThread()) {
      return;
    }

    // Glass installs a runtime icon from the ICNS during native startup. Clearing
    // that override lets macOS draw the same bundled icon when the app runs or quits.
    try (Arena arena = Arena.ofConfined()) {
      Linker linker = Linker.nativeLinker();
      SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena);
      FunctionDescriptor lookup = FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
      MethodHandle getClass = linker.downcallHandle(objc.findOrThrow("objc_getClass"), lookup);
      MethodHandle selector = linker.downcallHandle(objc.findOrThrow("sel_registerName"), lookup);
      MemorySegment send = objc.findOrThrow("objc_msgSend");
      MethodHandle sendGetter = linker.downcallHandle(send, FunctionDescriptor.of(
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      MethodHandle sendSetter = linker.downcallHandle(send, FunctionDescriptor.ofVoid(
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

      MemorySegment appClass = (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSApplication"));
      MemorySegment shared = (MemorySegment) selector.invokeExact(arena.allocateFrom("sharedApplication"));
      MemorySegment app = (MemorySegment) sendGetter.invokeExact(appClass, shared);
      MemorySegment icon = (MemorySegment) selector.invokeExact(arena.allocateFrom("setApplicationIconImage:"));
      sendSetter.invokeExact(app, icon, MemorySegment.NULL);
    } catch (VirtualMachineError failure) {
      throw failure;
    } catch (Throwable failure) {
      LOG.log(System.Logger.Level.WARNING, "Could not restore the bundled macOS Dock icon", failure);
    }
  }
}
