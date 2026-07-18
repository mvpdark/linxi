package top.mvpdark.lingxi.core.util

import kotlinx.coroutines.CancellationException

/**
 * 可取消版本的 [runCatching]。
 *
 * 与 [runCatching] 的区别：不会捕获 [CancellationException]，而是重新抛出，
 * 确保协程的结构化并发正常工作。
 *
 * 在 suspend 函数中应使用此函数替代 [runCatching]，避免吞掉取消信号。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
