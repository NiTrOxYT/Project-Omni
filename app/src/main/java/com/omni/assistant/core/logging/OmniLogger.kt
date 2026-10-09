package com.omni.assistant.core.logging

import android.util.Log

object OmniLogger {
    private const val TAG_PREFIX = "OmniAgent"

    fun d(tag: String, message: String) {
        val sanitized = sanitize(message)
        try {
            Log.d("$TAG_PREFIX:$tag", sanitized)
        } catch (e: RuntimeException) {
            println("DEBUG: [$TAG_PREFIX:$tag] $sanitized")
        }
    }

    fun i(tag: String, message: String) {
        val sanitized = sanitize(message)
        try {
            Log.i("$TAG_PREFIX:$tag", sanitized)
        } catch (e: RuntimeException) {
            println("INFO: [$TAG_PREFIX:$tag] $sanitized")
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val sanitized = sanitize(message)
        try {
            if (throwable != null) {
                Log.w("$TAG_PREFIX:$tag", sanitized, throwable)
            } else {
                Log.w("$TAG_PREFIX:$tag", sanitized)
            }
        } catch (e: RuntimeException) {
            println("WARN: [$TAG_PREFIX:$tag] $sanitized ${throwable?.message ?: ""}")
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val sanitized = sanitize(message)
        val errorSuffix = throwable?.let {
            " | [${it.javaClass.simpleName}] ${sanitize(it.message ?: "")}"
        } ?: ""
        val fullMessage = "$sanitized$errorSuffix"
        try {
            if (throwable != null) {
                Log.e("$TAG_PREFIX:$tag", fullMessage, throwable)
            } else {
                Log.e("$TAG_PREFIX:$tag", fullMessage)
            }
        } catch (e: RuntimeException) {
            System.err.println("ERROR: [$TAG_PREFIX:$tag] $fullMessage")
        }
    }

    /**
     * Redacts known sensitive patterns like tokens, keys, passwords.
     */
    private fun sanitize(input: String): String {
        return input
            .replace(Regex("(?i)(api[_-]?key|bearer|token|secret|password)\\s*[:=]\\s*[^\\s,]+"), "$1: [REDACTED]")
    }
}
