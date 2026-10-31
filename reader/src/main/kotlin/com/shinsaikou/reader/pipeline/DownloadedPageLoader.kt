package com.shinsaikou.reader.pipeline

/**
 * The loader for a chapter the download engine wrote, or one adopted from upstream's downloads.
 * Contract 07 section 4.3.
 *
 * A downloaded chapter is a folder of image files, or an archive for an adopted one, which is
 * exactly what [DirectoryPageLoader] and [ArchivePageLoader] already read for the Local source. So
 * this class is the local loader for the file the manifest points at, and holds nothing of its own:
 * the name says where the pages came from, and every call goes to [files].
 *
 * One difference from the contract, which said this loader skips the tall-strip split because the
 * engine would split at download time. The engine does not (`splitTallImages` is left unread, phase
 * 2 decision Q5), so a downloaded page is split at read time like every other local page.
 */
class DownloadedPageLoader(private val files: LocalPageLoader) : PageLoader by files
