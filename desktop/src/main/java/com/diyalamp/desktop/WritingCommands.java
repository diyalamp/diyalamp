package com.diyalamp.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;

/** Native-menu alternatives to every formatting control and the slash-command popup. */
final class WritingCommands {
  static int shortcutMask() {
    return GraphicsEnvironment.isHeadless()
        ? InputEvent.CTRL_DOWN_MASK
        : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
  }

  static JMenu menu(Supplier<WritingPane> current) {
    JMenu menu = new JMenu("Format");
    add(menu, current, "Bold", KeyEvent.VK_B, 0, editor -> editor.toggle("bold"));
    add(menu, current, "Italic", KeyEvent.VK_I, 0, editor -> editor.toggle("italic"));
    add(menu, current, "Inline code", KeyEvent.VK_BACK_QUOTE, 0, editor -> editor.toggle("code"));
    add(menu, current, "Insert or edit link…", KeyEvent.VK_K, 0, WritingPane::link);
    menu.addSeparator();
    String[][] blocks = {
      {"Text", "paragraph"},
      {"Heading 1", "heading"},
      {"Heading 2", "heading"},
      {"Heading 3", "heading"},
      {"Bullet list", "bullet"},
      {"Numbered list", "ordered"},
      {"Checklist", "task"},
      {"Quote", "quote"},
      {"Code block", "code"},
      {"Divider", "divider"}
    };
    for (String[] block : blocks) {
      int level = block[0].startsWith("Heading") ? Integer.parseInt(block[0].substring(8)) : 1;
      int key = block[0].startsWith("Heading") ? KeyEvent.VK_0 + level : 0;
      add(
          menu,
          current,
          block[0],
          key,
          key == 0 ? 0 : InputEvent.ALT_DOWN_MASK,
          editor -> {
            editor.block(block[1], level);
            if (block[1].equals("divider")) editor.replaceSelection("────────────────────────");
          });
    }
    menu.addSeparator();
    add(
        menu,
        current,
        "Toggle checklist item",
        KeyEvent.VK_ENTER,
        InputEvent.SHIFT_DOWN_MASK,
        WritingPane::toggleChecklist);
    return menu;
  }

  private static void add(
      JMenu menu,
      Supplier<WritingPane> current,
      String title,
      int key,
      int modifiers,
      Consumer<WritingPane> command) {
    JMenuItem item = new JMenuItem(title);
    if (key != 0) item.setAccelerator(KeyStroke.getKeyStroke(key, shortcutMask() | modifiers));
    item.addActionListener(
        event -> {
          WritingPane editor = current.get();
          if (editor != null) command.accept(editor);
        });
    menu.add(item);
  }

  static String helpText() {
    return "⌘N  New note\n"
        + "⌘O  Open folder\n"
        + "⌘S  Save\n"
        + "⌘W  Close tab\n"
        + "⌘B / ⌘I  Bold / italic\n"
        + "⌘K  Insert or edit link\n"
        + "⇧⌘Enter  Toggle checklist item\n"
        + "⌥⌘1 / 2 / 3  Heading levels\n"
        + "⌘F  Find in note\n"
        + "⌘Z / ⇧⌘Z  Undo / redo\n"
        + "⇧⌘B  Show or hide notes sidebar\n"
        + "⌘\\  Side by side\n\n"
        + "All block and formatting commands are also in the Format menu.\n"
        + "Type / on an empty line for blocks.\n"
        + "Type # then Space for a heading.\n"
        + "Double-click a checklist line or use ⇧⌘Enter to toggle it.\n\n"
        + "Tab / Shift Tab  Move between controls\n"
        + "Control Tab / Control Shift Tab  Leave the writing page";
  }

  private WritingCommands() {}
}
