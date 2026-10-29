/* -*- Mode: C++; tab-width: 4; indent-tabs-mode: nil; c-basic-offset: 4; fill-column: 100 -*- */
/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.libreoffice.androidlib;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Insets;
import android.hardware.input.InputManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;

import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.RatingBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import org.json.JSONException;
import org.json.JSONObject;
import org.libreoffice.androidlib.lok.LokClipboardData;
import org.libreoffice.androidlib.lok.LokClipboardEntry;

import com.android.tools.r8.keepanno.annotations.UsedByNative;

public class LOActivity extends AppCompatActivity {
    final static String TAG = "LOActivity";

    private static final String ASSETS_EXTRACTED_GIT_COMMIT = "ASSETS_EXTRACTED_GIT_COMMIT";
    private static final int PERMISSION_WRITE_EXTERNAL_STORAGE = 777;
    private static final String KEY_ENABLE_SHOW_DEBUG_INFO = "ENABLE_SHOW_DEBUG_INFO";
    private static final String KEY_ALL_FILES_ACCESS_DECLINED = "ALL_FILES_ACCESS_DECLINED";

    private static final String KEY_PROVIDER_ID = "providerID";
    private static final String KEY_DOCUMENT_URI = "documentUri";
    private static final String KEY_IS_EDITABLE = "isEditable";
    private static final String KEY_INTENT_URI = "intentUri";
    private static final String CLIPBOARD_FILE_PATH = "LibreofficeClipboardFile.data";
    private static final String CLIPBOARD_COOL_SIGNATURE = "cool-clip-magic-4a22437e49a8-";
    private static final int CLIPBOARD_SYSTEM_LIMIT_BYTES = 512 * 1024;
    public static final String RECENT_DOCUMENTS_KEY = "RECENT_DOCUMENTS_LIST";
    private static String USER_NAME_KEY = "USER_NAME";
    public static final String NIGHT_MODE_KEY = "NIGHT_MODE";

    private File mTempFile = null;

    private final AtomicBoolean mPendingCopyToSystemClipboard = new AtomicBoolean(false);

    // Since as the clipboard can be quite large, it's better to not do this on the same thread as everything else
    private static final ExecutorService clipboardExecutor = Executors.newSingleThreadExecutor();

    /// The actual file path of the document, when the app can write it directly.
    private volatile File mResolvedFile = null;

    /// True once the user has been asked for access to all files for the document being opened.
    private boolean mAllFilesAccessAsked = false;

    private int providerId;
    private Activity mActivity;

    /// Unique number identifying this app + document.
    private long loadDocumentMillis = 0;

    @Nullable
    private URI documentUri;

    private String urlToLoad;
    private COWebView mWebView = null;
    private MobileSocket mMobileSocket = null;
    private SharedPreferences sPrefs;
    private Handler mMainHandler = null;
    private RateAppController rateAppController;

    private boolean isDocEditable = false;
    private boolean isDocDebuggable = false;
    private boolean documentLoaded = false;
    // Permission, pending core modifications and durable approved output are distinct.
    private volatile boolean studioEditAuthorized = false;
    private volatile boolean studioEditEverAuthorized = false;
    private volatile boolean officeModified = false;
    private volatile boolean officeApprovedChanges = false;
    private volatile boolean officeSaveInFlight = false;
    private volatile boolean officeSavedApproved = false;
    private volatile boolean officeLoadFinished = false;
    private volatile boolean officeLoadFailed = false;
    private volatile boolean studioUiTextInputFocused = false;

    private ClipboardManager clipboardManager;
    private ClipData clipData;
    private Thread nativeMsgThread;
    private Handler nativeHandler;
    private Looper nativeLooper;
    private Bundle savedInstanceState;

    private ProgressDialog mProgressDialog = null;

    /** In case the mobile-wizard is visible, we have to intercept the Android's Back button. */
    private boolean mMobileWizardVisible = false;
    private boolean mIsEditModeActive = false;
    /// True while a slide show is on screen in the WebView.
    private volatile boolean mSlideShowActive = false;

    /// True while a physical keyboard is attached and can be typed on. Written on the main thread
    /// and read on the thread that serves the JavaScript bridge, so it is volatile.
    private volatile boolean mHardwareKeyboardAttached = false;

    /// The theme the loaded document is rendered in: true is dark mode, false is light mode.
    private boolean mDarkModeApplied = false;

    private InputManager mInputManager = null;
    private InputManager.InputDeviceListener mInputDeviceListener = null;

    private ValueCallback<Uri[]> valueCallback;

    /// Source file path remembered while a REQUEST_EXPORT_FILE intent is in flight.
    private String mPendingExportSourcePath = null;

    public static final int REQUEST_SELECT_IMAGE_FILE = 500;
    public static final int REQUEST_SAVEAS_PDF = 501;
    public static final int REQUEST_SAVEAS_RTF = 502;
    public static final int REQUEST_SAVEAS_ODT = 503;
    public static final int REQUEST_SAVEAS_ODP = 504;
    public static final int REQUEST_SAVEAS_ODS = 505;
    public static final int REQUEST_SAVEAS_DOCX = 506;
    public static final int REQUEST_SAVEAS_PPTX = 507;
    public static final int REQUEST_SAVEAS_XLSX = 508;
    public static final int REQUEST_SAVEAS_DOC = 509;
    public static final int REQUEST_SAVEAS_PPT = 510;
    public static final int REQUEST_SAVEAS_XLS = 511;
    public static final int REQUEST_SAVEAS_EPUB = 512;
    public static final int REQUEST_EXPORT_FILE = 513;
    public static final int REQUEST_COPY = 600;
    public static final int REQUEST_ALL_FILES_ACCESS = 601;

    /** Broadcasting event for passing info back to the shell. */
    public static final String LO_ACTIVITY_BROADCAST = "LOActivityBroadcast";

    /** Event description for passing info back to the shell. */
    public static final String LO_ACTION_EVENT = "LOEvent";

    /** Data description for passing info back to the shell. */
    public static final String LO_ACTION_DATA = "LOData";

    /** shared pref key for recent files. */
    public static final String EXPLORER_PREFS_KEY = "EXPLORER_PREFS";

    private static boolean copyFromAssets(AssetManager assetManager,
                                          String fromAssetPath, String targetDir) {
        try {
            String[] files = assetManager.list(fromAssetPath);
            boolean res = true;
            for (String file : files) {
                String[] dirOrFile = assetManager.list(fromAssetPath + "/" + file);
                if (dirOrFile.length == 0) {
                    // noinspection ResultOfMethodCallIgnored
                    new File(targetDir).mkdirs();
                    res &= copyAsset(assetManager,
                            fromAssetPath + "/" + file,
                            targetDir + "/" + file);
                } else
                    res &= copyFromAssets(assetManager,
                            fromAssetPath + "/" + file,
                            targetDir + "/" + file);
            }
            return res;
        } catch (Exception e) {
            
            PrivateLog.e(TAG, "copyFromAssets failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean copyAsset(AssetManager assetManager, String fromAssetPath, String toPath) {
        ReadableByteChannel source = null;
        FileChannel dest = null;
        try {
            try {
                source = Channels.newChannel(assetManager.open(fromAssetPath));
                dest = new FileOutputStream(toPath).getChannel();
                long bytesTransferred = 0;
                // might not copy all at once, so make sure everything gets copied....
                ByteBuffer buffer = ByteBuffer.allocate(4096);
                while (source.read(buffer) > 0) {
                    buffer.flip();
                    bytesTransferred += dest.write(buffer);
                    buffer.clear();
                }
                PrivateLog.v(TAG, "Success copying " + fromAssetPath + " to " + toPath + " bytes: " + bytesTransferred);
                return true;
            } finally {
                if (dest != null) dest.close();
                if (source != null) source.close();
            }
        } catch (FileNotFoundException e) {
            PrivateLog.e(TAG, "file " + fromAssetPath + " not found! " + e.getMessage());
            return false;
        } catch (IOException e) {
            PrivateLog.e(TAG, "failed to copy file " + fromAssetPath + " from assets to " + toPath + " - " + e.getMessage());
            return false;
        }
    }

    private Handler getMainHandler() {
        if (mMainHandler == null) {
            mMainHandler = new Handler(getMainLooper());
        }
        return mMainHandler;
    }

    /** True if the App is running under ChromeOS. */
    public static boolean isChromeOS(Context context) {
        return context.getPackageManager().hasSystemFeature("org.chromium.arc.device_management");
    }

    /** True if a physical keyboard with letter keys is attached and can be typed on right now. */
    private static boolean detectHardwareKeyboard(Context context) {
        Configuration configuration = context.getResources().getConfiguration();
        if (configuration.keyboard != Configuration.KEYBOARD_NOKEYS
                && configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES)
            return false;

        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual())
                continue;

            if ((device.getSources() & InputDevice.SOURCE_KEYBOARD) != 0
                    && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC)
                return true;
        }

        return false;
    }

