package com.diyalamp.desktop.fx;

import atlantafx.base.theme.CupertinoLight;
import java.io.InputStream;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import javafx.application.Application;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.transform.Scale;
import javafx.scene.text.Font;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** AtlantaFX light controls, pinned static Inter faces, and the existing Lucide SVG assets. */
public final class FxTheme {
  public static final Color DEFAULT_ACCENT = Color.web("#7B8FB7");
  static final String DISPLAYED_ACCENT = "diyalamp.displayedAccent";
  private static final Map<String, String> TEXT_FACES = new HashMap<>();
  private static final Map<String, Font> TEXT_FONTS = new HashMap<>();

  public static void setup() {
    loadTextFonts();
    Application.setUserAgentStylesheet(new CupertinoLight().getUserAgentStylesheet());
  }

  private static synchronized void loadTextFonts() {
    if (!TEXT_FACES.isEmpty()) return;
    Map<String, String> loaded = new HashMap<>();
    for (String face : new String[] {"Regular", "Italic", "SemiBold", "SemiBoldItalic"}) {
      try (InputStream input = FxTheme.class.getResourceAsStream("/fonts/Inter-" + face + ".ttf")) {
        Font font = input == null ? null : Font.loadFont(input, 16);
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
    root.getProperties().put(DISPLAYED_ACCENT, color);
    Color readable = color;
    // Preserve the chosen hue while ensuring controls remain visible on the light surface.
    for (int i = 1; contrastOnWhite(readable) < 4.5 && i <= 100; i++)
      readable = color.interpolate(Color.BLACK, i / 100.0);
    String hex = hex(readable);
    Color subtle = color.interpolate(Color.WHITE, .90);
    root.setStyle("-color-accent-emphasis: " + hex + "; -color-accent-fg: " + hex
        + "; -color-accent-subtle: " + hex(subtle) + "; -diyalamp-accent: " + hex
        + "; -diyalamp-accent-subtle: " + hex(subtle) + ";");
  }

  public static Color accentOf(Parent root) {
    Object color = root.getProperties().get(DISPLAYED_ACCENT);
    return color instanceof Color value ? value : DEFAULT_ACCENT;
  }

  private static double contrastOnWhite(Color color) {
    double luminance = .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen())
        + .0722 * linear(color.getBlue());
    return 1.05 / (luminance + .05);
  }

  private static double linear(double component) {
    return component <= .04045 ? component / 12.92 : Math.pow((component + .055) / 1.055, 2.4);
  }

  public static String hex(Color color) {
    return String.format(Locale.ROOT, "#%02X%02X%02X", Math.round(color.getRed() * 255),
        Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
  }

  public static Button iconButton(String icon, String description, Runnable action) {
    Button button = new Button(null, icon(icon, 16));
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

  /** Reuses the repository's upstream Lucide geometry without an icon font or WebView. */
  public static Node icon(String name, double size) {
    Pane pane = new Pane();
    pane.setMinSize(size, size);
    pane.setPrefSize(size, size);
    pane.setMaxSize(size, size);
    pane.setMouseTransparent(true);
    Group group = new Group();
    group.getTransforms().add(new Scale(size / 24, size / 24));
    try (InputStream in = FxTheme.class.getResourceAsStream("/icons/" + name + ".svg")) {
      if (in == null) return pane;
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      var nodes = factory.newDocumentBuilder().parse(in).getDocumentElement().getChildNodes();
      for (int i = 0; i < nodes.getLength(); i++) {
        if (!(nodes.item(i) instanceof Element element)) continue;
        Shape shape = switch (element.getTagName()) {
          case "path" -> {
            SVGPath path = new SVGPath(); path.setContent(element.getAttribute("d")); yield path;
          }
          case "line" -> new Line(number(element, "x1"), number(element, "y1"),
              number(element, "x2"), number(element, "y2"));
          case "circle" -> new Circle(number(element, "cx"), number(element, "cy"), number(element, "r"));
          case "ellipse" -> new Ellipse(number(element, "cx"), number(element, "cy"),
              number(element, "rx"), number(element, "ry"));
          case "rect" -> {
            Rectangle rectangle = new Rectangle(number(element, "x"), number(element, "y"),
                number(element, "width"), number(element, "height"));
            rectangle.setArcWidth(number(element, "rx") * 2);
            rectangle.setArcHeight(number(element, "rx") * 2);
            yield rectangle;
          }
          case "polyline", "polygon" -> {
            Polyline polyline = new Polyline();
            for (String point : element.getAttribute("points").strip().split("[ ,]+"))
              if (!point.isBlank()) polyline.getPoints().add(Double.parseDouble(point));
            if (element.getTagName().equals("polygon") && polyline.getPoints().size() >= 2)
              polyline.getPoints().addAll(polyline.getPoints().get(0), polyline.getPoints().get(1));
            yield polyline;
          }
          default -> null;
        };
        if (shape == null) continue;
        shape.setFill(Color.TRANSPARENT);
        shape.setStroke(Color.web("#777984"));
        shape.setStrokeWidth(1.7);
        shape.setStrokeLineCap(StrokeLineCap.ROUND);
        shape.setStrokeLineJoin(StrokeLineJoin.ROUND);
        shape.getStyleClass().add("icon-stroke");
        group.getChildren().add(shape);
      }
    } catch (Exception e) {
      System.err.println("Could not load bundled icon: " + name);
    }
    pane.getChildren().add(group);
    return pane;
  }

  private static double number(Element element, String key) {
    String value = element.getAttribute(key);
    return value.isBlank() ? 0 : Double.parseDouble(value);
  }

  private FxTheme() {}
}
