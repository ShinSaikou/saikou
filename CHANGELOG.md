# Changelog

Every change a user would notice goes here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the version numbers follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html), with the 0.x caveat that any minor
release may change behaviour.

## Unreleased

### Added

- An update interval button on every library entry says when the entry is next checked, or why it
  is not: unread chapters, not started, completed, or only fetched once. With automatic updates off
  it shows the predicted next release. Tap it for the details.
- **Upcoming** in the Updates bar lists the releases predicted for your library, day by day. Tap an
  entry to open it.
- **Custom update interval per entry** in Settings > Experimental, off by default, lets an entry's
  Update interval dialog set how many days apart it is checked. The setting stays on the device and
  is not in backups, and turning it off puts every entry back on its estimate.
- A search in Settings: tap the search button above the section list and type, and every matching
  setting is listed with the section it is in. Picking one opens that section scrolled to the
  setting, which flashes for a moment so it is easy to spot.
- While you search the library, a **Search all sources** button above the results runs the same
  words in every source, so an entry you do not have yet is one tap away, even while the library is
  empty. It is offered for plain words only, not for a search by genre, author or another field.
- Tap an entry's title or author to search every source for it. A long press still copies it.
- A **Hide NSFW entries** button in the History bar. While it is on, History leaves out the entries
  whose source comes from an installed extension marked NSFW, search results included. Entries from
  the Local source, or from a source with no installed extension, always stay. **Clear all** still
  clears every entry, hidden ones too.
- **Open random entry** in the Library's menu opens a random entry from the category on screen,
  after filters and search.
- An About screen: tap the logo at the top of the More tab. It shows the version, the build and
  the commit (tap them to copy one line for a report), has **Check for updates**, and lists the
  latest release's APKs with their size, to update or download. **Share bug report** sends a text
  file with the version, the device and the app's recent log, with sign-in tokens and cookies taken
  out; it can still name titles and sources from your library. **Copy device info** copies the
  same lines without the log, for a chat. Report an issue, Discord and the website are linked
  there too.
- From the release after this one, every release carries a debug APK next to the app. It installs
  beside your app as Shin Saikou Debug, with a library of its own, so you can try a fix or send a
  detailed bug report without touching your library. Its Check for updates offers the next
  release's debug APK.
- A restore can install the extensions the backup's sources come from. **Install extensions** on
  the Restore tab is on by default: once the library is back, the restore reads the extension
  stores and installs each extension it finds there, one after another, showing which of how many
  it is on. It installs only from the extension stores you had before the restore. An extension
  that only a store the backup adds offers is not installed: the Restore tab and the restore report
  name that store and its address, and **Install all** installs those extensions once you have
  checked it. A backup someone else made cannot install anything on its own. The restore report
  also lists what was installed, what could not be, and the sources no extension store lists, by
  name, so you know what to search for. The report's **Install all** installs whatever is still
  offered, for a restore made with the option off too.

### Changed

- The Backup screen has two tabs, **Create** and **Restore**, instead of one long list where
  restoring sat below every export option. The automatic backups are listed on the Restore tab,
  and a backup file opened from outside Saikou opens straight on it.
- Picking a file with **Restore backup** starts the restore right away, with the options ticked
  above it, instead of asking a second time. Extensions and tracker sign-ins the backup needs are
  listed in the restore report afterwards. A backup file opened from outside Saikou still asks
  first.
- Creating or restoring a backup shows a moving bar for as long as it is waiting or working, under
  the button that started it, and then says whether it finished, failed or was cancelled. Before,
  a queued export or restore showed nothing, and a restore's bar only appeared once the file had
  been copied and checked.
- Each row of the More tab has an icon, so Downloaded only, Download queue, Categories, Backup,
  Settings and the rest can be told apart at a glance.
- The app is called Shin Saikou on the phone, so searching the phone for the name the website uses
  finds it.
- The buttons at the top of Library, Updates, History and Browse follow one order: search first,
  then the switches that change what the list shows, then the tab's own action, then the menu. A
  long press on any of them shows its name. On Library the update errors button now sits first, so
  the others stay put when it appears. History's **Clear** text button is an icon now, and still
  asks before it clears.
