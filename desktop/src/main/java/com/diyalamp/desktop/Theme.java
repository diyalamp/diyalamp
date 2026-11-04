package com.diyalamp.desktop;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import java.awt.*;
import java.io.*;
import javax.swing.*;

public final class Theme {
  public static final Color PAPER = new Color(30, 30, 30),
      SIDEBAR = new Color(23, 23, 23),
      INK = new Color(242, 242, 242),
      MUTED = new Color(178, 178, 178),
      LINE = new Color(43, 43, 43),
      RED = new Color(238, 0, 0);
  private static Font base = new Font("Helvetica Neue", Font.PLAIN, 15);

  public static void setup() {
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
    System.setProperty("apple.laf.useScreenMenuBar", "true");
    System.setProperty("apple.awt.application.name", "diyalamp");
    try (InputStream in = Theme.class.getResourceAsStream("/Inter.ttf")) {
      if (in != null) {
        base = Font.createFont(Font.TRUETYPE_FONT, in);
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(base);
      }
    } catch (Exception ignored) {
    }
    com.formdev.flatlaf.FlatLaf.setSystemColorGetter(
        name -> ("accent".equals(name) || "highlight".equals(name)) ? RED : null);
    com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(java.util.Map.of("@accentColor", "#EE0000"));
    FlatMacDarkLaf.setup();
    UIManager.put("defaultFont", font(14));
    UIManager.put("Component.arc", 8);
    UIManager.put("Button.arc", 8);
    UIManager.put("TextComponent.arc", 8);
    UIManager.put("Component.focusWidth", 1);
    UIManager.put("TabbedPane.showTabSeparators", false);
    UIManager.put("TabbedPane.tabHeight", 38);
    UIManager.put("TabbedPane.tabInsets", new Insets(6, 12, 6, 12));
    UIManager.put("TabbedPane.tabType", "card");
    UIManager.put("TabbedPane.cardTabArc", 8);
    UIManager.put("TabbedPane.showContentSeparator", false);
    UIManager.put("Button.toolbar.spacingInsets", new Insets(4, 4, 4, 4));
    UIManager.put("ScrollBar.showButtons", false);
    UIManager.put("TabbedPane.contentBorderInsets", new Insets(0, 0, 0, 0));
    UIManager.put("ScrollBar.width", 9);
    UIManager.put("Panel.background", PAPER);
    UIManager.put("TextField.background", PAPER);
    UIManager.put("List.selectionBackground", new Color(48, 48, 48));
    UIManager.put("List.selectionForeground", INK);
    UIManager.put("Label.foreground", INK);
    UIManager.put("List.foreground", INK);
    UIManager.put("TextPane.foreground", INK);
    UIManager.put("TextField.foreground", INK);
    UIManager.put("TextField.caretForeground", INK);
    UIManager.put("Component.focusColor", RED);
    UIManager.put("Component.accentColor", RED);
    UIManager.put("TabbedPane.underlineColor", RED);
    UIManager.put("TabbedPane.inactiveUnderlineColor", RED);
    UIManager.put("TabbedPane.selectedBackground", PAPER);
    UIManager.put("TabbedPane.background", SIDEBAR);
    UIManager.put("TabbedPane.hoverColor", new Color(40, 40, 40));
    UIManager.put("Button.background", new Color(36, 36, 36));
    UIManager.put("Button.foreground", INK);
    UIManager.put("Button.default.background", RED);
    UIManager.put("Button.default.foreground", Color.WHITE);
  }

  public static Font font(float size) {
    return base.deriveFont(Font.PLAIN, size);
  }

  public static JButton button(String text, Runnable action) {
    JButton b = new JButton(text);
    b.setFocusable(false);
    b.setMargin(new Insets(8, 12, 8, 12));
    b.addActionListener(e -> action.run());
    return b;
  }

  public static JButton tool(String text, String label, Runnable action) {
    JButton b = button(text, action);
    b.putClientProperty("JButton.buttonType", "toolBarButton");
    b.setFont(
        font(13)
            .deriveFont(
                text.equals("B") ? Font.BOLD : text.equals("I") ? Font.ITALIC : Font.PLAIN));
    b.setMargin(new Insets(5, 9, 5, 9));
    b.setToolTipText(label);
    b.getAccessibleContext().setAccessibleName(label);
    return b;
  }

  public static ImageIcon lamp(int size) {
    java.net.URL u = Theme.class.getResource("/lamp.png");
    return u == null
        ? null
        : new ImageIcon(
            new ImageIcon(u).getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH));
  }

  private Theme() {}
}
