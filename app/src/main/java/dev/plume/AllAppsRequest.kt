package dev.plume

/** One deferred navigation request, retained without holding an Activity across rebuilds. */
internal class AllAppsRequest {
    var pending = false
        private set

    fun accept(action: String?): Boolean {
        if (action != ACTION) return false
        pending = true
        return true
    }

    fun consume(ready: Boolean, resumed: Boolean, appsLoaded: Boolean, recreating: Boolean = false): Boolean {
        if (!pending || !ready || !resumed || !appsLoaded || recreating) return false
        pending = false
        return true
    }

    fun cancel() { pending = false }

    companion object {
        const val ACTION = "dev.plume.OPEN_ALL_APPS"
    }
}
