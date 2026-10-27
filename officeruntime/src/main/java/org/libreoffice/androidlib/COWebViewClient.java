/* -*- tab-width: 4; indent-tabs-mode: nil; c-basic-offset: 4 -*- */
/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.libreoffice.androidlib;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Used to capture HTTP requests from mobile so we can use web requests without hosting a server
 *
 * Analogous to iOS's CoolURLSchemeHandler
 */
public class COWebViewClient extends WebViewClient {
    private MobileSocket mobileSocket;

    public COWebViewClient() {
        super();
        mobileSocket = new MobileSocket();
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        if (url != null && url.startsWith("file:///android_asset/dist/cool.html")) {
            try {
                java.io.ByteArrayOutputStream script = new java.io.ByteArrayOutputStream();
                for (String asset : new String[]{"dist/studio-editor-ui.js", "dist/studio-policy.js"}) {
                    try (InputStream input = view.getContext().getAssets().open(asset)) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = input.read(buffer)) != -1) script.write(buffer, 0, count);
                        script.write('\n');
                    }
                }
                view.evaluateJavascript(new String(script.toByteArray(), java.nio.charset.StandardCharsets.UTF_8), null);
            } catch (java.io.IOException failure) {
                reportLoadFailure(view);
            }
        }
    }

    private static void reportLoadFailure(WebView view) {
        if (view.getContext() instanceof LOActivity)
            ((LOActivity) view.getContext()).reportOfficeLoadFailure("오피스 화면을 불러오지 못했습니다.");
    }

    @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (request.isForMainFrame()) reportLoadFailure(view);
    }

    @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
        super.onReceivedHttpError(view, request, response);
        if (request.isForMainFrame()) reportLoadFailure(view);
    }

    public static WebResourceResponse response(int code, String reasonPhrase, InputStream data, Map<String, String> headers) {
        return new WebResourceResponse(
                null,
                null,
                code,
                reasonPhrase,
                headers,
                data
        );
    }
    public static WebResourceResponse response(int code, String reasonPhrase, InputStream data, long length) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "null"); // Yes, the origin really is 'null' for 'file:' origins
        headers.put("Content-Length", Long.toString(length));

        return response(code, reasonPhrase, data, headers);
    }
    public static WebResourceResponse response(int code, String reasonPhrase, InputStream data) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "null"); // Yes, the origin really is 'null' for 'file:' origins

        return response(code, reasonPhrase, data, headers);
    }
    public static WebResourceResponse response(int code, String reasonPhrase) {
        ByteArrayInputStream data = new ByteArrayInputStream(new byte[0]);

        return response(code, reasonPhrase, data, 0);
    }

    @Nullable
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (!Objects.equals(request.getUrl().getScheme(), "cool")) {
            return super.shouldInterceptRequest(view, request);
        }

        String path = request.getUrl().getPath();
        if (Objects.equals(path, "/cool/media")) {
            return handleMediaRequest(request);
        } else if (path != null && path.startsWith("/cool/mobilesocket/")) {
            return handleMobileSocketRequest(request);
        } else {
            return response(404, "Not Found");
        }
    }

    private WebResourceResponse handleMediaRequest(WebResourceRequest request) {
        Uri uriDecoded = Uri.parse(Uri.decode(request.getUrl().toString())); // We have to do this weird-looking decoding step as presentation mode gives us a broken (i.e. parameters are encoded, including the & delimiter, etc.) URI
        String tag = uriDecoded.getQueryParameter("Tag");

        if (tag == null) {
            return response(404, "Not Found");
        }

        String mediaPath = getEmbeddedMediaPath(tag);

        if (mediaPath.isEmpty()) {
            return response(404, "Not Found");
        }

        File media = new File(mediaPath);

        if (!media.exists()) {
            return response(404, "Not Found");
        }

        FileInputStream data;
        try {
            data = new FileInputStream(media);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

        return response(200, "OK", data, media.length());
    }

    private WebResourceResponse handleMobileSocketRequest(WebResourceRequest request) {
        // this'll be something like [ 'cool', 'mobilesocket', 'cool', 'wopipath', 'ws', 'ws', command, 'open', id ]
        List<String> path = request.getUrl().getPathSegments();

        if (path.size() < 7) {
            return response(400, "Bad Request");
        }

        if (path.get(6).equals("open")) {
            return mobileSocket.open();
        }

        // Anything except open can just be treated as "write". In the Android app we can't send data in "write"s anyway...
        return mobileSocket.write();
    }

    public MobileSocket getMobileSocket() {
        return mobileSocket;
    }

    private native String getEmbeddedMediaPath(String tag);
}
