package com.diyalamp.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/** Local files, optimistic conflict checks, and recoverable deletion. No network access. */
public final class NoteStore {
    public record Loaded(String text,String fingerprint) {}
    public record Deleted(Path original,Path trashed) {}
    public static final long MAX_BYTES=5*1024*1024;
    private final Path root;
    public NoteStore(Path root) throws IOException { Files.createDirectories(root);this.root=root.toRealPath(); }
    public Path root(){return root;}
    public List<Path> notes() throws IOException {
        try(Stream<Path> stream=Files.walk(root,12)){
            return stream.filter(p->!p.equals(root)).filter(p->root.relativize(p).toString().chars().count()<4096)
                .filter(p->{for(Path part:root.relativize(p))if(part.toString().startsWith("."))return false;return true;})
                .filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&isMarkdown(p))
                .sorted(Comparator.comparing(p->root.relativize(p).toString().toLowerCase(Locale.ROOT))).toList();
        }
    }
    public static boolean isMarkdown(Path p){String n=p.getFileName().toString().toLowerCase(Locale.ROOT);return n.endsWith(".md")||n.endsWith(".markdown");}
    private Path checked(Path p) throws IOException {
        p=p.toAbsolutePath().normalize();if(!p.startsWith(root)||p.equals(root))throw new IOException("Choose a note inside the open folder.");
        if(Files.isSymbolicLink(p)||!p.getParent().toRealPath().startsWith(root))throw new IOException("Symbolic links outside the notes folder are not opened.");return p;
    }
    public Loaded load(Path p) throws IOException {p=checked(p);if(Files.size(p)>MAX_BYTES)throw new IOException("This note is larger than the 5 MB editing limit.");byte[] b=Files.readAllBytes(p);return new Loaded(decode(b),hash(b));}
    private static String decode(byte[] bytes)throws IOException {try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException e){throw new IOException("This file is not valid UTF-8 Markdown. Its original bytes have not been changed.",e);}}
    public String save(Path p,String text,String expected) throws IOException {
        p=checked(p);byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IOException("This note is larger than the 5 MB editing limit. Export a copy before continuing.");
        if(!Files.exists(p)||!hash(Files.readAllBytes(p)).equals(expected))throw new ConflictException("This file changed outside diyalamp. Your edits are still open. Export a copy or reopen the disk version.");
        Path temp=Files.createTempFile(p.getParent(),".diyalamp-save-",".tmp");
        try{Files.write(temp,bytes);try{Files.move(temp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}return hash(bytes);
    }
    public Path create(String name,String text)throws IOException {Path p=unique(name);Files.writeString(p,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);return p;}
    public Path unique(String name)throws IOException {String base=cleanName(name);Path p=root.resolve(base+".md");for(int i=2;Files.exists(p);i++)p=root.resolve(base+" "+i+".md");return p;}
    public Path rename(Path from,String name)throws IOException {from=checked(from);Path to=from.resolveSibling(cleanName(name)+".md");if(from.equals(to))return from;return Files.move(from,to);}
    private static String cleanName(String name)throws IOException {name=name.strip().replaceFirst("(?i)\\.(md|markdown)$","");if(name.isBlank()||name.startsWith(".")||name.length()>120||name.matches(".*[\\\\/:\\p{Cntrl}].*"))throw new IOException("Use a name of 1–120 characters without slashes or a leading dot.");return name;}
    public Deleted trash(Path path)throws IOException {path=checked(path);Path trash=root.resolve(".diyalamp-trash");Files.createDirectories(trash);Path dest=trash.resolve(Instant.now().toEpochMilli()+"-"+UUID.randomUUID()+"-"+path.getFileName());Files.move(path,dest);return new Deleted(path,dest);}
    public Path restore(Deleted deleted)throws IOException {if(Files.exists(deleted.original()))throw new IOException("A note already uses that name. Rename it before restoring.");Files.move(deleted.trashed(),deleted.original());return deleted.original();}
    public static String hash(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    public static final class ConflictException extends IOException{public ConflictException(String s){super(s);}}
}