- The Extensions page of Browse looks like Mihon's. Updates pending comes first, with its
  **Update all** button in the heading, then Installed, then what you can install under a heading
  per language. Each row shows the extension's icon, its name, and one line with its language,
  version and any warning (18+, obsolete or untrusted) in red. Install, Update, Trust and the
  settings of an installed extension are icon buttons at the end of the row. While an extension
  downloads or installs, a ring turns around its icon and the row's button cancels it.
- On the Extensions page the search and the language filter are in the top bar, and the extension
  repositories are in its menu. Its search button searches the extensions; Global Search is on the
  Sources page. Long press an installed extension to uninstall it; the menu on each row is gone.
- The Local source is listed after every extension's sources on the Sources page and in Global
  Search, instead of among them by name.
- Adding an extension repository lists its extensions on the Extensions page right away, without
  pulling to refresh, and removing one takes them off the same way. The repository's row shows a
  spinner while its extensions load.

### Fixed

- Restoring a backup over entries already in the library no longer restarts each one's release
  prediction from today.
- The **Custom update interval** filter in the Library could never match anything. It now shows
  only while the experimental setting that gives it entries to find is on.
- The shortcuts on a long press of the app icon (Library, Updates, History, Browse) open the
  app again. They pointed at a package that does not exist, so tapping one did nothing.
- Restoring a backup from another reader no longer lists the same extension store twice, one of
  them without a name. Such a backup names a store both in an old setting and in its list of
  stores, and each became a row; a restore now keeps the one with the name. Stores listed twice by
  an earlier restore are merged the next time Saikou starts, when both rows have the same signing
  key; two rows with different keys stay apart. Adding a store by hand under another address of
  one already added, such as with `http` or a trailing slash, now says it is already added.
- With Two pane layout set to Always on a phone, an entry's title is readable again. The cover
  took the narrow pane's whole width and left the title one letter per line; the cover now sits
  above the title there, and the bar over the pane no longer shows a stray dot for the title.

## 0.1.1 - 2026-11-02

### Added

- An Experimental section at the end of Settings, for behaviour that may not suit every source or
  device, each switch off until turned on. Its first switch, **Load pages in parallel**, has an
  online chapter fetch up to three pages at a time instead of one.
- Start without extensions, on the crash screen, for when an extension makes Saikou close at every
  start. Until Saikou restarts no extension runs, library updates and downloads wait, and a banner
  says so with a Restart button. The Extensions tab still lists each extension, so the one at
  fault can be uninstalled or updated.
- A **Return to Saikou** button on the page the browser shows after you sign in to Google Drive,
  MyAnimeList or AniList, so you no longer have to switch back by hand.
- Every reader option in Settings > Reader, in groups (Display, E-Ink, Reading, Paged, Long strip,
  Navigation and Actions), so tap zones, scale type, crop borders, the long strip options and the
  rest can be set without opening a chapter. Before, part of them were only in the reader's own
  settings.

### Changed

- The settings list gives each section an icon and a line saying what is in it, such as "Reading
  mode, rotation, volume keys" under Reader, in a taller row that is easier to tap.

### Fixed

- Swiping turns the page in the paged reading modes, left, right and vertical. A swipe was
  cancelled as soon as it started, so only a tap on the side of the page or the volume keys
  turned it.
- The tap zones no longer cover the page each time the reader opens while "Show tap zones
  overlay" is off.
- With "Show actions on long tap" off, a long press still opens the page actions while the menu
  is showing.
- In the long strip, taps work after a sideways fling on a zoomed page. Before, every tap was
  ignored, the menu's included, until the strip was scrolled.
- In the long strip, a tap or a volume key scrolls three quarters of the screen after a rotation
  too, instead of the distance for the orientation the reader opened in.
- After a tall page is split, "Always show chapter transition" still holds, so the reader no
  longer goes straight from a chapter's last page into the next one.
- On Android 8 and 9, turning pure black off in the dark theme gives the navigation bar back its
  usual shade at once, instead of leaving it see-through until the screen rotated or the app
  restarted.
- The reader's vertical page slider shows for entries on the default reading mode, which is most
  of them, when "Use vertical chapter navigator in" lists the mode in use.
- Tapping the title in the reader's top bar opens the entry instead of closing the reader. The
  arrow still closes it.
