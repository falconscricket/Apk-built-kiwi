package com.alpha.social;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.os.Build;
import android.os.Environment;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

/**
 * Alpha Social: a tiny locked-down browser (Firefox engine / GeckoView) that
 * only opens WhatsApp Web, Instagram and Telegram Web, with the bundled
 * "Alpha" extension injected into them.
 */
public class MainActivity extends Activity {
    private static final String TAG = "AlphaSocial";
    private static final String EXT_URI = "resource://android/assets/alpha/";
    private static final String EXT_ID = "alpha-booster@alpha.local";
    private static final int REQ_PERMS = 41;
    private static final int REQ_FILE = 42;

    /** Only these sites (and their sub-domains) may be opened inside the app. */
    private static final String[] ALLOWED_HOSTS = {
            "whatsapp.com", "whatsapp.net", "wa.me",
            "instagram.com", "cdninstagram.com",
            "facebook.com", "fb.com", "fbcdn.net", "meta.com",
            "telegram.org", "t.me", "telegram.me",
            "discord.com", "discordapp.com", "discord.gg", "discordapp.net"
    };

    private static final class Site {
        final String name;
        final String url;
        final int defaultMode;
        boolean alwaysDesktopUa; // site refuses phone user agents (WhatsApp, Discord)
        int mode;
        GeckoSession session;
        boolean canGoBack;

        Site desktopOnly() {
            alwaysDesktopUa = true;
            return this;
        }

        Site(String name, String url, int defaultMode) {
            this.name = name;
            this.url = url;
            this.defaultMode = defaultMode;
            this.mode = defaultMode;
        }
    }

    // GeckoRuntime can only be created once per process.
    private static GeckoRuntime runtime;

    // Page modes: MOBILE = phone site, FIT = desktop site squeezed to phone width,
    // DESKTOP = full desktop site (980px wide, pinch to zoom).
    private static final int MOBILE = 0, FIT = 1, DESKTOP = 2;
    private static final String[] MODE_LABEL = {"\uD83D\uDCF1 Mobile", "\uD83D\uDDA5 Fit", "\uD83D\uDDA5 Desktop"};

    private final Site[] sites = {
            // WhatsApp Web refuses phone user agents, so it starts in FIT (desktop site, phone width).
            new Site("WhatsApp", "https://web.whatsapp.com/", FIT).desktopOnly(),
            new Site("Instagram", "https://www.instagram.com/", MOBILE),
            new Site("Telegram", "https://web.telegram.org/a/", MOBILE),
            // Discord web sends phones to the app store, so it also starts in FIT.
            new Site("Discord", "https://discord.com/app", FIT).desktopOnly(),
    };
    private View menuButton;
    private SharedPreferences prefs;

    private int current = -1;
    private GeckoView geckoView;
    private GeckoView popupView;
    private View popupOverlay;
    private GeckoSession popupSession;
    private WebExtension.Action extAction;

    private GeckoSession.PermissionDelegate.Callback pendingAndroidPerm;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pendingFileResult;
    private GeckoSession.PromptDelegate.FilePrompt pendingFilePrompt;

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (runtime == null) {
            runtime = GeckoRuntime.create(getApplicationContext(),
                    new GeckoRuntimeSettings.Builder().build());
        }
        installExtension();

