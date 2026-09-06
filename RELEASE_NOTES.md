# v3.0 (2026-09-06)

---

<div align='center'>

<img src=./assets/freedium_logo.svg width='150px'>

</div>

---

## Medium Unlocker is now Freedium

- **New name, same app** – History, bookmarks, reading positions and settings all carry over untouched. The package identifier is unchanged, so this arrives as an ordinary update rather than a second app.
- **One-time notice** – Anyone updating from an earlier version gets a short explanation of the rename on first launch. Fresh installs never see it.

## Features

### Seven publishers, not just Medium
- **Deep links widened** – Article links from The New York Times, The Washington Post, Bloomberg, Reuters, The Economist and the Financial Times now open in the app alongside Medium.
- **Works with list** – The home screen shows which publishers are supported at a glance.
- **Link settings prompt** – The banner now tracks every supported domain, instead of only reporting on medium.com.

### Bookmark categories
- **File your bookmarks** – Create categories and move bookmarks into them from the ⋮ menu on any row.
- **Filter by category** – A chip row on the Bookmarks tab, and on the home screen when it is showing bookmarks.
- **Rename and delete** – Managed from the toolbar menu. Deleting a category keeps its bookmarks and makes them uncategorised.

### Auto-paste is now optional
- **New setting** – Settings → *Auto-paste from Clipboard* controls whether the URL box fills itself from your clipboard on open. It was previously always on.
- **No longer overwrites** – A link arriving from the share sheet is no longer replaced by whatever is on the clipboard.

## Changes

- **Swipe switches tabs** – Swiping a row sideways moves between Recent and Bookmarks. It no longer deletes the article.
- **Deletion can be undone** – Removing an entry from the ⋮ menu offers an Undo, and restores it to its original position and date rather than the top of the list.
- **Visible row menu** – Every row has a ⋮ button, so the options are no longer hidden behind a long-press.
- **Home feed** – *Both* has been retired; the choice is now Recent Articles or Bookmarks. Cards show which category a bookmark is filed under.
- **See all** – Opens the tab matching your home feed instead of always landing on Recent.
- **Dialogs** – Every dialog now uses the app's own styling instead of the platform default.

## Fixes

- **Faster lists** – Checking whether an article was bookmarked re-parsed the entire bookmark list for every visible row. It is now read once per refresh.
- **Category filter selection** – The wrong filter chip could appear selected because the chips shared an identifier.
- **Stale list contents** – Emptying a tab left its previous rows in place, where a later search could surface entries belonging to the other tab.
- **Export covers everything** – Backups now include reading positions, categories and settings, not just history and bookmarks. Older backups still import.
- **Credits** – The mirror list named the same host twice and described a working mirror as offline.
- **Builds anywhere** – A machine-specific Java path had been committed, which prevented the project building on any other computer.

---

# v2.2 (2026-08-16)

---

<div align='center'>

<img src=./assets/freedium_logo.svg width='150px'>

</div>

---

## Features

### Hide Site Popups (closes #6)
- **New setting** – Settings → Reader now has a *Hide Site Popups* toggle that suppresses the announcement toasts the mirror site shows over articles ("Freedium has been updated!", "7 new sources supported!", and similar).
- **Off by default** – Nothing changes until you turn it on, and the setting applies immediately on the next article load.

### Copy as Markdown (closes #7)
- **New menu option** – The article's three-dot menu now has *Copy as Markdown*, which puts the whole article on your clipboard as markdown instead of making you select the text by hand.
- **Structure is preserved** – Headings, paragraphs, lists, tables, block quotes, fenced code blocks with language tags, links and images all carry over, with the original Medium URL appended as a `Source:` line.
- **Works on every mirror** – The article is read from the rendered page rather than any one site's internals, so it works on Freedium and the Archive.is mirrors alike.

---

# v2.1.2 (2026-06-03)

---

<div align='center'>

<img src=./assets/freedium_logo.svg width='150px'>

</div>

---

## Fixes

### Mirror Server
- **Freedium is back** – Default mirror reverted to Freedium Mirror, with Freedium as the first fallback. Archive.is and Archive.is (Alt) remain available as further fallbacks.
- **Automatic migration** – Existing users who had Archive.is set as their mirror are automatically switched to Freedium Mirror on next launch.

### Multiple Windows (relates to #5)
- **Zombie window on share** – Sharing a Medium link was leaving a blank black Medium Unlocker window in recents alongside the article window. MainActivity now closes itself immediately after handing off the URL.
- **MIUI / custom launchers** – The previous `FLAG_ACTIVITY_REORDER_TO_FRONT` fix was ignored by MIUI's task manager. WebViewActivity is now declared `singleTask`, which is enforced at the framework level and works reliably across all Android skins.

---

# v2.1.1 (2026-05-30)

---

<div align='center'>

<img src=./assets/freedium_logo.svg width='150px'>

</div>

---

## Fixes

### History & Bookmarks (relates to #5)
- **Wrong mirror on open** – History and Bookmarks were still redirecting to `freedium-mirror.cfd` regardless of the mirror selected in Settings. Now correctly uses your chosen mirror.
- **Title corruption** – Article titles in History were being overwritten with "Web page not available" when a mirror had no snapshot. Titles are now preserved on error pages and only updated on successful loads.

### Archive Mirror Handling
- **Full archive domain family supported** – The WebView now correctly handles all domains archive.is may redirect to: `archive.today`, `archive.ph`, `archive.fo`, `archive.li`, `archive.vn`, `archive.md`. Previously some of these were opening in an external browser.

