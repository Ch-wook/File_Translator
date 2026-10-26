package kr.documentstudio.fixtures;

import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

/** Instrumentation APK only. Exercises the real Android InputConnection, not DOM event injection. */
public final class KoreanImeFixture extends InputMethodService {
    private TextView label;
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public View onCreateInputView() {
        label=new TextView(this);
        label.setText("한국어 입력 검증 IME");
        label.setTextSize(18);
        label.setPadding(20,20,20,20);
        label.setBackgroundColor(0xffdae5f4);
        label.setTextColor(0xff16375a);
        int height=(int)(190*getResources().getDisplayMetrics().density);
        label.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,height));
        label.setMinimumHeight(height);
        return label;
    }
    @Override public void onAppPrivateCommand(String action, Bundle data) {
        if(!"kr.documentstudio.IME_FIXTURE".equals(action)) { super.onAppPrivateCommand(action,data); return; }
        InputConnection connection=getCurrentInputConnection();
        if(connection==null || data==null) return;
        String command=data.getString("command",""),text=data.getString("text","");
        boolean accepted=false;
        switch(command) {
            case "compose": accepted=connection.setComposingText(text,1); break;
            case "commit": accepted=connection.commitText(text,1); break;
            case "finish": accepted=connection.finishComposingText(); break;
            case "selectAll":
                ExtractedText extracted=connection.getExtractedText(new ExtractedTextRequest(),0);
                if(extracted!=null && extracted.text!=null) accepted=connection.setSelection(0,extracted.text.length());
                break;
            case "key":
                int key=data.getInt("key");
                accepted=connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));
                accepted=connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key)) && accepted;
                break;
        }
        if(label!=null) label.setText("IME 요청 "+data.getInt("sequence")+" "+(accepted?"처리":"실패"));
    }
}
