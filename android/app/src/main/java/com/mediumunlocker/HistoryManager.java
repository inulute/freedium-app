package com.inulute.mediumunlocker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HistoryManager {

    private static final String PREFS_NAME = "MediumUnlockerHistory";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String POSITIONS_PREFS = "ReadingPositions";
    private static final String APP_PREFS = "MediumUnlockerPrefs";
    private static final String KEY_CATEGORIES = "bookmark_categories";
    static final int MAX_CATEGORY_NAME = 40;

    private static HistoryManager instance;
    private final SharedPreferences prefs;
    private final SharedPreferences positionPrefs;
    private final SharedPreferences appPrefs;

    public static class HistoryItem {
        public String title;
        public String originalUrl;
        public String freediumUrl;
        public long timestamp;
        // Bookmarks only; "" means uncategorised. History items never set it.
        public String category = "";

        public HistoryItem(String title, String originalUrl, String freediumUrl, long timestamp) {
            this.title = title != null ? title : "";
            this.originalUrl = originalUrl != null ? originalUrl : "";
            this.freediumUrl = freediumUrl != null ? freediumUrl : "";
            this.timestamp = timestamp;
        }

        public JSONObject toJson() throws Exception {
            JSONObject obj = new JSONObject();
            obj.put("title", title);
            obj.put("originalUrl", originalUrl);
            obj.put("freediumUrl", freediumUrl);
            obj.put("timestamp", timestamp);
            if (!category.isEmpty()) obj.put("category", category);
            return obj;
        }

        public static HistoryItem fromJson(JSONObject obj) throws Exception {
            HistoryItem item = new HistoryItem(
                    obj.optString("title", ""),
                    obj.optString("originalUrl", ""),
                    obj.optString("freediumUrl", ""),
                    obj.optLong("timestamp", 0)
            );
            item.category = obj.optString("category", "");
            return item;
        }
    }

    private HistoryManager(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        positionPrefs = app.getSharedPreferences(POSITIONS_PREFS, Context.MODE_PRIVATE);
        appPrefs = app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
    }

    public static HistoryManager getInstance(Context context) {
        if (instance == null) {
            instance = new HistoryManager(context);
        }
        return instance;
    }

    public void saveToHistory(String title, String originalUrl, String freediumUrl) {
        int maxHistory = appPrefs.getInt(SettingsActivity.PREF_MAX_HISTORY, 100);
        List<HistoryItem> history = getHistory();
        List<HistoryItem> filtered = new ArrayList<>();
        for (HistoryItem item : history) {
            if (!item.originalUrl.equals(originalUrl)) filtered.add(item);
        }
        filtered.add(0, new HistoryItem(title, originalUrl, freediumUrl, System.currentTimeMillis()));
        if (filtered.size() > maxHistory) filtered = filtered.subList(0, maxHistory);
        saveList(KEY_HISTORY, filtered);
    }

    // ==================== Reading Positions ====================

    public void savePosition(String originalUrl, int scrollY) {
        if (originalUrl == null || originalUrl.isEmpty()) return;
        positionPrefs.edit().putInt(originalUrl, scrollY).apply();
    }

    public int getPosition(String originalUrl) {
        if (originalUrl == null || originalUrl.isEmpty()) return 0;
        return positionPrefs.getInt(originalUrl, 0);
    }

    public void clearPositions() {
        positionPrefs.edit().clear().apply();
    }

    public void removeFromHistory(String originalUrl) {
        List<HistoryItem> history = getHistory();
        List<HistoryItem> filtered = new ArrayList<>();
        for (HistoryItem item : history) {
            if (!item.originalUrl.equals(originalUrl)) filtered.add(item);
        }
        saveList(KEY_HISTORY, filtered);
    }

    public void addBookmark(String title, String originalUrl, String freediumUrl) {
        List<HistoryItem> bookmarks = getBookmarks();
        List<HistoryItem> filtered = new ArrayList<>();
        // Re-bookmarking an existing URL rebuilds the entry, so carry the
        // category across or filing would silently reset on every re-add.
        String existingCategory = "";
        for (HistoryItem item : bookmarks) {
            if (item.originalUrl.equals(originalUrl)) {
                existingCategory = item.category;
            } else {
                filtered.add(item);
            }
        }
        HistoryItem added = new HistoryItem(title, originalUrl, freediumUrl, System.currentTimeMillis());
        added.category = existingCategory;
        filtered.add(0, added);
        saveList(KEY_BOOKMARKS, filtered);
    }

    public void removeBookmark(String originalUrl) {
        List<HistoryItem> bookmarks = getBookmarks();
        List<HistoryItem> filtered = new ArrayList<>();
        for (HistoryItem item : bookmarks) {
            if (!item.originalUrl.equals(originalUrl)) filtered.add(item);
        }
        saveList(KEY_BOOKMARKS, filtered);
    }

    public boolean isBookmarked(String originalUrl) {
        if (originalUrl == null) return false;
        for (HistoryItem item : getBookmarks()) {
            if (item.originalUrl.equals(originalUrl)) return true;
        }
        return false;
    }

    /**
     * Puts a swiped item back exactly as it was. saveToHistory/addBookmark would
     * stamp a fresh timestamp and move the entry to the top, so undo needs its
     * own path that preserves the original timestamp, category and position.
     */
    private void restoreItem(String key, HistoryItem item, int index) {
        if (item == null) return;
        List<HistoryItem> list = loadList(key);
        for (HistoryItem existing : list) {
            if (existing.originalUrl.equals(item.originalUrl)) return;
        }
        int position = Math.max(0, Math.min(index, list.size()));
        list.add(position, item);
        saveList(key, list);
    }

    public void restoreHistoryItem(HistoryItem item, int index) {
        restoreItem(KEY_HISTORY, item, index);
    }

    public void restoreBookmark(HistoryItem item, int index) {
        restoreItem(KEY_BOOKMARKS, item, index);
    }

    public int indexOfUrl(List<HistoryItem> list, String originalUrl) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).originalUrl.equals(originalUrl)) return i;
        }
        return 0;
    }

    /**
     * url -> category for every bookmark ("" when uncategorised). One parse that
     * answers both "is this bookmarked" and "what is it filed under", which is
     * what the home feed needs per card.
     */
    public Map<String, String> getBookmarkCategories() {
        Map<String, String> map = new HashMap<>();
        for (HistoryItem item : getBookmarks()) {
            map.put(item.originalUrl, item.category);
        }
        return map;
    }

    // One parse instead of one per row: isBookmarked() reloads and re-parses the
    // whole bookmark list on every call, which list adapters do per bound view.
    public Set<String> getBookmarkedUrls() {
        Set<String> urls = new HashSet<>();
        for (HistoryItem item : getBookmarks()) {
            urls.add(item.originalUrl);
        }
        return urls;
    }

    // ==================== Bookmark Categories ====================

    public List<String> getCategories() {
        List<String> categories = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(appPrefs.getString(KEY_CATEGORIES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                String name = array.optString(i, "").trim();
                if (!name.isEmpty() && !categories.contains(name)) categories.add(name);
            }
        } catch (Exception ignored) { }
        return categories;
    }

    private void saveCategories(List<String> categories) {
        JSONArray array = new JSONArray();
        for (String name : categories) array.put(name);
        appPrefs.edit().putString(KEY_CATEGORIES, array.toString()).apply();
    }

    /** Returns the stored name, or null when the name is blank or already taken. */
    public String addCategory(String rawName) {
        String name = normaliseCategory(rawName);
        if (name == null) return null;
        List<String> categories = getCategories();
        for (String existing : categories) {
            if (existing.equalsIgnoreCase(name)) return null;
        }
        categories.add(name);
        saveCategories(categories);
        return name;
    }

    public boolean renameCategory(String oldName, String rawNewName) {
        String newName = normaliseCategory(rawNewName);
        if (newName == null) return false;
        List<String> categories = getCategories();
        int index = categories.indexOf(oldName);
        if (index < 0) return false;
        for (String existing : categories) {
            if (!existing.equals(oldName) && existing.equalsIgnoreCase(newName)) return false;
        }
        categories.set(index, newName);
        saveCategories(categories);

        List<HistoryItem> bookmarks = getBookmarks();
        for (HistoryItem item : bookmarks) {
            if (item.category.equals(oldName)) item.category = newName;
        }
        saveList(KEY_BOOKMARKS, bookmarks);
        return true;
    }

    /** Removes the category; its bookmarks are kept and become uncategorised. */
    public void deleteCategory(String name) {
        List<String> categories = getCategories();
        if (!categories.remove(name)) return;
        saveCategories(categories);

        List<HistoryItem> bookmarks = getBookmarks();
        for (HistoryItem item : bookmarks) {
            if (item.category.equals(name)) item.category = "";
        }
        saveList(KEY_BOOKMARKS, bookmarks);
    }

    public void setBookmarkCategory(String originalUrl, String category) {
        String value = category == null ? "" : category;
        List<HistoryItem> bookmarks = getBookmarks();
        for (HistoryItem item : bookmarks) {
            if (item.originalUrl.equals(originalUrl)) item.category = value;
        }
        saveList(KEY_BOOKMARKS, bookmarks);
    }

    private String normaliseCategory(String rawName) {
        if (rawName == null) return null;
        String name = rawName.trim();
        if (name.isEmpty()) return null;
        return name.length() > MAX_CATEGORY_NAME ? name.substring(0, MAX_CATEGORY_NAME) : name;
    }

    public List<HistoryItem> getHistory() {
        return loadList(KEY_HISTORY);
    }

    public List<HistoryItem> getBookmarks() {
        return loadList(KEY_BOOKMARKS);
    }

    public void clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply();
    }

    public void clearBookmarks() {
        prefs.edit().remove(KEY_BOOKMARKS).apply();
    }

    public String exportToJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("history", listToJsonArray(getHistory()));
            root.put("bookmarks", listToJsonArray(getBookmarks()));
            root.put("positions", positionsToJson());
            root.put("categories", new JSONArray(getCategories()));
            root.put("settings", settingsToJson());
            root.put("exportedAt", System.currentTimeMillis());
            root.put("version", 2);
            return root.toString(2);
        } catch (Exception e) {
            return null;
        }
    }

    // Every key is read when present, whatever version the file declares, so v1
    // exports (history and bookmarks only) still import unchanged.
    public boolean importFromJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (root.has("history")) {
                saveList(KEY_HISTORY, jsonArrayToList(root.getJSONArray("history")));
            }
            if (root.has("bookmarks")) {
                saveList(KEY_BOOKMARKS, jsonArrayToList(root.getJSONArray("bookmarks")));
            }
            if (root.has("categories")) {
                List<String> imported = new ArrayList<>();
                JSONArray array = root.getJSONArray("categories");
                for (int i = 0; i < array.length(); i++) {
                    String name = normaliseCategory(array.optString(i, ""));
                    if (name != null && !imported.contains(name)) imported.add(name);
                }
                saveCategories(imported);
            }
            if (root.has("positions")) {
                importPositions(root.getJSONObject("positions"));
            }
            if (root.has("settings")) {
                importSettings(root.getJSONObject("settings"));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject positionsToJson() throws Exception {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, ?> entry : positionPrefs.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Integer) {
                obj.put(entry.getKey(), (Integer) value);
            }
        }
        return obj;
    }

    private JSONObject settingsToJson() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put(SettingsActivity.PREF_TEXT_ZOOM,
                appPrefs.getInt(SettingsActivity.PREF_TEXT_ZOOM, 100));
        obj.put(SettingsActivity.PREF_MAX_HISTORY,
                appPrefs.getInt(SettingsActivity.PREF_MAX_HISTORY, 100));
        obj.put(SettingsActivity.PREF_REMEMBER_POSITION,
                appPrefs.getBoolean(SettingsActivity.PREF_REMEMBER_POSITION, true));
        obj.put(SettingsActivity.PREF_NEW_WINDOW,
                appPrefs.getBoolean(SettingsActivity.PREF_NEW_WINDOW, false));
        obj.put(SettingsActivity.PREF_HIDE_POPUPS,
                appPrefs.getBoolean(SettingsActivity.PREF_HIDE_POPUPS, false));
        obj.put(SettingsActivity.PREF_AUTO_CLIPBOARD,
                appPrefs.getBoolean(SettingsActivity.PREF_AUTO_CLIPBOARD, true));
        obj.put(SettingsActivity.PREF_HOME_FEED,
                appPrefs.getString(SettingsActivity.PREF_HOME_FEED, "history"));
        obj.put(SettingsActivity.PREF_MIRROR,
                appPrefs.getString(SettingsActivity.PREF_MIRROR, SettingsActivity.DEFAULT_MIRROR));
        return obj;
    }

    // Positions merge in: offsets for articles not named in the file are kept.
    private void importPositions(JSONObject obj) {
        SharedPreferences.Editor editor = positionPrefs.edit();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            int scrollY = obj.optInt(key, -1);
            if (scrollY >= 0) editor.putInt(key, scrollY);
        }
        editor.apply();
    }

    // SharedPreferences only raises ClassCastException when a value is read back,
    // so writing the wrong type here would surface later as a crash in
    // SettingsActivity. Each key is written with its real type, clamped or
    // checked against its allow-list so an edited export can't poison settings.
    private void importSettings(JSONObject obj) {
        SharedPreferences.Editor editor = appPrefs.edit();

        if (obj.has(SettingsActivity.PREF_TEXT_ZOOM)) {
            editor.putInt(SettingsActivity.PREF_TEXT_ZOOM,
                    clamp(obj.optInt(SettingsActivity.PREF_TEXT_ZOOM, 100), 50, 200));
        }
        if (obj.has(SettingsActivity.PREF_MAX_HISTORY)) {
            editor.putInt(SettingsActivity.PREF_MAX_HISTORY,
                    clamp(obj.optInt(SettingsActivity.PREF_MAX_HISTORY, 100), 10, 1000));
        }
        if (obj.has(SettingsActivity.PREF_REMEMBER_POSITION)) {
            editor.putBoolean(SettingsActivity.PREF_REMEMBER_POSITION,
                    obj.optBoolean(SettingsActivity.PREF_REMEMBER_POSITION, true));
        }
        if (obj.has(SettingsActivity.PREF_NEW_WINDOW)) {
            editor.putBoolean(SettingsActivity.PREF_NEW_WINDOW,
                    obj.optBoolean(SettingsActivity.PREF_NEW_WINDOW, false));
        }
        if (obj.has(SettingsActivity.PREF_HIDE_POPUPS)) {
            editor.putBoolean(SettingsActivity.PREF_HIDE_POPUPS,
                    obj.optBoolean(SettingsActivity.PREF_HIDE_POPUPS, false));
        }
        if (obj.has(SettingsActivity.PREF_AUTO_CLIPBOARD)) {
            editor.putBoolean(SettingsActivity.PREF_AUTO_CLIPBOARD,
                    obj.optBoolean(SettingsActivity.PREF_AUTO_CLIPBOARD, true));
        }
        if (obj.has(SettingsActivity.PREF_HOME_FEED)) {
            String feed = obj.optString(SettingsActivity.PREF_HOME_FEED, "");
            if (isOneOf(feed, SettingsActivity.FEED_VALUES)) {
                editor.putString(SettingsActivity.PREF_HOME_FEED, feed);
            }
        }
        if (obj.has(SettingsActivity.PREF_MIRROR)) {
            String mirror = obj.optString(SettingsActivity.PREF_MIRROR, "");
            if (isOneOf(mirror, SettingsActivity.MIRROR_VALUES)) {
                editor.putString(SettingsActivity.PREF_MIRROR, mirror);
            }
        }

        editor.apply();
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static boolean isOneOf(String value, String[] allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(value)) return true;
        }
        return false;
    }

    private List<HistoryItem> loadList(String key) {
        try {
            String json = prefs.getString(key, "[]");
            JSONArray array = new JSONArray(json);
            List<HistoryItem> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                list.add(HistoryItem.fromJson(array.getJSONObject(i)));
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveList(String key, List<HistoryItem> list) {
        try {
            prefs.edit().putString(key, listToJsonArray(list).toString()).apply();
        } catch (Exception ignored) { }
    }

    private JSONArray listToJsonArray(List<HistoryItem> list) throws Exception {
        JSONArray array = new JSONArray();
        for (HistoryItem item : list) {
            array.put(item.toJson());
        }
        return array;
    }

    private List<HistoryItem> jsonArrayToList(JSONArray array) throws Exception {
        List<HistoryItem> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            list.add(HistoryItem.fromJson(array.getJSONObject(i)));
        }
        return list;
    }
}
