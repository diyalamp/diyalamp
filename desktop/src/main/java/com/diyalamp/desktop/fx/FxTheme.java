package com.diyalamp.desktop.fx;

import atlantafx.base.theme.CupertinoDark;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javafx.application.Application;
import javafx.collections.MapChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.transform.Scale;
import javafx.scene.text.Font;
import javafx.stage.WindowEvent;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** AtlantaFX dark controls, pinned static Inter faces, and the existing Lucide SVG assets. */
public final class FxTheme {
  public static final double TEXT_SIZE = 16;
  public static final Color DEFAULT_ACCENT = Color.web("#EE0000");
  public static final Color EDITOR_BACKGROUND = Color.web("#141312");
  private static final Color DEFAULT_LINK = Color.web("#FF6B6B");
  private static final Color DEFAULT_FOCUS = Color.web("#FF5555B3");
  private static final Color DEFAULT_SELECTION = Color.web("#AB2828DD");
  private static final Color ICON_INK = Color.web("#8C8C8C");
  static final String DISPLAYED_ACCENT = "diyalamp.displayedAccent";
  private static final String DISPLAYED_SURFACE = "diyalamp.displayedSurface";
  private static final String REDUCED_MOTION = "diyalamp.reducedMotion";
  private static final PseudoClass REDUCED_MOTION_CLASS = PseudoClass.getPseudoClass("reduced-motion");
  private static final Map<String, String> TEXT_FACES = new HashMap<>();
  private static final Map<String, Font> TEXT_FONTS = new HashMap<>();
  private static final Map<String, List<Supplier<Shape>>> ICON_GEOMETRY = new ConcurrentHashMap<>();

