package com.lk21.webview

import android.net.Uri

/**
 * Kebijakan host:
 * - Halaman utama (main frame) hanya boleh membuka domain situs/mirror yang diizinkan.
 * - Sub-resource dan iframe (player) dibiarkan bebas, kecuali domain iklan di daftar blokir.
 */
object HostPolicy {

    private val allowedSuffixes = listOf(
        "lk21official.dev",
        "lk21official.cc",
        "lk21.de",
        "nontondrama.vip",
        "assetsy.de",
        "dadadidi.de",
        "d21.team",
        "videonode.de"
    )

    private val allowedKeywords = listOf("lk21", "nontondrama", "layarkaca")

    private val blockedSuffixes = listOf(
        "yellowishgather.com",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "adsterra.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.com",
        "hilltopads.net",
        "clickadu.com",
        "adcash.com",
        "onclickads.net",
        "mgid.com"
    )

    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            Uri.parse(url).host?.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    private fun matches(host: String, suffix: String): Boolean {
        return host == suffix || host.endsWith(".$suffix")
    }

    fun isBlocked(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return blockedSuffixes.any { matches(host, it) }
    }

    fun isAllowedPage(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        if (blockedSuffixes.any { matches(host, it) }) return false
        if (allowedSuffixes.any { matches(host, it) }) return true
        return allowedKeywords.any { host.contains(it) }
    }
}
