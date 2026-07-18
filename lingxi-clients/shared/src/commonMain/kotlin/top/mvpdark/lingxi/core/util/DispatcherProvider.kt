package top.mvpdark.lingxi.core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 协程调度器提供者。
 *
 * 抽象出 [Dispatchers] 的访问，便于在测试中替换为 [kotlinx.coroutines.test.UnconfinedTestDispatcher]
 * 等测试调度器，避免直接在业务代码中硬编码 [Dispatchers]。
 */
interface DispatcherProvider {
    /** 适用于磁盘 / 网络 / 数据库等阻塞型 IO 操作。 */
    val io: CoroutineDispatcher

    /** 主线程（UI 线程），用于更新 Compose 状态。 */
    val main: CoroutineDispatcher

    /** 适用于 CPU 密集型计算。 */
    val default: CoroutineDispatcher
}

/**
 * 默认实现，直接映射到 [Dispatchers]。
 *
 * 注意：[Dispatchers.IO] 是 JVM 平台特有的，在 commonMain 中不可用。
 * 此处使用 [Dispatchers.Default] 作为 IO 调度器的替代。
 * [Dispatchers.Main] 在 commonMain 中作为 expect 声明可用，
 * Android 端通过 AndroidX 依赖提供实现，Desktop 端通过 `kotlinx-coroutines-swing` 绑定到 Swing EDT。
 */
class DefaultDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val default: CoroutineDispatcher = Dispatchers.Default
}
