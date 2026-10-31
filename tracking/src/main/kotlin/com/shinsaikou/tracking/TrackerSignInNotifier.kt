package com.shinsaikou.tracking

/**
 * Tells the owner, outside the app's screens, that a tracker's service no longer accepts the stored
 * sign-in. `:tracking` has no notifications: `:app` binds the implementation, one notification per
 * tracker that opens the tracking settings, where Sign in again is.
 *
 * Only the retry worker posts it, the one time a background push meets the service's refusal
 * ([DelayedTrackingUpdate]); the track sheet, the search and Compare show "Sign in again" on screen
 * when they meet it. After that the tracker sends nothing until a new sign-in, so it is not posted
 * again until the sign-in expires once more.
 */
interface TrackerSignInNotifier {

    /** [tracker]'s service refused the stored sign-in while a background push was being sent. */
    fun expired(tracker: Tracker)

    /** The owner signed in to [tracker] again: the notice [expired] posted, if any, goes. */
    fun signedIn(tracker: Tracker)
}
