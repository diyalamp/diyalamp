package com.diyalamp.desktop;

import javax.swing.text.*;
import java.awt.Color;
import java.util.*;
import java.util.regex.*;

/** Common Markdown is stored as native styled text, never executable HTML. */
public final class Markdown {
    public static final String KIND="md.kind",LEVEL="md.level",LINK="md.link",INLINE_CODE="md.code",LANG="md.language",CHECKED="md.checked",NUMBER="md.number";
    private static final Pattern BLOCK=Pattern.compile("^(#{1,6}) (.*)$|^([-+*]) (\\[[ xX]\\] )?(.*)$|^(\\d+)\\. (.*)$|^> ?(.*)$");
    public static DefaultStyledDocument read(String source){
        DefaultStyledDocument d=new DefaultStyledDocument();String[] lines=source.replace("\r\n","\n").replace('\r','\n').split("\n",-1);boolean code=false;String language="";
        try{for(int i=0;i<lines.length;i++){
            String line=lines[i];if(i==lines.length-1&&line.isEmpty())break;
            if(line.startsWith("```")){if(!code){code=true;language=line.substring(3).strip();}else code=false;continue;}
            String kind="paragraph",text=line;int level=1,number=1;boolean checked=false;
            if(code)kind="code";
            else if(line.matches(" {0,3}([-*_])(?: *\\1){2,} *")){kind="divider";text="────────────────────────";}
            else{Matcher m=BLOCK.matcher(line);if(m.matches()){
                if(m.group(1)!=null){kind="heading";level=m.group(1).length();text=m.group(2);}
                else if(m.group(3)!=null){kind=m.group(4)!=null?"task":"bullet";checked=m.group(4)!=null&&m.group(4).toLowerCase(Locale.ROOT).contains("x");text=m.group(5);}
                else if(m.group(6)!=null){kind="ordered";try{number=Integer.parseInt(m.group(6));}catch(NumberFormatException ignored){}text=m.group(7);}
                else{kind="quote";text=m.group(8);}
            }}
            int start=d.getLength();if(kind.equals("code"))d.insertString(d.getLength(),text,null);else inline(d,text,new SimpleAttributeSet());d.insertString(d.getLength(),"\n",null);
            SimpleAttributeSet a=paragraph(kind,level);a.addAttribute(LANG,language);a.addAttribute(CHECKED,checked);a.addAttribute(NUMBER,number);d.setParagraphAttributes(start,Math.max(1,d.getLength()-start),a,true);
        }}catch(BadLocationException e){throw new IllegalStateException(e);}return d;
    }
    private static void inline(DefaultStyledDocument d,String text,AttributeSet inherited)throws BadLocationException{
        StringBuilder plain=new StringBuilder();for(int i=0;i<text.length();){
            if(text.charAt(i)=='\\'&&i+1<text.length()&&"\\`*_{}[]()#+-.!>~".indexOf(text.charAt(i+1))>=0){plain.append(text.charAt(i+1));i+=2;continue;}
            String marker=null;for(String candidate:new String[]{"**","__","~~","`","*","_"})if(text.startsWith(candidate,i)){marker=candidate;break;}
            if(marker!=null){int end=text.indexOf(marker,i+marker.length());if(end>i+marker.length()){
                if(!plain.isEmpty()){d.insertString(d.getLength(),plain.toString(),inherited);plain.setLength(0);}SimpleAttributeSet a=new SimpleAttributeSet(inherited);String inner=text.substring(i+marker.length(),end);
                if(marker.equals("**")||marker.equals("__"))StyleConstants.setBold(a,true);else if(marker.equals("~~"))StyleConstants.setStrikeThrough(a,true);else if(marker.equals("`")){a.addAttribute(INLINE_CODE,true);StyleConstants.setFontFamily(a,"Menlo");StyleConstants.setBackground(a,new Color(242,241,238));}else StyleConstants.setItalic(a,true);
                if(marker.equals("`"))d.insertString(d.getLength(),inner,a);else inline(d,inner,a);i=end+marker.length();continue;
            }}
            if(text.charAt(i)=='['){int middle=text.indexOf("](",i+1),end=middle<0?-1:text.indexOf(')',middle+2);if(end>middle&&middle>i){if(!plain.isEmpty()){d.insertString(d.getLength(),plain.toString(),inherited);plain.setLength(0);}SimpleAttributeSet a=new SimpleAttributeSet(inherited);a.addAttribute(LINK,text.substring(middle+2,end));StyleConstants.setUnderline(a,true);StyleConstants.setForeground(a,new Color(129,91,28));inline(d,text.substring(i+1,middle),a);i=end+1;continue;}}
            plain.append(text.charAt(i++));
        }if(!plain.isEmpty())d.insertString(d.getLength(),plain.toString(),inherited);
    }
    public static SimpleAttributeSet paragraph(String kind,int level){SimpleAttributeSet a=new SimpleAttributeSet();a.addAttribute(KIND,kind);a.addAttribute(LEVEL,level);StyleConstants.setFontFamily(a,kind.equals("code")?"Menlo":"Inter");StyleConstants.setFontSize(a,kind.equals("heading")?switch(level){case 1->32;case 2->25;case 3->21;default->18;}:16);StyleConstants.setBold(a,kind.equals("heading"));StyleConstants.setForeground(a,new Color(48,49,48));StyleConstants.setSpaceAbove(a,kind.equals("heading")?18:3);StyleConstants.setSpaceBelow(a,kind.equals("heading")?10:6);StyleConstants.setLineSpacing(a,.28f);StyleConstants.setLeftIndent(a,Set.of("bullet","ordered","task","quote").contains(kind)?28:0);return a;}
    public static String write(StyledDocument d){StringBuilder out=new StringBuilder();Element root=d.getDefaultRootElement();boolean code=false;String language="";
        try{for(int i=0;i<root.getElementCount();i++){Element p=root.getElement(i);int end=Math.min(p.getEndOffset(),d.getLength()),start=p.getStartOffset();if(start>=d.getLength())break;String plain=d.getText(start,end-start).replaceFirst("\\n$","");AttributeSet a=p.getAttributes();String kind=Objects.toString(a.getAttribute(KIND),"paragraph");
            if(code&&!kind.equals("code")){out.append("```\n");code=false;}if(kind.equals("code")){if(!code){language=Objects.toString(a.getAttribute(LANG),"");out.append("```").append(language).append('\n');code=true;}out.append(plain).append('\n');continue;}
            switch(kind){case "heading"->out.append("#".repeat(number(a,LEVEL,1))).append(' ');case "bullet"->out.append("- ");case "ordered"->out.append(number(a,NUMBER,1)).append(". ");case "task"->out.append(Boolean.TRUE.equals(a.getAttribute(CHECKED))?"- [x] ":"- [ ] ");case "quote"->out.append("> ");case "divider"->{out.append("---\n");continue;}}
            for(int j=0;j<p.getElementCount();j++){Element r=p.getElement(j);int rs=Math.max(start,r.getStartOffset()),re=Math.min(end,r.getEndOffset());String t=d.getText(rs,Math.max(0,re-rs)).replace("\n","");if(t.isEmpty())continue;AttributeSet attr=r.getAttributes();boolean c=Boolean.TRUE.equals(attr.getAttribute(INLINE_CODE)),bold=StyleConstants.isBold(attr)&&!kind.equals("heading"),italic=StyleConstants.isItalic(attr),strike=StyleConstants.isStrikeThrough(attr);String link=(String)attr.getAttribute(LINK);if(link!=null)out.append('[');if(bold)out.append("**");if(italic)out.append('*');if(strike)out.append("~~");if(c)out.append('`');out.append(c?t:escape(t));if(c)out.append('`');if(strike)out.append("~~");if(italic)out.append('*');if(bold)out.append("**");if(link!=null)out.append("](").append(link).append(')');}
            out.append('\n');
        }}catch(BadLocationException e){throw new IllegalStateException(e);}if(code)out.append("```\n");return out.toString();
    }
    private static int number(AttributeSet a,String key,int fallback){Object n=a.getAttribute(key);return n instanceof Number?((Number)n).intValue():fallback;}
    private static String escape(String s){return s.replace("\\","\\\\").replace("*","\\*").replace("_","\\_").replace("`","\\`").replace("[","\\[");}
}
