package com.diyalamp.desktop;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** A native rich-text surface. Markdown shortcuts become formatting as you type. */
public final class WritingPane extends JTextPane {
  private boolean converting, disposed;
  private final StyledDocument observedDocument;
  private final DocumentListener documentListener;
  private final Runnable changed;
  private final JPopupMenu commands = new JPopupMenu();

  public WritingPane(StyledDocument document, Runnable changed) {
    this.changed = changed;
    observedDocument = document;
    setEditorKit(new PageKit());
    setDocument(document);
    setMargin(new Insets(44, 44, 72, 44));
    addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(ComponentEvent event) {
            int inset = Math.max(28, (getWidth() - 680) / 2);
            Insets next = new Insets(44, inset, 72, inset);
            if (!next.equals(getMargin())) setMargin(next);
          }
        });
    setBackground(Theme.PAPER);
    setForeground(Theme.INK);
    setCaretColor(Theme.INK);
    setFont(Theme.font(16));
    putClientProperty("JTextPane.honorDisplayProperties", true);
    getAccessibleContext().setAccessibleName("Markdown writing page");
    documentListener =
        new DocumentListener() {
          public void insertUpdate(DocumentEvent e) {
            queue();
          }

          public void removeUpdate(DocumentEvent e) {
            queue();
          }

          public void changedUpdate(DocumentEvent e) {
            if (!converting) changed.run();
          }

          private void queue() {
            changed.run();
            if (!converting && hasFocus())
              SwingUtilities.invokeLater(
                  () -> {
                    if (!disposed) shortcuts();
                  });
          }
        };
    document.addDocumentListener(documentListener);
    getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "page-enter");
    getActionMap()
        .put(
            "page-enter",
            new AbstractAction() {
              public void actionPerformed(ActionEvent e) {
                enter();
              }
            });
    int menu =
        GraphicsEnvironment.isHeadless()
            ? InputEvent.CTRL_DOWN_MASK
            : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    bind(KeyEvent.VK_B, menu, () -> toggle("bold"));
    bind(KeyEvent.VK_I, menu, () -> toggle("italic"));
    bind(KeyEvent.VK_BACK_QUOTE, menu, () -> toggle("code"));
    bind(KeyEvent.VK_1, menu | InputEvent.ALT_DOWN_MASK, () -> block("heading", 1));
    bind(KeyEvent.VK_2, menu | InputEvent.ALT_DOWN_MASK, () -> block("heading", 2));
    bind(KeyEvent.VK_3, menu | InputEvent.ALT_DOWN_MASK, () -> block("heading", 3));
    addMouseListener(
        new MouseAdapter() {
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              Element p = getStyledDocument().getParagraphElement(viewToModel2D(e.getPoint()));
              if ("task".equals(p.getAttributes().getAttribute(Markdown.KIND))) {
                SimpleAttributeSet a = new SimpleAttributeSet();
                a.addAttribute(
                    Markdown.CHECKED,
                    !Boolean.TRUE.equals(p.getAttributes().getAttribute(Markdown.CHECKED)));
                getStyledDocument().setParagraphAttributes(p.getStartOffset(), 1, a, false);
              }
            }
          }
        });
  }

  /** Release both our observer and Swing's document listeners when the view is closed. */
  void disposeView() {
    if (disposed) return;
    disposed = true;
    commands.setVisible(false);
    observedDocument.removeDocumentListener(documentListener);
    setDocument(new DefaultStyledDocument());
  }

  private void bind(int key, int mods, Runnable task) {
    String id = "format-" + key + "-" + mods;
    getInputMap().put(KeyStroke.getKeyStroke(key, mods), id);
    getActionMap()
        .put(
            id,
            new AbstractAction() {
              public void actionPerformed(ActionEvent e) {
                task.run();
              }
            });
  }

  public void block(String kind, int level) {
    StyledDocument d = getStyledDocument();
    SimpleAttributeSet a = Markdown.paragraph(kind, level);
    a.addAttribute(Markdown.NUMBER, 1);
    a.addAttribute(Markdown.CHECKED, false);
    d.setParagraphAttributes(
        getSelectionStart(), Math.max(1, getSelectionEnd() - getSelectionStart()), a, true);
    requestFocusInWindow();
  }

  public void toggle(String kind) {
    SimpleAttributeSet a = new SimpleAttributeSet();
    AttributeSet at = getCharacterAttributes();
    switch (kind) {
      case "bold" -> StyleConstants.setBold(a, !StyleConstants.isBold(at));
      case "italic" -> StyleConstants.setItalic(a, !StyleConstants.isItalic(at));
      case "strike" -> StyleConstants.setStrikeThrough(a, !StyleConstants.isStrikeThrough(at));
      case "code" -> {
        boolean next = !Boolean.TRUE.equals(at.getAttribute(Markdown.INLINE_CODE));
        a.addAttribute(Markdown.INLINE_CODE, next);
        StyleConstants.setFontFamily(a, "Inter");
        StyleConstants.setBackground(a, next ? new Color(48, 48, 48) : Theme.PAPER);
      }
    }
    setCharacterAttributes(a, false);
    requestFocusInWindow();
  }

  public void link() {
    String target = JOptionPane.showInputDialog(this, "Link destination", "https://");
    if (target == null || target.isBlank()) return;
    SimpleAttributeSet a = new SimpleAttributeSet();
    a.addAttribute(Markdown.LINK, target.strip());
    StyleConstants.setUnderline(a, true);
    StyleConstants.setForeground(a, Theme.INK);
    if (getSelectedText() == null) replaceSelection(target.strip());
    setCharacterAttributes(a, false);
    requestFocusInWindow();
  }

  private void enter() {
    StyledDocument d = getStyledDocument();
    Element p = d.getParagraphElement(getCaretPosition());
    AttributeSet old = new SimpleAttributeSet(p.getAttributes());
    String kind = Objects.toString(old.getAttribute(Markdown.KIND), "paragraph");
    try {
      String text =
          d.getText(
                  p.getStartOffset(),
                  Math.min(p.getEndOffset(), d.getLength()) - p.getStartOffset())
              .strip();
      if (Set.of("bullet", "ordered", "task", "quote").contains(kind) && text.isEmpty()) {
        block("paragraph", 1);
        return;
      }
      replaceSelection("\n");
      SimpleAttributeSet a =
          Markdown.paragraph(
              kind.equals("heading") || kind.equals("divider") ? "paragraph" : kind, 1);
      a.addAttribute(
          Markdown.NUMBER,
          old.getAttribute(Markdown.NUMBER) instanceof Number n ? n.intValue() + 1 : 1);
      a.addAttribute(Markdown.CHECKED, false);
      a.addAttribute(Markdown.LANG, Objects.toString(old.getAttribute(Markdown.LANG), ""));
      if (kind.equals("code")) {
        for (String key :
            java.util.List.of(Markdown.FENCE_ID, Markdown.FENCE_OPEN, Markdown.FENCE_CLOSE)) {
          Object value = old.getAttribute(key);
          if (value != null) a.addAttribute(key, value);
        }
        a.addAttribute(Markdown.FENCE_EMPTY, false);
      }
      d.setParagraphAttributes(getCaretPosition(), 1, a, true);
      setCharacterAttributes(SimpleAttributeSet.EMPTY, true);
    } catch (BadLocationException e) {
      UIManager.getLookAndFeel().provideErrorFeedback(this);
    }
  }

  private void shortcuts() {
    if (converting || !hasFocus()) return;
    StyledDocument d = getStyledDocument();
    int caret = getCaretPosition();
    Element p = d.getParagraphElement(caret);
    String kind = Objects.toString(p.getAttributes().getAttribute(Markdown.KIND), "paragraph");
    if (kind.equals("code")) return;
    try {
      String before = d.getText(p.getStartOffset(), caret - p.getStartOffset());
      if (before.equals("/")) {
        showCommands();
        return;
      }
      if (before.matches("#{1,6} |[-*+] |\\d+\\. |\\[ \\] |[-*+] \\[ \\] |> |``` ")) {
        String newKind =
            before.startsWith("#")
                ? "heading"
                : before.startsWith(">")
                    ? "quote"
                    : before.startsWith("```")
                        ? "code"
                        : before.contains("[")
                            ? "task"
                            : Character.isDigit(before.charAt(0)) ? "ordered" : "bullet";
        int level = before.startsWith("#") ? before.strip().length() : 1;
        converting = true;
        d.remove(p.getStartOffset(), before.length());
        block(newKind, level);
        converting = false;
        return;
      }
      Matcher m =
          Pattern.compile(
                  "(\\*\\*[^*\\n]+\\*\\*|~~[^~\\n]+~~|`[^`\\n]+`|(?<!\\*)\\*[^*\\n]+\\*)(?: )?$")
              .matcher(before);
      if (m.find()) {
        String token = m.group(1);
        int start = p.getStartOffset() + m.start(1);
        DefaultStyledDocument rich = Markdown.read(token);
        int length = rich.getLength() - 1;
        converting = true;
        d.remove(start, token.length());
        for (int off = 0; off < length; ) {
          Element run = rich.getCharacterElement(off);
          int end = Math.min(length, run.getEndOffset());
          d.insertString(start + off, rich.getText(off, end - off), run.getAttributes());
          off = end;
        }
        setCaretPosition(start + length + (before.endsWith(" ") ? 1 : 0));
        setCharacterAttributes(SimpleAttributeSet.EMPTY, true);
        converting = false;
      }
    } catch (BadLocationException e) {
      converting = false;
    }
  }

  private void showCommands() {
    if (commands.isVisible()) return;
    commands.removeAll();
    String[][] items = {
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
    for (String[] item : items) {
      JMenuItem action = new JMenuItem(item[0]);
      action.addActionListener(
          e -> {
            commands.setVisible(false);
            try {
              int c = getCaretPosition();
              if (c > 0 && getDocument().getText(c - 1, 1).equals("/"))
                getDocument().remove(c - 1, 1);
              int level =
                  item[0].startsWith("Heading") ? Integer.parseInt(item[0].substring(8)) : 1;
              block(item[1], level);
              if (item[1].equals("divider")) replaceSelection("────────────────────────");
            } catch (BadLocationException ignored) {
            }
          });
      commands.add(action);
    }
    try {
      Rectangle r = modelToView2D(getCaretPosition()).getBounds();
      commands.show(this, r.x, r.y + r.height + 4);
    } catch (BadLocationException ignored) {
    }
  }

  private static final class PageKit extends StyledEditorKit {
    public ViewFactory getViewFactory() {
      return element -> {
        String n = element.getName();
        return switch (n) {
          case AbstractDocument.ContentElementName -> new LabelView(element);
          case AbstractDocument.ParagraphElementName -> new MarkedParagraph(element);
          case AbstractDocument.SectionElementName -> new BoxView(element, View.Y_AXIS);
          case StyleConstants.ComponentElementName -> new ComponentView(element);
          case StyleConstants.IconElementName -> new IconView(element);
          default -> new LabelView(element);
        };
      };
    }
  }

  private static final class MarkedParagraph extends ParagraphView {
    MarkedParagraph(Element e) {
      super(e);
    }

    public void paint(Graphics g, Shape allocation) {
      super.paint(g, allocation);
      String kind = Objects.toString(getElement().getAttributes().getAttribute(Markdown.KIND), "");
      if (!Set.of("bullet", "task", "ordered", "quote").contains(kind)) return;
      Rectangle r = allocation.getBounds();
      g.setColor(Theme.MUTED);
      g.setFont(Theme.font(15));
      int y = r.y + 19, x = r.x + 8;
      switch (kind) {
        case "bullet" -> g.fillOval(x + 3, y - 8, 5, 5);
        case "ordered" ->
            g.drawString(
                Objects.toString(getElement().getAttributes().getAttribute(Markdown.NUMBER), "1")
                    + ".",
                x,
                y);
        case "quote" -> g.fillRect(x + 3, r.y + 2, 2, Math.max(18, r.height - 5));
        case "task" -> {
          g.drawRoundRect(x, y - 12, 12, 12, 3, 3);
          if (Boolean.TRUE.equals(getElement().getAttributes().getAttribute(Markdown.CHECKED)))
            g.drawString("✓", x, y - 1);
        }
      }
    }
  }
}
