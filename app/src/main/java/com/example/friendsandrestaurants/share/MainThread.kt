package com.example.friendsandrestaurants.share

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs work from server threads on the main thread, where the bill may be read and changed. */
object MainThread {
    private val handler = Handler(Looper.getMainLooper())

    @Throws(TimeoutException::class, InterruptedException::class)
    fun <T> call(timeoutMs: Long = 10_000, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        check(handler.post(task)) { "Main thread is not running" }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Don't let a late run apply a change the guest was told failed.
            task.cancel(false)
            throw e
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
