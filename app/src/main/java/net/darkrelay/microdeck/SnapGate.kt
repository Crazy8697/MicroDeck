package net.darkrelay.microdeck

/**
 * When a snapshot is allowed. TextureView.getBitmap() hands back a blank
 * bitmap (not null) before the first camera frame, so a Snap needs an open
 * camera, a running session and at least one frame since that session began.
 * No Android types here so it can be unit tested.
 */
object SnapGate {

    /** A real preview frame is on screen and can be saved. */
    fun ready(cameraOpen: Boolean, sessionOpen: Boolean, frameSeen: Boolean, permissionDenied: Boolean): Boolean =
        !permissionDenied && cameraOpen && sessionOpen && frameSeen

    /** The Snap button: ready, and not under the panel lock. */
    fun buttonEnabled(ready: Boolean, locked: Boolean): Boolean = ready && !locked
}
