package com.andrognito.patternlockview.listener

import com.andrognito.patternlockview.PatternLockView

interface PatternLockViewListener {
    fun onStarted()
    fun onProgress(progressPattern: MutableList<PatternLockView.Dot>?)
    fun onComplete(pattern: MutableList<PatternLockView.Dot>?)
    fun onCleared()
}
