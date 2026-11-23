package com.azux.launcher.core;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Minimal .tar.gz extractor (ustar, GNU long names, pax paths, symlinks). No third-party code. */
public final class TarGz {
    private TarGz() { }

    public interface Progress { void update(long bytes); }

    public static void extract(InputStream raw, File dest, Progress p) throws Exception {
        InputStream in = new GZIPInputStream(new BufferedInputStream(raw, 1 << 16), 1 << 16);
        byte[] h = new byte[512];
        String longName = null, paxPath = null, paxLink = null;
        long total = 0;
        dest.mkdirs();
        String root = dest.getCanonicalPath() + File.separator;
        while (readFully(in, h, 512)) {
            if (isZero(h)) break;
            String name = cstr(h, 0, 100);
            int mode = (int) octal(h, 100, 8);
            long size = octal(h, 124, 12);
            char type = (char) h[156];
            String link = cstr(h, 157, 100);
            if (new String(h, 257, 5, StandardCharsets.US_ASCII).equals("ustar")) {
                String prefix = cstr(h, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
            }
            if (type == 'L') { longName = cstr(readBytes(in, size), 0, (int) size); skipPad(in, size); continue; }
            if (type == 'K') { paxLink = cstr(readBytes(in, size), 0, (int) size); skipPad(in, size); continue; }
            if (type == 'x') {
                String[] r = pax(readBytes(in, size));
                if (r[0] != null) paxPath = r[0];
                if (r[1] != null) paxLink = r[1];
                skipPad(in, size);
                continue;
            }
            if (type == 'g') { skip(in, size); skipPad(in, size); continue; }
            if (longName != null) name = longName;
            if (paxPath != null) name = paxPath;
            if (paxLink != null) link = paxLink;
            longName = paxPath = paxLink = null;
            while (name.startsWith("./")) name = name.substring(2);
            if (name.isEmpty() || name.equals(".")) { skip(in, size); skipPad(in, size); continue; }

            File out = new File(dest, name);
            if (!(out.getCanonicalPath() + File.separator).startsWith(root) && !out.getCanonicalPath().equals(root.substring(0, root.length() - 1))) {
                throw new IOException("Unsafe path in archive: " + name);
            }
            if (type == '5') {
                out.mkdirs();
            } else if (type == '2') {
                out.getParentFile().mkdirs();
                out.delete();
                try { android.system.Os.symlink(link, out.getPath()); } catch (Throwable t) { throw new IOException("symlink failed: " + name); }
            } else if (type == '1') {
                File target = new File(dest, link);
                out.getParentFile().mkdirs();
                copyFile(target, out);
            } else if (type == '0' || type == 0 || type == '7') {
                out.getParentFile().mkdirs();
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    long left = size;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("Unexpected end of archive");
                        fo.write(buf, 0, n);
                        left -= n;
                        total += n;
                    }
                }
                out.setReadable(true, false);
                out.setExecutable((mode & 0111) != 0, false);
                if (p != null) p.update(total);
                skipPad(in, size);
                continue;
            }
            skip(in, size);
            skipPad(in, size);
        }
    }

    private static void copyFile(File a, File b) throws IOException {
        try (InputStream i = new java.io.FileInputStream(a); FileOutputStream o = new FileOutputStream(b)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    private static boolean readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) return off != 0 && off == len;
            off += n;
        }
        return true;
    }

    private static byte[] readBytes(InputStream in, long size) throws IOException {
        byte[] b = new byte[(int) size];
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("Unexpected end of archive");
            off += n;
        }
        return b;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] buf = new byte[1 << 14];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("Unexpected end of archive");
            n -= r;
        }
    }

    private static void skipPad(InputStream in, long size) throws IOException { skip(in, (512 - size % 512) % 512); }

    private static boolean isZero(byte[] b) { for (byte x : b) if (x != 0) return false; return true; }

    private static String cstr(byte[] b, int off, int len) {
        int e = off;
        while (e < off + len && e < b.length && b[e] != 0) e++;
        return new String(b, off, e - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) { // base-256 for huge values
            long v = b[off] & 0x7F;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') { if (v == 0 && c == ' ') continue; break; }
            if (c < '0' || c > '7') break;
            v = (v << 3) + (c - '0');
        }
        return v;
    }

    /** pax records: "<len> key=value\n" -> {path, linkpath} */
    private static String[] pax(byte[] b) {
        String[] r = new String[2];
        int i = 0;
        while (i < b.length) {
            int sp = i;
            while (sp < b.length && b[sp] != ' ') sp++;
            if (sp >= b.length) break;
            int len;
            try { len = Integer.parseInt(new String(b, i, sp - i, StandardCharsets.US_ASCII)); } catch (Exception e) { break; }
            if (len <= 0 || i + len > b.length) break;
            String rec = new String(b, sp + 1, i + len - sp - 2, StandardCharsets.UTF_8);
            int eq = rec.indexOf('=');
            if (eq > 0) {
                String k = rec.substring(0, eq), v = rec.substring(eq + 1);
                if (k.equals("path")) r[0] = v;
                else if (k.equals("linkpath")) r[1] = v;
            }
            i += len;
        }
        return r;
    }
}
