package com.diyalamp.desktop;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;

public final class Diyalamp {
  public static final String VERSION = "1.0.3";
  private final JFrame frame = new JFrame("diyalamp");
  private NoteStore store;
  private final boolean persistFolder;
  private final Preferences preferences = Preferences.userRoot().node("com/diyalamp/desktop");
  private final Map<Path, Session> sessions = new LinkedHashMap<>();
  private final DefaultListModel<Path> files = new DefaultListModel<>();
  private final JList<Path> sidebar = new JList<>(files);
  private final JTextField search = new JTextField();
  private final JLabel folderName = new JLabel(),
      status = new JLabel("All changes saved"),
      count = new JLabel(" ");
  private final JTabbedPane left = new JTabbedPane(), right = new JTabbedPane();
  private final JPanel pages = new JPanel(new BorderLayout()),
      library = new JPanel(new BorderLayout());
  private final JSplitPane layout = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
  private JSplitPane split;
  private WritingPane active;
  private JButton libraryToggle;
  private NoteStore.Deleted lastDeleted;
  private boolean refreshing;
  private final javax.swing.Timer autosave = new javax.swing.Timer(650, e -> saveAll(false));

  static final class Session {
    Path path;
    String fingerprint;
    final DefaultStyledDocument document;
    final UndoManager undo = new UndoManager();
    boolean dirty, error;
    int loadingViews, viewCount;
    String serialized;

    Session(Path path, NoteStore.Loaded loaded) {
      this.path = path;
      fingerprint = loaded.fingerprint();
      document = Markdown.read(loaded.text());
      serialized = Markdown.write(document);
      document.addUndoableEditListener(undo);
    }
  }

  public static void main(String[] args) {
    Theme.setup();
    Path requested = args.length == 2 && args[0].equals("--workspace") ? Path.of(args[1]) : null;
    SwingUtilities.invokeLater(
        () -> {
          try {
            new Diyalamp(requested);
          } catch (Exception e) {
            JOptionPane.showMessageDialog(
                null, e.getMessage(), "diyalamp could not open", JOptionPane.ERROR_MESSAGE);
          }
        });
  }

