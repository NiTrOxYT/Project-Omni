package com.omni.assistant.core.common

sealed interface OmniResult<out T> {
    data class Success<out T>(val data: T) : OmniResult<T>
    data class Error(val message: String, val cause: Throwable? = null) : OmniResult<Nothing>
    data object Loading : OmniResult<Nothing>
}
