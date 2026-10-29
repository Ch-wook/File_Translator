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
import android.util.AtomicFile;
import android.view.inputmethod.EditorInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Instrumentation APK only. Exercises the real Android InputConnection, not DOM event injection. */
public final class KoreanImeFixture extends InputMethodService {
    private TextView label;
    private int lastSequence=0;private boolean lastAccepted=false;private String lastCommand="";
    @Override public void onStartInput(EditorInfo info,boolean restarting) {super.onStartInput(info,restarting);writeStatus();}
    private void writeStatus() {
        AtomicFile file=new AtomicFile(new File(getFilesDir(),"ime-fixture-status.json"));FileOutputStream output=null;
        try {EditorInfo info=getCurrentInputEditorInfo();JSONObject status=new JSONObject().put("sequence",lastSequence).put("accepted",lastAccepted).put("command",lastCommand).put("inputType",info==null?0:info.inputType);
            output=file.startWrite();output.write(status.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(output);
        }catch(Exception error){if(output!=null)file.failWrite(output);}
    }
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
                accepted=connection.performContextMenuAction(android.R.id.selectAll);
                if(!accepted){ExtractedText extracted=connection.getExtractedText(new ExtractedTextRequest(),0);
                    if(extracted!=null && extracted.text!=null)accepted=connection.setSelection(0,extracted.text.length());}
                break;
            case "status": accepted=true; break;
            case "key":
                int key=data.getInt("key");
                accepted=connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));
                accepted=connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key)) && accepted;
                break;
        }
        lastSequence=data.getInt("sequence");lastAccepted=accepted;lastCommand=command;writeStatus();
        if(label!=null) label.setText("IME 요청 "+data.getInt("sequence")+" "+(accepted?"처리":"실패"));
    }
}