  private Diyalamp(Path requested) throws IOException {
    persistFolder = requested == null;
    frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    frame.setMinimumSize(new Dimension(820, 560));
    frame.setSize(1180, 800);
    frame.setLocationRelativeTo(null);
    ImageIcon lamp = Theme.appIcon(128);
    if (lamp != null) frame.setIconImage(lamp.getImage());
    frame.getRootPane().putClientProperty("apple.awt.fullWindowContent", false);
    buildLibrary();
    buildPages();
    layout.setLeftComponent(library);
    layout.setRightComponent(pages);
    layout.setDividerLocation(230);
    layout.setDividerSize(1);
    layout.setBorder(null);
    JPanel shell = new JPanel(new BorderLayout());
    shell.add(buildActivityRail(), BorderLayout.WEST);
    shell.add(layout);
    frame.add(shell);
    frame.setJMenuBar(menu());
    frame.addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            if (saveAll(true)) {
              autosave.stop();
              frame.dispose();
            }
          }
        });
    if (Desktop.isDesktopSupported()) {
      Desktop desktop = Desktop.getDesktop();
      if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER))
        desktop.setQuitHandler(
            (e, response) -> {
              if (saveAll(true)) response.performQuit();
              else response.cancelQuit();
            });
      if (desktop.isSupported(Desktop.Action.APP_ABOUT)) desktop.setAboutHandler(e -> about());
    }
    Path folder =
        requested != null
            ? requested
            : Path.of(
                preferences.get(
                    "folder",
                    Path.of(System.getProperty("user.home"), "Documents", "diyalamp").toString()));
    setFolder(folder);
    if (store.notes().isEmpty()) store.create("Welcome to diyalamp", welcome());
    refresh();
    if (!files.isEmpty()) open(files.firstElement(), false);
    frame.setVisible(true);
    if (Desktop.isDesktopSupported()) {
      Desktop desktop = Desktop.getDesktop();
      if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND))
        desktop.requestForeground(true);
    }
    if (active != null) SwingUtilities.invokeLater(() -> active.requestFocusInWindow());
    autosave.setRepeats(false);
  }

  private JPanel buildActivityRail() {
    JPanel rail = new JPanel();
    rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
    rail.setBackground(Theme.RAIL);
    rail.setPreferredSize(new Dimension(44, 0));
    rail.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.LINE),
            BorderFactory.createEmptyBorder(8, 6, 8, 6)));
    libraryToggle = railButton("files", "Files · show or hide sidebar", this::toggleLibrary);
    libraryToggle.setSelected(true);
    rail.add(libraryToggle);
    rail.add(Box.createVerticalStrut(6));
    rail.add(
        railButton(
            "search",
            "Find a note by name",
            () -> {
              showLibrary();
              search.requestFocusInWindow();
              search.selectAll();
            }));
    rail.add(Box.createVerticalStrut(6));
    rail.add(railButton("square-pen", "New note · ⌘N", this::newNote));
    rail.add(Box.createVerticalGlue());
    rail.add(railButton("trash-2", "Deleted notes · restore a note", this::deletedNotes));
    rail.add(Box.createVerticalStrut(6));
    rail.add(railButton("folder-open", "Open notes folder · ⌘O", this::chooseFolder));
    return rail;
  }

  private JButton railButton(String icon, String label, Runnable action) {
    JButton button = Theme.iconButton(icon, label, action);
    button.setPreferredSize(new Dimension(32, 32));
    button.setMaximumSize(new Dimension(32, 32));
    button.setAlignmentX(Component.CENTER_ALIGNMENT);
    return button;
  }

  private void showLibrary() {
    library.setVisible(true);
    layout.setDividerLocation(230);
    if (libraryToggle != null) libraryToggle.setSelected(true);
  }

  private void toggleLibrary() {
    boolean visible = !library.isVisible();
    library.setVisible(visible);
    layout.setDividerLocation(visible ? 230 : 0);
    if (libraryToggle != null) libraryToggle.setSelected(visible);
  }

  private void buildLibrary() {
    library.setBackground(Theme.SIDEBAR);
    library.setBorder(BorderFactory.createEmptyBorder(12, 10, 10, 10));
    JPanel top = new JPanel();
    top.setOpaque(false);
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    JPanel identity = new JPanel(new BorderLayout(6, 0));
    identity.setOpaque(false);
    identity.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
    identity.setAlignmentX(Component.LEFT_ALIGNMENT);
    JLabel brand = new JLabel("diyalamp", Theme.lamp(18), JLabel.LEFT);
    brand.setFont(Theme.font(14).deriveFont(Font.BOLD));
    brand.setIconTextGap(8);
    identity.add(brand);
    identity.add(
        Theme.iconButton("panel-left", "Hide notes sidebar", this::toggleLibrary),
        BorderLayout.EAST);
    top.add(identity);
    top.add(Box.createVerticalStrut(14));
    search.putClientProperty("JTextField.placeholderText", "Find a note…");
    search.getAccessibleContext().setAccessibleName("Find a note by name");
    search.putClientProperty("JTextField.leadingIcon", Theme.icon("search", 14));
    search.putClientProperty("JTextField.showClearButton", true);
    search.setFont(Theme.font(14));
    search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
    search.setAlignmentX(Component.LEFT_ALIGNMENT);
    top.add(search);
    top.add(Box.createVerticalStrut(12));
    JPanel heading = new JPanel(new BorderLayout());
    heading.setOpaque(false);
    heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
    heading.setAlignmentX(Component.LEFT_ALIGNMENT);
    JLabel filesTitle = new JLabel("Files");
    filesTitle.setFont(Theme.font(14));
    filesTitle.setForeground(Theme.MUTED);
    filesTitle.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
    heading.add(filesTitle);
    heading.add(Theme.iconButton("plus", "New note · ⌘N", this::newNote), BorderLayout.EAST);
    top.add(heading);
    top.add(Box.createVerticalStrut(4));
    library.add(top, BorderLayout.NORTH);
    sidebar.setCellRenderer(
        new DefaultListCellRenderer() {
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel label =
                (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            Path path = (Path) value;
            label.setIcon(Theme.icon("file-text", 15));
            label.setIconTextGap(8);
            label.setText(
                (store == null ? path.getFileName() : store.root().relativize(path))
                    .toString()
                    .replaceFirst("(?i)\\.(md|markdown)$", ""));
            label.setToolTipText(path.getFileName().toString());
            label.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            label.setFont(Theme.font(14));
            if (!selected) {
              label.setBackground(Theme.SIDEBAR);
              label.setForeground(Theme.INK);
            }
            return label;
          }
        });
    sidebar.setFixedCellHeight(30);
    sidebar.setBackground(Theme.SIDEBAR);
    sidebar.getAccessibleContext().setAccessibleName("Markdown notes");
    sidebar.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting() && !refreshing && sidebar.getSelectedValue() != null)
            open(sidebar.getSelectedValue(), false);
        });
    sidebar.addMouseListener(
        new MouseAdapter() {
          public void mousePressed(MouseEvent e) {
            popup(e);
          }

          public void mouseReleased(MouseEvent e) {
            popup(e);
          }

          private void popup(MouseEvent e) {
            if (!e.isPopupTrigger()) return;
            int index = sidebar.locationToIndex(e.getPoint());
            if (index < 0) return;
            sidebar.setSelectedIndex(index);
            JPopupMenu menu = new JPopupMenu();
            item(menu, "Open beside", () -> open(sidebar.getSelectedValue(), true));
            item(menu, "Rename note…", () -> rename(sidebar.getSelectedValue()));
            item(menu, "Move to deleted notes", () -> delete(sidebar.getSelectedValue()));
            menu.show(sidebar, e.getX(), e.getY());
          }
        });
    sidebar.setDragEnabled(true);
    sidebar.setTransferHandler(
        new TransferHandler() {
          protected Transferable createTransferable(JComponent c) {
            return new StringSelection(sidebar.getSelectedValue().toString());
          }

          public int getSourceActions(JComponent c) {
            return COPY;
          }
        });
    JScrollPane scroll = new JScrollPane(sidebar);
    scroll.setBorder(null);
    library.add(scroll);
    JPanel bottom = new JPanel(new BorderLayout());
    bottom.setOpaque(false);
    bottom.setBorder(BorderFactory.createEmptyBorder(10, 6, 2, 4));
    folderName.setFont(Theme.font(14));
    folderName.setIcon(Theme.icon("folder-open", 14));
    folderName.setIconTextGap(8);
    folderName.setForeground(Theme.MUTED);
    bottom.add(folderName);
    library.add(bottom, BorderLayout.SOUTH);
    search
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                refresh();
              }

              public void removeUpdate(DocumentEvent e) {
                refresh();
              }

              public void changedUpdate(DocumentEvent e) {
                refresh();
              }
            });
  }

  private void buildPages() {
    pages.add(left);
    JPanel footer = new JPanel(new BorderLayout());
    footer.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE),
            BorderFactory.createEmptyBorder(6, 18, 6, 18)));
    status.setFont(Theme.font(14));
    status.setForeground(Theme.MUTED);
    count.setFont(Theme.font(14));
    count.setForeground(Theme.MUTED);
    footer.add(status);
    footer.add(count, BorderLayout.EAST);
    pages.add(footer, BorderLayout.SOUTH);
    for (JTabbedPane tabs : List.of(left, right)) {
      tabs.setFont(Theme.font(14));
      tabs.setBackground(Theme.SIDEBAR);
      tabs.putClientProperty("JTabbedPane.tabAreaAlignment", "leading");
      tabs.addChangeListener(
          e ->
              SwingUtilities.invokeLater(
                  () -> {
                    WritingPane p = pane(tabs.getSelectedComponent());
                    if (p != null) {
                      active = p;
                      Session selected = current();
                      if (selected != null) selectInLibrary(selected.path);
                      updateCount();
                    }
                  }));
      tabs.setTransferHandler(
          new TransferHandler() {
            public boolean canImport(TransferSupport s) {
              return s.isDataFlavorSupported(DataFlavor.stringFlavor)
                  || s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            public boolean importData(TransferSupport s) {
              if (!canImport(s)) return false;
              try {
                Path path;
                if (s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                  List<?> f =
                      (List<?>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                  path = ((File) f.getFirst()).toPath();
                  if (!path.toAbsolutePath().normalize().startsWith(store.root())) {
                    importFile(path);
                    return true;
                  }
                } else
                  path =
                      Path.of(
                          (String) s.getTransferable().getTransferData(DataFlavor.stringFlavor));
                boolean beside =
                    tabs == right
                        || (s.isDrop()
                            && s.getDropLocation().getDropPoint().x > tabs.getWidth() * .7);
                open(path, beside);
                return true;
              } catch (Exception e) {
                error(e);
                return false;
              }
            }
          });
    }
  }

  private JMenuBar menu() {
    JMenuBar bar = new JMenuBar();
    JMenu file = new JMenu("File"),
        edit = new JMenu("Edit"),
        view = new JMenu("View"),
        help = new JMenu("Help");
    bar.add(file);
    bar.add(edit);
    bar.add(WritingCommands.menu(() -> active));
    bar.add(view);
    bar.add(help);
    menuItem(file, "New note", KeyEvent.VK_N, 0, this::newNote);
    menuItem(file, "Open folder…", KeyEvent.VK_O, 0, this::chooseFolder);
    item(
        file,
        "Import Markdown…",
        () -> {
          JFileChooser c = new JFileChooser();
          c.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter(
                  "Markdown notes", "md", "markdown"));
          if (c.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION)
            importFile(c.getSelectedFile().toPath());
        });
    menuItem(file, "Save all", KeyEvent.VK_S, 0, () -> saveAll(false));
    item(file, "Export active note…", this::export);
    item(file, "Reload note from disk…", this::reloadFromDisk);
    menuItem(
        file,
        "Rename note…",
        KeyEvent.VK_R,
        InputEvent.SHIFT_DOWN_MASK,
        () -> {
          Session s = current();
          if (s != null) rename(s.path);
        });
    item(
        file,
        "Move note to deleted notes",
        () -> {
          Session s = current();
          if (s != null) delete(s.path);
        });
    item(file, "Restore last deleted note", this::restoreLast);
    item(file, "Deleted notes…", this::deletedNotes);
    menuItem(
        file,
        "Close note",
        KeyEvent.VK_W,
        0,
        () -> {
          JTabbedPane t = activeTabs();
          if (t.getSelectedIndex() >= 0) closeTab(t, t.getSelectedIndex());
        });
    menuItem(
        edit,
        "Undo",
        KeyEvent.VK_Z,
        0,
        () -> {
          Session s = current();
          if (s != null && s.undo.canUndo()) s.undo.undo();
        });
    menuItem(
        edit,
        "Redo",
        KeyEvent.VK_Z,
        InputEvent.SHIFT_DOWN_MASK,
        () -> {
          Session s = current();
          if (s != null && s.undo.canRedo()) s.undo.redo();
        });
    menuItem(edit, "Find in note…", KeyEvent.VK_F, 0, this::find);
    menuItem(
        view,
        "Toggle notes sidebar",
        KeyEvent.VK_B,
        InputEvent.SHIFT_DOWN_MASK,
        this::toggleLibrary);
    menuItem(view, "Side by side", KeyEvent.VK_BACK_SLASH, 0, this::openBeside);
    item(view, "Single pane", this::singlePane);
    item(
        help,
        "Keyboard shortcuts",
        () ->
            JOptionPane.showMessageDialog(
                frame,
                WritingCommands.helpText(),
                "Writing shortcuts",
                JOptionPane.PLAIN_MESSAGE));
    item(help, "About diyalamp", this::about);
    return bar;
  }

  private void setFolder(Path root) throws IOException {
    store = new NoteStore(root);
    folderName.setText(store.root().getFileName().toString());
    folderName.setToolTipText(store.root().toString());
    if (persistFolder) preferences.put("folder", store.root().toString());
    frame.setTitle("diyalamp · " + store.root().getFileName());
  }

  private void chooseFolder() {
    if (!saveAll(true)) return;
    JFileChooser c = new JFileChooser(store.root().toFile());
    c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (c.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    try {
      setFolder(c.getSelectedFile().toPath());
      sessions.clear();
      lastDeleted = null;
      removeViews(left);
      removeViews(right);
      active = null;
      singlePane();
      refresh();
      if (!files.isEmpty()) open(files.firstElement(), false);
    } catch (Exception e) {
      error(e);
    }
  }

  private void refresh() {
    if (store == null) return;
    try {
      Path selected = sidebar.getSelectedValue();
      String q = search.getText().strip().toLowerCase(Locale.ROOT);
      refreshing = true;
      files.clear();
      for (Path p : store.notes())
        if (p.getFileName().toString().toLowerCase(Locale.ROOT).contains(q)) files.addElement(p);
      sidebar.setSelectedValue(selected, true);
    } catch (Exception e) {
      error(e);
    } finally {
      refreshing = false;
    }
  }

  private void selectInLibrary(Path path) {
    boolean wasRefreshing = refreshing;
    refreshing = true;
    sidebar.setSelectedValue(path, true);
    refreshing = wasRefreshing;
  }

  private void newNote() {
    try {
      Path p = store.create("Untitled", "# Untitled\n\n");
      refresh();
      open(p, false);
      if (active != null) {
        active.setCaretPosition(0);
        active.moveCaretPosition(8);
        active.requestFocusInWindow();
      }
    } catch (Exception e) {
      error(e);
    }
  }

  private void open(Path path, boolean beside) {
    if (path == null) return;
    try {
      Session s = sessionFor(sessions, store, path);
      selectInLibrary(path);
      JTabbedPane target = beside ? right : left;
      if (beside) ensureSplit();
      for (int i = 0; i < target.getTabCount(); i++)
        if (target.getComponentAt(i) instanceof JScrollPane scroll
            && scroll.getClientProperty("session") == s) {
          target.setSelectedIndex(i);
          active = (WritingPane) scroll.getViewport().getView();
          active.requestFocusInWindow();
          return;
        }
      Session note = s;
      boolean firstView = note.viewCount++ == 0;
      note.loadingViews++;
      WritingPane editor =
          new WritingPane(
              note.document,
              () -> {
                if (note.loadingViews > 0) return;
                try {
                  String next = Markdown.write(note.document);
                  if (next.equals(note.serialized)) {
                    boolean recovered = note.error;
                    note.dirty = false;
                    note.error = false;
                    if (recovered) {
                      status.setForeground(Theme.MUTED);
                      status.setText("Changes reverted · local file unchanged");
                      status.setToolTipText(null);
                    }
                    return;
                  }
                  note.dirty = true;
                  note.error = false;
                  status.setForeground(Theme.MUTED);
                  status.setToolTipText(null);
                  status.setText("Saving…");
                  autosave.restart();
                } catch (IllegalStateException error) {
                  note.dirty = true;
                  note.error = true;
                  status.setForeground(Theme.RED);
                  status.setText("Not saved · undo or simplify the recent formatting");
                  status.setToolTipText(error.getMessage());
                }
                updateCount();
              });
      editor.addFocusListener(
          new FocusAdapter() {
            public void focusGained(FocusEvent e) {
              active = editor;
              selectInLibrary(note.path);
              updateCount();
            }
          });
      JScrollPane scroll = new JScrollPane(editor);
      scroll.putClientProperty("session", note);
      scroll.setBorder(null);
      scroll.setColumnHeaderView(documentBar(note, editor));
      scroll.getColumnHeader().setBackground(Theme.PAPER);
      scroll.getVerticalScrollBar().setUnitIncrement(18);
      target.addTab(note.path.getFileName().toString(), scroll);
      target.setTabComponentAt(target.indexOfComponent(scroll), tabHeader(note, target, scroll));
      target.setSelectedComponent(scroll);
      active = editor;
      editor.requestFocusInWindow();
      updateCount();
      SwingUtilities.invokeLater(
          () -> {
            if (!note.dirty) note.serialized = Markdown.write(note.document);
            note.loadingViews--;
            if (firstView) note.undo.discardAllEdits();
          });
    } catch (Exception e) {
      error(e);
    }
  }

  private JPanel documentBar(Session note, WritingPane editor) {
    JPanel bar = new JPanel(new BorderLayout(8, 0));
    bar.setBackground(Theme.PAPER);
    bar.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE),
            BorderFactory.createEmptyBorder(5, 16, 5, 10)));
    JLabel breadcrumb =
        new JLabel(note.path.getFileName().toString(), Theme.icon("file-text", 14), JLabel.LEFT);
    breadcrumb.setFont(Theme.font(14));
    breadcrumb.setForeground(Theme.MUTED);
    breadcrumb.setIconTextGap(8);
    breadcrumb.setToolTipText(store.root().relativize(note.path).toString());
    bar.add(breadcrumb);
    JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    tools.setOpaque(false);
    JButton style = Theme.tool("Text", "Paragraph style", () -> {});
    style.setIcon(Theme.icon("chevron-down", 13));
    style.setHorizontalTextPosition(SwingConstants.LEFT);
    style.setMargin(new Insets(5, 7, 5, 7));
    style.addActionListener(
        event -> {
          active = editor;
          JPopupMenu menu = new JPopupMenu();
          String[][] blocks = {
            {"Text", "paragraph"},
            {"Heading 1", "heading"},
            {"Heading 2", "heading"},
            {"Heading 3", "heading"},
            {"Bullet list", "bullet"},
            {"Numbered list", "ordered"},
            {"Checklist", "task"},
            {"Quote", "quote"},
            {"Code block", "code"}
          };
          for (String[] block : blocks)
            item(
                menu,
                block[0],
                () ->
                    editor.block(
                        block[1],
                        block[0].startsWith("Heading")
                            ? Integer.parseInt(block[0].substring(8))
                            : 1));
          menu.show(style, 0, style.getHeight());
        });
    tools.add(style);
    java.util.List<JButton> formatButtons = new ArrayList<>();
    String[][] formats = {
      {"bold", "Bold · ⌘B"}, {"italic", "Italic · ⌘I"}, {"code", "Inline code"}
    };
    for (String[] format : formats) {
      JButton button =
          Theme.iconButton(
              format[0],
              format[1],
              () -> {
                active = editor;
                editor.toggle(format[0]);
              });
      formatButtons.add(button);
      tools.add(button);
    }
    tools.add(
        Theme.iconButton(
            "link",
            "Insert or edit a link · ⌘K",
            () -> {
              active = editor;
              editor.link();
            }));
    JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
    separator.setPreferredSize(new Dimension(1, 18));
    tools.add(separator);
    tools.add(
        Theme.iconButton(
            "columns-2",
            "Open note beside writing · ⌘\\",
            () -> {
              active = editor;
              openBeside();
            }));
    Object previousListener = editor.getClientProperty("diyalamp.formatCaretListener");
    if (previousListener instanceof CaretListener listener) editor.removeCaretListener(listener);
    CaretListener formattingListener =
        event -> {
          AttributeSet attributes = editor.getCharacterAttributes();
          formatButtons.get(0).setSelected(StyleConstants.isBold(attributes));
          formatButtons.get(1).setSelected(StyleConstants.isItalic(attributes));
          formatButtons
              .get(2)
              .setSelected(Boolean.TRUE.equals(attributes.getAttribute(Markdown.INLINE_CODE)));
        };
    editor.addCaretListener(formattingListener);
    editor.putClientProperty("diyalamp.formatCaretListener", formattingListener);
    bar.add(tools, BorderLayout.EAST);
    return bar;
  }

  private JPanel tabHeader(Session s, JTabbedPane target, Component page) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    p.setOpaque(false);
    JLabel title =
        new JLabel(s.path.getFileName().toString().replaceFirst("(?i)\\.(md|markdown)$", ""));
    title.setIcon(Theme.icon("file-text", 14));
    title.setIconTextGap(8);
    title.setFont(Theme.font(14));
    p.add(title);
    JButton close =
        Theme.iconButton(
            "x",
            "Close " + s.path.getFileName(),
            () -> closeTab(target, target.indexOfComponent(page)));
    close.setIcon(Theme.icon("x", 12));
    close.setPreferredSize(new Dimension(20, 20));
    close.setMargin(new Insets(2, 2, 2, 2));
    p.add(close);
    return p;
  }

  private void closeTab(JTabbedPane t, int index) {
    if (index < 0) return;
    if (!saveAll(true)) return;
    removeView(t, index);
    if (right.getTabCount() == 0) singlePane();
    retireUnusedSessions(sessions, left, right);
    active = pane(activeTabs().getSelectedComponent());
    if (active == null) active = pane(right.getSelectedComponent());
    updateCount();
  }

  static Session sessionFor(Map<Path, Session> sessions, NoteStore store, Path path)
      throws IOException {
    Session session = sessions.get(path);
    if (session == null) {
      session = new Session(path, store.load(path));
      sessions.put(path, session);
    }
    return session;
  }

  static Session reloadSession(Map<Path, Session> sessions, NoteStore store, Session previous)
      throws IOException {
    // Read successfully before replacing any in-memory edits or their recovery information.
    Session replacement = new Session(previous.path, store.load(previous.path));
    sessions.put(previous.path, replacement);
    return replacement;
  }

  static void retireUnusedSessions(Map<Path, Session> sessions, JTabbedPane... panes) {
    for (Session session : sessions.values()) session.viewCount = 0;
    for (JTabbedPane tabs : panes)
      for (int i = 0; i < tabs.getTabCount(); i++)
        if (tabs.getComponentAt(i) instanceof JScrollPane scroll
            && scroll.getClientProperty("session") instanceof Session session) session.viewCount++;
    // Unsaved sessions remain recoverable even if a view is removed unexpectedly.
    sessions.values().removeIf(session -> session.viewCount == 0 && !session.dirty);
  }

  private void reloadFromDisk() {
    Session previous = current();
    if (previous == null) return;
    if (previous.dirty
        && JOptionPane.showOptionDialog(
                frame,
                "Reloading replaces your unsaved edits with the file on disk. "
                    + "Export a copy first if you want to keep those edits.",
                "Reload " + previous.path.getFileName() + "?",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new String[] {"Reload from disk", "Cancel"},
                "Cancel")
            != JOptionPane.OK_OPTION) return;
    try {
      boolean selectedRight = activeTabs() == right;
      reloadSession(sessions, store, previous);
      boolean openedLeft = removeSessionTabs(previous, left);
      boolean openedRight = removeSessionTabs(previous, right);
      if (selectedRight) {
        if (openedLeft) open(previous.path, false);
        if (openedRight) open(previous.path, true);
      } else {
        if (openedRight) open(previous.path, true);
        if (openedLeft) open(previous.path, false);
      }
      JTabbedPane selected = selectedRight ? right : left;
      active = pane(selected.getSelectedComponent());
      if (active != null) active.requestFocusInWindow();
      status.setForeground(Theme.MUTED);
      status.setText("Reloaded from disk · " + previous.path.getFileName());
      status.setToolTipText(null);
      updateCount();
    } catch (Exception e) {
      error(e);
    }
  }

  private boolean removeSessionTabs(Session session, JTabbedPane tabs) {
    boolean removed = false;
    for (int i = tabs.getTabCount() - 1; i >= 0; i--)
      if (tabs.getComponentAt(i) instanceof JScrollPane scroll
          && scroll.getClientProperty("session") == session) {
        removeView(tabs, i);
        removed = true;
      }
    return removed;
  }

  static void removeView(JTabbedPane tabs, int index) {
    Component page = tabs.getComponentAt(index);
    if (page instanceof JScrollPane scroll
        && scroll.getViewport().getView() instanceof WritingPane editor) editor.disposeView();
    tabs.remove(index);
  }

  private static void removeViews(JTabbedPane tabs) {
    for (int i = tabs.getTabCount() - 1; i >= 0; i--) removeView(tabs, i);
  }

  private WritingPane pane(Component c) {
    return c instanceof JScrollPane s && s.getViewport().getView() instanceof WritingPane p
        ? p
        : null;
  }

  private JTabbedPane activeTabs() {
    return active != null && SwingUtilities.isDescendingFrom(active, right) ? right : left;
  }

  private Session current() {
    if (active == null) return null;
    return sessions.values().stream()
        .filter(s -> s.document == active.getDocument())
        .findFirst()
        .orElse(null);
  }

  private void ensureSplit() {
    if (split != null) return;
    pages.remove(left);
    split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
    split.setResizeWeight(.5);
    split.setDividerSize(1);
    split.setBorder(null);
    pages.add(split);
    pages.revalidate();
    SwingUtilities.invokeLater(() -> split.setDividerLocation(.5));
  }

  private void singlePane() {
    if (split == null || !saveAll(true)) return;
    pages.remove(split);
    split = null;
    removeViews(right);
    retireUnusedSessions(sessions, left, right);
    pages.add(left);
    pages.revalidate();
    pages.repaint();
    active = pane(left.getSelectedComponent());
  }

  private void openBeside() {
    Path next = null;
    Session s = current();
    for (int i = 0; i < files.size(); i++)
      if (s == null || !files.get(i).equals(s.path)) {
        next = files.get(i);
        break;
      }
    if (next == null && s != null) next = s.path;
    if (next != null) open(next, true);
  }

  private boolean saveAll(boolean show) {
    boolean ok = true;
    for (Session s : sessions.values())
      if (s.dirty) {
        try {
          String text = Markdown.write(s.document);
          s.fingerprint = store.save(s.path, text, s.fingerprint);
          s.serialized = text;
          s.dirty = false;
          s.error = false;
        } catch (Exception e) {
          ok = false;
          status.setForeground(Theme.RED);
          status.setText(
              "Not saved · "
                  + s.path.getFileName()
                  + (e instanceof IllegalStateException
                      ? " · undo or simplify the recent formatting"
                      : " · export a copy to keep your edits"));
          status.setToolTipText(e.getMessage());
          if (show) error(e);
          s.error = true;
        }
      }
    if (ok) {
      status.setForeground(Theme.MUTED);
      status.setText("All changes saved · local files");
    }
    return ok;
  }

  private void rename(Path p) {
    if (p == null || !saveAll(true)) return;
    String name =
        JOptionPane.showInputDialog(
            frame,
            "Note name",
            p.getFileName().toString().replaceFirst("(?i)\\.(md|markdown)$", ""));
    if (name == null) return;
    try {
      Path renamed = store.rename(p, name);
      Session s = sessions.remove(p);
      if (s != null) {
        s.path = renamed;
        sessions.put(renamed, s);
        for (JTabbedPane t : List.of(left, right))
          for (int i = 0; i < t.getTabCount(); i++)
            if (t.getComponentAt(i) instanceof JScrollPane scroll
                && scroll.getClientProperty("session") == s) {
              t.setTitleAt(i, renamed.getFileName().toString());
              t.setTabComponentAt(i, tabHeader(s, t, scroll));
              scroll.setColumnHeaderView(
                  documentBar(s, (WritingPane) scroll.getViewport().getView()));
            }
      }
      refresh();
      sidebar.setSelectedValue(renamed, true);
    } catch (Exception e) {
      error(e);
    }
  }

  private void delete(Path p) {
    if (p == null || !saveAll(true)) return;
    try {
      lastDeleted = store.trash(p);
      Session deleted = sessions.remove(p);
      for (JTabbedPane t : List.of(left, right))
        for (int i = t.getTabCount() - 1; i >= 0; i--)
          if (t.getComponentAt(i) instanceof JScrollPane scroll
              && scroll.getClientProperty("session") == deleted) removeView(t, i);
      if (right.getTabCount() == 0) singlePane();
      refresh();
      active = pane(left.getSelectedComponent());
      status.setText("Moved to deleted notes · File → Restore last deleted note");
    } catch (Exception e) {
      error(e);
    }
  }

  private void restoreLast() {
    if (lastDeleted == null) return;
    try {
      Path p = store.restore(lastDeleted);
      lastDeleted = null;
      refresh();
      open(p, false);
    } catch (Exception e) {
      error(e);
    }
  }

  private void deletedNotes() {
    try {
      Path trash = store.root().resolve(".diyalamp-trash");
      Files.createDirectories(trash);
      JFileChooser c = new JFileChooser(trash.toFile());
      c.setDialogTitle("Restore a deleted Markdown note");
      if (c.showDialog(frame, "Restore note") == JFileChooser.APPROVE_OPTION) {
        Path chosen = c.getSelectedFile().toPath().toRealPath();
        if (!chosen.getParent().equals(trash.toRealPath()) || !NoteStore.isMarkdown(chosen))
          throw new IOException("Choose a deleted Markdown note from this folder.");
        String name = chosen.getFileName().toString().replaceFirst("^\\d+-[a-f0-9-]{36}-", "");
        Path target = store.unique(name);
        Files.move(chosen, target);
        refresh();
        open(target, false);
      }
    } catch (Exception e) {
      error(e);
    }
  }

  private void importFile(Path path) {
    try {
      if (!NoteStore.isMarkdown(path) || Files.size(path) > NoteStore.MAX_BYTES)
        throw new IOException("Choose a Markdown file smaller than 5 MB.");
      Path copy = store.create(path.getFileName().toString(), Files.readString(path));
      refresh();
      open(copy, false);
    } catch (Exception e) {
      error(e);
    }
  }

  private void export() {
    Session s = current();
    if (s == null) return;
    JFileChooser c = new JFileChooser();
    c.setSelectedFile(s.path.getFileName().toFile());
    if (c.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    Path target = c.getSelectedFile().toPath();
    if (Files.exists(target)
        && JOptionPane.showConfirmDialog(
                frame, "Replace this exported file?", "Export note", JOptionPane.OK_CANCEL_OPTION)
            != JOptionPane.OK_OPTION) return;
    try {
      Files.writeString(target, Markdown.write(s.document));
      status.setText("Exported a Markdown copy");
    } catch (Exception e) {
      error(e);
    }
  }

  private void find() {
    if (active == null) return;
    String query = JOptionPane.showInputDialog(frame, "Find in this note");
    if (query == null || query.isEmpty()) return;
    try {
      String text = active.getDocument().getText(0, active.getDocument().getLength());
      int start =
          text.toLowerCase(Locale.ROOT)
              .indexOf(query.toLowerCase(Locale.ROOT), active.getSelectionEnd());
      if (start < 0) start = text.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
      if (start >= 0) {
        active.requestFocusInWindow();
        active.select(start, start + query.length());
      } else status.setText("No match in this note");
    } catch (BadLocationException ignored) {
    }
  }

  private void updateCount() {
    if (active == null) {
      count.setText(" ");
      return;
    }
    try {
      String text = active.getDocument().getText(0, active.getDocument().getLength()).strip();
      count.setText((text.isEmpty() ? 0 : text.split("\\s+").length) + " words");
    } catch (BadLocationException ignored) {
    }
  }

  private void about() {
    JOptionPane.showMessageDialog(
        frame,
        "diyalamp "
            + VERSION
            + "\n"
            + "A tool for the mind.\n\n"
            + "Open source. Local Markdown files.\n"
            + "https://diyalamp.com\n"
            + "Apache License 2.0",
        "About diyalamp",
        JOptionPane.PLAIN_MESSAGE,
        Theme.lamp(64));
  }

  private void error(Exception e) {
    JOptionPane.showMessageDialog(
        frame, e.getMessage(), "Your writing stays open", JOptionPane.ERROR_MESSAGE);
  }

  private static void item(JComponent m, String text, Runnable r) {
    JMenuItem i = new JMenuItem(text);
    i.addActionListener(e -> r.run());
    m.add(i);
  }

  private static void menuItem(JComponent m, String text, int key, int mods, Runnable r) {
    JMenuItem i = new JMenuItem(text);
    i.setAccelerator(
        KeyStroke.getKeyStroke(key, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | mods));
    i.addActionListener(e -> r.run());
    m.add(i);
  }

  private static String welcome() {
    return "# A little light for your thoughts\n\n"
        + "Welcome to **diyalamp**. This is your quiet place to write, think, and keep what"
        + " matters.\n\n"
        + "## Make yourself at home\n\n"
        + "- Create a note from the sidebar.\n"
        + "- Type / on an empty line to choose a block.\n"
        + "- Use **bold**, *italic*, and `inline code` as you write.\n"
        + "- Open a second note with **Side by side**.\n\n"
        + "> Your words stay in ordinary Markdown files, in a folder you control.\n\n"
        + "## A small writing ritual\n\n"
        + "- [ ] Capture one idea.\n"
        + "- [ ] Give it room to grow.\n"
        + "- [ ] Keep on keeping on.\n\n"
        + "No account. No subscription. Just a little space to think.\n";
  }
}
