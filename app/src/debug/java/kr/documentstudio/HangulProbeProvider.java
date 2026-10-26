package kr.documentstudio;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Same-UID debug instrumentation access. No exported URI and no production dependency. */
public final class HangulProbeProvider extends ContentProvider {
    private WeakReference<HangulEditorActivity> current=new WeakReference<>(null);
    @Override public boolean onCreate() {
        ((Application)getContext().getApplicationContext()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity,Bundle state) {if(activity instanceof HangulEditorActivity)current=new WeakReference<>((HangulEditorActivity)activity);}
            @Override public void onActivityDestroyed(Activity activity) {if(current.get()==activity)current.clear();}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity,Bundle state) {}
        });
        return true;
    }
    @Override public Bundle call(String method,String argument,Bundle extras) {
        HangulEditorActivity activity=current.get();
        Bundle result=new Bundle();
        if(activity==null) {result.putBoolean("ready",false);return result;}
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Use a background instrumentation call");
        CountDownLatch done=new CountDownLatch(1);
        final Throwable[] failure={null};
        activity.runOnUiThread(()->{
            try {
                java.lang.reflect.Field field=HangulEditorActivity.class.getDeclaredField("browser");
                field.setAccessible(true);WebView browser=(WebView)field.get(activity);
                result.putBoolean("ready",true);
                if("eval".equals(method)) {
                    if(argument==null || argument.length()>30000)throw new IllegalArgumentException("Bounded JavaScript required");
                    browser.evaluateJavascript(argument,value->{result.putString("result",value);done.countDown();});
                    return;
                }
                InputMethodManager manager=activity.getSystemService(InputMethodManager.class);
                if("show-ime".equals(method)) {
                    browser.requestFocus();
                    manager.showSoftInput(browser,InputMethodManager.SHOW_IMPLICIT);
                    result.putBoolean("active",manager.isActive(browser));
                } else if("ime".equals(method)) {
                    if(!manager.isActive(browser))throw new IllegalStateException("WebView has no active InputConnection");
                    manager.sendAppPrivateCommand(browser,"kr.documentstudio.IME_FIXTURE",extras);
                } else if("geometry".equals(method)) {
                    int[] location=new int[2];browser.getLocationOnScreen(location);
                    result.putInt("left",location[0]);result.putInt("top",location[1]);result.putInt("width",browser.getWidth());result.putInt("height",browser.getHeight());
                } else throw new IllegalArgumentException("Unknown probe method");
            } catch(Throwable error) {failure[0]=error;}
            finally {if(!"eval".equals(method)||failure[0]!=null)done.countDown();}
        });
        try {if(!done.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Hangul probe timeout");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
        if(failure[0]!=null)throw new IllegalStateException("Hangul probe failed",failure[0]);
        return result;
    }
    @Override public String getType(Uri uri){return "application/json";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String order){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){return null;}
    @Override public int delete(Uri uri,String selection,String[] args){return 0;}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
}
