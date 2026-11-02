package dev.plume

internal fun shouldRebuildHome(ready: Boolean, recreating: Boolean, finishing: Boolean, destroyed: Boolean, userUnlocked: Boolean) =
    userUnlocked && !ready && !recreating && !finishing && !destroyed
