package com.diyalamp.desktop;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.function.Function;
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
  private final Function<String, String> linkDestination;
  private final JPopupMenu commands = new JPopupMenu();

  public WritingPane(StyledDocument document, Runnable changed) {
    this(document, changed, null);
  }

  WritingPane(StyledDocument document, Runnable changed, Function<String, String> linkDestination) {
    this.changed = changed;
    this.linkDestination =
        linkDestination == null
            ? initial -> JOptionPane.showInputDialog(this, "Link destination", initial)
            : linkDestination;
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
    addCaretListener(event -> describeContext());
    describeContext();
    documentListener =
        new DocumentListener() {
          public void insertUpdate(DocumentEvent e) {
            queue();
          }

          public void removeUpdate(DocumentEvent e) {
            queue();
          }

          public void changedUpdate(DocumentEvent e) {
            describeContext();
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
    int menu = WritingCommands.shortcutMask();
    bind(KeyEvent.VK_B, menu, () -> toggle("bold"));
    bind(KeyEvent.VK_I, menu, () -> toggle("italic"));
    bind(KeyEvent.VK_BACK_QUOTE, menu, () -> toggle("code"));
    bind(KeyEvent.VK_K, menu, this::link);
    bind(KeyEvent.VK_ENTER, menu | InputEvent.SHIFT_DOWN_MASK, this::toggleChecklist);
    bind(KeyEvent.VK_1, menu | InputEvent.ALT_DOWN_MASK, () -> block("heading", 1));
    bind(KeyEvent.VK_2, menu | InputEvent.ALT_DOWN_MASK, () -> block("heading", 2));
    bind(KeyEvent.VK_3, menu | InputEvent.ALT_DOWN_MASK, () -> block("heading", 3));
    addMouseListener(
        new MouseAdapter() {
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              toggleChecklistAt(viewToModel2D(e.getPoint()));
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
    AttributeSet at =
        getSelectionStart() < getSelectionEnd()
            ? getStyledDocument().getCharacterElement(getSelectionStart()).getAttributes()
            : getInputAttributes();
    switch (kind) {
      case "bold" -> StyleConstants.setBold(a, !StyleConstants.isBold(at));
      case "italic" -> StyleConstants.setItalic(a, !StyleConstants.isItalic(at));
      case "strike" -> StyleConstants.setStrikeThrough(a, !StyleConstants.isStrikeThrough(at));
      case "code" -> {
        inlineCode(!Boolean.TRUE.equals(at.getAttribute(Markdown.INLINE_CODE)));
        requestFocusInWindow();
        return;
      }
    }
    setCharacterAttributes(a, false);
    requestFocusInWindow();
  }

  private void inlineCode(boolean enabled) {
    StyledDocument document = getStyledDocument();
    int start = getSelectionStart(), end = getSelectionEnd();
    if (start == end) {
      setCharacterAttributes(codeAttributes(getInputAttributes(), enabled, start), true);
      return;
    }
    Runnable command =
        () -> {
          int position = start;
          while (position < end) {
            Element run = document.getCharacterElement(position);
            int next = Math.min(end, run.getEndOffset());
            document.setCharacterAttributes(
                position,
                next - position,
                codeAttributes(run.getAttributes(), enabled, position),
                true);
            position = next;
          }
        };
    if (document instanceof WritingDocument writing) writing.atomic(command);
    else command.run();
  }

  private AttributeSet codeAttributes(AttributeSet original, boolean enabled, int position) {
    SimpleAttributeSet attributes = new SimpleAttributeSet(original);
    attributes.addAttribute(Markdown.INLINE_CODE, enabled);
    // Removing the override restores each paragraph's own font, including fenced code.
    attributes.removeAttribute(StyleConstants.FontFamily);
    if (enabled) StyleConstants.setFontFamily(attributes, Theme.codeFont(16).getFamily());
    boolean codeBlock =
        "code".equals(
            getStyledDocument()
                .getParagraphElement(position)
                .getAttributes()
                .getAttribute(Markdown.KIND));
    StyleConstants.setBackground(attributes, enabled || codeBlock ? Theme.CODE : Theme.PAPER);
    return attributes;
  }

  public void link() {
    LinkRange range = linkRange();
    String target = linkDestination.apply(range.target() == null ? "https://" : range.target());
    if (target != null && !target.isBlank()) {
      select(range.start(), range.end());
      applyLink(target);
    }
    requestFocusInWindow();
  }

  /** Apply a destination to selected text, or insert a linked destination at the caret. */
  void applyLink(String destination) {
    if (destination == null || destination.isBlank()) return;
    String target = destination.strip();
    int start = getSelectionStart(), end = getSelectionEnd();
    StyledDocument document = getStyledDocument();
    SimpleAttributeSet following =
        new SimpleAttributeSet(
            end > start
                ? document.getCharacterElement(end - 1).getAttributes()
                : getInputAttributes());
    following.removeAttribute(Markdown.LINK);
    following.removeAttribute(Markdown.LINK_TITLE);
    StyleConstants.setUnderline(following, false);
    StyleConstants.setForeground(following, Theme.INK);
    SimpleAttributeSet a = new SimpleAttributeSet();
    a.addAttribute(Markdown.LINK, target);
    StyleConstants.setUnderline(a, true);
    StyleConstants.setForeground(a, Theme.RED);
    if (start == end) {
      SimpleAttributeSet inserted = new SimpleAttributeSet(getInputAttributes());
      inserted.addAttributes(a);
      try {
        document.insertString(start, target, inserted);
      } catch (BadLocationException error) {
        throw new IllegalStateException(error);
      }
      end = start + target.length();
    } else document.setCharacterAttributes(start, end - start, a, false);
    setCaretPosition(end);
    // A space or another sentence typed afterwards should not inherit the destination.
    setCharacterAttributes(following, true);
  }

  private record LinkRange(int start, int end, String target) {}

  private LinkRange linkRange() {
    int start = getSelectionStart(), end = getSelectionEnd();
    StyledDocument document = getStyledDocument();
    Element run = document.getCharacterElement(start);
    Object target = run.getAttributes().getAttribute(Markdown.LINK);
    if (start != end) return new LinkRange(start, end, target instanceof String s ? s : null);
    if (target == null && start > 0) {
      Element previous = document.getCharacterElement(start - 1);
      if (previous.getEndOffset() == start) {
        run = previous;
        target = run.getAttributes().getAttribute(Markdown.LINK);
      }
    }
    if (!(target instanceof String destination)) return new LinkRange(start, end, null);
    start = run.getStartOffset();
    end = Math.min(document.getLength(), run.getEndOffset());
    while (start > 0) {
      Element previous = document.getCharacterElement(start - 1);
      if (!destination.equals(previous.getAttributes().getAttribute(Markdown.LINK))) break;
      start = previous.getStartOffset();
    }
    while (end < document.getLength()) {
      Element next = document.getCharacterElement(end);
      if (!destination.equals(next.getAttributes().getAttribute(Markdown.LINK))) break;
      end = Math.min(document.getLength(), next.getEndOffset());
    }
    return new LinkRange(start, end, destination);
  }

  public void toggleChecklist() {
    toggleChecklistAt(getCaretPosition());
    requestFocusInWindow();
  }

  private void toggleChecklistAt(int position) {
    if (position < 0 || position > getDocument().getLength()) return;
    Element paragraph = getStyledDocument().getParagraphElement(position);
    if (!"task".equals(paragraph.getAttributes().getAttribute(Markdown.KIND))) return;
    SimpleAttributeSet a = new SimpleAttributeSet();
    a.addAttribute(
        Markdown.CHECKED,
        !Boolean.TRUE.equals(paragraph.getAttributes().getAttribute(Markdown.CHECKED)));
    getStyledDocument().setParagraphAttributes(paragraph.getStartOffset(), 1, a, false);
  }

  private void describeContext() {
    if (disposed) return;
    AttributeSet attributes =
        getStyledDocument().getParagraphElement(getCaretPosition()).getAttributes();
    String context =
        "task".equals(attributes.getAttribute(Markdown.KIND))
            ? (Boolean.TRUE.equals(attributes.getAttribute(Markdown.CHECKED))
                    ? "Checked checklist item. "
                    : "Unchecked checklist item. ")
            : "";
    String description =
        context
            + "Rich-text Markdown editor. Formatting commands are in the Format menu. "
            + "Command K inserts or edits a link. "
            + "Command Shift Enter toggles the current checklist item. "
            + "Control Tab moves focus out of the writing page.";
    if (!description.equals(getAccessibleContext().getAccessibleDescription()))
      getAccessibleContext().setAccessibleDescription(description);
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
