package org.libreoffice.androidlib;

/** Intentionally discards upstream diagnostics, which can include document text. */
final class PrivateLog {
    static int d(String t, String m) { return 0; }
    static int v(String t, String m) { return 0; }
    static int i(String t, String m) { return 0; }
    static int w(String t, String m) { return 0; }
    static int e(String t, String m) { return 0; }
    static int w(String t, String m, Throwable e) { return 0; }
    static int e(String t, String m, Throwable e) { return 0; }
}
