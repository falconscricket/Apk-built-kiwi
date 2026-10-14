package com.alpha.social;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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

import java.util.ArrayList;
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
        int mode;
        GeckoSession session;
        boolean canGoBack;

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
            new Site("WhatsApp", "https://web.whatsapp.com/", FIT),
            new Site("Instagram", "https://www.instagram.com/", MOBILE),
            new Site("Telegram", "https://web.telegram.org/a/", MOBILE),
            // Discord web sends phones to the app store, so it also starts in FIT.
            new Site("Discord", "https://discord.com/app", FIT),
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
        for (Site st : sites) st.mode = prefs.getInt("mode_" + st.name, st.defaultMode);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        geckoView = new GeckoView(this);
        column.addView(geckoView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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

        setContentView(root);

        requestMediaPermissionsUpFront();
        select(0);
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
        String modeText = current >= 0 ? MODE_LABEL[sites[current].mode] : "";
        m.getMenu().add(1, idMode, 100, "Page mode: " + modeText + "  (tap to change)");
        m.getMenu().add(1, idAlpha, 101, "\u03B1 Alpha panel");
        m.getMenu().add(1, idReload, 102, "Reload page");
        m.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id < sites.length) select(id);
            else if (id == idMode) cycleMode();
            else if (id == idAlpha) openAlphaPopup();
            else if (id == idReload && current >= 0) sites[current].session.reload();
            return true;
        });
        m.show();
    }

    private void cycleMode() {
        if (current < 0) return;
        Site site = sites[current];
        site.mode = (site.mode + 1) % 3;
        prefs.edit().putInt("mode_" + site.name, site.mode).apply();
        applyMode(site);
        site.session.reload();
        Toast.makeText(this, site.name + ": " + MODE_LABEL[site.mode], Toast.LENGTH_SHORT).show();
    }

    private static void applyMode(Site site) {
        GeckoSessionSettings st = site.session.getSettings();
        st.setUserAgentMode(site.mode == MOBILE
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

    // ---------------------------------------------------------- permissions

    private void requestMediaPermissionsUpFront() {
        List<String> missing = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMS || pendingAndroidPerm == null) return;
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
                    if (v == null && a == null) callback.reject();
                    else callback.grant(v, a);
                }
            };

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
