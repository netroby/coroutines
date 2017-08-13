package tech.pronghorn.coroutines.awaitable

import mu.KotlinLogging
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.EmptyCoroutineContext
import kotlin.coroutines.experimental.suspendCoroutine

internal enum class FutureState {
    INITIALIZED,
    PROMISED,
    COMPLETED_SUCCESS,
    COMPLETED_EXCEPTION,
    CANCELLED
}

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
class InternalFuture<T>(private val onComplete: ((T) -> Unit)? = null) {
    private val logger = KotlinLogging.logger {}
    private var result: T? = null
    private var exception: ExecutionException? = null
    private var state = FutureState.INITIALIZED
    private var waiter: Continuation<T>? = null

    companion object {
        private val cancelledException = CancellationException()
    }

    class InternalPromise<T>(private val future: InternalFuture<T>) {
        fun isCancelled(): Boolean = future.isCancelled()

        fun complete(result: T) {
            future.onComplete?.invoke(result)
            if (future.state != FutureState.PROMISED) {
                throw IllegalStateException("Attempted to complete future in invalid state : ${future.state}")
            }
            future.result = result
            future.state = FutureState.COMPLETED_SUCCESS
            future.wake(result)
        }

        fun completeExceptionally(exception: Throwable) {
            future.exception = ExecutionException(exception)
            future.state = FutureState.COMPLETED_EXCEPTION
            future.wakeExceptionally(exception)
        }
    }

    fun promise(): InternalPromise<T> {
        if (state != FutureState.INITIALIZED) {
            throw IllegalStateException("The promise can only be fetched immediately after creation, and only once.")
        }
        state = FutureState.PROMISED
        return InternalPromise(this)
    }

    private fun wakeExceptionally(exception: Throwable) {
        val waiter = this.waiter
        if (waiter != null) {
            this.waiter = null
            val context = waiter.context
            when (context) {
                is ServiceCoroutineContext -> context.service.wake(exception)
                is ServiceManagedCoroutineContext -> {
                    waiter.resumeWithException(exception)
                }
                is EmptyCoroutineContext -> waiter.resumeWithException(exception)
                else -> {
                    println("Can't wake context $context")
                    System.exit(1)
                }
            }
        }
//        else {
//            throw IllegalStateException("Unexpectedly completing future with no waiter")
//        }
    }

    private fun wake(value: T) {
        val waiter = this.waiter
        if (waiter != null) {
            this.waiter = null
            val context = waiter.context
            when (context) {
                is ServiceCoroutineContext -> context.service.wake(value)
                is ServiceManagedCoroutineContext -> {
                    waiter.resume(value)
                }
                is EmptyCoroutineContext -> waiter.resume(value)
                else -> {
                    logger.error("Can't wake context $context")
                    System.exit(1)
                }
            }
        }
//        else {
//            throw IllegalStateException("Unexpectedly completing future with no waiter")
//        }
    }

    suspend fun awaitAsync(): T {
        return suspendCoroutine<T> { continuation ->
            if (waiter != null) {
                throw IllegalStateException("Only one waiter is allowed.")
            }
            waiter = continuation
            val context = continuation.context
            when (context) {
                is ServiceCoroutineContext -> {
                    context.service.yield(continuation)
                    context.service.worker.next()
                }
                is ServiceManagedCoroutineContext -> {
                    // no-op for the moment
                }
                is EmptyCoroutineContext -> {
                    // no-op for the moment
                }
                else -> {
                    throw Exception("Illegal context type for awaiting a future.")
                }
            }
        }
    }

    fun cancel(): Boolean {
        if (state != FutureState.INITIALIZED && state != FutureState.PROMISED) {
            return false
        }

        state = FutureState.CANCELLED
        wakeExceptionally(cancelledException)
        return true
    }

    fun isCancelled(): Boolean {
        return state == FutureState.CANCELLED
    }

    fun isDone(): Boolean {
        return state != FutureState.PROMISED && state != FutureState.INITIALIZED
    }

    private fun getValue(): T? {
        when (state) {
            FutureState.COMPLETED_SUCCESS -> return result!!
            FutureState.COMPLETED_EXCEPTION -> throw exception!!
            FutureState.CANCELLED -> throw cancelledException
            else -> return null
        }
    }
}
