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
        JSONObject result = new JSONObject().put("x", x).put("y", y).put("zoom", zoom).put("part", part);
        org.json.JSONArray trace = source.optJSONArray("restoreTrace");
        if (trace != null && trace.length() <= 14) result.put("restoreTrace", trace);
        return result;
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
        if (stage == null || !stage.matches("[a-zA-Z0-9-]{1,128}")) return;
        AtomicFile state = new AtomicFile(new File(directory, "office-lifecycle.json"));
        FileOutputStream out = null;
        try {
            JSONObject value = new JSONObject();
            try (java.io.FileInputStream input = state.openRead()) {
                if (input.getChannel().size() <= 8192) value = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception absentOrInvalidDiagnostic) { /* This is bounded lifecycle metadata, never document recovery data. */ }
            org.json.JSONArray previous = value.optJSONArray("trace"), trace = new org.json.JSONArray();
            if (previous != null) for (int i = Math.max(0, previous.length()-31); i < previous.length(); i++) {
                String entry = previous.optString(i);
                if (entry.matches("[a-zA-Z0-9-]{1,128}")) trace.put(entry);
            }
            if (trace.length()==0 || !stage.equals(trace.optString(trace.length()-1))) trace.put(stage);
            value.put("stage",stage).put("trace",trace);
            if ((stage.contains("failed") || stage.startsWith("native-load-error")) && !value.has("firstFailure")) value.put("firstFailure",stage);
            out = state.startWrite();
            out.write(value.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync(); state.finishWrite(out);
        } catch (Exception ignored) { if (out != null) state.failWrite(out); }
    }
    /** BYE can terminate the pinned engine process; the host confirms closure after lock release. */
    public static void requestClose(File working, boolean saveOriginal) throws IOException {
        AtomicFile state = new AtomicFile(new File(working.getPath() + ".close-request"));
        FileOutputStream out = null;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.FileInputStream input = new java.io.FileInputStream(working)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            byte[] bytes = new JSONObject().put("version", 1).put("working", working.getName())
                .put("hash", hash.toString()).put("saveOriginal", saveOriginal)
                .toString().getBytes(StandardCharsets.UTF_8);
            out = state.startWrite(); out.write(bytes); out.getFD().sync(); state.finishWrite(out);
        } catch (Exception failure) {
            if (out != null) state.failWrite(out);
            throw new IOException("Could not persist Office close request", failure);
        }
    }
    public static void markEdited(File working) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(working.getPath() + ".edited"))) {
            out.write(1);
            out.getFD().sync();
        }
    }
}