- The previous and next chapter buttons show "Loading" while a chapter that is not ready yet loads.
  Back closes it and the chapter opens when it arrives.
- The crop button in the reader says whether cropping is now on or off.
- The reader slides in and out when it opens and closes.
- A failed download in the download queue shows the first line of the source's error, cut to 200
  characters, as the library update error list does, instead of the whole text (sometimes an
  entire error page).
- Downloaded only applies in the reader too: the next and previous chapters are the downloaded
  ones, so the reader no longer goes online to a chapter that is not downloaded.
- The chapter transition marks the chapters that are downloaded.
- A server that refuses the connection is named as such ("The server refused the connection")
  instead of reading as "No network connection", in snackbars, the library update error list,
  the download queue and the reader.
- An entry's screen left open behind the reader no longer reloads its chapter list after every page
  read. It pauses a few seconds after it leaves the screen and catches up when it comes back.
- Copying downloads from a folder also finds chapters with a scanlator that a compatible reader's
  latest releases saved without the hash at the end of the folder name: a long name, or a non-ASCII
  scanlator saved with "Disallow non-ASCII filenames" on.
- Saving a page from the reader also posts a notification with the page, which opens it, and a
  Share button (on Android 10 and later; earlier, the notification shows the page only).
- A page shared from the reader goes with "title: chapter, page N" as its text.
- A page saved or shared at a chapter boundary in the long strip is named after its own chapter,
  not the chapter the reader was still counting as current.
- Picking a folder to copy downloads from while a copy is already running no longer keeps a
  permanent read permission on that folder; the permission is taken only for a copy that starts.
- Opening an entry in the reader clears its new chapters notification, and the summary with it
  when it was the last.
- Turning incognito off closes a reader opened in incognito, which would otherwise go on without
  saving progress or history.
- With "Split wide pages" on, a wide page split in the chapter before or after the one being read
  is kept for its own chapter, instead of being placed in the current one or lost, which could
  crash the paged reader.
- A back gesture still in progress as the reader or a dialog closed no longer crashes the app.
- Progress and history in a chapter left open in the reader while a refresh finds it under a new
  address go to that chapter as the source now lists it, instead of being lost or written to
  another chapter. A page read just before the refresh is kept too.
- After a rotation, the page on screen in the paged reader fits the new screen again, with its
  zoom limits and the automatic zoom into wide images, instead of keeping the old orientation's zoom.
- The crash screen's buttons keep the same distance from the right edge as its text keeps from the
  left one.
- In the dark theme the reader's bottom bar icons, the previous and next chapter buttons and the
  page numbers beside the slider are light, as the top bar is, instead of black on the dark bar.
- In the light theme the system's status bar and navigation bar icons in the reader are dark on the
  reader's light bars, instead of white. With the menu closed they follow the page's background.

## 0.1.0 - 2026-11-01

The first public release.

### Security

- An extension repository keeps the signing key it was added with: refreshing the repository list
  or restoring a backup no longer replaces it, so neither can change which extensions are trusted.
  A repository that really changed its key has to be removed and added again.
- Restoring source settings from a backup writes only sources' own settings files; a backup can no
  longer reach the app's settings, the app lock among them, that way.

### Added

- Select several entries in a source's listing with a long press (and a range), then add them to
  the library in one go.
- Edit an entry's title and description; the edit syncs, while downloads, tracking and backups
  keep the source's text.
- Restoring a backup from TachiyomiSY, TachiyomiJ2K or Komikku keeps each entry's edited title and
  description, unless the entry already has its own edit.
- A cover viewer on the entry screen: share, save, set a custom cover or remove it.
- Notes on an entry: write them from the entry screen's menu, and see their first lines under the
  description, including notes restored from a backup.
- A tap on a genre on the entry screen searches the library for it, and a long press on a genre,
  the title or the author copies it.
- Removing an entry from the library on its screen offers Undo, which puts it back with its
  categories and its place in a date-added sort.
- A second long press in the library selects every entry between it and the selection.
- Sort A to Z on the Categories screen puts the categories, and those under each parent, in name
  order.
- Export the library as a CSV list of titles, authors and artists, from the Data settings next to
  backup.
