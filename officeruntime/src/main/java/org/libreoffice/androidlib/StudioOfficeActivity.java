package org.libreoffice.androidlib;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Hosts only a validated private working copy; provider originals are never passed here. */
public final class StudioOfficeActivity extends LOActivity {
    private File working;
    private RandomAccessFile lockFile;
    private FileLock lock;
    private String sessionId;
    private static boolean profilePrepared;

    @Override protected void onCreate(Bundle state) {
        if (!profilePrepared) {
            android.webkit.WebView.setDataDirectorySuffix("office");
            profilePrepared = true;
        }
        try {
            sessionId = getIntent().getStringExtra("sessionId");
            if (sessionId == null || !sessionId.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException();
            File directory = new File(getFilesDir(), "documents/" + sessionId).getCanonicalFile();
            working = new File(getIntent().getStringExtra("workingPath")).getCanonicalFile();
            if (!working.getParentFile().equals(directory) || !working.getName().matches("office-[a-zA-Z0-9-]+\\.(docx|doc|xlsx|xls|pptx|ppt)") || !working.isFile())
                throw new IllegalArgumentException();
            lockFile = new RandomAccessFile(new File(directory, "office.lock"), "rw");
            lock = lockFile.getChannel().tryLock();
            if (lock == null) throw new IllegalStateException();
            OfficePrivacy.requireQuiet();
            getIntent().setData(Uri.fromFile(working));
            setResult(RESULT_CANCELED, new Intent().putExtra("sessionId", sessionId));
        } catch (Exception failure) { throw new IllegalStateException("Invalid private Office session", failure); }
        super.onCreate(state);
    }

    @Override protected void onOfficeLifecycle(String stage) {
        if (working != null) DocumentViewState.lifecycle(working.getParentFile(), stage);
    }

    @Override protected String readOfficeViewState() {
        return working == null ? "{}" : DocumentViewState.read(working.getParentFile());
    }

    @Override protected void writeOfficeViewState(String state) {
        if (working != null) DocumentViewState.write(working.getParentFile(), state);
    }

    @Override protected void onOfficeSaved() throws java.io.IOException {
        // The native save callback has confirmed an actual, explicitly approved edit.
        DocumentViewState.markEdited(working);
    }

    @Override protected void onOfficeLoadFailed(String error) {
        setResult(RESULT_CANCELED, new Intent().putExtra("sessionId", sessionId).putExtra("error", error));
        finish();
    }

    @Override protected void onOfficeBeforeClose() throws java.io.IOException {
        DocumentViewState.requestClose(working, shouldSaveOriginal() && hasApprovedOfficeOutput());
    }

    @Override protected void onOfficeClosed() {
        try {
            File marker = new File(working.getPath() + ".closed");
            try (FileOutputStream out = new FileOutputStream(marker)) { out.write(1); out.getFD().sync(); }
            setResult(RESULT_OK, new Intent().putExtra("sessionId", sessionId).putExtra("saveOriginal", shouldSaveOriginal() && hasApprovedOfficeOutput()));
        } catch (Exception failure) {
            setResult(RESULT_CANCELED, new Intent().putExtra("sessionId", sessionId));
        }
    }

    @Override protected void onDestroy() {
        onOfficeLifecycle("activity-destroyed");
        super.onDestroy();
        try { if (lock != null) lock.release(); if (lockFile != null) lockFile.close(); }
        catch (Exception ignored) { }
        // The upstream kit keeps process-wide document/server state. A finished
        // host must not reuse that state for the next document. Only this private
        // :office process ends; the Compose host and its checkpoints remain alive.
        if (isFinishing()) android.os.Process.killProcess(android.os.Process.myPid());
    }
}
