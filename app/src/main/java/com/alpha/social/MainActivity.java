package com.alpha.social;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
            "telegram.org", "t.me", "telegram.me"
    };

    private static final class Site {
        final String name;
        final String url;
        final boolean desktopUserAgent;
        GeckoSession session;
        Button tab;
        boolean canGoBack;

        Site(String name, String url, boolean desktopUserAgent) {
            this.name = name;
            this.url = url;
            this.desktopUserAgent = desktopUserAgent;
        }
    }

    // GeckoRuntime can only be created once per process.
    private static GeckoRuntime runtime;

    private final Site[] sites = {
            // WhatsApp Web refuses phone user agents, so ask for the desktop one.
            new Site("WhatsApp", "https://web.whatsapp.com/", true),
            new Site("Instagram", "https://www.instagram.com/", false),
            new Site("Telegram", "https://web.telegram.org/a/", false),
    };

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

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        geckoView = new GeckoView(this);
        column.addView(geckoView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#111111"));
        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        for (int i = 0; i < sites.length; i++) {
            final int index = i;
            Button b = makeBarButton(sites[i].name);
            b.setOnClickListener(v -> select(index));
            sites[i].tab = b;
            bar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        Button alpha = makeBarButton("α Alpha");
        alpha.setTextColor(Color.parseColor("#FFC107"));
        alpha.setOnClickListener(v -> openAlphaPopup());
        bar.addView(alpha, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

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

    private Button makeBarButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.LTGRAY);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
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
        for (int i = 0; i < sites.length; i++) {
            sites[i].tab.setTextColor(i == index ? Color.WHITE : Color.GRAY);
        }
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
        GeckoSessionSettings st = s.getSettings();
        if (site.desktopUserAgent) {
            st.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP);
        }
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