- One level of sub-categories.
- A language filter for Extensions and a filter screen for Browse sources.
- A backup is checked before it is restored: the report lists missing sources and trackers, and a
  missing extension can be installed from there.
- A migration can carry the old entry's downloads, and says how many it keeps.
- A storage location for automatic backups, the Local source and exported downloads; choosing a new
  location moves what was in the old one.
- Saved pages and covers go to the gallery, under Pictures/Saikou.
- Sync warns a day before a stored Google sign-in expires, and once for each problem a background
  sync can act on.
- Settings that need a restart offer a Restart action.
- DNS over HTTPS has a Custom choice: enter the https address of any DoH server. The device's DNS
  looks up that server's own name.
- The Migrate page's help action opens the migration guide.
- Saikou's own theme, alongside thirteen named themes and a theme picker.
- Updates from GitHub: when a new version is out, Saikou offers it once a day and installs it with
  one tap, after checking the download's checksum and that it is signed with the same key as the
  installed app. Settings > Advanced has Check for updates and a switch for the daily check.
- A crash opens a screen with the crash log and Share, Restart and Close. It runs apart from the
  rest of the app, so it still opens when Saikou crashes on every start; the log leaves the device
  only when you tap Share.
- History shows a header for each day and the time each chapter was read.
- Global search has upstream's Pinned / All and Has results chips, remembered on the device, and
  marks results already in the library.
- The reader is listed under the S Pen's air actions, to turn pages, go back and change chapter
  with the pen.
- The restore report offers Copy downloads from a folder, since a backup holds no downloads.
- Sync over Wi-Fi only, in the sync settings: automatic syncs wait for an unmetered network, while
  Sync now runs on any connection.
- Automatic backups every 6 or 48 hours or weekly, besides 12 and 24, and a choice of how many to
  keep, from 2 to 10.
- Updates and Settings > Library show when the library was last updated.
- The update errors offer Retry, which updates the failed entries again, and Migrate for each
  source's failed entries; the library selection offers Update for the selected entries.
- Updates says how many entries the last automatic update skipped, and why.
- When MyAnimeList is ahead of the chapters read in the app, the track sheet says so and offers to
  mark them read up to its chapter.
- AniList as a second tracker: sign in from Settings > Tracking, then search, link and update
  entries from the track sheet, with the status, chapters, score in your AniList score format,
  start and finish dates, and private entries.

### Changed

- The library update keeps one request budget per site, so sources on the same site share a rate
  limit.
- An update error Saikou has no name for shows the extension's own message under it (a sign-in it
  needs, a rate limit, maintenance) instead of only the exception's name. Such a message is cut to
  its first line, there and in the other error messages.
- The library, the library update, sync and restore stay responsive at 40,000 entries; backups are
  read and written as a stream.
- The library's search opens in the top bar, and Update library moved into the overflow menu.
- Covers are fetched through their source, with its client and headers.
- A new entry's first chapter list counts only chapters uploaded after it joined the library.
- Download ahead also runs while a chapter is read online.
- The download notification names the chapter and the chapters left, and has Pause and Cancel
  all.
- The entry screen shows every genre, wrapping onto more lines, instead of the first four.
- Adding an entry that may already be in the library lists every match, with its source and
  chapter count, instead of the first one.
