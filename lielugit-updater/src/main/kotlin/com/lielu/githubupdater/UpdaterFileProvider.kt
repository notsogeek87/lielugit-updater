package com.lielu.githubupdater

import androidx.core.content.FileProvider

/**
 * FileProvider declared in the library manifest to share downloaded APKs with the system
 * installer. It exists only to avoid clashing with a FileProvider of the host app; there is
 * nothing to configure or call.
 */
public class UpdaterFileProvider : FileProvider()
