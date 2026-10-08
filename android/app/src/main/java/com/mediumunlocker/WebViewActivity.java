package com.inulute.mediumunlocker;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class WebViewActivity extends AppCompatActivity {

    private static final String TAG = "WebViewActivity";
    private static final String PREFS_NAME = "MediumUnlockerPrefs";
    private static final String PREF_WEBVIEW_POPUP_SHOWN_VERSION = "webview_popup_shown_version";
    private static final String STATE_PENDING_NAME = "pending_download_name";
    private static final String STATE_PENDING_MIME = "pending_download_mime";
    private static final String PENDING_DOWNLOAD_FILE = "pending_download";

    private static final String[] MIRROR_HOSTS = {
        "freedium.cfd", "freedium-mirror.cfd", "freedium-mirror-web.vercel.app",
        // Serves the web mirror's PDF / Markdown downloads.
        "medium-mirror-adey.vercel.app",
        "archive.is", "archive.ph", "archive.today", "archive.fo",
        "archive.li", "archive.vn", "archive.md"
    };

    private static final String POPUP_BLOCKER_JS =
            "(function(){" +
            "var id='mu-hide-popups';" +
            "if(!document.documentElement||document.getElementById(id))return;" +
            "var s=document.createElement('style');" +
            "s.id=id;" +
            "s.textContent='[data-sonner-toaster],[data-sonner-toast]{display:none !important;}';" +
            "document.documentElement.appendChild(s);" +
            "})();";

    /** Mirror base URLs, in the order a failed load falls back through. */
    private static final String[] MIRROR_BASES = new String[SettingsActivity.MIRROR_VALUES.length];
    static {
        for (int i = 0; i < MIRROR_BASES.length; i++) {
            MIRROR_BASES[i] = SettingsActivity.getMirrorBaseUrl(SettingsActivity.MIRROR_VALUES[i]);
        }
    }

    /**
     * How long a Freedium mirror gets to show a page before the next one is tried. When a
     * mirror is down its connection tends to hang rather than fail, so no error ever arrives.
     */
    private static final long MIRROR_TIMEOUT_MS = 15000;
    /** Shown by the Freedium site's service worker when it can't reach its server. */
    private static final String OFFLINE_PAGE_CHECK_JS =
            "(document.body&&document.body.innerText||'').indexOf('Reconnect to read new articles')>=0";
    /** Shown by the web mirror when it couldn't fetch the article. */
    private static final String WEB_MIRROR_FAILED_CHECK_JS =
            "(document.body&&document.body.innerText||'').indexOf('Could not load this article')>=0";
    private static final String WEB_MIRROR_HOST = "freedium-mirror-web.vercel.app";
    private static final String TITLE_SUFFIX = " — Freedium Mirror";

    private HistoryManager historyManager;
    private MenuItem bookmarkMenuItem;
    private MenuItem forwardMenuItem;

    private WebView webView;
    private LinearProgressIndicator progressBar;
    private MaterialToolbar toolbar;
    private ScrollView errorLayout;
    private TextView errorMessage;
    private MaterialCardView blockingInfoCard;
    private TextView proxyStatusText;
    private MaterialButton retryButton;
    private MaterialButton tryProxyButton;
    private MaterialButton tryAlternativeButton;
    private android.widget.LinearLayout loadingOverlay;
    private TextView loadingText;

    private String currentUrl;
    private String originalUrl;
    private boolean positionRestored = false;
    private int currentMirrorIndex = 0;
    private int startMirrorIndex = 0;
    private boolean mainFrameError = false;
    /** Mirror load the app started and hasn't yet seen a real page for, or null. */
    private String pendingMirrorUrl;
    private final Runnable mirrorTimeout = () -> tryNextMirror("timed out");

    // Article downloads (the mirror's "Download article" menu)
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private String downloadBridgeJs;
    /** Secret the injected scripts pass back to DownloadBridge, so other frames can't use it. */
    private final String bridgeToken = newBridgeToken();
    /** Main-frame URL, read from the JavaBridge thread to check who is asking to save. */
    private volatile String pageUrl;
    /** File waiting for the "Save as" picker used on Android 9 and below. */
    private String pendingDownloadName;
    private String pendingDownloadMime;

    private final ActivityResultLauncher<Intent> saveAsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Uri uri = result.getData() != null ? result.getData().getData() : null;
                File pending = new File(getCacheDir(), PENDING_DOWNLOAD_FILE);
                String name = pendingDownloadName;
                String mime = pendingDownloadMime;
                pendingDownloadName = null;
                pendingDownloadMime = null;
                if (result.getResultCode() != RESULT_OK || uri == null || name == null) {
                    pending.delete();
                    return;
                }
                runDownloadTask(() -> {
                    try {
                        ArticleDownloads.copyToUri(this, pending, uri);
                        runOnUiThread(() -> showDownloadSaved(uri, name, mime, "Saved " + name));
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to save " + name, e);
                        runOnUiThread(this::showDownloadFailed);
                    } finally {
                        pending.delete();
                    }
                });
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        if (savedInstanceState != null) {
            pendingDownloadName = savedInstanceState.getString(STATE_PENDING_NAME);
            pendingDownloadMime = savedInstanceState.getString(STATE_PENDING_MIME);
        }

        historyManager = HistoryManager.getInstance(this);
        initializeViews();
        setupToolbar();
        setupWebView();
        setupButtons();
        loadUrl();
        showUpdateDialogIfNeeded();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        saveReadingPosition();
        positionRestored = false;
        currentMirrorIndex = 0;
        startMirrorIndex = 0;
        loadUrl();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PENDING_NAME, pendingDownloadName);
        outState.putString(STATE_PENDING_MIME, pendingDownloadMime);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveReadingPosition();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveReadingPosition();
    }

    private void saveReadingPosition() {
        if (originalUrl == null || originalUrl.isEmpty() || webView == null) return;
        if (!rememberPosition()) return;
        final String urlKey = originalUrl;
        webView.evaluateJavascript("window.scrollY", value -> {
            try {
                int y = (int) Double.parseDouble(value.trim());
                if (y > 0) historyManager.savePosition(urlKey, y);
            } catch (Exception ignored) { }
        });
    }

    private boolean rememberPosition() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_REMEMBER_POSITION, true);
    }

    private boolean hidePopups() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_HIDE_POPUPS, false);
    }

    /** Injects a stylesheet that hides the mirror's toast notifications. Idempotent per page. */
    private void injectPopupBlocker() {
        if (webView == null || !hidePopups()) return;
        webView.evaluateJavascript(POPUP_BLOCKER_JS, null);
    }

    /** Lets the mirror's own PDF / Markdown download links save files. Idempotent per page. */
    private void injectDownloadBridge() {
        if (webView == null || !isMirrorUrl(webView.getUrl())) return;
        if (downloadBridgeJs == null) {
            String js = readRawResource(R.raw.download_bridge);
            if (js != null) downloadBridgeJs = js.replace("__MU_TOKEN__", JSONObject.quote(bridgeToken));
        }
        if (downloadBridgeJs != null) webView.evaluateJavascript(downloadBridgeJs, null);
    }

    private void showUpdateDialogIfNeeded() {
        Intent intent = getIntent();
        String updateVersion = intent != null ? intent.getStringExtra("update_version") : null;
        String updateUrl = intent != null ? intent.getStringExtra("update_url") : null;
        if (updateVersion == null || updateVersion.isEmpty() || updateUrl == null || updateUrl.isEmpty()) return;
        String shownVersion = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_WEBVIEW_POPUP_SHOWN_VERSION, "");
        if (updateVersion.equals(shownVersion)) return;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(PREF_WEBVIEW_POPUP_SHOWN_VERSION, updateVersion).apply();
        showUpdateDialog(updateVersion, updateUrl);
    }

    private void showUpdateDialog(String version, String url) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_update);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCancelable(true);
        ((TextView) dialog.findViewById(R.id.updateVersionText)).setText("v" + version + " is now available");
        dialog.findViewById(R.id.updateCancelButton).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.updateNowButton).setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
            dialog.dismiss();
        });
        dialog.show();
    }

    private void initializeViews() {
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        toolbar = findViewById(R.id.toolbar);
        errorLayout = findViewById(R.id.errorLayout);
        errorMessage = findViewById(R.id.errorMessage);
        blockingInfoCard = findViewById(R.id.blockingInfoCard);
        proxyStatusText = findViewById(R.id.proxyStatusText);
        retryButton = findViewById(R.id.retryButton);
        tryProxyButton = findViewById(R.id.tryProxyButton);
        tryAlternativeButton = findViewById(R.id.tryAlternativeButton);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        loadingText = findViewById(R.id.loadingText);
    }

    private void setupToolbar() {
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.webview_menu);

        bookmarkMenuItem = toolbar.getMenu().findItem(R.id.action_bookmark);
        forwardMenuItem = toolbar.getMenu().findItem(R.id.action_forward);
        if (forwardMenuItem != null) forwardMenuItem.setVisible(false);

        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_bookmark) { toggleBookmark(); return true; }
            else if (id == R.id.action_forward) { webView.goForward(); return true; }
            else if (id == R.id.action_copy_markdown) { copyAsMarkdown(); return true; }
            else if (id == R.id.action_open_browser) { openInBrowser(); return true; }
            else if (id == R.id.action_share) { shareArticle(); return true; }
            else if (id == R.id.action_refresh) { webView.reload(); return true; }
            return false;
        });
    }

    private void updateNavButtons() {
        if (forwardMenuItem != null) {
            forwardMenuItem.setVisible(webView != null && webView.canGoForward());
        }
    }

    private void toggleBookmark() {
        if (originalUrl == null || originalUrl.isEmpty()) return;
        String title = webView.getTitle() != null ? webView.getTitle() : "";
        String freediumUrl = webView.getUrl() != null ? webView.getUrl() : currentUrl;
        if (historyManager.isBookmarked(originalUrl)) {
            historyManager.removeBookmark(originalUrl);
            updateBookmarkIcon(false);
            Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show();
        } else {
            historyManager.addBookmark(title, originalUrl, freediumUrl != null ? freediumUrl : "");
            updateBookmarkIcon(true);
            Toast.makeText(this, "Bookmarked!", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateBookmarkIcon(boolean bookmarked) {
        if (bookmarkMenuItem != null) {
            bookmarkMenuItem.setIcon(bookmarked ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // Use text zoom from settings
        int textZoom = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(SettingsActivity.PREF_TEXT_ZOOM, 100);
        settings.setTextZoom(textZoom);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        settings.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        );

        webView.addJavascriptInterface(new DownloadBridge(), "MuDownloads");
        webView.setDownloadListener(this::onDownloadRequested);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                pageUrl = url;
                positionRestored = false;
                mainFrameError = false;
                showLoading();
                errorLayout.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                updateNavButtons();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideLoading();
                injectPopupBlocker();
                injectDownloadBridge();
                if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);

                String title = cleanTitle(view.getTitle());
                boolean goodTitle = title != null && !title.isEmpty()
                        && !title.startsWith("http") && !isErrorTitle(title);
                if (goodTitle) {
                    toolbar.setTitle(title);
                }
                // An abandoned load can still finish here, carrying the previous page's title.
                if (pendingMirrorUrl != null && !isStaleMirrorLoad(Uri.parse(url))) {
                    // Mirrors report some failures as ordinary pages; look for those first.
                    String failedCheck = isFreediumUrl(url) ? OFFLINE_PAGE_CHECK_JS
                            : isWebMirrorUrl(url) ? WEB_MIRROR_FAILED_CHECK_JS : null;
                    if (failedCheck != null) {
                        view.evaluateJavascript(failedCheck, failed -> {
                            if ("true".equals(failed)) tryNextMirror("mirror couldn't load the article");
                            else if (goodTitle) mirrorLoaded();
                        });
                    } else if (goodTitle) {
                        mirrorLoaded();
                    }
                }

                // Save to history only on clean loads with real titles
                if (!mainFrameError && originalUrl != null && !originalUrl.isEmpty()) {
                    String pageTitle = goodTitle ? title : "";
                    if (!pageTitle.isEmpty()) {
                        historyManager.saveToHistory(pageTitle, originalUrl, url != null ? url : "");
                    }
                    updateBookmarkIcon(historyManager.isBookmarked(originalUrl));
                }

                // Restore reading position (only on first load of this article)
                if (!positionRestored && rememberPosition() && originalUrl != null && !originalUrl.isEmpty()) {
                    positionRestored = true;
                    int savedY = historyManager.getPosition(originalUrl);
                    if (savedY > 0) {
                        webView.postDelayed(() ->
                            webView.evaluateJavascript("window.scrollTo({top:" + savedY + ",behavior:'smooth'})", null), 1200);
                    }
                }

                updateNavButtons();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // The mirror moves between articles client-side, without onPageStarted.
                pageUrl = url;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame() && !isStaleMirrorLoad(request.getUrl())) {
                    hideLoading();
                    tryNextMirror("error " + error.getErrorCode());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                super.onReceivedHttpError(view, request, response);
                // A mirror that is down behind its CDN answers with a 5xx page instead.
                if (request.isForMainFrame() && pendingMirrorUrl != null
                        && response.getStatusCode() >= 500 && !isStaleMirrorLoad(request.getUrl())) {
                    tryNextMirror("HTTP " + response.getStatusCode());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                String url = error.getUrl();
                if (url != null && (url.contains("freedium-mirror.cfd") || url.contains("freedium.cfd")
                        || isArchiveUrl(url)))
                    handler.proceed();
                else handler.cancel();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("freedium.cfd") || url.contains("freedium-mirror.cfd")
                        || isMirrorUrl(url) || isArchiveUrl(url) || url.contains("medium.com")) {
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { Log.e(TAG, "Failed to open URL: " + url, e); }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress > 0) {
                    injectPopupBlocker();
                    injectDownloadBridge();
                }
                if (newProgress == 100) hideLoading();
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                title = cleanTitle(title);
                if (isErrorTitle(title) || mainFrameError) return;
                if (!title.startsWith("http")) {
                    toolbar.setTitle(title);
                    if (originalUrl != null && !originalUrl.isEmpty()) {
                        String currentFreediumUrl = view.getUrl() != null ? view.getUrl() : "";
                        historyManager.saveToHistory(title, originalUrl, currentFreediumUrl);
                    }
                }
            }
        });
    }

    private void setupButtons() {
        retryButton.setOnClickListener(v -> {
            errorLayout.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            if (currentUrl != null) startMirrorLoad(currentUrl);
        });

        tryProxyButton.setVisibility(View.GONE);

        tryAlternativeButton.setText("Try Different Mirror");
        tryAlternativeButton.setVisibility(View.VISIBLE);
        tryAlternativeButton.setOnClickListener(v -> {
            if (originalUrl != null) {
                int next = nextSupportedMirror(currentMirrorIndex);
                startMirrorIndex = next;
                if (loadingText != null) loadingText.setText("Switching to " + getMirrorLabel(next) + "...");
                if (loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE);
                errorLayout.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                loadMirror(next);
            }
        });

        blockingInfoCard.setVisibility(View.GONE);
    }

    private void loadUrl() {
        Intent intent = getIntent();
        currentUrl = intent.getStringExtra("url");
        originalUrl = intent.getStringExtra("originalUrl");

        // Determine which mirror index matches the URL we're opening
        currentMirrorIndex = 0;
        if (currentUrl != null) {
            for (int i = 0; i < MIRROR_BASES.length; i++) {
                if (currentUrl.startsWith(MIRROR_BASES[i])) {
                    currentMirrorIndex = i;
                    break;
                }
            }
        }
        startMirrorIndex = currentMirrorIndex;

        if (currentUrl == null || currentUrl.isEmpty()) {
            showError();
        } else if (!mirrorSupportsArticle(currentMirrorIndex)) {
            // e.g. the web mirror picked in Settings, opening a New York Times link.
            int next = nextSupportedMirror(currentMirrorIndex);
            startMirrorIndex = next;
            loadMirror(next);
        } else {
            startMirrorLoad(currentUrl);
        }
    }

    private void loadMirror(int index) {
        currentMirrorIndex = index;
        currentUrl = SettingsActivity.buildMirrorUrl(MIRROR_BASES[index], originalUrl);
        startMirrorLoad(currentUrl);
    }

    /**
     * Loads a mirror page and watches it: an error, a 5xx, an offline page or (for the
     * Freedium mirrors) no page within MIRROR_TIMEOUT_MS moves on to the next mirror.
     * Archive.is is often slow, so it gets no timeout.
     */
    private void startMirrorLoad(String url) {
        webView.removeCallbacks(mirrorTimeout);
        pendingMirrorUrl = url;
        if (!isArchiveUrl(url)) webView.postDelayed(mirrorTimeout, MIRROR_TIMEOUT_MS);
        webView.loadUrl(url);
    }

    /** The pending mirror showed a real page; stop watching it. */
    private void mirrorLoaded() {
        webView.removeCallbacks(mirrorTimeout);
        pendingMirrorUrl = null;
    }

    /** Gives up on the current mirror and loads the next, or shows the error once all were tried. */
    private void tryNextMirror(String reason) {
        if (webView == null || isFinishing() || isDestroyed()) return;
        webView.removeCallbacks(mirrorTimeout);
        pendingMirrorUrl = null;
        int next = currentMirrorIndex;
        do {
            next = (next + 1) % MIRROR_BASES.length;
        } while (next != startMirrorIndex && !mirrorSupportsArticle(next));
        if (originalUrl == null || next == startMirrorIndex) {
            Log.d(TAG, getMirrorLabel(currentMirrorIndex) + " failed (" + reason + "), no mirrors left");
            webView.stopLoading();
            webView.setVisibility(View.GONE);
            mainFrameError = true;
            showError();
            return;
        }
        Log.d(TAG, getMirrorLabel(currentMirrorIndex) + " failed (" + reason + "), trying " + getMirrorLabel(next));
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.VISIBLE);
            if (loadingText != null) loadingText.setText("Trying " + getMirrorLabel(next) + "...");
        }
        errorLayout.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        loadMirror(next);
    }

    /**
     * True for a callback about a load the app already moved on from, e.g. the old mirror's
     * request being aborted after a timeout. Those mustn't trigger another fallback.
     */
    private boolean isStaleMirrorLoad(Uri requestUrl) {
        if (pendingMirrorUrl == null || requestUrl == null) return false;
        String pendingHost = Uri.parse(pendingMirrorUrl).getHost();
        return pendingHost != null && !pendingHost.equalsIgnoreCase(requestUrl.getHost());
    }

    private boolean mirrorSupportsArticle(int index) {
        return SettingsActivity.mirrorSupports(SettingsActivity.MIRROR_VALUES[index], originalUrl);
    }

    /** The next mirror after {@code index} that handles this article's publisher. */
    private int nextSupportedMirror(int index) {
        int next = index;
        do {
            next = (next + 1) % MIRROR_BASES.length;
        } while (next != index && !mirrorSupportsArticle(next));
        return next;
    }

    private static boolean isWebMirrorUrl(String url) {
        if (url == null) return false;
        String host = Uri.parse(url).getHost();
        return WEB_MIRROR_HOST.equalsIgnoreCase(host);
    }

    private static boolean isFreediumUrl(String url) {
        if (url == null) return false;
        String host = Uri.parse(url).getHost();
        return host != null && (host.equals("freedium.cfd") || host.endsWith(".freedium.cfd")
                || host.equals("freedium-mirror.cfd") || host.endsWith(".freedium-mirror.cfd"));
    }

    /** Drops the site name the web mirror appends to article titles. */
    private static String cleanTitle(String title) {
        if (title != null && title.endsWith(TITLE_SUFFIX)) {
            return title.substring(0, title.length() - TITLE_SUFFIX.length()).trim();
        }
        return title;
    }

    private boolean isArchiveUrl(String url) {
        return url.contains("archive.is") || url.contains("archive.ph")
                || url.contains("archive.today") || url.contains("archive.fo")
                || url.contains("archive.li") || url.contains("archive.vn")
                || url.contains("archive.md");
    }

    private static String newBridgeToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    /** True for pages served by one of the mirrors, matched on the host rather than anywhere in the URL. */
    private static boolean isMirrorUrl(String url) {
        if (url == null) return false;
        String host = Uri.parse(url).getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String mirror : MIRROR_HOSTS) {
            if (host.equals(mirror) || host.endsWith("." + mirror)) return true;
        }
        return false;
    }

    private boolean isErrorTitle(String title) {
        if (title == null || title.isEmpty()) return true;
        String lower = title.toLowerCase();
        return lower.contains("not available") || lower.contains("not found")
                || lower.contains("can't be reached") || lower.contains("no internet")
                || lower.contains("err_") || lower.equals("404") || lower.equals("error")
                // The web mirror's own error page has just the bare site name.
                || lower.equals("freedium mirror");
    }

    private String getMirrorLabel(int index) {
        return SettingsActivity.MIRROR_LABELS[index];
    }

    private void showLoading() {
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
    }

    private void hideLoading() {
        progressBar.setVisibility(View.GONE);
    }

    private void showError() {
        errorLayout.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        errorMessage.setText("Unable to load article. Please check your connection and try again.");
    }

    private void openInBrowser() {
        String url = webView.getUrl() != null ? webView.getUrl() : currentUrl;
        if (url != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    /** Extracts the rendered article as markdown and puts it on the clipboard. */
    private void copyAsMarkdown() {
        if (webView == null) return;
        String script = readRawResource(R.raw.copy_markdown);
        if (script == null) {
            Toast.makeText(this, "Couldn't read article text", Toast.LENGTH_SHORT).show();
            return;
        }
        webView.evaluateJavascript(script, value -> {
            String markdown = null;
            try {
                Object parsed = new JSONTokener(value).nextValue();
                if (parsed instanceof String) markdown = (String) parsed;
            } catch (JSONException e) {
                Log.e(TAG, "Failed to decode extracted markdown", e);
            }
            if (markdown == null || markdown.trim().isEmpty()) {
                Toast.makeText(this, "Couldn't read article text", Toast.LENGTH_SHORT).show();
                return;
            }
            if (originalUrl != null && !originalUrl.isEmpty()) {
                markdown = markdown + "\n\n---\n\nSource: " + originalUrl + "\n";
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) {
                Toast.makeText(this, "Clipboard unavailable", Toast.LENGTH_SHORT).show();
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Article markdown", markdown));
            // Android 13+ shows its own copy confirmation, so don't double up.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                Toast.makeText(this, "Copied as Markdown", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String readRawResource(int resId) {
        try (java.io.InputStream in = getResources().openRawResource(resId);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Failed to read raw resource", e);
            return null;
        }
    }

    /**
     * Receives files from download_bridge.js. Methods run on the WebView's JavaBridge
     * thread, and only act while the main frame is one of the mirrors.
     *
     * The interface is visible to every frame, iframes from any origin included, so each
     * call must carry {@link #bridgeToken}. Only scripts this activity injects into the
     * main frame know it.
     */
    private class DownloadBridge {
        private boolean allowed(String token) {
            if (!bridgeToken.equals(token) || !isMirrorUrl(pageUrl)) {
                Log.w(TAG, "Ignoring download bridge call on " + pageUrl);
                return false;
            }
            return true;
        }

        @JavascriptInterface
        public void started(String token, String fileName) {
            if (!allowed(token)) return;
            runOnUiThread(() -> Toast.makeText(WebViewActivity.this, "Downloading…", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void save(String token, String base64, String fileName, String mimeType) {
            if (!allowed(token)) return;
            // Base64 is 4 chars per 3 bytes; refuse before decoding anything that big.
            if (base64 == null || base64.length() / 4L * 3 > ArticleDownloads.MAX_BYTES) {
                Log.w(TAG, "Ignoring oversized download " + fileName);
                runOnUiThread(WebViewActivity.this::showDownloadFailed);
                return;
            }
            runDownloadTask(() -> {
                byte[] bytes;
                try {
                    bytes = Base64.decode(base64, Base64.DEFAULT);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Bad download payload", e);
                    runOnUiThread(WebViewActivity.this::showDownloadFailed);
                    return;
                }
                saveDownload(bytes, fileName, mimeType);
            });
        }

        @JavascriptInterface
        public void fail(String token, String fileName) {
            if (!allowed(token)) return;
            runOnUiThread(WebViewActivity.this::showDownloadFailed);
        }
    }

    /** Downloads the bridge didn't catch: plain navigations to a file, or other origins. */
    private void onDownloadRequested(String url, String userAgent, String contentDisposition,
                                     String mimeType, long contentLength) {
        if (url == null) return;
        // Downloads are only for the mirror's article menu; a page or ad elsewhere can't save files.
        if (!isMirrorUrl(webView.getUrl())) {
            Log.w(TAG, "Ignoring download on " + webView.getUrl());
            return;
        }
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            // Only readable from inside the page, and only while the page still holds it.
            String token = JSONObject.quote(bridgeToken);
            webView.evaluateJavascript("(function(u,t){fetch(u).then(function(r){return r.blob();})"
                    + ".then(function(b){var f=new FileReader();f.onload=function(){var s=String(f.result);"
                    + "MuDownloads.save(t,s.substring(s.indexOf(',')+1),'',b.type);};f.readAsDataURL(b);})"
                    + ".catch(function(){MuDownloads.fail(t,'');});})(" + JSONObject.quote(url) + "," + token + ")", null);
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        String cookies = CookieManager.getInstance().getCookie(url);
        String referer = webView.getUrl();
        Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show();
        runDownloadTask(() -> {
            try {
                ArticleDownloads.Download download = ArticleDownloads.fetch(
                        url, userAgent, cookies, referer, contentDisposition, mimeType);
                saveDownload(download.bytes, download.fileName, download.mimeType);
            } catch (IOException e) {
                Log.e(TAG, "Download failed: " + url, e);
                runOnUiThread(this::showDownloadFailed);
            }
        });
    }

    /** Saves a downloaded file. Does disk I/O, so run it on the download executor. */
    private void saveDownload(byte[] bytes, String fileName, String mimeType) {
        String name = ArticleDownloads.sanitizeFileName(fileName, mimeType);
        if (!ArticleDownloads.isAllowedType(name)) {
            Log.w(TAG, "Refusing to save " + name);
            runOnUiThread(this::showDownloadFailed);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Uri uri = ArticleDownloads.saveToDownloads(this, bytes, name);
                runOnUiThread(() -> showDownloadSaved(uri, name, mimeType,
                        "Saved to Downloads/" + ArticleDownloads.FOLDER));
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Failed to save " + name, e);
                runOnUiThread(this::showDownloadFailed);
            }
            return;
        }
        // Android 9 and below: writing to Downloads needs a storage permission, so let the
        // user pick the location instead. The bytes wait in the cache until they do.
        File pending = new File(getCacheDir(), PENDING_DOWNLOAD_FILE);
        try (FileOutputStream out = new FileOutputStream(pending)) {
            out.write(bytes);
        } catch (IOException e) {
            Log.e(TAG, "Failed to stage " + name, e);
            runOnUiThread(this::showDownloadFailed);
            return;
        }
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            pendingDownloadName = name;
            pendingDownloadMime = mimeType;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(ArticleDownloads.storageMimeType(name))
                    .putExtra(Intent.EXTRA_TITLE, name);
            try {
                saveAsLauncher.launch(intent);
            } catch (ActivityNotFoundException e) {
                pendingDownloadName = null;
                pendingDownloadMime = null;
                pending.delete();
                showDownloadFailed();
            }
        });
    }

    private void runDownloadTask(Runnable task) {
        try {
            downloadExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // The activity is being destroyed.
        }
    }

    private void showDownloadSaved(Uri uri, String fileName, String mimeType, String message) {
        if (isFinishing() || isDestroyed()) return;
        Snackbar.make(webView, message, Snackbar.LENGTH_LONG)
                .setAction("Open", v -> openDownload(uri, fileName, mimeType))
                .show();
    }

    private void showDownloadFailed() {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, "Download failed. Please try again.", Toast.LENGTH_SHORT).show();
    }

    private void openDownload(Uri uri, String fileName, String mimeType) {
        String type = ArticleDownloads.viewMimeType(fileName, mimeType);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException e) {
            if (type.startsWith("text/") && !"text/plain".equals(type)) {
                // Few devices have a markdown viewer, but most can show plain text.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "text/plain")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    return;
                } catch (ActivityNotFoundException ignored) { }
            }
            Toast.makeText(this, "No app found to open " + fileName, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareArticle() {
        String url = originalUrl != null ? originalUrl : webView.getUrl();
        String title = webView.getTitle();
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
        shareIntent.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(shareIntent, "Share article"));
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        downloadExecutor.shutdown();
        if (webView != null) webView.removeCallbacks(mirrorTimeout);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