- The image utilities moved into their own library,
  [saikou-image](https://github.com/ShinSaikou/saikou-image).
- The project is licensed under the Apache License 2.0, with a README, contributing guide, code of
  conduct and security policy for its public release.
- In the long strip, arrow, volume and page keys repeat while held; Page Up, Page Down and Space
  (Shift+Space back) scroll one screen; and a mouse wheel or touchpad scrolls as far as it moved
  rather than three quarters of a screen per event.
- With double-tap zoom off, a tap in the long strip acts at once instead of after a short wait for
  a second tap.

### Fixed

- A chapter with split tall pages is marked read on its last page, and the page slider and
  reopening the chapter land on the right page instead of a part of an earlier one.
- Zooming the long strip after a rotation, a split-screen change or a window resize no longer
  leaves a gap or an oversized view.
- A zoomed-in long strip with side padding pans to the edge of the page instead of into the empty
  padding.
- In paged mode, tapping to turn pages works again after a press on Retry turned into a swipe.
- Long-strip side padding is measured against the reader's window, so split screen and freeform
  windows get the padding they ask for, and it follows a rotation or a resize.
- A tap in the long strip just before the reader closes no longer crashes the app.
- Downloads waiting for Wi-Fi start on mobile data as soon as "Only download over Wi-Fi" is turned
  off, and the download queue says when it is waiting for Wi-Fi or a network.
- Downloads, and copies of downloads from a folder, stop with a notice when less than 200 MB of
  storage is free, instead of filling the storage the library database needs; the chapters stay
  queued.
- Partly downloaded chapters that nothing will finish are deleted when downloads start, instead of
  taking up storage for good.
- A download that loses its network stays queued and resumes by itself when the network is back,
  instead of failing every chapter left; one that keeps failing still fails after three tries.
- A download a site refuses with "too many requests" waits as long as the site asks, up to a minute,
  before it tries again.
- With "Delete after reading" and "Mark duplicate chapters as read" both on, the duplicates marked
  read with a chapter lose their downloads with it.
- Verbose network logging no longer writes credentials or cookies to the system log.
- Copying downloads from a folder also finds chapters a compatible reader's recent releases named
  differently: a name starting with a space or a dot, a long name, or a non-ASCII scanlator.
- A challenge solved by hand in the in-app WebView carries over to the source's own requests: the
  page opens with the source's user agent and headers, and no longer tells the site it runs inside
  an app.
- A source that sends an Origin header no longer closes the app when its page opens in WebView or
  one of its challenges is solved.
- When WebView's page process crashes or is stopped by the system, the WebView screen closes with a
  message, and a challenge solve fails, instead of the whole app closing.
- A challenge that wants a tap no longer holds every request to its site for 30 seconds: the solve
  gives up as soon as the challenge asks for a person or fails, and for a minute after a failed
  solve the site's requests fail at once instead of each trying again.
- A challenge met by a request that sends data, such as a search or a login, is solved on the site's
  front page, where it used to fail every time and then hold the site's other requests for a minute.
- A failed extension install or update says why on its row (a different signer, a newer version
  already installed, no network) and offers Retry, where the button used to come back with no word.
- An extension the catalog moved to a library version Saikou does not load is labelled "Needs a newer
  Saikou" and is no longer offered as an update, which would have replaced a working extension with
  one that cannot load. An update that fails part way leaves the installed extension in place.
- An extension built against an API Saikou lacks fails its own request instead of closing the app.
- A source listing that fails to load offers Open in WebView beside Retry, so a challenge or a login
  can be answered from where the error shows.
- When one extension repository fails to refresh, its extensions stay listed and the error names the
  repository; repository errors read as sentences ("The address does not lead to a repository
  index") instead of the app's internal codes.
- An extension that did not load is no longer a dead end: a tap shows why, with the error details to
  copy, its menu uninstalls it (even a file that is not a package at all), and an update for it is
  offered, counted and covered by Update all, as for a loaded one.
- Adding an entry with a blank title no longer reports every library entry as its duplicate.
- Removing an entry from History asks first and removes the whole entry, instead of bringing it
  straight back with the chapter read before.
- Resume in History opens the next chapter when the last one read is finished, instead of starting
  the finished chapter again.
- A migration keeps the entry's edited title and description.
- A migration with Chapters ticked carries the reading history to the new entry, so History and
  Resume follow it to the new source; Copy copies only the read dates. Another synced device keeps
  the old entry in its History as well, with its reading time, until it is removed there.
- Migration's automatic match no longer takes a source's only search result whatever its title,
  and compares titles regardless of case and bracketed text.
- Migration gives each source 25 seconds to answer a search, so a source that is down no longer
  holds up every entry for minutes.
- Global search puts the keyboard away when a search is submitted.
- On Android 8 to 14 the status and navigation bars take the app's colours: no purple status bar
  in the light theme, true black bars in pure black, and visible navigation buttons.
- A source's search runs from the keyboard's Search key and from Enter, and the keyboard closes;
  before, the key only closed the keyboard.
- With the app lock on, the app's screen no longer shows before the unlock prompt, and the recent
  apps view shows a blank card instead of the last screen.
- A page that fails to load says why: no network, a timeout, the server's HTTP status, or an image
  that could not be read. A page that timed out, lost its connection or met a busy server is tried
  once more on its own before the error shows.
- AVIF, HEIF and JPEG XL pages are shown instead of failing as an unsupported format. Animated AVIF
  and HEIF pages still are not.
- With "Always show chapter transition" off, a transition reached before the next chapter has
  loaded gives way to that chapter's page once it loads, so sources with one page per chapter no
  longer show a transition between every page.
- Saved and shared pages carry the chapter number in their file name, so pages of two chapters with
  the same name no longer overwrite each other.
- A backup gives every category a position of its own, so restoring it no longer moves the entries
  of one category into another that sync had left at the same position. Restoring an older backup
  with such a tie puts those entries in both categories, and the restore report names them.
- Chapters that sync brings in from another device take their place in the chapter list, where
  they used to mix in with the older chapters until the next library update.
- An automatic backup that fails partway no longer costs the oldest kept backup, and never leaves a
  half-written file among the kept ones: older backups are deleted only once the new one is complete.
- A restore that keeps failing stops after a few retries and says so in a notification, where it
  used to start again without end; restoring the same file again continues where it stopped.
- A backup with an entry that cannot be read restores every other entry, and the restore report
  names the one it skipped; the whole file used to be refused.
- A rare "database is locked" crash, when a background task and the app reached the database at the
  same moment: every connection now waits a moment for the lock.
- An automatic backup names the sources whose extension is gone, where it could write their bare
  numbers instead.
- Restoring a backup over an entry already tracked, at the same chapter, keeps the tracker entry it
  is bound to; it could take the backup's while still showing the old one's title, so updates went
  to an entry the tracking sheet did not show.
- Cancelling a library update no longer turns off automatic updates until the app restarts.
- An automatic library update that comes due during a manual one waits for it to finish.
- Back from a chapter opened from a new chapters notification goes to the entry instead of leaving
  the app.
- A library update with many updated entries posts a notification for the first 20 and names the
  rest in the summary, instead of losing some, and two entries no longer share one notification.
- A category update or a cancelled one no longer clears the update errors of the entries it did
  not reach, and an entry's error goes once it updates without one.
- New chapters from a scanlator excluded for an entry are no longer counted, notified or downloaded
  automatically.
- A chapter from an excluded scanlator opens from a notification, History or Updates instead of
  closing the reader with "That chapter is gone".
- With "Predict next release time" on, an entry whose predicted release had passed
  without a new chapter is no longer checked on every automatic update.
- A chapter a source moves to a new address keeps its reading history, so the entry stays in
  History.
- Tracker dates: Completed sets a finish date, the first chapter read or set sets a start date
  whatever its number (and never moves one already set), the start date set when tracking begins is
  no longer a day off, and the date picker offers today and same-day start and finish dates in
  every time zone.
- A MyAnimeList sign-in refused before its time is renewed once instead of failing every update;
  when it has truly expired, one notification says so, and the progress read meanwhile is kept and
  sent after the next sign-in instead of being dropped.
- Restoring a backup from upstream 0.21 keeps the entries of the tracker it added, which were
  skipped as an unknown tracker's.
- MyAnimeList search takes an `id:` with spaces around it and an entry's address pasted from the
  browser, and its results show the entry's mean score.
- A progress update MyAnimeList refuses for good (an entry it no longer has, or a title waiting for
  approval) is dropped instead of being sent again with every later update.

## 0.0.1 - 2026-10-01

The first closed-test release.

### Added

- A library with categories, filters, sorting and search.
- Browse and global search over third-party extensions built for the Tachiyomi extension API, lib
  1.4 and 1.6, from catalogs the user adds.
- A reader with paged and long-strip modes, tall-strip splitting, animated images, double-page
  handling and page notes.
- Downloads as folders or CBZ archives, with cleanup rules, and a copy of an existing downloads
  folder from a compatible reader.
- The Local source, for archives and folders of images on the device.
- Backup and restore in the `.tachibk` format.
- Sync between devices through Google Drive's app folder.
- MyAnimeList tracking.
- Migration of entries between sources.
- Library updates, Updates, History and Statistics.
- An app lock and a secure screen option.
