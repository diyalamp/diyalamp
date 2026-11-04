package com.diyalamp.desktop;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
public final class Theme {
 public static final Color PAPER=Color.WHITE,SIDEBAR=new Color(247,247,245),INK=new Color(48,49,48),MUTED=new Color(126,126,119),LINE=new Color(232,232,228),GOLD=new Color(155,106,28);
 private static Font base=new Font("Helvetica Neue",Font.PLAIN,15);
 public static void setup(){System.setProperty("apple.laf.useScreenMenuBar","true");System.setProperty("apple.awt.application.name","diyalamp");try(InputStream in=Theme.class.getResourceAsStream("/Inter.ttf")){if(in!=null){base=Font.createFont(Font.TRUETYPE_FONT,in);GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(base);}}catch(Exception ignored){}FlatMacLightLaf.setup();UIManager.put("defaultFont",font(14));UIManager.put("Component.arc",8);UIManager.put("Button.arc",8);UIManager.put("TextComponent.arc",8);UIManager.put("Component.focusWidth",1);UIManager.put("TabbedPane.showTabSeparators",false);UIManager.put("TabbedPane.tabHeight",36);UIManager.put("TabbedPane.contentBorderInsets",new Insets(0,0,0,0));UIManager.put("ScrollBar.width",9);UIManager.put("Panel.background",PAPER);UIManager.put("TextField.background",PAPER);UIManager.put("List.selectionBackground",new Color(234,234,229));UIManager.put("List.selectionForeground",INK);}
 public static Font font(float size){return base.deriveFont(Font.PLAIN,size);}
 public static JButton button(String text,Runnable action){JButton b=new JButton(text);b.setFocusable(false);b.setMargin(new Insets(8,12,8,12));b.addActionListener(e->action.run());return b;}
 public static ImageIcon lamp(int size){java.net.URL u=Theme.class.getResource("/lamp.png");return u==null?null:new ImageIcon(new ImageIcon(u).getImage().getScaledInstance(size,size,Image.SCALE_SMOOTH));}
 private Theme(){}
}
