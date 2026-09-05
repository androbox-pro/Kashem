package com.andrognito.patternlockview.utils

import com.andrognito.patternlockview.PatternLockView
import java.security.MessageDigest

object PatternLockUtils {
    fun patternToString(view: PatternLockView, pattern: List<PatternLockView.Dot>?): String =
        pattern.orEmpty().joinToString(separator = "") { it.id.toString() }

    fun patternToSha1(view: PatternLockView, pattern: List<PatternLockView.Dot>?): String {
        val value = patternToString(view, pattern)
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
