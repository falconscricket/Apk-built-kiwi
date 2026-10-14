package com.alpha.social;

import android.Manifest;
import android.app.AlertDialog;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.app.Activity;
import android.os.Build;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.List;

/**
 * WhatsApp Web in Android WebView (Chromium engine, same family as Chrome/Kiwi).
 * Has a LOG button that copies mic / call / permission debug lines to the clipboard.
 */
public class WhatsAppActivity extends Activity {
    private static final String TAG = "AlphaSocial";
    private static final int REQ_MEDIA = 77;
    private static final String WA_URL = "https://web.whatsapp.com/";
    private static final int MAX_LINES = 400;
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    // Injected into WhatsApp page: logs getUserMedia calls and every mic mute/unmute.
    private static final String DEBUG_JS =
            "(function(){"
          + "if(window.__alphaDbg)return;window.__alphaDbg=true;"
          + "var log=function(m){console.log('ALPHA_DBG '+m);};"
          + "var md=navigator.mediaDevices;if(!md||!md.getUserMedia)return;"
          + "var g=md.getUserMedia.bind(md);"
          + "md.getUserMedia=function(c){log('gUM '+JSON.stringify(c));"
          + "return g(c).then(function(s){s.getAudioTracks().forEach(function(t){"
          + "log('track kind='+t.kind+' enabled='+t.enabled+' state='+t.readyState);"
          + "t.addEventListener('ended',function(){log('track ended');});});return s;},"
          + "function(e){log('gUM error '+e.name+' '+e.message);throw e;});};"
          + "var d=Object.getOwnPropertyDescriptor(MediaStreamTrack.prototype,'enabled');"
          + "if(d&&d.set){Object.defineProperty(MediaStreamTrack.prototype,'enabled',{"
          + "get:d.get,set:function(v){log('track.enabled='+v+' kind='+this.kind);d.set.call(this,v);},configurable:true});}"
          + "log('debug hook ready');"
          + "})();";

    private static final LinkedList<String> logLines = new LinkedList<>();

    private static synchronized void addLog(String line) {
        String t = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        logLines.add(t + " " + line);
        while (logLines.size() > MAX_LINES) logLines.removeFirst();
        Log.d(TAG, line);
    }

    private WebView web;
    private PermissionRequest pendingRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addLog("activity created");

        web = new WebView(this);
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true); // keep header/call icons below the status bar
        root.addView(web);

        TextView logBtn = new TextView(this);
        logBtn.setText("LOG");
        logBtn.setTextColor(Color.WHITE);
        logBtn.setTextSize(12);
        logBtn.setBackgroundColor(0xAA7C3AED);
        logBtn.setPadding(28, 14, 28, 14);
        logBtn.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, 24, 200);
        logBtn.setLayoutParams(lp);
        logBtn.setOnClickListener(v -> copyLogs());
        root.addView(logBtn);

        TextView audioBtn = new TextView(this);
        audioBtn.setText("AUDIO");
        audioBtn.setTextColor(Color.WHITE);
        audioBtn.setTextSize(12);
        audioBtn.setBackgroundColor(0xAA0E7490);
        audioBtn.setPadding(28, 14, 28, 14);
        audioBtn.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        alp.setMargins(24, 0, 0, 200);
        audioBtn.setLayoutParams(alp);
        audioBtn.setOnClickListener(v -> showAudioRouteDialog());
        root.addView(audioBtn);
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(DESKTOP_UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                addLog("page started: " + url);
                view.evaluateJavascript(DEBUG_JS, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                addLog("page finished: " + url);
                view.evaluateJavascript(DEBUG_JS, null);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (req.isForMainFrame()) addLog("page error: " + err.getDescription());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.contains("whatsapp")) return false;
                addLog("external link: " + url);
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                String msg = m.message();
                if (msg != null && msg.startsWith("ALPHA_DBG")) addLog(msg);
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                boolean wantsMic = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                }
                addLog("permission request: " + java.util.Arrays.toString(request.getResources()));
                boolean hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
                if (!wantsMic || hasMic) {
                    addLog("permission granted (android mic ok=" + hasMic + ")");
                    request.grant(request.getResources());
                } else {
                    addLog("android RECORD_AUDIO missing, asking user");
                    pendingRequest = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MEDIA);
                }
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                addLog("permission request canceled");
                if (pendingRequest == request) pendingRequest = null;
            }
        });

        web.loadUrl(WA_URL);
    }

    /** Manual output picker. Nothing is forced: "Auto" releases the route to Android. */
    private void showAudioRouteDialog() {
        final AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        final List<String> names = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        names.add("Auto (phone decides)");
        actions.add(() -> {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice();
            } else {
                am.stopBluetoothSco();
                am.setBluetoothScoOn(false);
                am.setSpeakerphoneOn(false);
            }
        });

        if (Build.VERSION.SDK_INT >= 31) {
            for (final AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                names.add(deviceLabel(d));
                actions.add(() -> am.setCommunicationDevice(d));
            }
        } else {
            names.add("Speaker");
            actions.add(() -> { am.setBluetoothScoOn(false); am.setSpeakerphoneOn(true); });
            names.add("Receiver (earpiece)");
            actions.add(() -> { am.setBluetoothScoOn(false); am.setSpeakerphoneOn(false); });
            names.add("Bluetooth");
            actions.add(() -> { am.setSpeakerphoneOn(false); am.setBluetoothScoOn(true); am.startBluetoothSco(); });
        }

        new AlertDialog.Builder(this)
                .setTitle("Audio output (call sirpam select pannunga)")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    try {
                        actions.get(which).run();
                        addLog("audio route: " + names.get(which));
                        Toast.makeText(this, names.get(which), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        addLog("audio route failed: " + e.getMessage());
                        Toast.makeText(this, "Route fail: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private static String deviceLabel(AudioDeviceInfo d) {
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "Receiver (earpiece)";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "Speaker";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "Wired headphones";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth (audio)";
            default:
                String n = d.getProductName() == null ? "" : d.getProductName().toString();
                return n.isEmpty() ? "Other device" : n;
        }
    }

    private void copyLogs() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ALPHA WhatsApp debug log ===\n");
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append(" android ")
                .append(android.os.Build.VERSION.RELEASE).append('\n');
        sb.append("webview: ").append(web.getSettings().getUserAgentString()).append("\n\n");
        sb.append("--- app events ---\n");
        synchronized (WhatsAppActivity.class) {
            for (String l : logLines) sb.append(l).append('\n');
        }
        sb.append("\n--- logcat (filtered) ---\n");
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            LinkedList<String> keep = new LinkedList<>();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.matches("(?i).*(AlphaSocial|chromium|webview|AudioRecord|AudioTrack|AudioFlinger|getUserMedia|WebRTC).*")) {
                    keep.add(line);
                    if (keep.size() > 300) keep.removeFirst();
                }
            }
            for (String k : keep) sb.append(k).append('\n');
        } catch (Exception e) {
            sb.append("logcat not available: ").append(e.getMessage()).append('\n');
        }

        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            cb.setPrimaryClip(ClipData.newPlainText("alpha-log", sb.toString()));
            Toast.makeText(this, "Log copied (" + logLines.size() + " events)", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MEDIA && pendingRequest != null) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            addLog("android mic permission result: " + ok);
            if (ok) pendingRequest.grant(pendingRequest.getResources());
            else pendingRequest.deny();
            pendingRequest = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        addLog("activity destroyed");
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
