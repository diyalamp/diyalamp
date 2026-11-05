package com.diyalamp.desktop;

import com.formdev.flatlaf.themes.FlatMacLightLaf;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.font.FontRenderContext;
import java.util.Locale;
import javax.swing.*;
import javax.swing.text.*;

/** Checks the shipped light appearance and fonts through actual Swing documents and actions. */
public final class NativeThemeTests {
  private static int checks;

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            Theme.setup();
            nativeFontsAndResources();
            lightAppearanceAndContrast();
            loadedMarkdownFonts();
            fontChangesWhileWriting();
            mixedBlocksAndNewTyping();
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    System.out.println(
        "Passed "
            + checks
            + " native theme checks. Text: "
            + Theme.font(14).getPSName()
            + "; code: "
            + Theme.codeFont(16).getPSName()
            + ".");
  }

  private static void nativeFontsAndResources() {
    Font text = Theme.font(16), code = Theme.codeFont(16);
    if (System.getProperty("os.name").startsWith("Mac")) {
      Font installedSystem = new Font(".AppleSystemUIFont", Font.PLAIN, 16);
      check(
          installedSystem.getFamily(Locale.ROOT).equals(".AppleSystemUIFont"),
          "The supported Mac runtime must resolve its installed system-font alias");
      check(
          text.getPSName().equals(installedSystem.getPSName()),
          "Text must use the actual macOS system font, not a silently substituted family");
      check(code.getFamily(Locale.ROOT).equals("Menlo"), "Code must use installed Menlo on macOS");
    }
    check(!text.getFamily().equals("Inter"), "Text must not request the removed bundled font");
    check(
        Theme.class.getResource("/Inter.ttf") == null
            && Theme.class.getResource("/Inter-LICENSE.txt") == null,
        "Removed font resources must not remain on the compiled application classpath");
    for (JComponent control :
        new JComponent[] {new JLabel(), new JButton(), new JTextField(), new JList<>(), new JTabbedPane()})
      check(
          control.getFont().getFamily().equals(text.getFamily()),
          "Every app control must use the same system family: " + control.getClass().getSimpleName());
    FontRenderContext context = new FontRenderContext(null, true, true);
    check(
        Math.abs(code.getStringBounds("iiii", context).getWidth()
                - code.getStringBounds("WWWW", context).getWidth())
            < .01,
        "Code must use equal-width glyph advances");
  }

  private static void lightAppearanceAndContrast() {
    check(UIManager.getLookAndFeel() instanceof FlatMacLightLaf, "App must install the light Mac theme");
    check(
        "NSAppearanceNameAqua".equals(System.getProperty("apple.awt.application.appearance")),
        "The native app appearance must stay light even when macOS uses dark appearance");
    check(Theme.PAPER.equals(Color.WHITE), "Writing pages must be white");
    check(contrast(Theme.INK, Theme.PAPER) >= 7, "Body text must have strong contrast on white");
    check(contrast(Theme.MUTED, Theme.SIDEBAR) >= 4.5, "Sidebar labels must remain readable");
    check(contrast(Theme.MUTED, Theme.RAIL) >= 4.5, "Rail icons must remain readable");
    check(contrast(Theme.RED, Theme.PAPER) >= 4.5, "Red links must meet normal-text contrast");
    check(contrast(Theme.INK, Theme.CODE) >= 7, "Code text must remain readable");
    check(
        UIManager.getColor("TextPane.selectionForeground").equals(Theme.INK),
        "Selected text must use dark ink on the light selection");
  }

  private static double contrast(Color a, Color b) {
    double x = luminance(a), y = luminance(b);
    return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
  }

  private static double luminance(Color color) {
    double[] rgb = {color.getRed() / 255d, color.getGreen() / 255d, color.getBlue() / 255d};
    for (int i = 0; i < rgb.length; i++)
      rgb[i] = rgb[i] <= .04045 ? rgb[i] / 12.92 : Math.pow((rgb[i] + .055) / 1.055, 2.4);
    return .2126 * rgb[0] + .7152 * rgb[1] + .0722 * rgb[2];
  }

  private static void loadedMarkdownFonts() throws Exception {
    String source =
        "# Heading\nBody **bold** and `inline` text.\n[Link](https://example.com)\n```java\nint answer = 42;\n```\n";
    DefaultStyledDocument document = Markdown.read(source);
    String rendered = document.getText(0, document.getLength());
    for (String phrase : new String[] {"Heading", "Body", "bold", "Link"}) {
      AttributeSet a = document.getCharacterElement(rendered.indexOf(phrase)).getAttributes();
      check(
          document.getFont(a).getFamily().equals(Theme.font(16).getFamily()),
          "Loaded prose must resolve to the system font: " + phrase);
      check(contrast(StyleConstants.getForeground(a), Theme.PAPER) >= 4.5, "Loaded text must be legible");
    }
    for (String phrase : new String[] {"inline", "int answer"})
      check(
          document
              .getFont(document.getCharacterElement(rendered.indexOf(phrase)).getAttributes())
              .getFamily()
              .equals(Theme.codeFont(16).getFamily()),
          "Both inline and fenced code must resolve to the monospace font");
    check(Markdown.write(document).equals(source), "Font changes must not change Markdown source");
  }

  private static void fontChangesWhileWriting() throws Exception {
    DefaultStyledDocument document = Markdown.read("Some **bold** words\n");
    WritingPane editor = new WritingPane(document, () -> {});
    editor.select(0, 4);
    editor.toggle("code");
    check(
        document.getFont(document.getCharacterElement(0).getAttributes()).getFamily()
            .equals(Theme.codeFont(16).getFamily()),
        "Turning on inline code must change the selected text to monospace");
    editor.toggle("code");
    check(
        document.getFont(document.getCharacterElement(0).getAttributes()).getFamily()
            .equals(Theme.font(16).getFamily()),
        "Turning off inline code must restore the system text font");
    editor.select(0, document.getLength() - 1);
    editor.block("code", 1);
    check(
        document.getFont(document.getCharacterElement(0).getAttributes()).getFamily()
            .equals(Theme.codeFont(16).getFamily()),
        "A code block must override a prior explicit prose font");
    editor.block("paragraph", 1);
    check(
        document.getFont(document.getCharacterElement(0).getAttributes()).getFamily()
            .equals(Theme.font(16).getFamily()),
        "Leaving a code block must restore the system font");
    check(
        StyleConstants.isBold(document.getCharacterElement(6).getAttributes()),
        "Block-font changes must preserve existing inline emphasis");
    editor.select(0, 4);
    editor.toggle("code");
    editor.block("heading", 1);
    check(
        document.getFont(document.getCharacterElement(0).getAttributes()).getFamily()
            .equals(Theme.codeFont(16).getFamily()),
        "Changing a block must retain explicit inline-code formatting");
    editor.disposeView();

    DefaultStyledDocument fence = Markdown.read("```java\nfirst\n```\n");
    WritingPane codeEditor = new WritingPane(fence, () -> {});
    codeEditor.setCaretPosition(5);
    codeEditor.getActionMap().get("page-enter").actionPerformed(
        new ActionEvent(codeEditor, ActionEvent.ACTION_PERFORMED, "page-enter"));
    codeEditor.replaceSelection("second");
    check(
        fence.getFont(fence.getCharacterElement(6).getAttributes()).getFamily()
            .equals(Theme.codeFont(16).getFamily()),
        "Code typed after Enter must keep the monospace font");
    check(
        Markdown.write(fence).equals("```java\nfirst\nsecond\n```\n"),
        "Code continuation must still preserve the fenced Markdown");
    codeEditor.disposeView();
  }
  private static void mixedBlocksAndNewTyping() throws Exception {
    DefaultStyledDocument mixed = Markdown.read("`one`\n```java\ntwo\n```\n");
    WritingPane editor = new WritingPane(mixed, () -> {});
    editor.select(0, mixed.getLength() - 1);
    editor.toggle("code");
    check(
        mixed.getFont(mixed.getCharacterElement(0).getAttributes()).getFamily()
            .equals(Theme.font(16).getFamily()),
        "Disabling inline code across blocks must restore the prose paragraph's system font");
    check(
        mixed.getFont(mixed.getCharacterElement(4).getAttributes()).getFamily()
            .equals(Theme.codeFont(16).getFamily()),
        "The same selection must leave a fenced-code paragraph monospace");
    check(
        Markdown.write(mixed).equals("one\n```java\ntwo\n```\n"),
        "Changing inline formatting across blocks must retain the code fence");
    editor.disposeView();

    DefaultStyledDocument typed = Markdown.read("\n");
    WritingPane typing = new WritingPane(typed, () -> {});
    typing.toggle("code");
    typing.replaceSelection("value");
    check(
        typed.getFont(typed.getCharacterElement(0).getAttributes()).getFamily()
            .equals(Theme.codeFont(16).getFamily()),
        "Newly typed inline code must use Menlo");
    typing.toggle("code");
    typing.replaceSelection(" prose");
    check(
        typed.getFont(typed.getCharacterElement(6).getAttributes()).getFamily()
            .equals(Theme.font(16).getFamily()),
        "Typing after switching code off must return to the system font");
    check(
        Markdown.write(typed).equals("`value` prose\n"),
        "Code entry and exit must preserve the intended Markdown boundary");
    typing.disposeView();
  }

}
