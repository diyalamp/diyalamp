package com.diyalamp.desktop;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
public final class Theme {
 public static final Color PAPER=new Color(28,28,28),SIDEBAR=new Color(19,19,19),INK=new Color(242,242,242),MUTED=new Color(178,178,178),LINE=new Color(58,58,58),RED=new Color(238,0,0);
 private static Font base=new Font("Helvetica Neue",Font.PLAIN,15);
 public static void setup(){System.setProperty("apple.awt.application.appearance","NSAppearanceNameDarkAqua");System.setProperty("apple.laf.useScreenMenuBar","true");System.setProperty("apple.awt.application.name","diyalamp");try(InputStream in=Theme.class.getResourceAsStream("/Inter.ttf")){if(in!=null){base=Font.createFont(Font.TRUETYPE_FONT,in);GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(base);}}catch(Exception ignored){}FlatMacDarkLaf.setup();UIManager.put("defaultFont",font(14));UIManager.put("Component.arc",8);UIManager.put("Button.arc",8);UIManager.put("TextComponent.arc",8);UIManager.put("Component.focusWidth",1);UIManager.put("TabbedPane.showTabSeparators",false);UIManager.put("TabbedPane.tabHeight",36);UIManager.put("TabbedPane.contentBorderInsets",new Insets(0,0,0,0));UIManager.put("ScrollBar.width",9);UIManager.put("Panel.background",PAPER);UIManager.put("TextField.background",PAPER);UIManager.put("List.selectionBackground",new Color(48,48,48));UIManager.put("List.selectionForeground",INK);UIManager.put("Label.foreground",INK);UIManager.put("List.foreground",INK);UIManager.put("TextPane.foreground",INK);UIManager.put("TextField.foreground",INK);UIManager.put("TextField.caretForeground",INK);UIManager.put("Component.focusColor",RED);UIManager.put("Component.accentColor",RED);UIManager.put("TabbedPane.underlineColor",RED);UIManager.put("TabbedPane.selectedBackground",PAPER);UIManager.put("TabbedPane.background",SIDEBAR);UIManager.put("TabbedPane.hoverColor",new Color(40,40,40));UIManager.put("Button.background",new Color(36,36,36));UIManager.put("Button.foreground",INK);UIManager.put("Button.default.background",RED);UIManager.put("Button.default.foreground",Color.WHITE);}
 public static Font font(float size){return base.deriveFont(Font.PLAIN,size);}
 public static JButton button(String text,Runnable action){JButton b=new JButton(text);b.setFocusable(false);b.setMargin(new Insets(8,12,8,12));b.addActionListener(e->action.run());return b;}
 public static ImageIcon lamp(int size){java.net.URL u=Theme.class.getResource("/lamp.png");return u==null?null:new ImageIcon(new ImageIcon(u).getImage().getScaledInstance(size,size,Image.SCALE_SMOOTH));}
 private Theme(){}
}
