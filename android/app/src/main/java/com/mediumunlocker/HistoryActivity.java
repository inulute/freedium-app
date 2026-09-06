package com.inulute.mediumunlocker;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HistoryActivity extends AppCompatActivity {

    static final String EXTRA_TAB = "tab";
    static final String TAB_BOOKMARKS = "bookmarks";

    private HistoryManager historyManager;
    private RecyclerView recyclerView;
    private TextView emptyView;
    private HistoryAdapter adapter;
    private boolean showingHistory = true;
    private TabLayout tabLayout;

    private View categoryScroll;
    private ChipGroup categoryChips;
    private boolean buildingChips = false;
    // Category filter for the Bookmarks tab. "" as the value means uncategorised,
    // which is why "show everything" needs its own flag rather than a sentinel.
    private boolean filterAllCategories = true;
    private String categoryFilter = "";
    // Rebuilt once per refresh; isBookmarked() re-parses the whole list per call.
    private Set<String> bookmarkedUrls = new HashSet<>();

    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) importFromUri(uri); }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        historyManager = HistoryManager.getInstance(this);

        MaterialToolbar toolbar = findViewById(R.id.historyToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.history_menu);
        toolbar.setOnMenuItemClickListener(this::onMenuItemSelected);

        tabLayout = findViewById(R.id.tabLayout);
        recyclerView = findViewById(R.id.historyListView);
        emptyView = findViewById(R.id.emptyView);
        categoryScroll = findViewById(R.id.categoryScroll);
        categoryChips = findViewById(R.id.categoryChips);

        TextInputEditText searchInput = findViewById(R.id.searchInput);
        if (searchInput != null) {
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (adapter != null) adapter.filter(s.toString());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new HistoryAdapter();
        recyclerView.setAdapter(adapter);

        attachTabSwipe();

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                showingHistory = tab.getPosition() == 0;
                filterAllCategories = true;
                categoryFilter = "";
                if (searchInput != null) searchInput.setText("");
                refreshList();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        if (TAB_BOOKMARKS.equals(getIntent().getStringExtra(EXTRA_TAB))) {
            TabLayout.Tab bookmarksTab = tabLayout.getTabAt(1);
            if (bookmarksTab != null) bookmarksTab.select();
        }

        refreshList();
    }

    /**
     * Horizontal flings move between History and Bookmarks. The detector only
     * observes touches and never consumes them, so vertical scrolling and row
     * taps are untouched.
     */
    private void attachTabSwipe() {
        final float minDistance = 80 * getResources().getDisplayMetrics().density;
        GestureDetector detector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) < minDistance) return false;
                        // Anything close to vertical belongs to the list, not to us.
                        if (Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                        selectTab(dx < 0 ? 1 : 0);
                        return true;
                    }
                });

        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                detector.onTouchEvent(e);
                return false;
            }
        });
        // The list is gone when a tab is empty, so the empty view needs it too.
        emptyView.setOnTouchListener((v, e) -> detector.onTouchEvent(e));
    }

    private void selectTab(int index) {
        TabLayout.Tab tab = tabLayout.getTabAt(index);
        if (tab != null && !tab.isSelected()) tab.select();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private List<HistoryManager.HistoryItem> getCurrentList() {
        if (showingHistory) return historyManager.getHistory();
        List<HistoryManager.HistoryItem> bookmarks = historyManager.getBookmarks();
        if (filterAllCategories) return bookmarks;
        List<HistoryManager.HistoryItem> filtered = new ArrayList<>();
        for (HistoryManager.HistoryItem item : bookmarks) {
            if (item.category.equals(categoryFilter)) filtered.add(item);
        }
        return filtered;
    }

    private String defaultEmptyMessage() {
        if (showingHistory) return "No recent articles yet.\nUnlock an article to get started.";
        if (!filterAllCategories) {
            return categoryFilter.isEmpty()
                    ? "Nothing uncategorised.\nEverything is filed away."
                    : "Nothing in \"" + categoryFilter + "\" yet.\nLong-press a bookmark to file it here.";
        }
        return "No bookmarks yet.\nBookmark an article while reading, then tap \u22EE on it to file it into a category.";
    }

    private void refreshList() {
        bookmarkedUrls = historyManager.getBookmarkedUrls();
        if (showingHistory) {
            categoryScroll.setVisibility(View.GONE);
        } else {
            rebuildCategoryChips();
        }
        emptyView.setText(defaultEmptyMessage());
        // Always hand the adapter the current list, including when it is empty:
        // otherwise stale rows survive and a later search can surface items
        // belonging to the other tab. applyFilter() owns the visibility swap.
        adapter.setItems(getCurrentList());
    }

    // ==================== Categories ====================

    private void rebuildCategoryChips() {
        buildingChips = true;
        categoryScroll.setVisibility(View.VISIBLE);
        categoryChips.removeAllViews();
        addCategoryChip("All", true, "");
        addCategoryChip("Uncategorised", false, "");
        for (String name : historyManager.getCategories()) {
            addCategoryChip(name, false, name);
        }
        buildingChips = false;
    }

    private void addCategoryChip(String label, boolean isAll, String value) {
        Chip chip = (Chip) LayoutInflater.from(this)
                .inflate(R.layout.item_category_chip, categoryChips, false);
        chip.setId(View.generateViewId());
        chip.setText(label);
        chip.setChecked(isAll ? filterAllCategories
                : (!filterAllCategories && categoryFilter.equals(value)));
        chip.setOnClickListener(v -> {
            if (buildingChips) return;
            filterAllCategories = isAll;
            categoryFilter = value;
            refreshList();
        });
        categoryChips.addView(chip);
    }

    private void showMoveToCategoryDialog(HistoryManager.HistoryItem item) {
        List<String> categories = historyManager.getCategories();
        List<String> labels = new ArrayList<>();
        labels.add("Uncategorised");
        labels.addAll(categories);
        labels.add("New category\u2026");

        new MaterialAlertDialogBuilder(this)
                .setTitle("Move to")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which == labels.size() - 1) {
                        promptNewCategory(item);
                        return;
                    }
                    historyManager.setBookmarkCategory(item.originalUrl,
                            which == 0 ? "" : categories.get(which - 1));
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Creates a category, and files {@code item} into it when one was given. */
    private void promptNewCategory(HistoryManager.HistoryItem item) {
        EditText input = buildNameInput(null);
        new MaterialAlertDialogBuilder(this)
                .setTitle("New category")
                .setView(input)
                .setPositiveButton("Create", (dialog, which) -> {
                    String created = historyManager.addCategory(input.getText().toString());
                    if (created == null) {
                        Toast.makeText(this, "Pick a different name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (item != null) {
                        historyManager.setBookmarkCategory(item.originalUrl, created);
                    }
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showManageCategoriesDialog() {
        List<String> categories = historyManager.getCategories();
        if (categories.isEmpty()) {
            promptNewCategory(null);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Categories")
                .setItems(categories.toArray(new String[0]),
                        (dialog, which) -> showCategoryActions(categories.get(which)))
                .setPositiveButton("New", (dialog, which) -> promptNewCategory(null))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showCategoryActions(String name) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(name)
                .setItems(new String[]{"Rename", "Delete"}, (dialog, which) -> {
                    if (which == 0) promptRenameCategory(name);
                    else confirmDeleteCategory(name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptRenameCategory(String name) {
        EditText input = buildNameInput(name);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Rename category")
                .setView(input)
                .setPositiveButton("Rename", (dialog, which) -> {
                    if (!historyManager.renameCategory(name, input.getText().toString())) {
                        Toast.makeText(this, "Pick a different name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!filterAllCategories && categoryFilter.equals(name)) {
                        categoryFilter = input.getText().toString().trim();
                    }
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteCategory(String name) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete \"" + name + "\"?")
                .setMessage("The bookmarks in it are kept and become uncategorised.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    historyManager.deleteCategory(name);
                    if (!filterAllCategories && categoryFilter.equals(name)) {
                        filterAllCategories = true;
                        categoryFilter = "";
                    }
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText buildNameInput(String initial) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Category name");
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_muted));
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        if (initial != null) {
            input.setText(initial);
            input.setSelection(initial.length());
        }
        return input;
    }

    private void openItem(HistoryManager.HistoryItem item) {
        SharedPreferences prefs = getSharedPreferences("MediumUnlockerPrefs", MODE_PRIVATE);
        String mirror = prefs.getString(SettingsActivity.PREF_MIRROR, SettingsActivity.DEFAULT_MIRROR);
        String url = SettingsActivity.getMirrorBaseUrl(mirror) + item.originalUrl;
        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra("url", url);
        intent.putExtra("originalUrl", item.originalUrl);
        startActivity(intent);
    }

    private void showItemOptions(HistoryManager.HistoryItem item) {
        String title = item.title.isEmpty() ? "Article" : item.title;
        String displayTitle = title.length() > 50 ? title.substring(0, 50) + "…" : title;
        boolean isBookmarked = bookmarkedUrls.contains(item.originalUrl);

        String[] options = showingHistory
                ? new String[]{"Open Article", isBookmarked ? "Remove Bookmark" : "Add Bookmark", "Delete from Recent"}
                : new String[]{"Open Article", "Move to Category\u2026", "Remove Bookmark"};

        new MaterialAlertDialogBuilder(this)
                .setTitle(displayTitle)
                .setItems(options, (dialog, which) -> {
                    if (showingHistory) {
                        switch (which) {
                            case 0: openItem(item); break;
                            case 1:
                                if (isBookmarked) {
                                    historyManager.removeBookmark(item.originalUrl);
                                    Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show();
                                } else {
                                    historyManager.addBookmark(item.title, item.originalUrl, item.freediumUrl);
                                    Toast.makeText(this, "Bookmarked!", Toast.LENGTH_SHORT).show();
                                }
                                refreshList();
                                break;
                            case 2:
                                deleteWithUndo(item, true);
                                break;
                        }
                    } else {
                        switch (which) {
                            case 0: openItem(item); break;
                            case 1: showMoveToCategoryDialog(item); break;
                            case 2:
                                deleteWithUndo(item, false);
                                break;
                        }
                    }
                })
                .show();
    }

    /**
     * Removals are reversible: the entry's position in storage is captured first,
     * because a row's place in the visible list is not its place in the stored one
     * once a search or category filter is applied.
     */
    private void deleteWithUndo(HistoryManager.HistoryItem item, boolean fromHistory) {
        List<HistoryManager.HistoryItem> stored = fromHistory
                ? historyManager.getHistory() : historyManager.getBookmarks();
        int storageIndex = historyManager.indexOfUrl(stored, item.originalUrl);

        if (fromHistory) {
            historyManager.removeFromHistory(item.originalUrl);
        } else {
            historyManager.removeBookmark(item.originalUrl);
        }
        refreshList();

        Snackbar.make(recyclerView,
                        fromHistory ? "Removed from recent articles" : "Bookmark removed",
                        Snackbar.LENGTH_LONG)
                .setAction("Undo", v -> {
                    if (fromHistory) {
                        historyManager.restoreHistoryItem(item, storageIndex);
                    } else {
                        historyManager.restoreBookmark(item, storageIndex);
                    }
                    refreshList();
                })
                .show();
    }

    private boolean onMenuItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_categories) { showManageCategoriesDialog(); return true; }
        else if (id == R.id.action_export) { exportData(); return true; }
        else if (id == R.id.action_import) {
            importLauncher.launch(new String[]{"application/json", "text/plain", "*/*"});
            return true;
        } else if (id == R.id.action_clear) { showClearDialog(); return true; }
        return false;
    }

    private void exportData() {
        String json = historyManager.exportToJson();
        if (json == null) { Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show(); return; }
        try {
            File file = new File(getCacheDir(), "freedium_backup.json");
            FileWriter writer = new FileWriter(file);
            writer.write(json);
            writer.close();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Export Recent & Bookmarks"));
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void importFromUri(Uri uri) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(getContentResolver().openInputStream(uri)));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            if (historyManager.importFromJson(sb.toString())) {
                Toast.makeText(this, "Import successful!", Toast.LENGTH_SHORT).show();
                refreshList();
            } else {
                Toast.makeText(this, "Invalid file format", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showClearDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Clear Data")
                .setItems(new String[]{"Clear Recent Articles", "Clear Bookmarks", "Clear All"}, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            historyManager.clearHistory();
                            historyManager.clearPositions();
                            Toast.makeText(this, "Recent articles cleared", Toast.LENGTH_SHORT).show();
                            break;
                        case 1:
                            historyManager.clearBookmarks();
                            Toast.makeText(this, "Bookmarks cleared", Toast.LENGTH_SHORT).show();
                            break;
                        case 2:
                            historyManager.clearHistory();
                            historyManager.clearBookmarks();
                            historyManager.clearPositions();
                            Toast.makeText(this, "All data cleared", Toast.LENGTH_SHORT).show();
                            break;
                    }
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ==================== RecyclerView Adapter ====================

    class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {
        private List<HistoryManager.HistoryItem> fullList = new ArrayList<>();
        List<HistoryManager.HistoryItem> displayList = new ArrayList<>();
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault());
        private String currentFilter = "";

        void setItems(List<HistoryManager.HistoryItem> items) {
            fullList = new ArrayList<>(items);
            applyFilter(currentFilter);
        }

        void filter(String query) {
            currentFilter = query;
            applyFilter(query);
        }

        private void applyFilter(String query) {
            if (query == null || query.trim().isEmpty()) {
                displayList = new ArrayList<>(fullList);
            } else {
                String lower = query.toLowerCase(Locale.getDefault());
                displayList = new ArrayList<>();
                for (HistoryManager.HistoryItem item : fullList) {
                    if (item.title.toLowerCase(Locale.getDefault()).contains(lower)
                            || item.originalUrl.toLowerCase(Locale.getDefault()).contains(lower)) {
                        displayList.add(item);
                    }
                }
            }
            notifyDataSetChanged();
            emptyView.setVisibility(displayList.isEmpty() ? View.VISIBLE : View.GONE);
            recyclerView.setVisibility(displayList.isEmpty() ? View.GONE : View.VISIBLE);
            if (displayList.isEmpty() && !currentFilter.isEmpty()) {
                emptyView.setText("No results for \"" + currentFilter + "\"");
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            HistoryManager.HistoryItem item = displayList.get(position);
            String title = item.title.isEmpty() ? "Article" : item.title;
            holder.titleView.setText(title);

            String date = item.timestamp > 0 ? dateFormat.format(new Date(item.timestamp)) : "";
            String domain = extractDomain(item.originalUrl);
            String meta = domain.isEmpty() ? date : (date.isEmpty() ? domain : domain + " · " + date);
            if (!showingHistory && !item.category.isEmpty()) {
                meta = meta.isEmpty() ? item.category : meta + " · " + item.category;
            }
            holder.metaView.setText(meta);

            if (holder.bookmarkIndicator != null) {
                holder.bookmarkIndicator.setVisibility(
                        showingHistory && bookmarkedUrls.contains(item.originalUrl)
                                ? View.VISIBLE : View.GONE);
            }

            holder.itemView.setOnClickListener(v -> openItem(item));
            holder.itemView.setOnLongClickListener(v -> { showItemOptions(item); return true; });
            if (holder.optionsButton != null) {
                holder.optionsButton.setOnClickListener(v -> showItemOptions(item));
            }
        }

        @Override
        public int getItemCount() { return displayList.size(); }

        private String extractDomain(String url) {
            try {
                Uri uri = Uri.parse(url);
                String host = uri.getHost();
                if (host == null) return "";
                return host.startsWith("www.") ? host.substring(4) : host;
            } catch (Exception e) { return ""; }
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView titleView;
            TextView metaView;
            View bookmarkIndicator;
            View optionsButton;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                titleView = itemView.findViewById(R.id.itemTitle);
                metaView = itemView.findViewById(R.id.itemMeta);
                bookmarkIndicator = itemView.findViewById(R.id.bookmarkIndicator);
                optionsButton = itemView.findViewById(R.id.itemOptions);
            }
        }
    }
}
