package top.mvpdark.lingxi.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import top.mvpdark.lingxi.LingxiApp

/**
 * Android 主 Activity。
 *
 * - 继承 [ComponentActivity]
 * - [enableEdgeToEdge] 启用边到边布局
 * - [setContent] 渲染 [LingxiApp] 共享根组件
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LingxiApp()
        }
    }
}