    /** Watch for keyboards being paired, plugged in or removed. */
    private void startWatchingForHardwareKeyboard() {
        mHardwareKeyboardAttached = detectHardwareKeyboard(this);

        mInputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (mInputManager == null)
            return;

        mInputDeviceListener = new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
                updateHardwareKeyboardState();
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                updateHardwareKeyboardState();
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
                updateHardwareKeyboardState();
            }
        };

        mInputManager.registerInputDeviceListener(mInputDeviceListener, getMainHandler());
    }

    private void stopWatchingForHardwareKeyboard() {
        if (mInputManager != null && mInputDeviceListener != null)
            mInputManager.unregisterInputDeviceListener(mInputDeviceListener);

        mInputManager = null;
        mInputDeviceListener = null;
    }

    /** Re-read the keyboard state and pass it on when it differs from what JavaScript last saw. */
    private void updateHardwareKeyboardState() {
        boolean attached = detectHardwareKeyboard(this);
        if (attached == mHardwareKeyboardAttached)
            return;

        mHardwareKeyboardAttached = attached;
        PrivateLog.i(TAG, "Hardware keyboard " + (attached ? "attached" : "detached"));

        if (documentLoaded)
            callFakeWebsocketOnMessage(attached ? "mobile: hardwarekeyboardattached"
                                                : "mobile: hardwarekeyboarddetached");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        this.savedInstanceState = savedInstanceState;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        setContentView(R.layout.lolib_activity_main);
        mProgressDialog = new ProgressDialog(this);
        if (BuildConfig.GOOGLE_PLAY_ENABLED)
            this.rateAppController = new RateAppController(this);
        else
            this.rateAppController = null;
        this.mActivity = this;

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!documentLoaded) {
                    finish();
                    return;
                }

                if (mSlideShowActive) {
                    callFakeWebsocketOnMessage("mobile: slideshowback");
                    return;
                } else if (mMobileWizardVisible) {
                    // just return one level up in the mobile-wizard (or close it)
                    callFakeWebsocketOnMessage("mobile: mobilewizardback");
                    return;
                } else if (mIsEditModeActive) {
                    callFakeWebsocketOnMessage("mobile: readonlymode");
                    return;
                }

                finishWithProgress();
            }
        });

        startWatchingForHardwareKeyboard();

        init();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfiguration) {
        super.onConfigurationChanged(newConfiguration);
        updateHardwareKeyboardState();
        updateDarkMode();
    }

    /** Retheme the loaded document when the effective dark mode setting has changed. */
    private void updateDarkMode() {
        if (!documentLoaded)
            return;

        boolean darkMode = isDarkMode();
        if (darkMode == mDarkModeApplied)
            return;

        mDarkModeApplied = darkMode;
        callFakeWebsocketOnMessage(darkMode ? "mobile: darkthemeactivated"
                                            : "mobile: lightthemeactivated");
    }

    /** Initialize the app - copy the assets and create the UI. */
    private void init() {
        if (sPrefs.getString(ASSETS_EXTRACTED_GIT_COMMIT, "").equals(BuildConfig.GIT_COMMIT)) {
            // all is fine, we have already copied the assets
            initUI();
            return;
        }

        mProgressDialog.indeterminate(R.string.preparing_for_the_first_start_after_an_update);

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                // copy the new assets
                if (copyFromAssets(getAssets(), "unpack", getApplicationInfo().dataDir)) {
                    sPrefs.edit().putString(ASSETS_EXTRACTED_GIT_COMMIT, BuildConfig.GIT_COMMIT).apply();
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                initUI();
            }
        }.execute();
    }

    /** Actual initialization of the UI. */
    private void initUI() {
        isDocDebuggable = false;
        mResolvedFile = null;

        if (getIntent().getData() != null) {

            if (getIntent().getData().getScheme().equals(ContentResolver.SCHEME_CONTENT)) {
                isDocEditable = true;

                // The launching app did not grant write access, so we were
                // handed a read-only copy and cannot save changes back to the
                // original. Ask for "All files access" permission to edit the
                // underlying file.
                if ((getIntent().getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                    mResolvedFile = resolveWritableFile(getIntent().getData());
                    if (mResolvedFile == null) {
                        isDocEditable = false;
                        PrivateLog.d(TAG, "Disabled editing: Read-only");
                        if (!canAllFilesAccessHelp())
                            showReadOnlyToast();
                    } else {
                        PrivateLog.i(TAG, "Editing enabled, saving back to " + mResolvedFile);
                    }
                }

                // turns out that on ChromeOS, it is not possible to save back
                // to Google Drive; detect it already here to avoid disappointment
                // also the volumeprovider does not work for saving back,
                // which is much more serious :-(
                if (isDocEditable && (getIntent().getData().toString().startsWith("content://org.chromium.arc.chromecontentprovider/externalfile") ||
                                      getIntent().getData().toString().startsWith("content://org.chromium.arc.volumeprovider/"))) {
                    isDocEditable = false;
                    PrivateLog.d(TAG, "Disabled editing: Chrome OS unsupported content providers");
                    Toast.makeText(this, getResources().getString(R.string.file_chromeos_read_only), Toast.LENGTH_LONG).show();
                }

                if (copyFileToTemp() && mTempFile != null) {
                    documentUri = mTempFile.toURI();
                    urlToLoad = documentUri.toString();
                    PrivateLog.d(TAG, "SCHEME_CONTENT: getPath(): " + getIntent().getData().getPath());
                } else {
                    PrivateLog.e(TAG, "couldn't create temporary file from " + getIntent().getData());
                    Toast.makeText(this, R.string.cant_open_the_document, Toast.LENGTH_SHORT).show();
                    finish();
                }
            } else if (getIntent().getData().getScheme().equals(ContentResolver.SCHEME_FILE)) {
                isDocEditable = true;
                urlToLoad = getIntent().getData().toString();
                PrivateLog.d(TAG, "SCHEME_FILE: getPath(): " + getIntent().getData().getPath());
                // Gather data to rebuild IFile object later
                providerId = getIntent().getIntExtra(
                        "org.libreoffice.document_provider_id", 0);
                documentUri = (URI) getIntent().getSerializableExtra(
                        "org.libreoffice.document_uri");
            }
        } else if (savedInstanceState != null) {
            getIntent().setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse(savedInstanceState.getString(KEY_INTENT_URI)));
            urlToLoad = getIntent().getData().toString();
            providerId = savedInstanceState.getInt(KEY_PROVIDER_ID);
            if (savedInstanceState.getString(KEY_DOCUMENT_URI) != null) {
                try {
                    documentUri = new URI(savedInstanceState.getString(KEY_DOCUMENT_URI));
                    urlToLoad = documentUri.toString();
                } catch (URISyntaxException e) {
                    
                }
            }
            isDocEditable = savedInstanceState.getBoolean(KEY_IS_EDITABLE);
        } else {
            //User can't reach here but if he/she does then
            Toast.makeText(this, getString(R.string.failed_to_load_file), Toast.LENGTH_SHORT).show();
            finish();
        }
        // some types don't have export filter so we cannot edit them
        // only set it to false if it returns false otherwise it can break previous controls
        if (!canDocumentBeExported())
            isDocEditable = false;
        if (urlToLoad != null)
        {
            mWebView = (COWebView) findViewById(R.id.browser);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Asset preparation can finish after the initial inset dispatch.
                // Pad the parent so drawing, touch and accessibility move together;
                // changing WebView margins alone can leave its header under system UI.
                View content = findViewById(android.R.id.content);
                content.setOnApplyWindowInsetsListener((v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()
                            | WindowInsets.Type.displayCutout()
                            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ? WindowInsets.Type.systemOverlays() : 0));
                    if (v.getPaddingLeft()!=insets.left || v.getPaddingTop()!=insets.top
                            || v.getPaddingRight()!=insets.right || v.getPaddingBottom()!=insets.bottom)
                        v.setPadding(insets.left,insets.top,insets.right,insets.bottom);
                    return windowInsets;
                });
                content.requestApplyInsets();

                boolean lightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_YES) == 0;
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(lightMode);
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(lightMode);
            }

            mMobileSocket = mWebView.getWebViewClient().getMobileSocket();

            WebSettings webSettings = mWebView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setBlockNetworkLoads(true);
            mWebView.addJavascriptInterface(this, "COOLMessageHandler");

            webSettings.setDomStorageEnabled(true);

            // allow debugging (when building the debug version); see details in
            // https://developers.google.com/web/tools/chrome-devtools/remote-debugging/webviews
            boolean isChromeDebugEnabled = sPrefs.getBoolean("ENABLE_CHROME_DEBUGGING", false);
            if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 || isChromeDebugEnabled) {
                WebView.setWebContentsDebuggingEnabled(false);
            }

            getMainHandler();

            clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            nativeMsgThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Looper.prepare();
                    nativeLooper = Looper.myLooper();
                    nativeHandler = new Handler(nativeLooper);
                    Looper.loop();
                }
            });
            nativeMsgThread.start();

            mWebView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(android.webkit.ConsoleMessage message) { return true; }
                @Override
                public boolean onShowFileChooser(WebView mWebView, ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams) {
                    if (valueCallback != null) {
                        valueCallback.onReceiveValue(null);
                        valueCallback = null;
                    }

                    valueCallback = filePathCallback;
                    Intent intent = fileChooserParams.createIntent();

                    try {
                        intent.setType("image/*");
                        startActivityForResult(intent, REQUEST_SELECT_IMAGE_FILE);
                    } catch (ActivityNotFoundException e) {
                        valueCallback = null;
                        Toast.makeText(LOActivity.this, getString(R.string.cannot_open_file_chooser), Toast.LENGTH_LONG).show();
                        return false;
                    }
                    return true;
                }
            });

            if (!"file".equals(getIntent().getData().getScheme()) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                PrivateLog.i(TAG, "asking for read storage permission");
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        PERMISSION_WRITE_EXTERNAL_STORAGE);
            } else {
                loadDocument();
            }
        }
    }

    /** True when "All files access" permission can help edit the document. */
    private boolean canAllFilesAccessHelp() {
        if (mAllFilesAccessAsked || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            return false;

        if (sPrefs.getBoolean(KEY_ALL_FILES_ACCESS_DECLINED, false))
            return false;

        if (isDocEditable || !canDocumentBeExported())
            return false;

        Uri uri = getIntent().getData();
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()))
            return false;

        if ((getIntent().getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
            return false;

        return !Environment.isExternalStorageManager() && resolveSaveTarget(uri) != null;
    }

    /** Offer the user access to all files, so the document can be saved where it is stored. */
    private void askForAllFilesAccess() {
        mAllFilesAccessAsked = true;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.all_files_access_title));
        builder.setMessage(getString(R.string.all_files_access_message));
        builder.setPositiveButton(getString(R.string.all_files_access_grant),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            showAllFilesAccessSettings();
                    }
                });
        builder.setNegativeButton(getString(R.string.view_only),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        showReadOnlyToast();
                    }
                });
        builder.setNeutralButton(getString(R.string.do_not_ask_again),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        sPrefs.edit().putBoolean(KEY_ALL_FILES_ACCESS_DECLINED, true).apply();
                        showReadOnlyToast();
                    }
                });
        builder.setCancelable(true);
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                showReadOnlyToast();
            }
        });
        builder.show();
    }

    /** Tell the user that the document stays as it is, whatever they do to it here. */
    private void showReadOnlyToast() {
        Toast.makeText(this, getResources().getString(R.string.temp_file_saving_disabled),
                Toast.LENGTH_LONG).show();
    }

    /** Open the system screen where the user turns access to all files on for this app. */
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void showAllFilesAccessSettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, REQUEST_ALL_FILES_ACCESS);
        } catch (ActivityNotFoundException e) {
            PrivateLog.i(TAG, "no per-app screen for access to all files: " + e.getMessage());
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        REQUEST_ALL_FILES_ACCESS);
            } catch (ActivityNotFoundException e2) {
                PrivateLog.i(TAG, "no screen for access to all files at all: " + e2.getMessage());
            }
        }
    }

    /** The place in the filesystem where saving could work, if the app may write it. */
    private File resolveSaveTarget(Uri uri) {
        File file = UriPathResolver.resolve(this, uri);
        if (file == null)
            return null;

        // Access to all files stops at the private directory of another app.
        String path = file.getAbsolutePath();
        if (path.contains("/Android/data/") || path.contains("/Android/obb/"))
            return null;

        return file;
    }

    /** True when the URI and the file describe one and the same document. */
    private boolean isSameFileAsUri(Uri uri, File file) {
        String[] projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst())
                return false;

            int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameColumn < 0 || cursor.isNull(nameColumn)
                    || !cursor.getString(nameColumn).equals(file.getName()))
                return false;

            // A provider that keeps the size of the document to itself leaves the name of the
            // file as the only thing the two can be compared by.
            int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (sizeColumn < 0 || cursor.isNull(sizeColumn))
                return true;

            return cursor.getLong(sizeColumn) == file.length();
        } catch (Exception e) {
            PrivateLog.i(TAG, "no name and size for " + uri + ": " + e.getMessage());
            return false;
        }
    }

    /** The document as a file this app can write, or null when the save has to go elsewhere. */
    private File resolveWritableFile(Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Environment.isExternalStorageManager())
            return null;

        File file = resolveSaveTarget(uri);
        if (file == null || !file.isFile() || !file.canWrite())
            return null;

        // The new content is written beside the document before it is put in place, so the
        // directory has to take a new file as well.
        File directory = file.getParentFile();
        if (directory == null || !directory.canWrite())
            return null;

        if (!isSameFileAsUri(uri, file)) {
            return null;
        }

        return file;
    }

    @Override
    protected void onNewIntent(Intent intent) {

        PrivateLog.i(TAG, "onNewIntent");

        if (documentLoaded && officeModified && officeApprovedChanges) {
            saveApprovedWorkingCopy();
        }

        super.onNewIntent(intent);

        if (documentLoaded) {
            mProgressDialog.indeterminate(R.string.exiting);
        }

        // Closing a document ends the process, so the system holds the intent and opens the picked
        // document in a fresh activity. Finishing first sends the start to a new instance.
        final Intent reopenIntent = new Intent(intent);
        reopenIntent.setClass(this, LOActivity.class);
        finish();
        startActivity(reopenIntent);

        if (!documentLoaded) {
            return;
        }

        getMainHandler().post(new Runnable() {
            @Override
            public void run() {
                documentLoaded = false;
                postMobileMessageNative("BYE");
                //copyTempBackToIntent();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_INTENT_URI, getIntent().getData().toString());
        outState.putInt(KEY_PROVIDER_ID, providerId);
        if (documentUri != null) {
            outState.putString(KEY_DOCUMENT_URI, documentUri.toString());
        }
        //If this activity was opened via contentUri
        outState.putBoolean(KEY_IS_EDITABLE, isDocEditable);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_WRITE_EXTERNAL_STORAGE:
                if (permissions.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadDocument();
                } else {
                    Toast.makeText(this, getString(R.string.storage_permission_required), Toast.LENGTH_SHORT).show();
                    finish();
                    break;
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /** When we get the file via a content: URI, we need to put it to a temp file. */
    private boolean copyFileToTemp() {
        final ContentResolver contentResolver = getContentResolver();
        class CopyThread extends Thread {
            /** Whether copy operation was successful. */
            private boolean result = false;
            @Override
            public void run() {
                InputStream inputStream = null;
                OutputStream outputStream = null;
                // CSV files need a .csv suffix to be opened in Calc.
                String suffix = null;
                @Nullable String intentType = mActivity.getIntent().getType();
                if (mActivity.getIntent().getType() == null) {
                    intentType = getMimeType();
                }
                // K-9 mail uses the first, GMail uses the second variant.
                if ("text/comma-separated-values".equals(intentType) || "text/csv".equals(intentType))
                    suffix = ".csv";
                else if ("application/pdf".equals(intentType))
                    suffix = ".pdf";
                else if ("application/vnd.ms-excel".equals(intentType))
                    suffix = ".xls";
                else if ("application/vnd.ms-powerpoint".equals(intentType))
                    suffix = ".ppt";
                try {
                    try {
                        Uri uri = mActivity.getIntent().getData();
                        inputStream = contentResolver.openInputStream(uri);

                        mTempFile = File.createTempFile("CollaboraOffice", suffix, mActivity.getCacheDir());
                        outputStream = new FileOutputStream(mTempFile);

                        byte[] buffer = new byte[1024];
                        int length;
                        long bytes = 0;
                        while ((length = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, length);
                            bytes += length;
                        }

                        PrivateLog.i(TAG, "Success copying " + bytes + " bytes from " + uri + " to " + mTempFile);
                    } finally {
                        if (inputStream != null)
                            inputStream.close();
                        if (outputStream != null)
                            outputStream.close();
                        result = true;
                    }
                } catch (FileNotFoundException e) {
                    PrivateLog.e(TAG, "file not found: " + e.getMessage());
                    result = false;
                } catch (IOException e) {
                    PrivateLog.e(TAG, "exception: " + e.getMessage());
                    result = false;
                }
            }
        }
        CopyThread copyThread = new CopyThread();
        copyThread.start();
        try {
            // wait for copy operation to finish
            // NOTE: might be useful to add some indicator in UI for long copy operations involving network...
            copyThread.join();
        } catch (InterruptedException e) {
            
        }
        return copyThread.result;
    }

    /** Check that we have created a temp file, and if yes, copy it back to the content: URI. */
    private void copyTempBackToIntent() {
        if (!isDocEditable || mTempFile == null || getIntent().getData() == null || !getIntent().getData().getScheme().equals(ContentResolver.SCHEME_CONTENT))
            return;

        if (mResolvedFile != null) {
            copyTempBackToFile();
            return;
        }

        final ContentResolver contentResolver = getContentResolver();
        try {
            Thread copyThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    InputStream inputStream = null;
                    OutputStream outputStream = null;
                    try {
                        try {
                            studioPublishNativeOutput(mTempFile,getIntent().getData());
                        } finally {
                            if (inputStream != null)
                                inputStream.close();
                            if (outputStream != null)
                                outputStream.close();
                        }
                    } catch (FileNotFoundException e) {
                        PrivateLog.e(TAG, "file not found: " + e.getMessage());
                    } catch (Exception e) {
                        PrivateLog.e(TAG, "exception: " + e.getMessage());
                    }
                }
            });
            copyThread.start();
            copyThread.join();
        } catch (Exception e) {
            PrivateLog.i(TAG, "copyTempBackToIntent: " + e.getMessage());
        }
    }

    /** Copy the temp file back to the document. */
    private void copyTempBackToFile() {
        final File resolvedFile = mResolvedFile;
        if (resolvedFile == null)
            return;

        File newContent = new File(resolvedFile.getParentFile(), "." + resolvedFile.getName() + ".part");
        try {
            long bytes = 0;
            try (InputStream inputStream = new FileInputStream(mTempFile)) {
                int len = inputStream.available();
                if (len <= 0)
                    return;

                try (FileOutputStream outputStream = new FileOutputStream(newContent)) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, length);
                        bytes += length;
                    }
                    outputStream.flush();
                    outputStream.getFD().sync();
                }
            }

            if (!newContent.renameTo(resolvedFile)) {
                PrivateLog.e(TAG, "failed to put the new content in place of " + resolvedFile);
                newContent.delete();
                reportSaveToFileFailed();
                return;
            }

            PrivateLog.i(TAG, "Success copying " + bytes + " bytes from " + mTempFile + " to " + resolvedFile);

            // Make the system read the file again to update meta data.
            MediaScannerConnection.scanFile(this, new String[]{resolvedFile.getAbsolutePath()}, null, null);
        } catch (Exception e) {
            PrivateLog.e(TAG, "copyTempBackToFile: " + e.getMessage());
            newContent.delete();
            reportSaveToFileFailed();
        }
    }

    /** Tell the user that the document still holds the content it had before this save. */
    private void reportSaveToFileFailed() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(LOActivity.this, getString(R.string.failed_to_save_file), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        PrivateLog.i(TAG, "onResume..");
    }

    @Override
    protected void onPause() {
        // A Save similar to an autosave
        if (documentLoaded && officeModified && officeApprovedChanges && studioEditAuthorized)
            saveApprovedWorkingCopy();

        super.onPause();
        PrivateLog.d(TAG, "onPause() - hinting to save, we might need to return to the doc");
    }

    @Override
    protected void onDestroy() {
        stopWatchingForHardwareKeyboard();

        if (!documentLoaded) {
            super.onDestroy();
            return;
        }
        nativeLooper.quit();

        // Remove the webview from the hierarchy & destroy
        final ViewGroup viewGroup = (ViewGroup) mWebView.getParent();
        if (viewGroup != null)
            viewGroup.removeView(mWebView);
        mWebView.destroy();
        mWebView = null;
        mMobileSocket = null;

        // Most probably the native part has already got a 'BYE' from
        // finishWithProgress(), but it is actually better to send it twice
        // than never, so let's call it from here too anyway
        documentLoaded = false;
        postMobileMessageNative("BYE");

        mProgressDialog.dismiss();

        super.onDestroy();
        PrivateLog.i(TAG, "onDestroy() - we know we are leaving the document");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == REQUEST_ALL_FILES_ACCESS) {
            mResolvedFile = resolveWritableFile(getIntent().getData());
            if (mResolvedFile != null) {
                isDocEditable = true;
                callFakeWebsocketOnMessage("mobile: editmode");
                PrivateLog.i(TAG, "Editing enabled, saving back to " + mResolvedFile);
            } else {
                showReadOnlyToast();
            }
            return;
        }

        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_SELECT_IMAGE_FILE) {
                valueCallback.onReceiveValue(null);
                valueCallback = null;
            } else if (requestCode == REQUEST_EXPORT_FILE && mPendingExportSourcePath != null) {
                new File(mPendingExportSourcePath).delete();
                mPendingExportSourcePath = null;
            }
            return;
        }

        /*
            Copy is just save-as in general but with TakeOwnership.
            Meaning that we will switch to the copied (saved-as) document in the bg
            this way we don't need to reload the activity.
        */
        if(requestCode==REQUEST_SAVEAS_PDF && intent!=null && intent.getData()!=null){
            convertStudioPdf(intent.getData());return;
        }
        boolean requestCopy = false;
        if (requestCode == REQUEST_COPY) {
            requestCopy = true;
            if (Objects.equals(getMimeType(), "text/plain")) {
                requestCode = REQUEST_SAVEAS_ODT;
            } else if (Objects.equals(getMimeType(), "text/comma-separated-values")) {
                requestCode = REQUEST_SAVEAS_ODS;
            } else if (Objects.equals(getMimeType(), "application/vnd.ms-excel.sheet.binary.macroenabled.12")) {
                requestCode = REQUEST_SAVEAS_ODS;
            } else {
                String filename = getFileName(true);
                String extension = filename.substring(filename.lastIndexOf('.') + 1);
                requestCode = getRequestIDForFormat(extension);
                assert (requestCode != 0);
            }
        }
        switch (requestCode) {
            case REQUEST_SELECT_IMAGE_FILE:
                if (valueCallback == null)
                    return;
                valueCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, intent));
                valueCallback = null;
                return;
            case REQUEST_EXPORT_FILE: {
                if (intent == null || mPendingExportSourcePath == null) {
                    mPendingExportSourcePath = null;
                    return;
                }
                final File srcFile = new File(mPendingExportSourcePath);
                final Uri destination=intent.getData();
                mPendingExportSourcePath = null;
                if(studioPdfBusy){Toast.makeText(this,"현재 저장이 끝난 뒤 다시 시도하세요.",Toast.LENGTH_LONG).show();return;}
                studioPdfBusy=true;
                final android.app.ProgressDialog progress=new android.app.ProgressDialog(this);
                progress.setMessage("파일 저장·검증 중");progress.setCancelable(false);progress.show();
                mWebView.setEnabled(false);
                new Thread(()->{
                    try {studioPublishNativeOutput(srcFile,destination);srcFile.delete();}
                    catch(Exception failure){final String message=studioOutputError(failure);runOnUiThread(()->Toast.makeText(this,message,Toast.LENGTH_LONG).show());}
                    finally {runOnUiThread(()->{studioPdfBusy=false;if(mWebView!=null)mWebView.setEnabled(true);if(!isFinishing())progress.dismiss();});}
                },"studio-output-publication").start();
                return;
            }
            case REQUEST_SAVEAS_PDF:
            case REQUEST_SAVEAS_RTF:
            case REQUEST_SAVEAS_ODT:
            case REQUEST_SAVEAS_ODP:
            case REQUEST_SAVEAS_ODS:
            case REQUEST_SAVEAS_DOCX:
            case REQUEST_SAVEAS_PPTX:
            case REQUEST_SAVEAS_XLSX:
            case REQUEST_SAVEAS_DOC:
            case REQUEST_SAVEAS_PPT:
            case REQUEST_SAVEAS_XLS:
            case REQUEST_SAVEAS_EPUB:
                if (intent == null) {
                    return;
                }
                String format = getFormatForRequestCode(requestCode);
                File _tempFile = null;
                if (format != null) {
                    InputStream inputStream = null;
                    OutputStream outputStream = null;
                    try {
                        final File tempFile = File.createTempFile("CollaboraOffice", "." + format, this.getCacheDir());
                        LOActivity.this.saveAs(tempFile.toURI().toString(), format, requestCopy && !(getApplication() instanceof StudioDocumentHost) ? "TakeOwnership" : null);

                        studioPublishNativeOutput(tempFile,intent.getData());
                        _tempFile = tempFile;
                    } catch (Exception e) {
                        Toast.makeText(this, studioOutputError(e), Toast.LENGTH_SHORT).show();
                        
                    } finally {
                        try {
                            if (inputStream != null)
                                inputStream.close();
                            if (outputStream != null)
                                outputStream.close();
                        } catch (Exception e) {
                        }
                    }
                    if (requestCopy == true && _tempFile != null && !(getApplication() instanceof StudioDocumentHost)) {
                        mTempFile = _tempFile;
                        getIntent().setData(intent.getData());
                        mResolvedFile = null;
                        /** add the document to recents */
                        addIntentToRecents(intent);
                        callFakeWebsocketOnMessage("mobile: editmode");
                        isDocEditable = true;
                    }
                    return;
                }
                break;
        }
        Toast.makeText(this, "Unknown request", Toast.LENGTH_LONG).show();
    }

    private void addIntentToRecents(Intent intent) {
        Uri treeFileUri = intent.getData();
        SharedPreferences recentPrefs = getSharedPreferences(EXPLORER_PREFS_KEY, MODE_PRIVATE);
        String recentList =  recentPrefs.getString(RECENT_DOCUMENTS_KEY, "");
        recentList = treeFileUri.toString() + "\n" + recentList;
        recentPrefs.edit().putString(RECENT_DOCUMENTS_KEY, recentList).apply();
    }

    private String getFormatForRequestCode(int requestCode) {
        switch(requestCode) {
            case REQUEST_SAVEAS_PDF: return "pdf";
            case REQUEST_SAVEAS_RTF: return "rtf";
            case REQUEST_SAVEAS_ODT: return "odt";
            case REQUEST_SAVEAS_ODP: return "odp";
            case REQUEST_SAVEAS_ODS: return "ods";
            case REQUEST_SAVEAS_DOCX: return "docx";
            case REQUEST_SAVEAS_PPTX: return "pptx";
            case REQUEST_SAVEAS_XLSX: return "xlsx";
            case REQUEST_SAVEAS_DOC: return "doc";
            case REQUEST_SAVEAS_PPT: return "ppt";
            case REQUEST_SAVEAS_XLS: return "xls";
            case REQUEST_SAVEAS_EPUB: return "epub";
        }
        return null;
    }

    /** Show the Saving progress and finish the app. */
    private volatile java.util.concurrent.CountDownLatch closeSave;
    private volatile boolean closeSaveSucceeded;
    private boolean studioSaveOriginal;
    protected boolean shouldSaveOriginal() { return studioSaveOriginal; }
    protected void onOfficeClosed() { }
    protected void onPristineViewerClosed() { mProgressDialog.dismiss(); onOfficeClosed(); finish(); }
    protected void onOfficeBeforeClose() throws IOException { }
    protected void onOfficeLifecycle(String stage) { }
    protected void onOfficeSaved() throws IOException { }
    protected void onOfficeLoadFailed(String error) { }
    protected String readOfficeViewState() { return "{}"; }
    protected void writeOfficeViewState(String state) { }
    protected boolean hasApprovedOfficeOutput() { return officeSavedApproved; }

    @JavascriptInterface
    public String getStudioViewState() { return readOfficeViewState(); }

    @JavascriptInterface
    public String getStudioDocumentName() {
        String name = getIntent().getStringExtra("displayName");
        return name == null || name.trim().isEmpty() ? "Office 문서" : name;
    }

    @JavascriptInterface
    public void saveStudioViewState(String state) {
        if (state != null && state.length() <= 4096) writeOfficeViewState(state);
    }

    @JavascriptInterface
    public boolean requestStudioEdit() {
        if (isStudioConversionOnly()) return false;
        if (!isDocEditable) { onOfficeLifecycle("edit-refused-capability"); return false; }
        if (!documentLoaded) { onOfficeLifecycle("edit-refused-not-loaded"); return false; }
        if (officeLoadFailed) { onOfficeLifecycle("edit-refused-load-error"); return false; }
        if (closeSave != null) { onOfficeLifecycle("edit-refused-closing"); return false; }
        studioEditAuthorized = true;
        studioEditEverAuthorized = true;
        onOfficeLifecycle("edit-authorized");
        return true;
    }

    private void saveApprovedWorkingCopy() {
        if (!officeModified || !officeApprovedChanges || officeLoadFailed) return;
        officeSaveInFlight = true;
        // Back commits the current cell before entering read mode. Temporarily
        // permit only this native save; the Java/JS input gates remain closed.
        if (!studioEditAuthorized) postMobileMessageNative("setviewreadonly value=false");
        postMobileMessageNative("save dontTerminateEdit=1 dontSaveIfUnmodified=1");
        if (!studioEditAuthorized) postMobileMessageNative("setviewreadonly value=true");
    }

    private void observeSaveResult(JSONObject result) {
        if (!".uno:Save".equals(result.optString("commandName"))) return;
        if (officeSaveInFlight && result.optBoolean("success", false)) {
            try {
                onOfficeSaved();
                officeSavedApproved = true;
            } catch (IOException failure) {
                officeSaveInFlight = false;
                if (closeSave != null) { closeSaveSucceeded = false; closeSave.countDown(); }
                runOnUiThread(() -> Toast.makeText(this, "편집 결과 기록을 확인하지 못했습니다. 다시 저장해 주세요.", Toast.LENGTH_LONG).show());
                return;
            }
        }
        officeSaveInFlight = false;
        confirmCloseSave(result);
    }

    private void confirmCloseSave(JSONObject result) {
        java.util.concurrent.CountDownLatch pending = closeSave;
        if (pending == null || !".uno:Save".equals(result.optString("commandName"))) return;
        JSONObject value = result.optJSONObject("result");
        closeSaveSucceeded = result.optBoolean("success", false)
                || (value != null && "unmodified".equals(value.optString("value")));
        pending.countDown();
    }

    private void finishWithProgress() {
        if (studioPdfBusy) { Toast.makeText(this, "파일을 저장·검증하는 중입니다. 완료 후 닫아 주세요.", Toast.LENGTH_SHORT).show(); return; }
        onOfficeLifecycle("close-requested");
        if (!documentLoaded) {
            finish();
            return;
        }
        if (closeSave != null) return;
        closeSaveSucceeded = false;
        closeSave = new java.util.concurrent.CountDownLatch(1);
        mProgressDialog.indeterminate(R.string.exiting);

        // The 'BYE' blocks until the document has closed, so it runs off the UI thread and the
        // progress dialog keeps drawing.
        nativeHandler.post(() -> {
            onOfficeLifecycle("close-worker");
            OfficePrivacy.requireQuiet();
            if (officeModified && officeApprovedChanges) {
                saveApprovedWorkingCopy();
            }
            else { closeSaveSucceeded = true; closeSave.countDown(); }
            boolean saved = false;
            try { saved = closeSave.await(20, java.util.concurrent.TimeUnit.SECONDS) && closeSaveSucceeded; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (!saved) {
                closeSave = null;
                runOnUiThread(() -> {
                    mProgressDialog.dismiss();
                    Toast.makeText(this, "저장을 확인하지 못했습니다. 저장 버튼을 누른 뒤 다시 닫아 주세요.", Toast.LENGTH_LONG).show();
                });
                return;
            }
            try { onOfficeBeforeClose(); }
            catch (IOException failure) {
                closeSave = null;
                runOnUiThread(() -> {
                    mProgressDialog.dismiss();
                    Toast.makeText(this, "종료 요청을 기록하지 못했습니다. 작업 파일을 보존했습니다. 다시 닫아 주세요.", Toast.LENGTH_LONG).show();
                });
                return;
            }
            documentLoaded = false;
            // This final host owns an isolated process and a private working copy.
            // A successfully loaded session that NEVER authorized editing has no
            // document changes to flush. The durable close request above already
            // seals its bytes. Finish the viewer now; StudioOfficeActivity releases
            // the file lock and ends only this private process in onDestroy().
            // Edited, saving, failed-load and active PDF conversion sessions retain
            // the complete acknowledged-save + native BYE path below.
            if (this instanceof StudioOfficeActivity && !isStudioConversionOnly() && officeLoadFinished && !officeLoadFailed
                    && !studioEditEverAuthorized && !officeModified && !officeApprovedChanges
                    && !officeSaveInFlight && !studioPdfBusy) {
                onOfficeLifecycle("readonly-close-sealed");
                runOnUiThread(() -> {
                    java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
                    Runnable complete = () -> {
                        if (completed.compareAndSet(false, true)) {
                            onPristineViewerClosed();
                        }
                    };
                    // System Back must capture the last scroll too. A dead renderer
                    // cannot hold an unchanged viewer open indefinitely.
                    getMainHandler().postDelayed(complete, 250);
                    try {
                        mWebView.evaluateJavascript("window.studioOffice?.flushView?.()", ignored -> {
                            getMainHandler().removeCallbacks(complete); complete.run();
                        });
                    } catch (RuntimeException failure) {
                        getMainHandler().removeCallbacks(complete); complete.run();
                    }
                });
                return;
            }
            onOfficeLifecycle("sending-native-bye");
            postMobileMessageNative("BYE");
            onOfficeLifecycle("native-bye-returned");
            //copyTempBackToIntent();

            runOnUiThread(() -> { mProgressDialog.dismiss(); onOfficeLifecycle("reporting-closed"); onOfficeClosed(); finish(); });
        });
    }

    private void loadDocument() {
        OfficePrivacy.requireQuiet();
        mProgressDialog.determinate(R.string.loading);

        // setup the COOLWSD
        ApplicationInfo applicationInfo = getApplicationInfo();
        String dataDir = applicationInfo.dataDir;
        PrivateLog.i(TAG, String.format("Initializing COKit, dataDir=%s\n", dataDir));

        String cacheDir = getApplication().getCacheDir().getAbsolutePath();
        String apkFile = getApplication().getPackageResourcePath();
        AssetManager assetManager = getResources().getAssets();
        String uiMode = (isLargeScreen() && !isChromeOS()) ? "notebookbar" : "classic";
        String userName = getPrefs().getString(USER_NAME_KEY, "Guest User");
        createCOOLWSD(dataDir, cacheDir, apkFile, assetManager, urlToLoad, uiMode, userName);

        // trigger the load of the document
        String finalUrlToLoad = "file:///android_asset/dist/cool.html?file_path=" +
                urlToLoad + "&closebutton=1";

        // set the language
        String language = getResources().getConfiguration().locale.toLanguageTag();

        PrivateLog.i(TAG, "Loading with language:  " + language);

        finalUrlToLoad += "&lang=" + language;
        // Keep edit capability, while entering an actual core read-only view.
        finalUrlToLoad += "&startreadonly=true&studioopen="
                + ("EDIT".equals(getIntent().getStringExtra("openIntent")) ? "EDIT" : "VIEW");

        if (isDocEditable) {
            finalUrlToLoad += "&permission=edit";
        } else {
            finalUrlToLoad += "&permission=readonly";
        }

        if (isDocDebuggable) {
            finalUrlToLoad += "&debug=true";
        }

        if (isLargeScreen() && !isChromeOS())
            finalUrlToLoad += "&userinterfacemode=notebookbar";

        mDarkModeApplied = isDarkMode();
        if (mDarkModeApplied) {
            finalUrlToLoad += "&darkTheme=true";
        }

        // load the page
        mWebView.loadUrl(finalUrlToLoad);

        documentLoaded = true;

        loadDocumentMillis = android.os.SystemClock.uptimeMillis();
    }

    private boolean isDarkMode() {
        SharedPreferences recentPrefs = getSharedPreferences(EXPLORER_PREFS_KEY, MODE_PRIVATE);
        int mode = recentPrefs.getInt(NIGHT_MODE_KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        switch (mode) {
            case -1:
                int darkModeFlag = getBaseContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                return darkModeFlag == Configuration.UI_MODE_NIGHT_YES;
            case 1:
                return false;
            case 2:
                return true;
        }
        return false;
    }

    static {
        System.loadLibrary("androidapp");
    }

    /**
     * Used for determining tablets
     */
    public boolean isLargeScreen() {
        return getResources().getBoolean(R.bool.isLargeScreen);
    }

    public SharedPreferences getPrefs() {
        return sPrefs;
    }

    /**
     * Initialize the COOLWSD to load 'loadFileURL'.
     */
    public native void createCOOLWSD(String dataDir, String cacheDir, String apkFile, AssetManager assetManager, String loadFileURL, String uiMode, String userName);

    /** Cached focus avoids synchronously querying WebView during a hardware paste event. */
    @JavascriptInterface
    public void studioSetUiTextFocus(boolean focused) {
        studioUiTextInputFocused = focused;
    }

    boolean studioBlocksDocumentPaste() {
        return this instanceof StudioOfficeActivity && !studioEditAuthorized && !studioUiTextInputFocused;
    }

    /**
     * Passing messages from JS (instead of the websocket communication).
     */
    @JavascriptInterface
    @UsedByNative // androidapp.cpp postDirectMessage()
    public void postMobileMessage(String message) {
        OfficePrivacy.requireQuiet();
        if (message.startsWith("SAVE ") && closeSave != null) {
            try { observeSaveResult(new JSONObject(message.substring(5))); }
            catch (JSONException malformed) { /* A malformed acknowledgement cannot confirm a save. */ }
        }
        PrivateLog.d(TAG, "postMobileMessage: " + message);

        String[] messageAndParameterArray= message.split(" ", 2); // the command and the rest (that can potentially contain spaces too)

        if (beforeMessageFromWebView(messageAndParameterArray)) {
            postMobileMessageNative(message);
            // Preserve queue order: initial native load, then a read-only core view,
            // before any user input. readonly=1 would forbid later explicit editing.
            if (message.startsWith("load ")) postMobileMessageNative("setviewreadonly value=true");
            afterMessageFromWebView(messageAndParameterArray);
        }
    }

    /**
     * Call the post method form C++
     */
    public native void postMobileMessageNative(String message);

    /**
     * Passing messages from JS (instead of the websocket communication).
     */
    @JavascriptInterface
    public void postMobileError(String message) {
        if (!officeLoadFinished) reportOfficeLoadFailure("오피스 엔진을 시작하지 못했습니다.");
    }

    /**
     * Passing messages from JS (instead of the websocket communication).
     */
    public void reportOfficeLoadFailure(String error) {
        officeLoadFailed = true;
        runOnUiThread(() -> onOfficeLoadFailed(error));
    }

    @JavascriptInterface
    public void postMobileDebug(String message) {
        // TODO handle this
        PrivateLog.d(TAG, "postMobileDebug: " + message);
    }

    /**
     * Provide the info that this app is actually running under ChromeOS - so
     * has to mostly look like on desktop.
     */
    @JavascriptInterface
    public boolean isChromeOS() {
        return isChromeOS(this);
    }

    /**
     * Tell the JavaScript side whether a physical keyboard is attached.
     */
    @JavascriptInterface
    public boolean hasHardwareKeyboard() {
        return mHardwareKeyboardAttached;
    }

    /**
     * Passing message the other way around - from Java to the FakeWebSocket in JS.
     */
    void callFakeWebsocketOnMessage(final String message) {
        rawCallFakeWebsocketOnMessage(message.getBytes());
    }

    /**
     * Similar to callFakeWebsocketOnMessage but 'message' is instead any expression evaluable as
     * JavaScript. For example, you should use this to pass Base64ToArrayBuffer invocations to
     * the fake websocket
     */
    @UsedByNative // androidapp.cpp send2JS
    void rawCallFakeWebsocketOnMessage(final byte[] message) {
        // Native state is authoritative even when the browser closes its socket.
        if (messageStartsWith(message, "status:")) officeLoadFinished = true;
        if (messageStartsWith(message, "statechanged:")) {
            String state = new String(message, java.nio.charset.StandardCharsets.UTF_8).substring("statechanged:".length()).trim();
            String modified = null;
            try {
                if (state.startsWith("{")) {
                    JSONObject value = new JSONObject(state);
                    if (".uno:ModifiedStatus".equals(value.optString("commandName"))) modified = value.optString("state");
                } else if (state.startsWith(".uno:ModifiedStatus=")) modified = state.substring(".uno:ModifiedStatus=".length()).trim();
            } catch (JSONException ignored) { }
            if (modified != null) {
                officeModified = "true".equals(modified);
                if (officeModified && studioEditEverAuthorized) officeApprovedChanges = true;
            }
        }
        if (messageStartsWith(message, "error:")) {
            String failure = new String(message, java.nio.charset.StandardCharsets.UTF_8);
            if (!officeLoadFinished && !failure.toLowerCase(java.util.Locale.ROOT).contains("password")) {
                officeLoadFailed = true;
                runOnUiThread(() -> onOfficeLoadFailed("오피스 문서를 열지 못했습니다."));
            }
        }
        if (messageStartsWith(message, "unocommandresult:")) {
            try {
                String response = new String(message, java.nio.charset.StandardCharsets.UTF_8);
                observeSaveResult(new JSONObject(response.substring(response.indexOf('{'))));
            } catch (Exception malformed) { /* Keep the old baseline if acknowledgement is invalid. */ }
        }
        try {
            mMobileSocket.queueSend(message, () -> {
                mWebView.post(() -> {
                    mWebView.loadUrl("javascript:window.socket.doSend();");
                });
            });
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // update progress bar when loading
        if (messageStartsWith(message, "progress")) {
            runOnUiThread(() -> {
                JSONObject messageJSON;
                String messageID;
                String messageString;
                try {
                    messageString = new String(message, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }

                int jsonStart = messageString.indexOf("{");
                if (jsonStart == -1) {
                    return;
                }

                try {
                    messageJSON = new JSONObject(messageString.substring(jsonStart));
                    messageID = messageJSON.getString("id");
                } catch (JSONException e) {
                    return;
                }

                if (messageID.equals("finish")) {
                    mProgressDialog.dismiss();
                    if (BuildConfig.GOOGLE_PLAY_ENABLED && rateAppController != null) { /* Host has no rating prompt. */ }
                        
                    return;
                }

                try {
                    String text = messageJSON.getString("text");
                    mProgressDialog.message(text);
                } catch (JSONException ignored) {}

                try {
                    int progress = messageJSON.getInt("value");
                    mProgressDialog.determinateProgress(progress);
                } catch (JSONException ignored) {}
            });
        } else if (messageStartsWith(message, "error:")) {
            runOnUiThread(() -> mProgressDialog.dismiss());
        } else if (messageStartsWith(message, "unocommandresult:")
                   && mPendingCopyToSystemClipboard.get()) {
            final String result = new String(message, Charset.forName("UTF-8"));
            if ((result.contains(".uno:Copy") || result.contains(".uno:Cut"))
                    && mPendingCopyToSystemClipboard.compareAndSet(true, false))
                clipboardExecutor.execute(this::populateClipboard);
        }
    }

    /**
     * @param message The message to test for the prefix
     * @param prefix The prefix to test for
     * @return true if the decoded message starts with the prefix, else false
     */
    private static boolean messageStartsWith(byte[] message, String prefix) {
        byte[] prefixBytes = prefix.getBytes();
        if (message.length < prefixBytes.length)
            return false;
        for (int i = 0; i < prefixBytes.length; i++) {
            if (message[i] != prefixBytes[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * return true to pass the message to the native part or false to block the message
     */
    private boolean beforeMessageFromWebView(String[] messageAndParam) {
        if (messageAndParam.length == 0) return false;
        String command = messageAndParam[0];
        String argument = messageAndParam.length > 1 ? messageAndParam[1] : "";
        if (this instanceof StudioOfficeActivity && !studioEditAuthorized) {
            // Native view readonly is the primary barrier; these guards additionally
            // stop keyboard/IME, paste and object commands at the Android bridge.
            if (command.equals("key") || command.equals("textinput") || command.equals("paste")
                    || command.equals("windowkey") || command.equals("insertfile")
                    || command.equals("removetextcontext") || command.equals("formfieldevent")
                    || command.equals("contentcontrolevent")
                    || command.equals("graphicselection") || command.equals("selectgraphic")
                    || (command.equals("setviewreadonly") && argument.contains("value=false"))) return false;
            if (command.equals("uno") && !OfficeEditPolicy.allowsViewUno(argument)) return false;
            if (command.equals("save")) return false;
        }
        if (command.equals("save") || (command.equals("uno") && (argument.equals(".uno:Save") || argument.startsWith(".uno:Save ")))) {
            if (!officeModified || !officeApprovedChanges) return false;
            officeSaveInFlight = true;
        }
        switch (command) {
            case "DOCUMENT_STUDIO_CONVERT_PDF":
                runOnUiThread(() -> initiateSaveAs("format=pdf"));
                return false;
            case "DOCUMENT_STUDIO_SAVE_AND_CLOSE":
                studioSaveOriginal = officeApprovedChanges || officeSavedApproved;
                runOnUiThread(this::finishWithProgress);
                return false;
            case "BYE":
                runOnUiThread(this::finishWithProgress);
                return false;
            case "PRINT":
                getMainHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        LOActivity.this.initiatePrint();
                    }
                });
                return false;
            case "SAVE":
                copyTempBackToIntent();
                sendBroadcast(messageAndParam[0], messageAndParam[1]);
                return false;
            case "downloadas":
                initiateSaveAs(messageAndParam[1]);
                return false;
            case "exportfile":
                initiateExportFile(messageAndParam[1]);
                return false;
            case "uno":
                switch (messageAndParam[1]) {
                    case ".uno:Paste":
                        return performPaste(false);
                    case ".uno:PasteSpecial":
                        return performPaste(true);
                    default:
                        break;
                }
                break;
            case "DIM_SCREEN": {
                getMainHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                });
                return false;
            }
            case "LIGHT_SCREEN": {
                getMainHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                });
                return false;
            }
            case "MOBILEWIZARD": {
                switch (messageAndParam[1]) {
                    case "show":
                        mMobileWizardVisible = true;
                        break;
                    case "hide":
                        mMobileWizardVisible = false;
                        break;
                }
                return false;
            }
            case "HYPERLINK": {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(messageAndParam[1]));
                startActivity(intent);
                return false;
            }
            case "EDITMODE": {
                switch (messageAndParam[1]) {
                    case "on":
                        if (!studioEditAuthorized) {
                            runOnUiThread(() -> callFakeWebsocketOnMessage("mobile: readonlymode"));
                            return false;
                        }
                        mIsEditModeActive = true;
                        // Studio edits the existing work copy in its original format.
                        // Format conversion is a separate explicit export action.
                        if (!(this instanceof StudioOfficeActivity))
                            runOnUiThread(this::requestForOdf);
                        break;
                    case "off":
                        mIsEditModeActive = false;
                        studioEditAuthorized = false;
                        break;
                }
                return false;
            }
            case "SLIDESHOW": {
                switch (messageAndParam[1]) {
                    case "on":
                        mSlideShowActive = true;
                        break;
                    case "off":
                        mSlideShowActive = false;
                        break;
                }
                return false;
            }
            case "hideProgressbar": {
                if (mProgressDialog != null)
                    mProgressDialog.dismiss();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (canAllFilesAccessHelp())
                            askForAllFilesAccess();
                    }
                });
                return false;
            }
            case "loadwithpassword": {
                mProgressDialog.determinate(R.string.loading);
                return true;
            }
            case "REQUESTFILECOPY": {
                requestForCopy();
                return false;
            }
        }
        return true;
    }

    public static void createNewFileInputDialog(Activity activity, final String defaultFileName, final @Nullable String mimeType, final int requestCode) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);

        // The mime type and category must be set
        i.setType(mimeType);
        i.addCategory(Intent.CATEGORY_OPENABLE);

        i.putExtra(Intent.EXTRA_TITLE, defaultFileName);

        // Try to default to the Documents folder
        Uri documentsUri = Uri.parse("content://com.android.externalstorage.documents/document/home%3A");
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentsUri);

        activity.startActivityForResult(i, requestCode);
    }

    private AlertDialog.Builder buildPrompt(final String mTitle, final String mMessage, final String mPositiveBtnText, final String mNegativeBtnText, DialogInterface.OnClickListener callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(mTitle);
        if (mMessage.length() > 0)
            builder.setMessage(mMessage);
        builder.setPositiveButton(mPositiveBtnText, callback);
        builder.setNegativeButton(mNegativeBtnText, null);
        builder.setCancelable(false);
        return builder;
    }

    private @Nullable String getMimeType() {
        ContentResolver cR = getContentResolver();

        Uri data = getIntent().getData();
        if (data == null) return null;

        return cR.getType(data);
    }

    private String getFileName(boolean withExtension) {
        Cursor cursor = null;
        String filename = this instanceof StudioOfficeActivity ? getIntent().getStringExtra("displayName") : null;
        if (filename != null && (filename.contains("/") || filename.contains("\\") || filename.contains("\n") || filename.contains("\r"))) filename = null;
        if (filename == null && "file".equals(getIntent().getData().getScheme())) filename = getIntent().getData().getLastPathSegment();
        try {
            if (filename == null) cursor = getContentResolver().query(getIntent().getData(), null, null, null, null);
            if (cursor != null && cursor.moveToFirst())
                filename = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
        } catch (Exception e) {
            return null;
        } finally {
            if (cursor != null)
                cursor.close();
        }
        if (filename != null && !withExtension) {
            int dot = filename.lastIndexOf('.');
            if (dot > 0)
                filename = filename.substring(0, dot);
        }
        return filename;
    }

    /**
     * Returns the name to offer in a save dialog for document export. The base name
     * is the display name the content provider reports for the document.
     */
    private String getExportDisplayName(String exportedName) {
        String displayName = getFileName(false);
        if (displayName == null || displayName.isEmpty())
            return exportedName;

        int dot = exportedName.lastIndexOf('.');
        String extension = dot >= 0 ? exportedName.substring(dot) : "";
        return displayName + extension;
    }

    private void requestForCopy() {
        final boolean canBeExported = canDocumentBeExported();
        buildPrompt(getString(R.string.ask_for_copy), "", canBeExported ? getString(R.string.edit_copy) : getString(R.string.use_odf), getString(R.string.view_only), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                if (canBeExported)
                    createNewFileInputDialog(mActivity, getFileName(true), getMimeType(), REQUEST_COPY);
                else {
                    String extension = getOdfExtensionForDocType(getMimeType());
                    createNewFileInputDialog(mActivity, getFileName(false) + "." + extension, getMimeForFormat(extension), REQUEST_COPY);
                }

            }
        }).show();
    }

    // readonly formats here
    private boolean canDocumentBeExported() {
        if (Objects.equals(getMimeType(), "application/vnd.ms-excel.sheet.binary.macroenabled.12")) {
            return false;
        }
        return true;
    }

    private String getOdfExtensionForDocType(@Nullable String mimeType)
    {
        String extTemp = null;
        if (Objects.equals(mimeType, "text/plain")) {
            extTemp = "odt";
        } else if (Objects.equals(mimeType, "text/comma-separated-values")) {
            extTemp = "ods";
        } else if (Objects.equals(mimeType, "application/vnd.ms-excel.sheet.binary.macroenabled.12")) {
            extTemp = "ods";
        }
        return extTemp;
    }

    private void requestForOdf() {
        String extTemp = getOdfExtensionForDocType(getMimeType());
        if (extTemp == null)
            // this means we don't need to request for odf type.
            return;
        final String ext = extTemp;
        buildPrompt(getString(R.string.ask_for_convert_odf), getString(R.string.convert_odf_message), getString(R.string.use_odf), getString(R.string.use_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                createNewFileInputDialog(mActivity, getFileName(false) + "." + ext, getMimeForFormat(ext), REQUEST_COPY);
            }
        }).show();
    }

    private void initiateExportFile(String optionsString) {
        // The kit's COKitCallbackType::EXPORT_FILE branch sends "exportfile url=file:///..."
        // for export flows like .uno:ExportToPDF. The file is already at a tmp
        // path; show ACTION_CREATE_DOCUMENT and copy on result.
        Map<String, String> optionsMap = new HashMap<>();
        for (String option : optionsString.split(" ")) {
            String[] keyValue = option.split("=", 2);
            if (keyValue.length == 2)
                optionsMap.put(keyValue[0], keyValue[1]);
        }
        String urlString = optionsMap.get("url");
        if (urlString == null) {
            PrivateLog.e(TAG, "exportfile: missing url=");
            return;
        }
        Uri srcUri = Uri.parse(urlString);
        String srcPath = srcUri.getPath();
        if (srcPath == null) {
            PrivateLog.e(TAG, "exportfile: bad url=" + urlString);
            return;
        }
        File srcFile = new File(srcPath);
        if (!srcFile.exists()) {
            PrivateLog.e(TAG, "exportfile: source file missing: " + srcPath);
            return;
        }

        String filename = getExportDisplayName(srcFile.getName());
        int dot = filename.lastIndexOf('.');
        String extension = dot > 0 ? filename.substring(dot + 1).toLowerCase() : "";
        String mime = getMimeForFormat(extension);
        if (mime == null)
            mime = "application/octet-stream";

        mPendingExportSourcePath = srcPath;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, false);
        File folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.fromFile(folder).toString());
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        startActivityForResult(intent, REQUEST_EXPORT_FILE);
    }

    private StudioDocumentHost studioStorage() throws java.io.IOException {
        if(!(getApplication() instanceof StudioDocumentHost))throw new java.io.IOException("안전한 저장 호스트를 확인하지 못했습니다.");
        return (StudioDocumentHost)getApplication();
    }
    private void studioPublishNativeOutput(File file,Uri destination) throws Exception {
        if(destination==null)throw new java.io.IOException("저장할 파일을 선택하세요.");
        File canonical=file.getCanonicalFile();
        String cacheRoot=getCacheDir().getCanonicalPath()+File.separator;
        String session=getIntent().getStringExtra("sessionId");
        File documentRoot=session!=null && session.matches("[a-zA-Z0-9-]+") ? new File(getFilesDir(),"documents/"+session).getCanonicalFile() : null;
        if(!canonical.getPath().startsWith(cacheRoot) && (documentRoot==null || !canonical.getParentFile().equals(documentRoot)))
            throw new java.io.IOException("문서의 작업 파일만 내보낼 수 있습니다.");
        studioStorage().publishResult(canonical,destination,getIntent().getStringExtra("originalUri"));
    }
    private String studioOutputError(Throwable failure) {
        try {return studioStorage().describeFailure(failure);}
        catch(Exception ignored){return "저장을 완료하지 못했습니다. 작업 파일을 보존했습니다. 저장 복구 또는 다른 이름으로 저장을 선택하세요.";}
    }
    private boolean studioPdfBusy;
    private volatile String studioPdfError;
    /** Direct native PDF filter; no print UI, original ownership or screenshot conversion. */
    private void convertStudioPdf(Uri destination) {
        if(studioPdfBusy)return;
        // Loading the WebView URL is not completion of native document loading.
        // The JNI filter dereferences the document; never call it before status:.
        if (!officeLoadFinished || officeLoadFailed || !documentLoaded || closeSave != null || isFinishing()) {
            studioPdfError="문서를 아직 준비하지 못했습니다. 문서가 표시된 뒤 다시 변환하세요.";
            Toast.makeText(this,studioPdfError,Toast.LENGTH_LONG).show();return;
        }
        if(getApplication() instanceof StudioDocumentHost && ((StudioDocumentHost)getApplication()).sameDocument(destination.toString(),getIntent().getStringExtra("originalUri"))){
            Toast.makeText(this,"원본과 다른 파일을 선택하세요.",Toast.LENGTH_LONG).show();return;
        }
        onPdfConversionStarted();
        studioPdfError=null;
        studioPdfBusy=true;
        final android.app.ProgressDialog progress=new android.app.ProgressDialog(this);
        progress.setMessage("PDF 변환 중 · 전체 문서 레이아웃 처리");progress.setCancelable(false);
        final java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean publishing=new java.util.concurrent.atomic.AtomicBoolean(false);
        progress.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,"변환 취소",(dialog,which)->{});progress.show();
        progress.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener(view->{
            synchronized(cancelled) {
                if(publishing.get()){Toast.makeText(this,"파일을 저장·검증하는 중입니다. 완료 후 닫아 주세요.",Toast.LENGTH_SHORT).show();return;}
                cancelled.set(true);progress.setMessage("변환 취소 요청을 확인하는 중입니다.");
            }
        });
        mWebView.setEnabled(false);
        new Thread(() -> {
            File stage=null;
            try {
                stage=File.createTempFile("studio-pdf-",".pdf",getCacheDir());
                saveAs(stage.toURI().toString(),"pdf",null);
                if(stage.length()<10 || stage.length()>128L*1024*1024)throw new java.io.IOException("PDF 파일을 만들지 못했습니다.");
                int pages;
                try(android.os.ParcelFileDescriptor fd=android.os.ParcelFileDescriptor.open(stage,android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                    android.graphics.pdf.PdfRenderer renderer=new android.graphics.pdf.PdfRenderer(fd)){
                    pages=renderer.getPageCount();if(pages<1)throw new java.io.IOException("PDF 페이지가 없습니다.");
                    try(android.graphics.pdf.PdfRenderer.Page page=renderer.openPage(0)){
                        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(128,128,android.graphics.Bitmap.Config.ARGB_8888);
                        try{page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);}finally{bitmap.recycle();}
                    }
                }
                synchronized(cancelled) {
                    if(cancelled.get())throw new java.io.IOException("변환을 취소했습니다.");
                    publishing.set(true);
                }
                runOnUiThread(()->{progress.setMessage("PDF 저장·검증 중");progress.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setEnabled(false);});
                studioPublishNativeOutput(stage,destination);
                final int count=pages;runOnUiThread(()->{
                    onPdfConversionCompleted(destination);
                    new AlertDialog.Builder(this).setTitle("PDF 변환 완료").setMessage(count+"쪽 PDF를 저장하고 다시 읽어 검증했습니다.").setPositiveButton("결과 열기",(d,w)->{
                        Intent view=new Intent().setClassName(getPackageName(),"kr.documentstudio.ConversionPreviewActivity").setData(destination).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(view);
                    }).setNegativeButton("닫기",null).show();
                });
            }catch(Exception failure){
                studioPdfError=cancelled.get() && !publishing.get() ? "변환을 취소했습니다. 원본은 변경하지 않았습니다." : studioOutputError(failure);
                runOnUiThread(()->Toast.makeText(this,studioPdfError,Toast.LENGTH_LONG).show());
            }finally{
                if(stage!=null)stage.delete();
                runOnUiThread(()->{studioPdfBusy=false;if(mWebView!=null)mWebView.setEnabled(true);if(!isFinishing())progress.dismiss();});
            }
        },"studio-pdf-conversion").start();
    }
    private static String studioFileDigest(File file) throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        return android.util.Base64.encodeToString(digest.digest(),android.util.Base64.NO_WRAP);
    }
    @JavascriptInterface public boolean isStudioConversionOnly(){return getIntent().getBooleanExtra("conversionOnly",false);}
    protected void onPdfConversionStarted() { }
    protected void onPdfConversionCompleted(Uri destination) { }
    private static void studioCopyStream(InputStream input,OutputStream out) throws java.io.IOException {byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1)out.write(b,0,n);}
    private void initiateSaveAs(String optionsString) {
        Map<String, String> optionsMap = new HashMap<>();
        String[] options = optionsString.split(" ");
        for (String option : options) {
            String[] keyValue = option.split("=", 2);
            if (keyValue.length == 2)
                optionsMap.put(keyValue[0], keyValue[1]);
        }
        String format = optionsMap.get("format");
        if("pdf".equals(format))onPdfConversionStarted();
        String mime = getMimeForFormat(format);
        if (format != null && mime != null) {
            String filename = optionsMap.get("name");
            if (filename == null)
                filename = "document." + format;
            filename = getExportDisplayName(filename);
            int requestID = getRequestIDForFormat(format);

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_TITLE, filename);
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, false);
            File folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.fromFile(folder).toString());
            intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
            startActivityForResult(intent, requestID);
        }
    }

    private int getRequestIDForFormat(String format) {
        switch (format) {
            case "pdf": return REQUEST_SAVEAS_PDF;
            case "rtf": return REQUEST_SAVEAS_RTF;
            case "odt": return REQUEST_SAVEAS_ODT;
            case "odp": return REQUEST_SAVEAS_ODP;
            case "ods": return REQUEST_SAVEAS_ODS;
            case "docx": return REQUEST_SAVEAS_DOCX;
            case "pptx": return REQUEST_SAVEAS_PPTX;
            case "xlsx": return REQUEST_SAVEAS_XLSX;
            case "doc": return REQUEST_SAVEAS_DOC;
            case "ppt": return REQUEST_SAVEAS_PPT;
            case "xls": return REQUEST_SAVEAS_XLS;
            case "epub": return REQUEST_SAVEAS_EPUB;
        }
        return 0;
    }

    private String getMimeForFormat(String format) {
        switch(format) {
            case "pdf": return "application/pdf";
            case "rtf": return "text/rtf";
            case "odt": return "application/vnd.oasis.opendocument.text";
            case "odp": return "application/vnd.oasis.opendocument.presentation";
            case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "doc": return "application/msword";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "xls": return "application/vnd.ms-excel";
            case "epub": return "application/epub+zip";
        }
        return null;
    }

    private void afterMessageFromWebView(String[] messageAndParameterArray) {
        switch (messageAndParameterArray[0]) {
            case "uno":
                switch (messageAndParameterArray[1]) {
                    case ".uno:Copy":
                    case ".uno:Cut":
                        mPendingCopyToSystemClipboard.set(true);
                        break;
                    default:
                        break;
                }
                break;
            default:
                break;
        }
    }

    private void initiatePrint() {
        PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
        PrintDocumentAdapter printAdapter = new PrintAdapter(LOActivity.this);
        printManager.print("Document", printAdapter, new PrintAttributes.Builder().build());
    }

    /** Send message back to the shell (for example for the cloud save). */
    public void sendBroadcast(String event, String data) {
        Intent intent = new Intent(LO_ACTIVITY_BROADCAST);
        intent.putExtra(LO_ACTION_EVENT, event);
        intent.putExtra(LO_ACTION_DATA, data);
    }

    public native void saveAs(String fileUri, String format, String options);

    public native boolean getClipboardContent(LokClipboardData aData);

    public native void setClipboardContent(LokClipboardData aData);

    public native void paste(String mimeType, byte[] data);

    public native void postUnoCommand(String command, String arguments, boolean bNotifyWhenFinished);

    /// Returns a magic that specifies this application - and this document.
    private final String getClipboardMagic() {
        return CLIPBOARD_COOL_SIGNATURE + Long.toString(loadDocumentMillis);
    }

    /// Needs to be executed after the .uno:Copy / Paste has executed
    public final void populateClipboard()
    {
        // No longer written; left over from previous versions of the app.
        File legacyFile = new File(getApplicationContext().getCacheDir(), CLIPBOARD_FILE_PATH);
        if (legacyFile.exists())
            legacyFile.delete();

        LokClipboardData clipboardData = new LokClipboardData();
        if (!LOActivity.this.getClipboardContent(clipboardData)) {
            PrivateLog.e(TAG, "no clipboard to copy");
            return;
        }

        // The full content always goes to a file: in-app pastes read it
        // back from there, and ClipboardFileProvider streams it to other
        // apps, both without any size constraint.
        File clipboardFile = newClipboardFile();
        if (clipboardFile != null && !clipboardData.writeToFile(clipboardFile)) {
            PrivateLog.e(TAG, "failed to write the clipboard file");
            clipboardFile = null;
        }

        String text = clipboardData.getText();
        String html = clipboardData.getHtml();

        if (html == null)
            return;

        int idx = html.indexOf("<meta name=\"generator\" content=\"");

        if (idx < 0)
            idx = html.indexOf("<meta http-equiv=\"content-type\" content=\"text/html;");

        if (idx >= 0) { // inject our magic
            StringBuffer newHtml = new StringBuffer(html);
            newHtml.insert(idx, "<meta name=\"origin\" content=\"" + getClipboardMagic() + "\"/>\n");
            html = newHtml.toString();
        }

        if (text == null || text.length() == 0)
            PrivateLog.i(TAG, "set text to clipoard with: text '" + text + "' and html '" + html + "'");

        Uri clipUri = clipboardFile != null
            ? ClipboardFileProvider.uriFor(this, clipboardFile.getName()) : null;

        // Binder transactions share a ~1 MB buffer per process, and larger
        // setPrimaryClip() calls throw TransactionTooLargeException
        // (strings are parceled as UTF-16, two bytes per char). Oversized
        // content is therefore not inlined into the clip - receivers
        // stream it from clipUri instead, which coerceToText() /
        // coerceToHtmlText() do transparently when there is no inline text.
        int estimatedParcelSize = 2 * (html.length() + (text != null ? text.length() : 0));
        boolean inline = estimatedParcelSize <= CLIPBOARD_SYSTEM_LIMIT_BYTES || clipUri == null;

        clipData = makeClipData(inline ? text : null, inline ? html : null, clipUri);
        try {
            clipboardManager.setPrimaryClip(clipData);
        } catch (RuntimeException e) {
            // Some vendors reject clips well below the nominal limit; a
            // URI-only clip is tiny and always fits.
            PrivateLog.w(TAG, "setPrimaryClip failed, retrying with the URI only: " + e);
            if (clipUri != null) {
                clipData = makeClipData(null, null, clipUri);
                clipboardManager.setPrimaryClip(clipData);
            }
        }
    }

    /// a clipboard carrying our magic in the label, the full-fidelity data
    /// behind 'uri', and - when small enough - the inline text and HTML
    private final ClipData makeClipData(String text, String html, Uri uri) {
        // ClipData.Item refuses HTML without plain text
        if (html != null && text == null)
            text = "";
        ClipDescription description = new ClipDescription(getClipboardMagic(),
            new String[]{ClipDescription.MIMETYPE_TEXT_HTML, ClipDescription.MIMETYPE_TEXT_PLAIN});
        return new ClipData(description, new ClipData.Item(text, html, null, uri));
    }

    /// create a fresh file for the current clipboard... open streams of a
    /// deleted file keep working, so a paste that is happening while we copy
    /// is allowed to finish. We prefer this than deleting/recreating due to
    /// potential permission issues if we or the smartphone vendor does that
    /// improperly...
    private final File newClipboardFile() {
        File dir = ClipboardFileProvider.getClipboardDir(this);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            PrivateLog.e(TAG, "failed to create the clipboard directory");
            return null;
        }

        File[] oldFiles = dir.listFiles();
        if (oldFiles != null)
            for (File oldFile : oldFiles)
                oldFile.delete();

        return new File(dir, "clip-" + System.currentTimeMillis() + ".data");
    }

    /// Do the paste, and return true if we should short-circuit the paste locally (ie. let the core handle that)
    /// With chooseFormat, foreign content goes to the kit clipboard whole, for the Paste Special dialog.
    private final boolean performPaste(boolean chooseFormat)
    {
        clipData = clipboardManager.getPrimaryClip();
        if (clipData == null)
            return false;

        ClipDescription clipDesc = clipData.getDescription();
        if (clipDesc == null || clipData.getItemCount() < 1)
            return false;

        CharSequence label = clipDesc.getLabel();
        if (label != null && label.toString().startsWith(CLIPBOARD_COOL_SIGNATURE)) {
            if (label.toString().equals(getClipboardMagic())) {
                PrivateLog.i(TAG, "clipboard comes from us - same instance: short circuit it");
                return true;
            }

            PrivateLog.i(TAG, "clipboard comes from us - other instance: paste from the clipboard file");
            File clipboardFile = ClipboardFileProvider.fileFor(this, clipData.getItemAt(0).getUri());
            LokClipboardData clipboardData = null;
            if (clipboardFile != null && clipboardFile.exists())
                clipboardData = LokClipboardData.createFromFile(clipboardFile);

            if (clipboardData != null) {
                LOActivity.this.setClipboardContent(clipboardData);
                return true;
            }
            // couldn't read the clipboard file, try a standard paste...
        }

        if (chooseFormat && !isClipboardFromThisApp())
            return offerSystemClipboardToKit();

        // Choose a representation from the clipboard that we prefer rather than going with the
        // first one by default ... ideally we'd eventually change this so that we have a native
        // clipboard handler in VCL so everything is done like in the other apps...
        for (int i = 0; i < clipDesc.getMimeTypeCount(); ++i) {
            if (clipDesc.getMimeType(i).startsWith("image/")) {
                PrivateLog.d(TAG, "Pasting image mime: " + clipDesc.getMimeType(i));
                byte[] image = readUriContent(clipData.getItemAt(0).getUri());
                if (image != null) {
                    LOActivity.this.paste(clipDesc.getMimeType(i), image);
                    return false;
                }
            }
        }

        for (int i = 0; i < clipDesc.getMimeTypeCount(); ++i) {
            PrivateLog.d(TAG, "Pasting mime " + i + ": " + clipDesc.getMimeType(i));

            if (clipDesc.getMimeType(i).equals(ClipDescription.MIMETYPE_TEXT_HTML)) {
                final String html = clipData.getItemAt(0).coerceToHtmlText(this);
                if (html == null || html.isEmpty())
                    continue;
                // Check if the clipboard content was made with the app
                if (html.contains(CLIPBOARD_COOL_SIGNATURE)) {
                    // Check if the clipboard content is from the same app instance
                    if (html.contains(getClipboardMagic())) {
                        PrivateLog.i(TAG, "clipboard comes from us - same instance: short circuit it " + html);
                        return true;
                    } else {
                        PrivateLog.i(TAG, "clipboard comes from us - other instance: paste from clipboard file");

                        File clipboardFile = new File(getApplicationContext().getCacheDir(), CLIPBOARD_FILE_PATH);
                        LokClipboardData clipboardData = null;
                        if (clipboardFile.exists())
                            clipboardData = LokClipboardData.createFromFile(clipboardFile);

                        if (clipboardData != null) {
                            LOActivity.this.setClipboardContent(clipboardData);
                            return true;
                        }

                        // Couldn't get data from the clipboard file, but we can still paste html
                        if (chooseFormat)
                            return offerSystemClipboardToKit();

                        byte[] htmlByteArray = html.getBytes(Charset.forName("UTF-8"));
                        LOActivity.this.paste("text/html", htmlByteArray);
                        return false;
                    }
                } else {
                    PrivateLog.i(TAG, "foreign html, length " + html.length());
                    byte[] htmlByteArray = html.getBytes(Charset.forName("UTF-8"));
                    LOActivity.this.paste("text/html", htmlByteArray);
                    return false;
                }
            }
        }

        // try the plaintext as the last resort
        for (int i = 0; i < clipDesc.getMimeTypeCount(); ++i) {
            PrivateLog.d(TAG, "Plain text paste attempt " + i + ": " + clipDesc.getMimeType(i));

            if (clipDesc.getMimeType(i).equals(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                final ClipData.Item clipItem = clipData.getItemAt(0);
                final CharSequence textSequence = clipItem.coerceToText(this);
                if (textSequence == null)
                    continue;
                String text = textSequence.toString();
                byte[] textByteArray = text.getBytes(Charset.forName("UTF-8"));
                LOActivity.this.paste("text/plain;charset=utf-8", textByteArray);
            }
        }

        return false;
    }

    private boolean isClipboardFromThisApp()
    {
        CharSequence label = clipData.getDescription().getLabel();
        if (label != null && label.toString().startsWith(CLIPBOARD_COOL_SIGNATURE))
            return true;

        for (int i = 0; i < clipData.getItemCount(); ++i) {
            String html = clipData.getItemAt(i).getHtmlText();
            if (html != null && html.contains(CLIPBOARD_COOL_SIGNATURE))
                return true;
        }
        return false;
    }

    /// Hand the system clipboard to the kit. Returns false when there was nothing to hand over.
    private boolean offerSystemClipboardToKit()
    {
        LokClipboardData clipboardData = new LokClipboardData();
        for (int i = 0; i < clipData.getItemCount(); ++i) {
            ClipData.Item item = clipData.getItemAt(i);

            String html = item.getHtmlText();
            if (html != null)
                addClipboardEntry(clipboardData, "text/html",
                                  html.getBytes(Charset.forName("UTF-8")));

            CharSequence text = item.getText();
            if (text != null)
                addClipboardEntry(clipboardData, "text/plain;charset=utf-8",
                                  text.toString().getBytes(Charset.forName("UTF-8")));

            Uri uri = item.getUri();
            if (uri != null) {
                String mimeType = imageMimeTypeOf(uri);
                if (mimeType != null) {
                    byte[] image = readUriContent(uri);
                    if (image != null)
                        addClipboardEntry(clipboardData, mimeType, image);
                }
            }
        }

        if (clipboardData.clipboardEntries.isEmpty()) {
            PrivateLog.i(TAG, "system clipboard has no html, text or image to hand to the kit");
            return false;
        }

        LOActivity.this.setClipboardContent(clipboardData);
        return true;
    }

    /// Image mime type of a clipboard uri, or null when it is not an image.
    private String imageMimeTypeOf(Uri uri)
    {
        String mimeType = getContentResolver().getType(uri);
        if (mimeType != null && mimeType.startsWith("image/"))
            return mimeType;

        ClipDescription clipDesc = clipData.getDescription();
        for (int i = 0; i < clipDesc.getMimeTypeCount(); ++i) {
            if (clipDesc.getMimeType(i).startsWith("image/"))
                return clipDesc.getMimeType(i);
        }
        return null;
    }

    private static void addClipboardEntry(LokClipboardData clipboardData, String mimeType,
                                          byte[] data)
    {
        LokClipboardEntry entry = new LokClipboardEntry();
        entry.mime = mimeType;
        entry.data = data;
        clipboardData.clipboardEntries.add(entry);
    }

    /// Read the whole uri content, or null when that fails.
    private byte[] readUriContent(Uri uri)
    {
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            int nRead;
            byte[] data = new byte[16384];
            while ((nRead = stream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }

            return buffer.toByteArray();
        } catch (Exception e) {
            PrivateLog.d(TAG, "Failed to read clipboard content: " + e.getMessage());
            return null;
        }
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab cinoptions=b1,g0,N-s cinkeys+=0=break: */
