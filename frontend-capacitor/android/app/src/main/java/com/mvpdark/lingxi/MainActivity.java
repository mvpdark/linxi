package com.mvpdark.lingxi;

import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 注册自定义插件
        registerPlugin(ApkUpdaterPlugin.class);
        super.onCreate(savedInstanceState);

        // 自签 SSL 证书旁路：允许 WebView 访问使用自签证书的 HTTPS 后端
        // 后端 Lucky 反向代理使用自签证书，WebView 默认拒绝 → fetch 抛异常 → "网络错误"
        // 生产环境建议替换为正规 CA 证书（如 Let's Encrypt），此处仅为兼容当前部署
        setupSslBypass();
    }

    /**
     * 覆盖 BridgeWebViewClient，接受自签证书。
     * Capacitor 7 在 Bridge.init() 中设置了默认的 BridgeWebViewClient，
     * 此处通过 setWebViewClient 替换为允许自签证书的子类实例。
     */
    private void setupSslBypass() {
        try {
            if (this.bridge == null) return;

            this.bridge.setWebViewClient(new BridgeWebViewClient(this.bridge) {
                @Override
                public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    // 接受自签证书，继续加载
                    handler.proceed();
                }
            });
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "SSL bypass setup failed", e);
        }
    }
}
