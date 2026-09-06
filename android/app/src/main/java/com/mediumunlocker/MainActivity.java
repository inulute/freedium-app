package com.inulute.mediumunlocker;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.Window;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // Primary: Shields.io (no rate limit)
    private static final String SHIELDS_API_URL = "https://img.shields.io/github/v/release/inulute/freedium-app.json";
    // Backup: GitHub API (has rate limit)
    private static final String GITHUB_API_URL = "https://api.github.com/repos/inulute/freedium-app/releases/latest";
    private static final String GITHUB_RELEASES_URL = "https://github.com/inulute/freedium-app/releases/latest";

    // Sites Freedium resolves directly. Keep in sync with the deep-link
    // intent-filter in AndroidManifest.xml, which also declares the *. form
    // of each host (the wildcard does not cover the apex on its own).
    static final String[] SUPPORTED_HOSTS = {
            "medium.com", "nytimes.com", "washingtonpost.com", "bloomberg.com",
            "reuters.com", "economist.com", "ft.com"
    };

    private static final String PREFS_NAME = "MediumUnlockerPrefs";
    private static final String PREF_SKIP_VERSION = "skip_version";
    private static final String PREF_RENAME_NOTICE_SHOWN = "rename_notice_shown";
    private static final String PREF_POPUP_SHOWN_VERSION = "popup_shown_version";
    private static final String PREF_CACHED_VERSION = "cached_latest_version";
    private static final String PREF_LAST_CHECK = "last_update_check";
    private static final long UPDATE_CHECK_INTERVAL = 6 * 60 * 60 * 1000; // 6 hours

    private TextInputEditText urlInput;
    private MaterialButton unlockButton;
    private MaterialButton aboutButton;
    private MaterialButton supportButton;
    private MaterialButton githubButton;
    private MaterialButton updateButton;
    private MaterialButton historyButton;
    private MaterialButton settingsButton;
    private View deepLinkBanner;
    private MaterialButton deepLinkSettingsButton;
    private LinearLayout recentSection;
    private LinearLayout recentContainer;
    private TextView recentSectionLabel;
    private View homeCategoryScroll;
    private ChipGroup homeCategoryChips;
    private boolean buildingHomeChips = false;
    // Home-screen bookmark filter. "" as a value means uncategorised, so "show
    // everything" needs its own flag rather than a sentinel string.
    private boolean homeFilterAll = true;
    private String homeCategoryFilter = "";

    private ExecutorService executor;

    // Pending update info
    private String pendingUpdateVersion = null;
    private String pendingUpdateUrl = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        executor = Executors.newSingleThreadExecutor();

        migrateMirrorPref();

        initializeViews();
        setupListeners();
        handleIntent(getIntent());

        // Check for updates in background
        checkForUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndShowDeepLinkBanner();
        refreshRecentArticles();
    }

    private void refreshRecentArticles() {
        if (recentSection == null || recentContainer == null) return;
        HistoryManager hm = HistoryManager.getInstance(this);
        String feed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(SettingsActivity.PREF_HOME_FEED, "history");

        List<HistoryManager.HistoryItem> items;
        if ("bookmarks".equals(feed)) {
            items = applyHomeCategoryFilter(hm, hm.getBookmarks());
            if (recentSectionLabel != null) recentSectionLabel.setText("BOOKMARKS");
        } else {
            items = hm.getHistory();
            if (recentSectionLabel != null) recentSectionLabel.setText("RECENT");
        }

        if (!"bookmarks".equals(feed) && homeCategoryScroll != null) {
            homeCategoryScroll.setVisibility(View.GONE);
        }

        List<HistoryManager.HistoryItem> recent = items.subList(0, Math.min(5, items.size()));

        // The section still has to stay up when a filter matched nothing, or the
        // chips that produced the empty result would disappear along with it.
        boolean filtering = "bookmarks".equals(feed) && !homeFilterAll;
        if (recent.isEmpty() && !filtering) {
            recentSection.setVisibility(View.GONE);
            return;
        }

        recentSection.setVisibility(View.VISIBLE);
        recentContainer.removeAllViews();

        // Parsed once for the whole feed rather than twice per card, and kept in
        // step with the inline toggle below so the star never goes stale. The map
        // also carries each bookmark's category, so cards can show where it is filed
        // even in the history and combined feeds, where the item itself has none.
        Map<String, String> bookmarkCategories = hm.getBookmarkCategories();

        SimpleDateFormat sdf = new SimpleDateFormat("MMM d", Locale.getDefault());
        for (HistoryManager.HistoryItem item : recent) {
            View card = LayoutInflater.from(this).inflate(R.layout.item_recent, recentContainer, false);

            TextView titleView = card.findViewById(R.id.recentItemTitle);
            TextView metaView = card.findViewById(R.id.recentItemMeta);
            ImageButton bookmarkBtn = card.findViewById(R.id.recentItemBookmark);

            titleView.setText(item.title.isEmpty() ? extractDomain(item.originalUrl) : item.title);
            String date = item.timestamp > 0 ? sdf.format(new Date(item.timestamp)) : "";
            String domain = extractDomain(item.originalUrl);
            String meta = domain.isEmpty() ? date : (date.isEmpty() ? domain : domain + " · " + date);
            String category = bookmarkCategories.get(item.originalUrl);
            if (category != null && !category.isEmpty()) {
                meta = meta.isEmpty() ? category : meta + " · " + category;
            }
            metaView.setText(meta);

            View tagView = card.findViewById(R.id.recentItemTag);
            if (tagView != null) tagView.setVisibility(View.GONE);

            updateBookmarkBtn(bookmarkBtn, bookmarkCategories.containsKey(item.originalUrl));
            bookmarkBtn.setOnClickListener(v -> {
                boolean nowBookmarked;
                if (bookmarkCategories.containsKey(item.originalUrl)) {
                    hm.removeBookmark(item.originalUrl);
                    bookmarkCategories.remove(item.originalUrl);
                    nowBookmarked = false;
                } else {
                    hm.addBookmark(item.title, item.originalUrl, item.freediumUrl);
                    // addBookmark preserves any category the URL already had
                    bookmarkCategories.put(item.originalUrl, "");
                    nowBookmarked = true;
                }
                updateBookmarkBtn(bookmarkBtn, nowBookmarked);
            });

            final HistoryManager.HistoryItem finalItem = item;
            card.setOnClickListener(v -> {
                Intent intent = new Intent(this, WebViewActivity.class);
                intent.putExtra("url", convertToFreedium(finalItem.originalUrl));
                intent.putExtra("originalUrl", finalItem.originalUrl);
                startActivity(intent);
            });

            recentContainer.addView(card);
        }
    }

    private void updateBookmarkBtn(ImageButton btn, boolean bookmarked) {
        btn.setImageResource(bookmarked ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        btn.setColorFilter(bookmarked
                ? getResources().getColor(R.color.primary, getTheme())
                : getResources().getColor(R.color.text_secondary, getTheme()));
    }

    private String extractDomain(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return "";
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) { return ""; }
    }

    private void initializeViews() {
        urlInput = findViewById(R.id.urlInput);
        unlockButton = findViewById(R.id.unlockButton);
        aboutButton = findViewById(R.id.aboutButton);
        supportButton = findViewById(R.id.supportButton);
        githubButton = findViewById(R.id.githubButton);
        updateButton = findViewById(R.id.updateButton);
        historyButton = findViewById(R.id.historyButton);
        settingsButton = findViewById(R.id.settingsButton);
        deepLinkBanner = findViewById(R.id.deepLinkBanner);
        deepLinkSettingsButton = findViewById(R.id.deepLinkSettingsButton);
        recentSection = findViewById(R.id.recentSection);
        recentContainer = findViewById(R.id.recentContainer);
        recentSectionLabel = findViewById(R.id.recentSectionLabel);
        homeCategoryScroll = findViewById(R.id.homeCategoryScroll);
        homeCategoryChips = findViewById(R.id.homeCategoryChips);
    }

    private void setupListeners() {
        unlockButton.setOnClickListener(v -> processUrl());

        aboutButton.setOnClickListener(v -> showAboutDialog());

        // Top bar buttons
        updateButton.setOnClickListener(v -> showUpdateDialog());

        historyButton.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));

        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        supportButton.setOnClickListener(v -> openUrl("https://support.inulute.com"));

        githubButton.setOnClickListener(v -> openUrl("https://github.com/inulute/freedium-app"));

        // Handle keyboard "Go" button
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                processUrl();
                return true;
            }
            return false;
        });

        deepLinkSettingsButton.setOnClickListener(v -> openDefaultLinksSettings());

        TextView seeAllButton = findViewById(R.id.seeAllButton);
        if (seeAllButton != null) {
            seeAllButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, HistoryActivity.class);
                String feed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getString(SettingsActivity.PREF_HOME_FEED, "history");
                if ("bookmarks".equals(feed)) {
                    intent.putExtra(HistoryActivity.EXTRA_TAB, HistoryActivity.TAB_BOOKMARKS);
                }
                startActivity(intent);
            });
        }

        // Opt-out setting. Kept in onCreate rather than onResume: on Android 12+
        // every clipboard read raises a system "pasted from your clipboard"
        // notice, and onResume would fire that on each return to the app.
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_AUTO_CLIPBOARD, true)) {
            tryAutoPasteFromClipboard();
        }

        maybeShowRenameNotice();
    }

    /** Filters the home bookmark feed by category and rebuilds the chip row. */
    private List<HistoryManager.HistoryItem> applyHomeCategoryFilter(
            HistoryManager hm, List<HistoryManager.HistoryItem> bookmarks) {
        rebuildHomeCategoryChips(hm);
        if (homeFilterAll) return bookmarks;
        List<HistoryManager.HistoryItem> filtered = new ArrayList<>();
        for (HistoryManager.HistoryItem item : bookmarks) {
            if (item.category.equals(homeCategoryFilter)) filtered.add(item);
        }
        return filtered;
    }

    private void rebuildHomeCategoryChips(HistoryManager hm) {
        if (homeCategoryScroll == null || homeCategoryChips == null) return;
        List<String> categories = hm.getCategories();
        if (categories.isEmpty()) {
            // Nothing to filter by yet: a lone "All" chip would just be noise.
            homeCategoryScroll.setVisibility(View.GONE);
            homeFilterAll = true;
            return;
        }

        buildingHomeChips = true;
        homeCategoryScroll.setVisibility(View.VISIBLE);
        homeCategoryChips.removeAllViews();
        addHomeCategoryChip("All", true, "");
        addHomeCategoryChip("Uncategorised", false, "");
        for (String name : categories) addHomeCategoryChip(name, false, name);
        buildingHomeChips = false;
    }

    private void addHomeCategoryChip(String label, boolean isAll, String value) {
        Chip chip = (Chip) LayoutInflater.from(this)
                .inflate(R.layout.item_category_chip, homeCategoryChips, false);
        // ChipGroup tracks its single selection by id, and every chip inflated from
        // the shared layout would otherwise share View.NO_ID.
        chip.setId(View.generateViewId());
        chip.setText(label);
        chip.setChecked(isAll ? homeFilterAll
                : (!homeFilterAll && homeCategoryFilter.equals(value)));
        chip.setOnClickListener(v -> {
            if (buildingHomeChips) return;
            homeFilterAll = isAll;
            homeCategoryFilter = value;
            refreshRecentArticles();
        });
        homeCategoryChips.addView(chip);
    }

    /**
     * Explains the rename to people who were already using the app under the old
     * name. A launcher label is the wrong place for this: most launchers cut off
     * around a dozen characters, so a parenthetical there is never read.
     */
    private void maybeShowRenameNotice() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_RENAME_NOTICE_SHOWN, false)) return;
        if (!isUpgradeInstall()) {
            // Fresh install: mark it seen so a new user is never told about a
            // rename they were not around for.
            prefs.edit().putBoolean(PREF_RENAME_NOTICE_SHOWN, true).apply();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_rename);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        );
        dialog.setCancelable(true);

        MaterialButton okButton = dialog.findViewById(R.id.renameOkButton);
        okButton.setOnClickListener(v -> dialog.dismiss());

        // Recorded on dismissal rather than on display, so the notice survives the
        // app being killed mid-dialog and keeps returning until it is acknowledged
        // or explicitly dismissed.
        dialog.setOnDismissListener(d ->
                prefs.edit().putBoolean(PREF_RENAME_NOTICE_SHOWN, true).apply());

        dialog.show();
    }

    /** True when this install has been updated at least once, rather than freshly installed. */
    private boolean isUpgradeInstall() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.lastUpdateTime > info.firstInstallTime;
        } catch (Exception e) {
            return false;
        }
    }

    private void checkAndShowDeepLinkBanner() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Below Android 12, the chooser dialog works fine — no banner needed
            return;
        }
        // The banner stays up until every supported host resolves here: approving
        // medium.com alone leaves the other publications opening in the browser.
        PackageManager pm = getPackageManager();
        boolean allDefault = true;
        for (String host : SUPPORTED_HOSTS) {
            Intent testIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + host + "/test"));
            android.content.pm.ResolveInfo resolveInfo = pm.resolveActivity(
                    testIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolveInfo == null || !getPackageName().equals(resolveInfo.activityInfo.packageName)) {
                allDefault = false;
                break;
            }
        }
        deepLinkBanner.setVisibility(allDefault ? View.GONE : View.VISIBLE);
    }

    private void openDefaultLinksSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            // Fallback to general app settings if the deep link settings page isn't available
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open URL: " + url, e);
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        String type = intent.getType();
        Log.d(TAG, "Handling intent - Action: " + action + ", Type: " + type);

        // Handle URL shared from browser or other apps
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null) {
                String url = data.toString();
                Log.d(TAG, "Received VIEW intent with URL: " + url);
                processAndOpenUrl(url);
                finish();
            }
        }
        // Handle text/URL shared via share menu
        else if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            Log.d(TAG, "Received SEND intent with text: " + sharedText);
            if (sharedText != null) {
                String url = extractUrl(sharedText);
                if (url != null) {
                    Log.d(TAG, "URL found in shared text: " + url);
                    processAndOpenUrl(url);
                    finish();
                } else {
                    Log.w(TAG, "No URL found in shared text");
                    urlInput.setText(sharedText);
                    Toast.makeText(this, "Please paste a valid URL", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void tryAutoPasteFromClipboard() {
        // A share intent may already have filled the box; clipboard must not win.
        if (urlInput == null || urlInput.getText() == null
                || urlInput.getText().toString().trim().length() > 0) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            if (item != null && item.getText() != null) {
                String clipText = item.getText().toString();
                String url = extractUrl(clipText);
                if (url != null) {
                    urlInput.setText(url);
                    urlInput.setSelection(url.length());
                }
            }
        }
    }

    private void processUrl() {
        String url = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        Log.d(TAG, "Processing URL from input: " + url);

        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show();
            return;
        }

        // Extract URL if text contains other content
        String extractedUrl = extractUrl(url);
        if (extractedUrl != null) {
            Log.d(TAG, "Extracted URL: " + extractedUrl);
            url = extractedUrl;
        }

        processAndOpenUrl(url);
    }

    private void processAndOpenUrl(String mediumUrl) {
        Log.d(TAG, "Processing Medium URL: " + mediumUrl);
        String freediumUrl = convertToFreedium(mediumUrl);
        Log.d(TAG, "Converted to Freedium URL: " + freediumUrl);

        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra("url", freediumUrl);
        intent.putExtra("originalUrl", mediumUrl);
        // Pass update info so WebView can show popup on first open (e.g. when opened from share sheet before async check completes)
        String updateVersion = pendingUpdateVersion;
        String updateUrl = pendingUpdateUrl;
        if (updateVersion == null || updateUrl == null) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String cachedVersion = prefs.getString(PREF_CACHED_VERSION, "");
            String skipVersion = prefs.getString(PREF_SKIP_VERSION, "");
            if (!cachedVersion.isEmpty() && !cachedVersion.equals(skipVersion)
                    && isNewerVersion(getAppVersion(), cachedVersion)) {
                updateVersion = cachedVersion;
                updateUrl = GITHUB_RELEASES_URL;
            }
        }
        if (updateVersion != null && updateUrl != null) {
            intent.putExtra("update_version", updateVersion);
            intent.putExtra("update_url", updateUrl);
        }
        boolean newWindow = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_NEW_WINDOW, false);
        if (newWindow) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        startActivity(intent);

        // Clear the input for next use
        if (urlInput != null) {
            urlInput.setText("");
        }
    }

    private void migrateMirrorPref() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String mirror = prefs.getString(SettingsActivity.PREF_MIRROR, SettingsActivity.DEFAULT_MIRROR);
        if (mirror.startsWith("archive")) {
            prefs.edit().putString(SettingsActivity.PREF_MIRROR, SettingsActivity.DEFAULT_MIRROR).apply();
        }
    }

    private String convertToFreedium(String mediumUrl) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String mirror = prefs.getString(SettingsActivity.PREF_MIRROR, SettingsActivity.DEFAULT_MIRROR);
        return SettingsActivity.getMirrorBaseUrl(mirror) + mediumUrl;
    }

    private String extractUrl(String text) {
        if (text == null) return null;

        String urlPattern = "(https?://[^\\s]+)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(urlPattern);
        java.util.regex.Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            return matcher.group(1);
        }

        if (text.contains(".") && !text.contains(" ")) {
            if (!text.startsWith("http")) {
                return "https://" + text.trim();
            }
            return text.trim();
        }

        return null;
    }

    // ==================== Update Checker ====================

    private void checkForUpdates() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long lastCheck = prefs.getLong(PREF_LAST_CHECK, 0);
        long now = System.currentTimeMillis();
        String currentVersion = getAppVersion();

        // Check if we should use cached version or fetch new
        if (now - lastCheck < UPDATE_CHECK_INTERVAL) {
            // Use cached version
            String cachedVersion = prefs.getString(PREF_CACHED_VERSION, "");
            Log.d(TAG, "Using cached version: " + cachedVersion);

            if (!cachedVersion.isEmpty() && isNewerVersion(currentVersion, cachedVersion)) {
                String skipVersion = prefs.getString(PREF_SKIP_VERSION, "");
                if (!cachedVersion.equals(skipVersion)) {
                    pendingUpdateVersion = cachedVersion;
                    pendingUpdateUrl = GITHUB_RELEASES_URL;
                    updateButton.setVisibility(View.VISIBLE);
                    Log.d(TAG, "Update button visible (from cache)");

                    // Show popup on launch (first time only per version) when using cache
                    String popupShownVersion = prefs.getString(PREF_POPUP_SHOWN_VERSION, "");
                    if (!cachedVersion.equals(popupShownVersion)) {
                        runOnUiThread(() -> {
                            showUpdateDialog();
                            prefs.edit().putString(PREF_POPUP_SHOWN_VERSION, cachedVersion).apply();
                        });
                    }
                }
            }
            return;
        }

        // Fetch from network
        Log.d(TAG, "Starting update check from network...");

        executor.execute(() -> {
            try {
                Log.d(TAG, "Current app version: " + currentVersion);

                // Try shields.io first (no rate limit)
                String latestVersion = fetchVersionFromShields();

                // Fallback to GitHub API if shields.io fails
                if (latestVersion == null) {
                    Log.d(TAG, "Shields.io failed, trying GitHub API...");
                    latestVersion = fetchVersionFromGitHub();
                }

                if (latestVersion != null) {
                    Log.d(TAG, "Latest version from remote: " + latestVersion);

                    // Cache the response
                    prefs.edit()
                        .putString(PREF_CACHED_VERSION, latestVersion)
                        .putLong(PREF_LAST_CHECK, now)
                        .apply();

                    if (isNewerVersion(currentVersion, latestVersion)) {
                        String skipVersion = prefs.getString(PREF_SKIP_VERSION, "");
                        String popupShownVersion = prefs.getString(PREF_POPUP_SHOWN_VERSION, "");
                        Log.d(TAG, "New version available! Current: " + currentVersion + ", Latest: " + latestVersion);

                        if (!latestVersion.equals(skipVersion)) {
                            pendingUpdateVersion = latestVersion;
                            pendingUpdateUrl = GITHUB_RELEASES_URL;

                            final boolean shouldShowPopup = !latestVersion.equals(popupShownVersion);
                            final String versionToSave = latestVersion;

                            runOnUiThread(() -> {
                                updateButton.setVisibility(View.VISIBLE);
                                Log.d(TAG, "Update button now visible");

                                // Show popup only once per version
                                if (shouldShowPopup) {
                                    showUpdateDialog();
                                    // Mark popup as shown for this version
                                    prefs.edit()
                                        .putString(PREF_POPUP_SHOWN_VERSION, versionToSave)
                                        .apply();
                                }
                            });
                        }
                    } else {
                        Log.d(TAG, "App is up to date");
                    }
                } else {
                    Log.e(TAG, "Failed to fetch version from all sources");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking for updates", e);
            }
        });
    }

    private String fetchVersionFromShields() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(SHIELDS_API_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Shields.io response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                Log.d(TAG, "Shields.io response: " + response.toString());

                JSONObject json = new JSONObject(response.toString());
                String version = json.getString("value");
                // Clean version string (remove 'v' prefix if present)
                version = version.replace("v", "").trim();
                Log.d(TAG, "Parsed version from shields.io: " + version);
                return version;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch from shields.io", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    private String fetchVersionFromGitHub() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(GITHUB_API_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            int responseCode = connection.getResponseCode();
            Log.d(TAG, "GitHub API response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(response.toString());
                String tagName = json.getString("tag_name");
                // Clean version string
                tagName = tagName.replace("v", "").trim();
                Log.d(TAG, "Parsed version from GitHub: " + tagName);
                return tagName;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch from GitHub API", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    private String getAppVersion() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0";
        }
    }

    private boolean isNewerVersion(String current, String latest) {
        try {
            // Handle null or empty
            if (current == null || latest == null || current.isEmpty() || latest.isEmpty()) {
                return false;
            }

            String[] currentParts = current.split("\\.");
            String[] latestParts = latest.split("\\.");

            int maxLength = Math.max(currentParts.length, latestParts.length);
            for (int i = 0; i < maxLength; i++) {
                int currentNum = 0;
                int latestNum = 0;

                if (i < currentParts.length) {
                    String part = currentParts[i].replaceAll("[^0-9]", "");
                    if (!part.isEmpty()) currentNum = Integer.parseInt(part);
                }
                if (i < latestParts.length) {
                    String part = latestParts[i].replaceAll("[^0-9]", "");
                    if (!part.isEmpty()) latestNum = Integer.parseInt(part);
                }

                if (latestNum > currentNum) return true;
                if (latestNum < currentNum) return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error comparing versions: " + current + " vs " + latest, e);
        }
        return false;
    }

    private void showUpdateDialog() {
        if (pendingUpdateVersion == null || pendingUpdateUrl == null) {
            Toast.makeText(this, "No update available", Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_update);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        );
        dialog.setCancelable(true);

        // Set version text
        TextView versionText = dialog.findViewById(R.id.updateVersionText);
        versionText.setText("v" + pendingUpdateVersion + " is now available");

        // Cancel button
        MaterialButton cancelButton = dialog.findViewById(R.id.updateCancelButton);
        cancelButton.setOnClickListener(v -> dialog.dismiss());

        // Update button
        MaterialButton updateNowButton = dialog.findViewById(R.id.updateNowButton);
        updateNowButton.setOnClickListener(v -> {
            openUrl(pendingUpdateUrl);
            dialog.dismiss();
        });

        dialog.show();
    }

    // ==================== About Dialog ====================

    private void showAboutDialog() {
        String version = getAppVersion();

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_about);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        );
        dialog.setCancelable(true);

        // Set version text
        TextView versionText = dialog.findViewById(R.id.aboutVersion);
        versionText.setText("Version " + version);

        // GitHub button
        MaterialButton githubButton = dialog.findViewById(R.id.aboutGithubButton);
        githubButton.setOnClickListener(v -> {
            openUrl("https://github.com/inulute/freedium-app");
            dialog.dismiss();
        });

        // Share button
        MaterialButton shareButton = dialog.findViewById(R.id.aboutShareButton);
        shareButton.setOnClickListener(v -> {
            shareApp();
            dialog.dismiss();
        });

        dialog.show();
    }

    private void shareApp() {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Freedium");
        shareIntent.putExtra(Intent.EXTRA_TEXT,
                "Check out Freedium - Read Medium articles without restrictions!\n\nhttps://github.com/inulute/freedium-app");
        startActivity(Intent.createChooser(shareIntent, "Share via"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }
}
