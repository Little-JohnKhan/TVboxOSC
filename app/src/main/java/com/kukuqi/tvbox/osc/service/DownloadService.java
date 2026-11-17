package com.kukuqi.tvbox.osc.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import com.kukuqi.tvbox.osc.R;
import com.kukuqi.tvbox.osc.base.App;
import com.kukuqi.tvbox.osc.core.CoreBridge;
import com.kukuqi.tvbox.osc.util.LOG;
import com.kukuqi.tvbox.osc.util.M3u8Downloader;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 下载队列（纯静态，不依赖前台服务）。
 *
 * <p>Android 12+ 对 startForegroundService 限制很严，ColorOS 上经常直接拒绝且不抛给调用方，
 * 所以这里改成「在 App 进程内起线程」，通知只作为可选展示，失败不影响下载。
 *
 * <p>每一步都写 /Download/TVBox/.download.log，方便排查。
 */
public final class DownloadService {

    private static final String CHANNEL_ID = "tvbox_download";
    private static final int NOTI_ID = 9527;
    private static final String ROOT = "TVBox";

    public static class Task {
        public String sourceKey;
        public String playFlag;
        public String seriesUrl;
        public String vodName;
        public String seriesName;
    }

    private static final List<Task> QUEUE = new LinkedList<>();
    private static final Object LOCK = new Object();
    private static volatile boolean running;
    private static int total;
    private static int done;
    private static Context appCtx;

    private DownloadService() {
    }

    public static void enqueue(Context ctx, Task task) {
        if (task == null || TextUtils.isEmpty(task.seriesUrl)) return;
        if (ctx != null) appCtx = ctx.getApplicationContext();
        if (appCtx == null) appCtx = App.getInstance();
        synchronized (QUEUE) {
            QUEUE.add(task);
            total++;
        }
        writeLog("入队: " + task.vodName + " / " + task.seriesName + " url=" + task.seriesUrl);
        startIfNeeded();
    }

    public static int pending() {
        synchronized (QUEUE) {
            return QUEUE.size();
        }
    }

    // ------------------------------------------------------------------ 线程

    private static void startIfNeeded() {
        synchronized (LOCK) {
            if (running) return;
            running = true;
            Thread t = new Thread(DownloadService::drain, "tvbox-download");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
            writeLog("下载线程已启动");
        }
    }

    private static void drain() {
        while (true) {
            Task task;
            synchronized (QUEUE) {
                if (QUEUE.isEmpty()) {
                    running = false;
                    break;
                }
                task = QUEUE.remove(0);
            }
            try {
                downloadOne(task);
            } catch (Throwable e) {
                writeLog("任务异常: " + e);
                LOG.e(e);
                notifyUpdate("失败：" + task.seriesName + " · " + e.getClass().getSimpleName(), -1);
            }
            done++;
        }
        writeLog("队列结束 done=" + done + "/" + total);
        done = 0;
        total = 0;
        cancelNotification();
    }

    // ------------------------------------------------------------------ 单集

    private static void downloadOne(Task task) {
        String title = safe(task.vodName) + " " + safe(task.seriesName);
        notifyUpdate("解析地址：" + title, 0);
        writeLog("开始: " + title);

        String m3u8;
        Map<String, String> headers;
        try {
            com.kukuqi.tvbox.engine.bean.Result result =
                    CoreBridge.player(task.sourceKey, task.playFlag, task.seriesUrl);
            if (result == null) {
                writeLog("解析失败: result == null");
                notifyUpdate("解析失败：" + title, -1);
                return;
            }
            if (result.needParse()) {
                writeLog("需要解析器，跳过: " + title);
                notifyUpdate("该集需要解析器，跳过：" + title, -1);
                return;
            }
            String prefix = result.getPlayUrl();
            String url = result.getUrl().v();
            m3u8 = (TextUtils.isEmpty(prefix) ? "" : prefix) + (url == null ? "" : url);
            headers = result.getHeader();
            writeLog("地址: " + m3u8 + "  headers=" + (headers == null ? "null" : headers.keySet().toString()));
        } catch (Throwable e) {
            writeLog("解析异常: " + e + " @ " + task.seriesUrl);
            notifyUpdate("解析异常：" + title, -1);
            return;
        }

        if (TextUtils.isEmpty(m3u8)) {
            writeLog("地址为空");
            notifyUpdate("没拿到播放地址：" + title, -1);
            return;
        }

        File dir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS + File.separator + ROOT + File.separator + safe(task.vodName));
        if (!dir.exists() && !dir.mkdirs()) {
            writeLog("无法创建目录: " + dir.getAbsolutePath());
            notifyUpdate("无法创建目录：" + dir.getAbsolutePath(), -1);
            return;
        }

        File out = new File(dir, safe(task.seriesName));
        writeLog("输出: " + out.getAbsolutePath());

        M3u8Downloader.download(m3u8, headers, out, new M3u8Downloader.Callback() {
            @Override
            public void onProgress(String stage, int percent) {
                notifyUpdate(title + " · " + stage, percent);
            }

            @Override
            public void onSuccess(File file) {
                writeLog("完成: " + file.getAbsolutePath() + " size=" + file.length());
                notifyUpdate("完成：" + file.getName(), 100);
            }

            @Override
            public void onError(String message) {
                writeLog("失败: " + title + " · " + message);
                notifyUpdate("失败：" + title + " · " + message, -1);
            }
        });
    }

    // ------------------------------------------------------------------ 日志

    /** 写诊断日志，路径 Download/TVBox/.download.log */
    private static void writeLog(String msg) {
        LOG.i("[下载] " + msg);
        try {
            Context ctx = appCtx != null ? appCtx : App.getInstance();
            if (ctx == null) return;
            File dir = new File(Environment.getExternalStorageDirectory(),
                    Environment.DIRECTORY_DOWNLOADS + File.separator + ROOT);
            if (!dir.exists()) dir.mkdirs();
            File log = new File(dir, ".download.log");
            FileWriter fw = new FileWriter(log, true);
            try {
                String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
                fw.write(time + "  " + msg + "\n");
            } finally {
                fw.close();
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 通知（可失败）

    private static void notifyUpdate(String text, int percent) {
        try {
            Context ctx = appCtx != null ? appCtx : App.getInstance();
            if (ctx == null) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "视频下载", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(total > 0
                            ? String.format(Locale.US, "下载队列 %d/%d", Math.min(done + 1, total), total)
                            : "视频下载")
                    .setContentText(text)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(percent < 0);
            if (percent >= 0) {
                b.setProgress(100, Math.min(100, percent), false).setOngoing(true);
            }
            nm.notify(NOTI_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    private static void cancelNotification() {
        try {
            Context ctx = appCtx != null ? appCtx : App.getInstance();
            if (ctx == null) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID);
        } catch (Throwable ignored) {
        }
    }

    private static String safe(String name) {
        if (TextUtils.isEmpty(name)) return "未命名";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (s.length() > 60) s = s.substring(0, 60);
        return s.isEmpty() ? "未命名" : s;
    }
}
