package com.shinsaikou.domain.chapter.model

/**
 * A chapter's page list came back empty. The reader throws it where upstream's chapter loader throws
 * its `page_list_empty_error` text, and [com.shinsaikou.domain.error.ErrorKey] gives it its own key, so
 * the reader closes naming the failure in the reader's language (ruling R67 rule 3). The message is
 * for the log only; no screen shows it.
 */
class PageListEmptyException(chapterId: Long) : IllegalStateException("chapter $chapterId has no pages")
