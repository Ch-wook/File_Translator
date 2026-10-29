/* -*- tab-width: 4; indent-tabs-mode: nil; c-basic-offset: 4 -*- */
/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.libreoffice.androidlib;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.KeyEvent;
import android.util.AttributeSet;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

public class COWebView extends WebView {
    private Context mContext;

    public COWebView(Context context) {
        super(context);
        mContext = context;
        setWebViewClient(new COWebViewClient());
    }

    public COWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWebViewClient(new COWebViewClient());
    }

    public COWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWebViewClient(new COWebViewClient());
    }

    public COWebView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setWebViewClient(new COWebViewClient());
    }

    @NonNull
    @Override
    public COWebViewClient getWebViewClient() {
        return (COWebViewClient) super.getWebViewClient();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Context owner = getContext();
        while (owner instanceof ContextWrapper && !(owner instanceof LOActivity)) {
            Context next = ((ContextWrapper) owner).getBaseContext();
            if (next == owner) break;
            owner = next;
        }
        boolean paste = event.getKeyCode() == KeyEvent.KEYCODE_PASTE
                || (event.isCtrlPressed() && event.getKeyCode() == KeyEvent.KEYCODE_V)
                || (event.isShiftPressed() && event.getKeyCode() == KeyEvent.KEYCODE_INSERT);
        // VIEW must not enter WebView's synchronous clipboard path. Search and
        // settings input retain normal paste through a cached DOM focus report.
        if (paste && owner instanceof LOActivity && ((LOActivity) owner).studioBlocksDocumentPaste())
            return true;
        return super.dispatchKeyEvent(event);
    }

    /*
     * This fixes Unidentified key events in javascript such as 'space' key and
     * shortcuts that require such keys
     * */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // TODO FIXME
        //return new BaseInputConnection(this, false);
        // ^ caused a regression on inserting special characters like Non-English ones

        return super.onCreateInputConnection(outAttrs);
    }
}
