package org.libreoffice.androidlib;

import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** View metadata never serializes or changes document bytes. */
public final class DocumentViewState {
    private DocumentViewState() { }
    private static AtomicFile file(File directory) {
        return new AtomicFile(new File(directory, "office-view-state.json"));
    }
    private static JSONObject checked(String text) throws Exception {
        JSONObject source = new JSONObject(text);
        double x = source.getDouble("x"), y = source.getDouble("y"), zoom = source.getDouble("zoom");
        int part = source.getInt("part");
        if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 1e12 || Math.abs(y) > 1e12
                || !Double.isFinite(zoom) || zoom < 0 || zoom > 30 || part < 0 || part > 1000000)
            throw new IllegalArgumentException("Invalid viewport");
        return new JSONObject().put("x", x).put("y", y).put("zoom", zoom).put("part", part);
    }
    public static synchronized String read(File directory) {
        try {
            byte[] bytes = file(directory).readFully();
            if (bytes.length > 4096) return "{}";
            return checked(new String(bytes, StandardCharsets.UTF_8)).toString();
        } catch (Exception unavailable) { return "{}"; }
    }
    public static synchronized void write(File directory, String text) {
        if (text == null || text.length() > 4096) return;
        AtomicFile state = file(directory);
        FileOutputStream out = null;
        try {
            byte[] bytes = checked(text).toString().getBytes(StandardCharsets.UTF_8);
            out = state.startWrite();
            out.write(bytes);
            out.getFD().sync();
            state.finishWrite(out);
            new File(directory, "office-view-state-error.json").delete();
        } catch (Exception failure) {
            if (out != null) state.failWrite(out);
            AtomicFile error = new AtomicFile(new File(directory, "office-view-state-error.json"));
            FileOutputStream diagnostic = null;
            try {
                byte[] bytes = new JSONObject().put("error", failure.toString()).put("view", text).toString().getBytes(StandardCharsets.UTF_8);
                diagnostic = error.startWrite(); diagnostic.write(bytes); diagnostic.getFD().sync(); error.finishWrite(diagnostic);
            } catch (Exception ignored) { if (diagnostic != null) error.failWrite(diagnostic); }
        }
    }
    public static synchronized void lifecycle(File directory, String stage) {
        if (stage == null || !stage.matches("[a-z-]{1,40}")) return;
        AtomicFile state = new AtomicFile(new File(directory, "office-lifecycle.json"));
        FileOutputStream out = null;
        try {
            out = state.startWrite();
            out.write(new JSONObject().put("stage", stage).toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync(); state.finishWrite(out);
        } catch (Exception ignored) { if (out != null) state.failWrite(out); }
    }
    public static void markEdited(File working) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(working.getPath() + ".edited"))) {
            out.write(1);
            out.getFD().sync();
        }
    }
}
