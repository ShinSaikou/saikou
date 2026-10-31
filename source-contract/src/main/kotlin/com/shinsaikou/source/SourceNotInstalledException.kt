package com.shinsaikou.source

/** Thrown by [StubSource]. [sourceId] lets the UI deep-link to the extension that provides it. */
class SourceNotInstalledException(val sourceId: Long) :
    IllegalStateException("Source $sourceId is not installed")