  public static void setup() {
    loadTextFonts();
    Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());
  }

  private static synchronized void loadTextFonts() {
    if (!TEXT_FACES.isEmpty()) return;
    Map<String, String> loaded = new HashMap<>();
    for (String face : new String[] {"Regular", "Italic", "SemiBold", "SemiBoldItalic"}) {
      try (InputStream input = FxTheme.class.getResourceAsStream("/fonts/Inter-" + face + ".ttf")) {
        Font font = input == null ? null : Font.loadFont(input, TEXT_SIZE);
        String expected = "Inter " + face.replace("SemiBoldItalic", "SemiBold Italic");
        if (font == null || !font.getName().equals(expected)) throw new IllegalStateException("Could not load the bundled Inter " + face + " face");
        loaded.put(face, font.getName());
      } catch (java.io.IOException error) {
        throw new IllegalStateException("Could not read the bundled text font", error);
      }
    }
    TEXT_FACES.putAll(loaded);
  }

  public static Font textFont(double size, boolean emphasized, boolean italic) {
    loadTextFonts();
    String face = emphasized ? (italic ? "SemiBoldItalic" : "SemiBold") : (italic ? "Italic" : "Regular");
    String name = TEXT_FACES.get(face);
    return TEXT_FONTS.computeIfAbsent(name + ":" + size, ignored -> new Font(name, size));
  }

  public static void accent(Parent root, Color color) {
    paint(root, color, surfaceOf(root));
  }

  static void focusSurface(Parent root, double progress) {
    paint(root, accentOf(root), EDITOR_BACKGROUND.interpolate(Color.BLACK, progress));
  }

  static void inherit(Parent root, Parent source) {
    paint(root, accentOf(source), surfaceOf(source));
  }

  static boolean themeChanged(Object key) {
    return DISPLAYED_ACCENT.equals(key) || DISPLAYED_SURFACE.equals(key) || REDUCED_MOTION.equals(key);
  }

  private static void paint(Parent root, Color color, Color surface) {
    applyMotionPreference(root);
    root.getProperties().put(DISPLAYED_ACCENT, color);
    root.getProperties().put(DISPLAYED_SURFACE, surface);
    // Derive readable tones continuously as the accent animates.
    Color readable = accentTone(color, DEFAULT_LINK);
    for (int i = 1; contrast(readable, EDITOR_BACKGROUND) < 4.5 && i <= 100; i++)
      readable = readable.interpolate(Color.WHITE, .02);
    Color foreground = contrast(Color.WHITE, color) >= 4.5 ? Color.WHITE : Color.BLACK;
    Color focus = accentTone(color, DEFAULT_FOCUS);
    Color subtle = new Color(focus.getRed(), focus.getGreen(), focus.getBlue(), 0x26 / 255.0);
    root.setStyle("-diyalamp-surface: " + hex(surface) + "; -color-accent-emphasis: " + hex(color)
        + "; -color-accent-fg: " + hex(readable)
        + "; -color-accent-subtle: " + cssColor(subtle) + "; -diyalamp-accent: " + hex(color)
        + "; -diyalamp-link: " + hex(readable) + "; -diyalamp-accent-contrast: " + hex(foreground)
        + "; -diyalamp-accent-subtle: " + cssColor(subtle) + "; -diyalamp-focus: " + cssColor(focus)
        + "; -diyalamp-selection: " + cssColor(accentTone(color, DEFAULT_SELECTION)) + ";");
  }

  static void applyMotionPreference(Parent root) {
    boolean reduced = FxMotion.reduced();
    root.pseudoClassStateChanged(REDUCED_MOTION_CLASS, reduced);
    if (!Boolean.valueOf(reduced).equals(root.getProperties().get(REDUCED_MOTION))) {
      root.getProperties().put(REDUCED_MOTION, reduced);
    }
  }

  private static Color accentTone(Color color, Color target) {
    return Color.hsb(color.getHue() + target.getHue() - DEFAULT_ACCENT.getHue(),
        Math.min(1, color.getSaturation() * target.getSaturation() / DEFAULT_ACCENT.getSaturation()),
        Math.max(0, Math.min(1, color.getBrightness() + target.getBrightness() - DEFAULT_ACCENT.getBrightness())),
        target.getOpacity());
  }

  private static String cssColor(Color color) {
    return color.getOpacity() == 1 ? hex(color) : hex(color) + String.format(Locale.ROOT, "%02X", Math.round(color.getOpacity() * 255));
  }

  public static Color accentOf(Parent root) {
    Object color = root == null ? null : root.getProperties().get(DISPLAYED_ACCENT);
    return color instanceof Color value ? value : DEFAULT_ACCENT;
  }

  static Color surfaceOf(Parent root) {
    Object color = root == null ? null : root.getProperties().get(DISPLAYED_SURFACE);
    return color instanceof Color value ? value : EDITOR_BACKGROUND;
  }

  static ContextMenu contextMenu() {
    ContextMenu menu = new ContextMenu();
    Parent[] owner = {null};
    Runnable sync = () -> menu.setStyle("-color-bg-default: " + hex(surfaceOf(owner[0])) + ";");
    MapChangeListener<Object, Object> listener = change -> {
      if (DISPLAYED_SURFACE.equals(change.getKey())) sync.run();
    };
    menu.addEventHandler(WindowEvent.WINDOW_SHOWING, event -> {
      var window = menu.getOwnerWindow();
      owner[0] = window == null || window.getScene() == null ? null : window.getScene().getRoot();
      if (owner[0] != null) owner[0].getProperties().addListener(listener);
      sync.run();
    });
    menu.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> {
      if (owner[0] != null) owner[0].getProperties().removeListener(listener);
      owner[0] = null;
    });
    return menu;
  }

  static double contrast(Color first, Color second) {
    double a = luminance(first), b = luminance(second);
    return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
  }

  private static double luminance(Color color) {
    return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue());
  }

  private static double linear(double component) {
    return component <= .04045 ? component / 12.92 : Math.pow((component + .055) / 1.055, 2.4);
  }

  public static String hex(Color color) {
    return String.format(Locale.ROOT, "#%02X%02X%02X", Math.round(color.getRed() * 255),
        Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
  }

  public static Button iconButton(String icon, String description, Runnable action) {
    Button button = new Button(null, icon(icon));
    button.getStyleClass().addAll("flat", "button-icon", "workspace-tool");
    button.setAccessibleText(description);
    button.setTooltip(new Tooltip(description));
    button.setOnAction(event -> action.run());
    return button;
  }

  public static Image image(String name) {
    try (InputStream in = FxTheme.class.getResourceAsStream("/" + name)) {
      return in == null ? null : new Image(in);
    } catch (java.io.IOException e) {
      return null;
    }
  }

  public static ImageView lamp(double size) {
    ImageView view = new ImageView(image("lamp.png"));
    view.setFitWidth(size);
    view.setFitHeight(size);
    view.setPreserveRatio(true);
    return view;
  }

  static Node icon(String name) { return icon(name, 16); }

  /** Reuses the repository's upstream Lucide geometry without an icon font or WebView. */
  public static Node icon(String name, double size) {
    StackPane pane = new StackPane();
    pane.setMinSize(size, size);
    pane.setPrefSize(size, size);
    pane.setMaxSize(size, size);
    pane.setMouseTransparent(true);
    if (name == null) return pane;
    List<Supplier<Shape>> geometry = ICON_GEOMETRY.computeIfAbsent(name, FxTheme::readIconGeometry);
    if (geometry.isEmpty()) return pane;
    Group group = new Group();
    group.getTransforms().add(new Scale(size / 24, size / 24));
    for (Supplier<Shape> part : geometry) {
      Shape shape = part.get();
      shape.setFill(Color.TRANSPARENT);
      shape.setStroke(ICON_INK);
      shape.setStrokeWidth(1.7);
      shape.setStrokeLineCap(StrokeLineCap.ROUND);
      shape.setStrokeLineJoin(StrokeLineJoin.ROUND);
      shape.getStyleClass().add("icon-stroke");
      group.getChildren().add(shape);
    }
    // A wrapping group includes the scaled artwork in its layout bounds, so the
    // icon slot centers the painted strokes even when the SVG has uneven margins.
    pane.getChildren().add(new Group(group));
    return pane;
  }

  // Rows are recycled frequently. Parse bundled SVG once while giving each control
  // fresh nodes so its CSS state and scene-graph parent remain independent.
  private static List<Supplier<Shape>> readIconGeometry(String name) {
    List<Supplier<Shape>> geometry = new ArrayList<>();
    try (InputStream in = FxTheme.class.getResourceAsStream("/icons/" + name + ".svg")) {
      if (in == null) return List.of();
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      var nodes = factory.newDocumentBuilder().parse(in).getDocumentElement().getChildNodes();
      for (int i = 0; i < nodes.getLength(); i++) {
        if (!(nodes.item(i) instanceof Element element)) continue;
        Supplier<Shape> part = iconPart(element);
        if (part != null) geometry.add(part);
      }
    } catch (Exception e) {
      System.err.println("Could not load bundled icon: " + name);
    }
    return List.copyOf(geometry);
  }

  private static Supplier<Shape> iconPart(Element element) {
    return switch (element.getTagName()) {
      case "path" -> {
        String commands = element.getAttribute("d");
        yield () -> {
          SVGPath path = new SVGPath();
          path.setContent(commands);
          return path;
        };
      }
      case "line" -> {
        double x1 = number(element, "x1"), y1 = number(element, "y1");
        double x2 = number(element, "x2"), y2 = number(element, "y2");
        yield () -> new Line(x1, y1, x2, y2);
      }
      case "circle" -> {
        double x = number(element, "cx"), y = number(element, "cy"), radius = number(element, "r");
        yield () -> new Circle(x, y, radius);
      }
      case "ellipse" -> {
        double x = number(element, "cx"), y = number(element, "cy");
        double radiusX = number(element, "rx"), radiusY = number(element, "ry");
        yield () -> new Ellipse(x, y, radiusX, radiusY);
      }
      case "rect" -> {
        double x = number(element, "x"), y = number(element, "y");
        double width = number(element, "width"), height = number(element, "height");
        double arc = number(element, "rx") * 2;
        yield () -> {
          Rectangle rectangle = new Rectangle(x, y, width, height);
          rectangle.setArcWidth(arc);
          rectangle.setArcHeight(arc);
          return rectangle;
        };
      }
      case "polyline", "polygon" -> {
        List<Double> points = new ArrayList<>();
        for (String point : element.getAttribute("points").strip().split("[ ,]+"))
          if (!point.isBlank()) points.add(Double.parseDouble(point));
        if (element.getTagName().equals("polygon") && points.size() >= 2)
          points.addAll(List.of(points.get(0), points.get(1)));
        List<Double> coordinates = List.copyOf(points);
        yield () -> {
          Polyline polyline = new Polyline();
          polyline.getPoints().setAll(coordinates);
          return polyline;
        };
      }
      default -> null;
    };
  }

  private static double number(Element element, String key) {
    String value = element.getAttribute(key);
    return value.isBlank() ? 0 : Double.parseDouble(value);
  }

  private FxTheme() {}
}
