package com.lielu.githubupdater.core

/** Persists the result of the last successful check so it can be reused within the interval. */
internal interface UpdateStore {
    var cacheKey: String?
    var lastCheckMillis: Long

    /** Last [com.lielu.githubupdater.UpdateInfo] serialized as JSON, or `null` if none was newer. */
    var cachedUpdateJson: String?
}

internal class InMemoryUpdateStore : UpdateStore {
    override var cacheKey: String? = null
    override var lastCheckMillis: Long = 0
    override var cachedUpdateJson: String? = null
}