### Multiple Article Windows (relates to #5)
- **Duplicate windows on share** – Sharing an article from Medium repeatedly was opening a new app window each time. `MainActivity` is now `singleTop` to prevent duplicate instances.
- **New setting: Open in New Window** – Settings → Reader now has a toggle to control whether articles open in a new window each time or reuse the existing one (default: reuse).

### UI
- **Button alignment and text cropping** – The About and Unlock Article buttons on the home screen were misaligned and had different heights due to inconsistent padding and Material3 default insets. Both buttons now use identical sizing with zero insets, and "Unlock Article" text no longer gets cropped.

---

# v2.1 (2026-05-29)

---

<div align='center'>

<img src=./assets/freedium_logo.svg width='150px'>

</div>

---

## Fixes

### Mirror Server Overhaul (closes #4)
- **freedium.cfd and freedium-mirror.cfd are offline** – both servers are no longer reachable. This release switches the default to **archive.is**, which provides archived snapshots of Medium articles.
- **Auto-fallback cycle** – if the selected mirror fails, the app automatically tries the next one in order (Archive.is → Archive.is Alt → Freedium → Freedium Mirror) before showing an error.
- **"Try Different Mirror" button** – manually cycles through all four mirrors from the error screen.

## New Features

### Mirror Selector
- **Settings** – a new **Mirror Server** picker lets you choose your preferred mirror: Archive.is, Archive.is (Alt), Freedium, or Freedium Mirror.
- **Website** – the mirror toggle on the web app is now a dropdown matching the same four options, with your choice saved across sessions.

## UI
- **About dialog** – removed the prominent Close button (tap outside to dismiss); replaced "Powered by freedium.cfd" with "Made by inulute" using the heart icon; removed the left accent bar.
- **Update dialog** – removed the Skip button; dialog now has just Later and Update.
- **Settings** – added a Credits section listing archive.is and freedium.cfd with a "Made with ♥ by inulute" footer.

---

# v2.0 (2026-04-19)

---

<div align='center'>

<img src=./assets/freedium_logo.svg width='150px'>

</div>

---

## New Features

### History & Bookmarks
- **Reading history** – Every article you unlock is automatically saved with its title, source domain, and timestamp.
- **Bookmarks** – Bookmark any article while reading and access it instantly from the History screen.
- **Search** – Live search across all history and bookmark entries by title or URL.
- **Swipe to delete** – Swipe left on any item to remove it.
- **Export / Import** – Back up and restore your history and bookmarks as a JSON file.

### Home Screen Feed
- **Recent articles** – The last 5 read articles appear directly on the home screen so you can jump back in without opening the full history.
- **Bookmark toggle** – Bookmark or unbookmark articles right from the home screen cards.
- **Configurable feed** – Settings let you choose what the home screen shows: Recent Articles, Bookmarks, or Both. In "Both" mode, bookmarked items are tagged so you can tell them apart at a glance.
- **See all** – Tap "See all →" to open the full History & Bookmarks screen.

### Settings Screen
- **Text Zoom** – Adjust article font size from 50% to 200% with a live preview; reset to 100% with one tap.
- **Remember Reading Position** – Toggle scroll-position memory on or off.
- **Max History Size** – Set how many articles are kept in history (10–1000).
- **Home Screen Feed** – Choose what appears in the home screen recent section.

### Reader Improvements
- **Resume reading** – Scroll position is saved per article and smoothly restored when you reopen it.
- **Forward navigation** – A forward button appears in the WebView toolbar when you've navigated back, letting you move forward again.
- **Smooth scroll restore** – Position is restored with a smooth scroll animation instead of a hard jump.

---

# v1.4 (2026-03-28)

---

<div align='center'>

<img src=./assets/freedium_logo.svg width='150px'>

</div>

---

## New Features

### Open Medium Links Automatically
- **Deep link support** – Medium article links clicked in email, WhatsApp, or any other app now open directly in Medium Unlocker instead of the browser.
- **Android 12+ setup** – A banner on the home screen provides a one-tap **"Enable in Settings"** button that takes you directly to the app's link-handling settings.
- **Android 11 and below** – A system chooser dialog appears when opening Medium links; select Medium Unlocker and tap "Always" to set it as the default.
- **Smart banner** – The setup banner automatically hides once you've enabled link handling.

## Misc

- Fixed intent filter to correctly handle `medium.com` and `*.medium.com` links without the failed auto-verification that was silently blocking deep links on Android 12+.

---

# v1.3 (2026-02-08)

---

<div align='center'>

<img src=./assets/freedium_logo.svg width='150px'>

</div>

---

## 🚀 New Features

### Medium & Custom Domain URLs
- **Medium article URLs** – Paste standard `medium.com` links as before.
- **Custom domain URLs** – Now supports article links from custom domains (e.g. publication subdomains and custom Medium sites).
- Use any article URL in the app or on the website; both are accepted and routed through Freedium.

## 📋 Misc

- **Update popup** – Update-available dialog now shows on app launch (once per new version) and when opening an article in WebView for the first time; also shown when opening from the share sheet using cached update info.
- **Launcher icon** – Icon scaled to fit the adaptive-icon safe zone so it no longer gets cropped on circular or squircle launcher masks.
- **Title font** – “Medium Unlocker.” title is explicitly bold for consistent appearance across devices.

---

# Release v1.2

## 🚀 New Features

### Mirror Server Support
- Added support for `freedium-mirror.cfd` as a robust alternative to the primary server.
- **Default Behavior**: The app now defaults to the mirror server to ensure maximum availability for all users.

### Smart Auto-Retry
- **Seamless Switching**: If a connection fails (e.g., due to DNS blocking), the app automatically detects the issue and switches to the working server.

## 📦 Installation
Download the `MediumUnlocker_v1.2.apk` below and install it on your Android device.
