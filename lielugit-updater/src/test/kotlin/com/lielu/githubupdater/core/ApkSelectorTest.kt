package com.lielu.githubupdater.core

import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ApkSelectorTest {

    private val arm64Device = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val x86Device = listOf("x86_64", "x86")

    private fun pick(names: List<String>, abis: List<String>, pattern: String? = null) =
        ApkSelector.select(names.map(::githubAsset), pattern, abis).name

    private fun failure(names: List<String>, abis: List<String>, pattern: String? = null): UpdateError =
        assertFailsWith<UpdateException> { pick(names, abis, pattern) }.error

    @Test
    fun `single apk is selected`() {
        assertEquals("app.apk", pick(listOf("app.apk", "notes.txt", "app.apk.sha256"), arm64Device))
    }

    @Test
    fun `no apk at all`() {
        assertIs<UpdateError.ApkNotFound>(failure(listOf("notes.txt", "source.zip"), arm64Device))
        assertIs<UpdateError.ApkNotFound>(failure(emptyList(), arm64Device))
    }

    @Test
    fun `pattern restricts the candidates`() {
        val names = listOf("app-debug.apk", "app-release.apk")
        assertEquals("app-release.apk", pick(names, arm64Device, "app-release\\.apk"))
        assertEquals("app-debug.apk", pick(names, arm64Device, ".*debug.*"))
        assertIs<UpdateError.ApkNotFound>(failure(names, arm64Device, "other\\.apk"))
    }

    @Test
    fun `pattern must match the whole name`() {
        assertIs<UpdateError.ApkNotFound>(failure(listOf("myapp-arm64-v8a.apk"), arm64Device, "arm64"))
    }

    @Test
    fun `several apks are resolved by device architecture`() {
        val names = listOf("app-arm64-v8a.apk", "app-armeabi-v7a.apk", "app-x86_64.apk", "app-universal.apk")
        assertEquals("app-arm64-v8a.apk", pick(names, arm64Device))
        assertEquals("app-armeabi-v7a.apk", pick(names, listOf("armeabi-v7a", "armeabi")))
        assertEquals("app-x86_64.apk", pick(names, x86Device))
    }

    @Test
    fun `x86 is not confused with x86_64`() {
        val names = listOf("app-x86.apk", "app-x86_64.apk")
        assertEquals("app-x86_64.apk", pick(names, x86Device))
        assertEquals("app-x86.apk", pick(listOf("app-x86.apk", "app-arm64-v8a.apk"), listOf("x86")))
    }

    @Test
    fun `universal is the fallback`() {
        val names = listOf("app-x86_64.apk", "app-universal.apk")
        assertEquals("app-universal.apk", pick(names, arm64Device))
    }

    @Test
    fun `never selects an incompatible architecture silently`() {
        val error = failure(listOf("myapp-x86_64.apk"), arm64Device)
        assertIs<UpdateError.ApkNotFound>(error)
        assertIs<UpdateError.ApkNotFound>(failure(listOf("a-x86.apk", "a-x86_64.apk"), arm64Device))
        // Even when the pattern points at it explicitly.
        assertIs<UpdateError.ApkNotFound>(failure(listOf("myapp-x86_64.apk"), arm64Device, "myapp-x86_64\\.apk"))
    }

    @Test
    fun `ambiguity is an error, not a guess`() {
        assertIs<UpdateError.ApkNotFound>(failure(listOf("app-debug.apk", "app-release.apk"), arm64Device))
        assertIs<UpdateError.ApkNotFound>(failure(listOf("a-universal.apk", "b-universal.apk"), arm64Device))
    }

    @Test
    fun `an untagged apk is accepted next to incompatible ones`() {
        assertEquals("app.apk", pick(listOf("app.apk", "app-x86_64.apk"), arm64Device))
    }

    @Test
    fun `file extension is case-insensitive`() {
        assertEquals("App.APK", pick(listOf("App.APK"), arm64Device))
    }
}