        prefs = getSharedPreferences("alpha_social", MODE_PRIVATE);
        for (Site st : sites) {
            st.mode = prefs.getInt("mode_" + st.name, st.defaultMode);
            if (st.alwaysDesktopUa && st.mode == MOBILE) st.mode = FIT;
            // WhatsApp call button is hidden in Mobile/Fit view, so always use Desktop here.
            if (st.name.equals("WhatsApp")) st.mode = DESKTOP;
        }

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams wl = getWindow().getAttributes();
            wl.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wl);
        }

        requestHighRefreshRate();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // Keep the page clear of the notch, status bar and gesture/nav bar.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = in.left; t = in.top; r = in.right; b = in.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        geckoView = new GeckoView(this);
        column.addView(geckoView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Thin footer: OWNER + ALPHA logo.
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        footer.setBackgroundColor(Color.BLACK);
        footer.setPadding(dp(10), 0, dp(12), 0);
        TextView ownerLabel = new TextView(this);
        ownerLabel.setText("OWNER");
        ownerLabel.setTextColor(Color.parseColor("#8A8A8A"));
        ownerLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        ownerLabel.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        ownerLabel.setLetterSpacing(0.3f);
        footer.addView(ownerLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView footLogo = new ImageView(this);
        footLogo.setImageResource(R.drawable.alpha_logo);
        footLogo.setAdjustViewBounds(true);
        footLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(15));
        flp.setMargins(dp(8), 0, 0, 0);
        footer.addView(footLogo, flp);
        column.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));

        // Small floating, draggable 3-dot button -> menu with all apps.
        Button menu = new Button(this);
        menu.setText("\u22EE");
        menu.setAllCaps(false);
        menu.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        menu.setTypeface(Typeface.DEFAULT_BOLD);
        menu.setTextColor(Color.WHITE);
        menu.setPadding(0, 0, 0, 0);
        menu.setBackgroundColor(Color.parseColor("#99000000"));
        menu.setAlpha(0.75f);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(38), dp(38),
                Gravity.TOP | Gravity.END);
        mlp.setMargins(0, dp(6), dp(6), 0);
        root.addView(menu, mlp);
        menuButton = menu;
        setupDraggableMenu(menu, root);

        // Overlay that shows the extension popup (license status + show/hide panel).
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#AA000000"));
        overlay.setVisibility(View.GONE);
        overlay.setOnClickListener(v -> closePopup());
        popupView = new GeckoView(this);
        FrameLayout.LayoutParams card = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420), Gravity.CENTER);
        card.setMargins(dp(16), dp(16), dp(16), dp(16));
        overlay.addView(popupView, card);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        popupOverlay = overlay;

        // Splash: ALPHA logo on black, fades away after a moment.
        final FrameLayout splash = new FrameLayout(this);
        splash.setBackgroundColor(Color.BLACK);
        splash.setClickable(true);
        ImageView splashLogo = new ImageView(this);
        splashLogo.setImageResource(R.drawable.alpha_logo);
        splashLogo.setAdjustViewBounds(true);
        splashLogo.setAlpha(0f);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                getResources().getDisplayMetrics().widthPixels * 7 / 10,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        splash.addView(splashLogo, slp);
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        splashLogo.animate().alpha(1f).setDuration(500).start();
        splash.postDelayed(() -> splash.animate().alpha(0f).setDuration(450)
                .withEndAction(() -> root.removeView(splash)).start(), 1700);

        setContentView(root);

        requestMediaPermissionsUpFront();
        select(0);
    }

    /** Ask Android for the phone's highest refresh rate (90/120/144 Hz) at the current resolution. */
    @SuppressWarnings("deprecation")
    private void requestHighRefreshRate() {
        try {
            Display d = getWindowManager().getDefaultDisplay();
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (Build.VERSION.SDK_INT >= 23) {
                Display.Mode cur = d.getMode();
                Display.Mode best = cur;
                for (Display.Mode m : d.getSupportedModes()) {
                    if (m.getPhysicalWidth() == cur.getPhysicalWidth()
                            && m.getPhysicalHeight() == cur.getPhysicalHeight()
                            && m.getRefreshRate() > best.getRefreshRate()) {
                        best = m;
                    }
                }
                lp.preferredDisplayModeId = best.getModeId();
                lp.preferredRefreshRate = best.getRefreshRate();
            } else {
                lp.preferredRefreshRate = d.getRefreshRate();
            }
            getWindow().setAttributes(lp);
        } catch (Exception e) {
            Log.w(TAG, "High refresh rate request failed", e);
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void select(int index) {
        closePopup();
        if (index == current) return;
        Site site = sites[index];
        if (site.session == null) {
            site.session = createSession(site);
            site.session.loadUri(site.url);
        }
        geckoView.setSession(site.session);
        runtime.getWebExtensionController().setTabActive(site.session, true);
        if (current >= 0 && sites[current].session != null) {
            runtime.getWebExtensionController().setTabActive(sites[current].session, false);
        }
        current = index;
    }

    private void setupDraggableMenu(final View btn, final FrameLayout root) {
        final float[] down = new float[4]; // rawX, rawY, startTranslationX, startTranslationY
        final boolean[] moved = {false};
        btn.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getRawX(); down[1] = ev.getRawY();
                    down[2] = v.getTranslationX(); down[3] = v.getTranslationY();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - down[0], dy = ev.getRawY() - down[1];
                    if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved[0] = true;
                    if (moved[0]) {
                        v.setTranslationX(down[2] + dx);
                        v.setTranslationY(down[3] + dy);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) showMainMenu(v);
                    return true;
                default:
                    return false;
            }
        });
    }

    private void showMainMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        for (int i = 0; i < sites.length; i++) {
            m.getMenu().add(0, i, i, (i == current ? "\u25CF " : "    ") + sites[i].name);
        }
        final int idMode = 100, idAlpha = 101, idReload = 102;
        String modeText = current >= 0 ? modeLabel(sites[current]) : "";
        m.getMenu().add(1, idMode, 100, "Page mode: " + modeText + "  (tap to change)");
        m.getMenu().add(1, idAlpha, 101, "\u03B1 Alpha panel");
        m.getMenu().add(1, idReload, 102, "Reload page");
        m.getMenu().add(1, 104, 104, "Copy debug log");
        m.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id < sites.length) select(id);
            else if (id == idMode) cycleMode();
            else if (id == idAlpha) openAlphaPopup();
            else if (id == idReload && current >= 0) sites[current].session.reload();
            else if (id == 104) copyDebugLog();
            return true;
        });
        m.show();
    }

    private void cycleMode() {
        if (current < 0) return;
        Site site = sites[current];
        if (site.alwaysDesktopUa) {
            site.mode = site.mode == DESKTOP ? FIT : DESKTOP; // phone view <-> PC view
        } else {
            site.mode = (site.mode + 1) % 3;
        }
        prefs.edit().putInt("mode_" + site.name, site.mode).apply();
        applyMode(site);
        site.session.reload();
        Toast.makeText(this, site.name + ": " + modeLabel(site), Toast.LENGTH_SHORT).show();
    }

    private static String modeLabel(Site site) {
        if (site.alwaysDesktopUa) {
            return site.mode == DESKTOP ? "\uD83D\uDDA5 PC view" : "\uD83D\uDCF1 Mobile view";
        }
        return MODE_LABEL[site.mode];
    }

    private static void applyMode(Site site) {
        GeckoSessionSettings st = site.session.getSettings();
        st.setUserAgentMode(site.mode == MOBILE && !site.alwaysDesktopUa
                ? GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                : GeckoSessionSettings.USER_AGENT_MODE_DESKTOP);
        st.setViewportMode(site.mode == DESKTOP
                ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
    }

    @Override
    public void onBackPressed() {
        if (popupOverlay.getVisibility() == View.VISIBLE) {
            closePopup();
        } else if (current >= 0 && sites[current].canGoBack) {
            sites[current].session.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------- sessions

    private GeckoSession createSession(final Site site) {
        GeckoSession s = new GeckoSession();
        site.session = s; // so applyMode can reach it before open()
        applyMode(site);
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,
                                                          LoadRequest request) {
                addLog("[" + site.name + "] load: " + request.uri);
                return GeckoResult.fromValue(handleLoad(request.uri));
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
                // target=_blank / window.open: keep everything in the same tab.
                if (isAllowed(uri)) {
                    session.loadUri(uri);
                } else {
                    openExternally(uri);
                }
                return null;
            }

            @Override
            public void onCanGoBack(GeckoSession session, boolean canGoBack) {
                site.canGoBack = canGoBack;
            }
        });
        s.setContentDelegate(contentDelegate);
        s.setPermissionDelegate(permissionDelegate);
        s.setPromptDelegate(promptDelegate);
        s.open(runtime);
        return s;
    }

    private AllowOrDeny handleLoad(String uri) {
        if (uri == null) return AllowOrDeny.DENY;
        Uri u = Uri.parse(uri);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        switch (scheme) {
            case "about":
            case "blob":
            case "data":
            case "moz-extension":
            case "resource":
                return AllowOrDeny.ALLOW;
            case "http":
            case "https":
                if (isAllowed(uri)) return AllowOrDeny.ALLOW;
                openExternally(uri); // anything else goes to the phone's normal browser
                return AllowOrDeny.DENY;
            default:
                return AllowOrDeny.DENY; // tg:, whatsapp:, intent: ... stay blocked
        }
    }

    private static boolean isAllowed(String uri) {
        try {
            Uri u = Uri.parse(uri);
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return false;
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            for (String allowed : ALLOWED_HOSTS) {
                if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void openExternally(String uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "No app for " + uri);
        }
    }

    // ------------------------------------------------------------ extension

    private void installExtension() {
        WebExtensionController controller = runtime.getWebExtensionController();
        controller.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override
            public GeckoResult<AllowOrDeny> onOptionalPrompt(WebExtension extension,
                                                             String[] permissions,
                                                             String[] origins,
                                                             String[] dataCollectionPermissions) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
        });
        controller.ensureBuiltIn(EXT_URI, EXT_ID).accept(
                ext -> {
                    if (ext == null) return;
                    Log.i(TAG, "Alpha extension ready: " + ext.id);
                    ext.setActionDelegate(actionDelegate);
                },
                err -> Log.e(TAG, "Alpha extension failed to load", err));
    }

    private final WebExtension.ActionDelegate actionDelegate = new WebExtension.ActionDelegate() {
        @Override
        public void onBrowserAction(WebExtension extension, GeckoSession session,
                                    WebExtension.Action action) {
            if (session == null) extAction = action; // the default (global) action
        }

        @Override
        public GeckoResult<GeckoSession> onOpenPopup(WebExtension extension,
                                                     WebExtension.Action action) {
            return showPopup();
        }

        @Override
        public GeckoResult<GeckoSession> onTogglePopup(WebExtension extension,
                                                       WebExtension.Action action) {
            return showPopup();
        }
    };

    private GeckoResult<GeckoSession> showPopup() {
        if (popupSession != null) {
            popupView.releaseSession();
            popupSession.close();
        }
        GeckoSession ps = new GeckoSession();
        ps.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCloseRequest(GeckoSession session) {
                closePopup();
            }
        });
        ps.open(runtime);
        popupView.setSession(ps);
        popupSession = ps;
        popupOverlay.setVisibility(View.VISIBLE);
        return GeckoResult.fromValue(ps);
    }

    private void openAlphaPopup() {
        if (extAction == null) {
            Toast.makeText(this, "Alpha extension is still loading...", Toast.LENGTH_SHORT).show();
            return;
        }
        if (popupOverlay.getVisibility() == View.VISIBLE) {
            closePopup();
        } else {
            extAction.click();
        }
    }

    private void closePopup() {
        popupOverlay.setVisibility(View.GONE);
        if (popupSession != null) {
            popupView.releaseSession();
            popupSession.close();
            popupSession = null;
        }
    }

    // ------------------------------------------------------------ debug log
    private static final LinkedList<String> debugLog = new LinkedList<>();
    private static final int MAX_DEBUG_LINES = 400;

    static synchronized void addLog(String line) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        debugLog.add(t + " " + line);
        while (debugLog.size() > MAX_DEBUG_LINES) debugLog.removeFirst();
        Log.d(TAG, line);
    }

    private void copyDebugLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ALPHA debug log (old GeckoView app) ===\n");
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" android ").append(Build.VERSION.RELEASE).append('\n');
        sb.append("sites: ");
        for (Site st : sites) sb.append(st.name).append('(').append(modeLabel(st)).append(") ");
        sb.append("\n\n--- app events ---\n");
        synchronized (MainActivity.class) {
            for (String l : debugLog) sb.append(l).append('\n');
        }
        sb.append("\n--- logcat (filtered) ---\n");
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "3000", "-v", "time"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            LinkedList<String> keep = new LinkedList<>();
            String line;
            while ((line = r.readLine()) != null) {
                // skip codec/GL noise so the mic lines are not pushed out
                if (line.matches("(?i).*(AudioCapabilities|GetShaderInfoLog|GetShaderSource|GLSL error|#extension|void main|compilation errors|Camera2Enumerator|GeckoLibLoad|GeckoThread|audio_processing_impl|input_volume_stats|AudioProcessing::|Neural residual|Echo control|Echo detector|Capture analyzer|Capture post|Render pre).*")) continue;
                if (line.matches("(?i).*(AlphaSocial|AAudio|AudioTrack|AudioRecord|AudioManager|AudioSystem|AudioStreamBuilder|audio_processing|input_volume|WebRTC|webrtc|getUserMedia|Bluetooth|bluetooth|communication mode|GeckoAppShell|MediaRecording|media perm|dir = INPUT|INPUT).*")) {
                    keep.add(line);
                    if (keep.size() > 800) keep.removeFirst();
                }
            }
            for (String k : keep) sb.append(k).append('\n');
        } catch (Exception e) {
            sb.append("logcat not available: ").append(e.getMessage()).append('\n');
        }
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cb != null) {
            cb.setPrimaryClip(ClipData.newPlainText("alpha-log", sb.toString()));
            Toast.makeText(this, "Log copied (" + debugLog.size() + " events)", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------- permissions

    private void requestMediaPermissionsUpFront() {
        List<String> missing = new ArrayList<>();
        List<String> wanted = new ArrayList<>();
        wanted.add(Manifest.permission.RECORD_AUDIO);
        wanted.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 31) wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) wanted.add("android.permission.POST_NOTIFICATIONS");
        for (String p : wanted) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
        startKeepAlive();
    }

    private void startKeepAlive() {
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                startForegroundService(new Intent(this, KeepAliveService.class));
            }
        } catch (Exception e) {
            Log.w(TAG, "Keep-alive service not started", e);
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) stopService(new Intent(this, KeepAliveService.class));
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMS) return;
        startKeepAlive();
        if (pendingAndroidPerm == null) return;
        boolean ok = results.length > 0;
        for (int r : results) ok &= r == PackageManager.PERMISSION_GRANTED;
        GeckoSession.PermissionDelegate.Callback cb = pendingAndroidPerm;
        pendingAndroidPerm = null;
        if (ok) cb.grant(); else cb.reject();
    }

    private final GeckoSession.PermissionDelegate permissionDelegate =
            new GeckoSession.PermissionDelegate() {
                @Override
                public void onAndroidPermissionsRequest(GeckoSession session, String[] permissions,
                                                        Callback callback) {
                    addLog("android perms requested: " + java.util.Arrays.toString(permissions));
                    List<String> missing = new ArrayList<>();
                    if (permissions != null) {
                        for (String p : permissions) {
                            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                                missing.add(p);
                            }
                        }
                    }
                    if (missing.isEmpty()) {
                        callback.grant();
                    } else {
                        pendingAndroidPerm = callback;
                        requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
                    }
                }

                @Override
                public void onMediaPermissionRequest(GeckoSession session, String uri,
                                                     MediaSource[] video, MediaSource[] audio,
                                                     MediaCallback callback) {
                    MediaSource v = (video != null && video.length > 0) ? video[0] : null;
                    MediaSource a = (audio != null && audio.length > 0) ? audio[0] : null;
                    addLog("media perm request: video=" + (v != null) + " audio=" + (a != null) + " uri=" + uri);
                    if (v == null && a == null) callback.reject();
                    else callback.grant(v, a);
                }
            };

    // ------------------------------------------------------------ downloads
    // The extension saves recordings as a blob download; GeckoView hands those to us.

    private final GeckoSession.ContentDelegate contentDelegate = new GeckoSession.ContentDelegate() {
        @Override
        public void onExternalResponse(GeckoSession session, WebResponse response) {
            saveDownload(response);
        }
    };

    private static String header(WebResponse r, String key) {
        if (r.headers == null) return null;
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private void saveDownload(final WebResponse r) {
        if (r.body == null) return;
        String type = header(r, "content-type");
        final String mime = type != null ? type.split(";")[0].trim() : "application/octet-stream";
        String name = null;
        String disp = header(r, "content-disposition");
        if (disp != null) {
            Matcher m = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?",
                    Pattern.CASE_INSENSITIVE).matcher(disp);
            if (m.find()) name = Uri.decode(m.group(1)).replaceAll("[^A-Za-z0-9 _.-]", "_");
        }
        if (name == null || name.isEmpty()) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            name = "alpha-" + System.currentTimeMillis() + (ext != null ? "." + ext : "");
        }
        final String fname = name;
        new Thread(() -> {
            try (InputStream in = r.body) {
                OutputStream out;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, fname);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    out = getContentResolver().openOutputStream(u);
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    out = new FileOutputStream(new File(dir, fname));
                }
                try (OutputStream o = out) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                runOnUiThread(() -> Toast.makeText(this, "Saved to Downloads: " + fname,
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                runOnUiThread(() -> Toast.makeText(this, "Download failed", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ---------------------------------------------------------- file upload

    private final GeckoSession.PromptDelegate promptDelegate = new GeckoSession.PromptDelegate() {
        @Override
        public GeckoResult<PromptResponse> onFilePrompt(GeckoSession session, FilePrompt prompt) {
            pendingFilePrompt = prompt;
            pendingFileResult = new GeckoResult<>();

            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            if (prompt.mimeTypes != null) {
                List<String> mimes = new ArrayList<>();
                for (String m : prompt.mimeTypes) {
                    if (m != null && m.contains("/")) mimes.add(m); // skip ".jpg" style entries
                }
                if (!mimes.isEmpty()) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
            }
            if (prompt.type == FilePrompt.Type.MULTIPLE) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(Intent.createChooser(i, "Select file"), REQ_FILE);
            } catch (Exception e) {
                pendingFileResult.complete(prompt.dismiss());
            }
            return pendingFileResult;
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || pendingFileResult == null) return;
        GeckoSession.PromptDelegate.FilePrompt prompt = pendingFilePrompt;
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result = pendingFileResult;
        pendingFilePrompt = null;
        pendingFileResult = null;

        List<Uri> uris = new ArrayList<>();
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int n = 0; n < clip.getItemCount(); n++) uris.add(clip.getItemAt(n).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        if (uris.isEmpty()) {
            result.complete(prompt.dismiss());
        } else if (uris.size() == 1) {
            result.complete(prompt.confirm(this, uris.get(0)));
        } else {
            result.complete(prompt.confirm(this, uris.toArray(new Uri[0])));
        }
    }
}
