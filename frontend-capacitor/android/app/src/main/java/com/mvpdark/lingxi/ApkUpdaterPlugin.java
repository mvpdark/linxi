package com.mvpdark.lingxi;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

@CapacitorPlugin(name = "ApkUpdater")
public class ApkUpdaterPlugin extends Plugin {

    private static final String TAG = "ApkUpdater";
    private static final String GITHUB_API = "https://api.github.com/repos/mvpdark/linxi/releases/latest";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 600000; // 10 min for large APK

    /**
     * 检查 GitHub Releases 最新版本
     * 返回: { version, downloadUrl, releaseNotes, isNewer }
     */
    @PluginMethod
    public void checkUpdate(PluginCall call) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(GITHUB_API);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "Lingxi-Android");

                int code = conn.getResponseCode();
                if (code != 200) {
                    call.reject("GitHub API 返回错误: " + code);
                    return;
                }

                Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8");
                StringBuilder sb = new StringBuilder();
                while (scanner.hasNextLine()) {
                    sb.append(scanner.nextLine());
                }
                scanner.close();

                JSONObject release = new JSONObject(sb.toString());
                String tagName = release.optString("tag_name", ""); // e.g. "v1.0.42"
                String body = release.optString("body", "");
                String htmlUrl = release.optString("html_url", "");

                // 从 tag_name 提取版本号
                String version = tagName.replaceFirst("^v", "");

                // 查找 APK 下载 URL
                String downloadUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url", "");
                            break;
                        }
                    }
                }

                if (downloadUrl == null) {
                    call.reject("未找到 APK 下载文件");
                    return;
                }

                // 比较版本号
                String currentVersion = getAppVersion();
                boolean isNewer = compareVersions(version, currentVersion) > 0;

                JSObject result = new JSObject();
                result.put("version", version);
                result.put("currentVersion", currentVersion);
                result.put("downloadUrl", downloadUrl);
                result.put("releaseNotes", body);
                result.put("htmlUrl", htmlUrl);
                result.put("isNewer", isNewer);
                call.resolve(result);

            } catch (Exception e) {
                Log.e(TAG, "检查更新失败", e);
                call.reject("检查更新失败: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /**
     * 下载 APK 并自动触发安装
     * 参数: url (APK 下载地址)
     * 进度通过 notifyListeners("downloadProgress", {...}) 回调
     */
    @PluginMethod
    public void downloadAndInstall(PluginCall call) {
        String downloadUrl = call.getString("url");
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            call.reject("缺少下载地址");
            return;
        }

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(downloadUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestProperty("User-Agent", "Lingxi-Android");
                conn.setInstanceFollowRedirects(true);

                int code = conn.getResponseCode();
                if (code != 200) {
                    call.reject("下载失败: HTTP " + code);
                    return;
                }

                long totalSize = conn.getContentLength();
                File cacheDir = getContext().getCacheDir();
                File apkFile = new File(cacheDir, "lingxi-update.apk");
                if (apkFile.exists()) apkFile.delete();

                InputStream input = conn.getInputStream();
                FileOutputStream output = new FileOutputStream(apkFile);
                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int bytesRead;
                int lastProgress = -1;

                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;

                    if (totalSize > 0) {
                        int progress = (int) (downloaded * 100 / totalSize);
                        // 每变化 5% 通知一次
                        if (progress / 5 != lastProgress / 5) {
                            lastProgress = progress;
                            JSObject progressData = new JSObject();
                            progressData.put("progress", progress);
                            progressData.put("downloaded", downloaded);
                            progressData.put("total", totalSize);
                            notifyListeners("downloadProgress", progressData);
                        }
                    }
                }

                output.flush();
                output.close();
                input.close();

                // 通知下载完成
                JSObject completeData = new JSObject();
                completeData.put("progress", 100);
                completeData.put("path", apkFile.getAbsolutePath());
                notifyListeners("downloadProgress", completeData);

                // 触发安装
                installApk(apkFile);
                call.resolve(new JSObject().put("ok", true));

            } catch (Exception e) {
                Log.e(TAG, "下载安装失败", e);
                call.reject("下载安装失败: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /**
     * 使用 Intent + FileProvider 触发 APK 安装
     */
    private void installApk(File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            String authority = getContext().getPackageName() + ".fileprovider";
            uri = FileProvider.getUriForFile(getContext(), authority, apkFile);
        } else {
            uri = Uri.fromFile(apkFile);
        }

        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        getContext().startActivity(intent);
    }

    /**
     * 获取当前 App 版本号
     */
    private String getAppVersion() {
        try {
            return getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /**
     * 版本号比较: a > b 返回 1, a == b 返回 0, a < b 返回 -1
     * 支持 "1.0.42" 格式
     */
    private int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int maxLen = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < maxLen; i++) {
            int va = i < partsA.length ? Integer.parseInt(partsA[i]) : 0;
            int vb = i < partsB.length ? Integer.parseInt(partsB[i]) : 0;
            if (va > vb) return 1;
            if (va < vb) return -1;
        }
        return 0;
    }
}
