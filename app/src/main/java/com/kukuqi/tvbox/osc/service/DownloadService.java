package com.kukuqi.tvbox.osc.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.kukuqi.tvbox.osc.R;
import com.kukuqi.tvbox.osc.core.CoreBridge;
import com.kukuqi.tvbox.osc.util.LOG;
import com.kukuqi.tvbox.osc.util.M3u8Downloader;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 串行下载队列：每点一次「下载」入队一集，服务按顺序跑完。
 * 前台服务 + dataSync 类型，避免切后台被系统回收。
 */
public class DownloadService extends Service {

    private static final String CHANNEL_ID = "tvbox_download";
    private static final int NOTI_ID = 9527;
    private static final String ROOT = "TVBox";

    /** 下载任务 */
    public static class Task {
        public String sourceKey;
        public String playFlag;
        public String seriesUrl;
        public String vodName;
        public String seriesName;
    }

    private static final List<Task> QUEUE = new LinkedList<>();
    private static int total = 0;
    private static int doneCount = 0;

    private volatile boolean running;
    private NotificationManager nm;

    /** 入队并拉起服务 */
    public static void enqueue(Context ctx, Task task) {
        if (task == null || TextUtils.isEmpty(task.seriesUrl)) return;
        synchronized (QUEUE) {
            QUEUE.add(task);
            total++;
        }
        Intent intent = new Intent(ctx, DownloadService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(ctx, intent);
        } else {
            ctx.startService(intent);
        }
    }

    /** 队列里还剩多少（含正在下的） */
    public static int pending() {
        synchronized (QUEUE) {
            return QUEUE.size();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "视频下载", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 12+ 要求启动后 5 秒内进入前台；14+ 还要带 type
        Notification first = buildNotification("准备下载", 0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTI_ID, first);
        }
        if (!running) {
            running = true;
            new Thread(this::loop, "tvbox-download").start();
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loop() {
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
                LOG.e(e);
                notifyUpdate("下载失败：" + e.getMessage(), 0, total, doneCount);
            }
            doneCount++;
        }
        doneCount = 0;
        total = 0;
        stopForeground(true);
        stopSelf();
    }

    private void downloadOne(Task task) {
        String title = safe(task.vodName) + " " + safe(task.seriesName);
        notifyUpdate("正在解析：" + title, 0, total, doneCount);

        String m3u8;
        Map<String, String> headers;
        try {
            com.kukuqi.tvbox.engine.bean.Result result =
                    CoreBridge.player(task.sourceKey, task.playFlag, task.seriesUrl);
            if (result == null) {
                notifyUpdate("解析失败：" + title, 0, total, doneCount);
                return;
            }
            if (result.needParse()) {
                notifyUpdate("该集需要解析器，无法批量下载：" + title, 0, total, doneCount);
                return;
            }
            String prefix = result.getPlayUrl();
            String url = result.getUrl().v();
            m3u8 = (TextUtils.isEmpty(prefix) ? "" : prefix) + (url == null ? "" : url);
            headers = result.getHeader();
        } catch (Throwable e) {
            notifyUpdate("解析异常：" + title, 0, total, doneCount);
            return;
        }
        if (TextUtils.isEmpty(m3u8)) {
            notifyUpdate("没拿到播放地址：" + title, 0, total, doneCount);
            return;
        }

        File dir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOAD + File.separator + ROOT + File.separator + safe(task.vodName));
        if (!dir.exists() && !dir.mkdirs()) {
            notifyUpdate("无法创建目录：" + dir.getAbsolutePath(), 0, total, doneCount);
            return;
        }
        File out = new File(dir, safe(task.seriesName));

        M3u8Downloader.download(m3u8, headers, out, new M3u8Downloader.Callback() {
            @Override
            public void onProgress(String stage, int percent) {
                notifyUpdate(title + " · " + stage, percent, total, doneCount);
            }

            @Override
            public void onSuccess(File file) {
                notifyUpdate("完成：" + file.getName(), 100, total, doneCount + 1);
            }

            @Override
            public void onError(String message) {
                notifyUpdate("失败：" + title + " · " + message, 0, total, doneCount);
            }
        });
    }

    // ------------------------------------------------------------------ 通知

    private void notifyUpdate(String text, int percent, int totalCount, int done) {
        Notification n = buildNotification(text, percent, totalCount, done);
        if (nm != null) nm.notify(NOTI_ID, n);
    }

    private Notification buildNotification(String text, int percent, int totalCount, int done) {
        String title = totalCount > 0
                ? String.format(Locale.US, "下载队列 %d/%d", Math.min(done + 1, totalCount), totalCount)
                : "视频下载";
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);
        if (percent > 0) {
            b.setProgress(100, Math.min(100, percent), false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    // ------------------------------------------------------------------ 工具

    private static String safe(String name) {
        if (TextUtils.isEmpty(name)) return "未命名";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (s.length() > 60) s = s.substring(0, 60);
        return s.isEmpty() ? "未命名" : s;
    }
}
