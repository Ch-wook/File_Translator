package org.libreoffice.androidlib;

/** Suppresses native protocol logs before any document is loaded. */
final class OfficePrivacy {
    static { System.loadLibrary("studiooffice"); }
    static native boolean quiet();
    static void requireQuiet() {
        if (!quiet()) throw new IllegalStateException("Office logging privacy initialization failed");
    }
}
