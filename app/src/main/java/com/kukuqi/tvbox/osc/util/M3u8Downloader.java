package com.kukuqi.tvbox.osc.util;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 视频下载器。
 *
 * <p>支持两种源：
 * <ul>
 *   <li><b>m3u8 流</b>：拉清单 → 净化广告 → 逐片下载 → 拼接 → 转 mp4；</li>
 *   <li><b>直链文件</b>（mp4/mkv/flv…）：直接下载，不做净化。</li>
 * </ul>
 *
 * <p>类型判断顺序：URL 后缀 / query 文件名 → 尝试按文本读（限 2 MB）→ 都不像清单就当文件下。
 */
public final class M3u8Downloader {

    public interface Callback {
        void onProgress(String stage, int percent);

        void onSuccess(File file);

        void onError(String message);
    }

    private static final int THREADS = 2;
    private static final int RETRY = 2;
    private static final long CONNECT_TIMEOUT = 20;
    private static final long READ_TIMEOUT = 60;
    /** 清单最大读取量，超过就认定不是 m3u8 */
    private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;

    private static volatile OkHttpClient client;

    private M3u8Downloader() {
    }

    private static OkHttpClient client() {
        if (client == null) {
            synchronized (M3u8Downloader.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                            .writeTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return client;
    }

    /**
     * 下载一集。调用方保证 outFile 的父目录存在。
     *
     * @param url     播放地址（m3u8 或直链文件）
     * @param outFile 目标文件基名（不带扩展名，扩展名由实现决定）
     */
    public static void download(String url, Map<String, String> headers, File outFile, Callback cb) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        try {
            cb.onProgress("探测地址", 0);
            LOG.i("下载开始: " + url);

            // ① URL 看起来就是文件 → 直接下
            if (looksLikeMediaUrl(url)) {
                downloadFile(url, headers, outFile, cancelled, cb);
                return;
            }

            // ② 试着按文本读清单（限 2 MB，避免把视频读进内存）
            String content = fetchText(url, headers, MAX_PLAYLIST_BYTES);
            if (content == null) {
                LOG.i("清单读取失败或过大，按文件下载: " + url);
                downloadFile(url, headers, outFile, cancelled, cb);
                return;
            }
            if (!M3u8Purifier.looksLikeM3u8(content)) {
                LOG.i("返回内容不是 m3u8，按文件下载: " + url);
                downloadFile(url, headers, outFile, cancelled, cb);
                return;
            }

            // ③ m3u8 流程
            String base = url;
            String variant = M3u8Purifier.pickVariant(content, base);
            if (variant != null) {
                base = variant;
                content = fetchText(variant, headers, MAX_PLAYLIST_BYTES);
                if (content == null) {
                    cb.onError("子清单拉取失败");
                    return;
                }
            }

            M3u8Purifier.Report report = new M3u8Purifier.Report();
            String clean = M3u8Purifier.purify(content, base, report);
            String playlist = clean != null ? clean : M3u8Purifier.stripBom(content);
            LOG.i("清单净化: " + report);

            List<String> segs = parseSegments(playlist, base);
            if (segs.isEmpty()) {
                cb.onError("清单里没有分片 · " + snippet(playlist));
                return;
            }

            cb.onProgress("下载分片 0/" + segs.size(), 1);

            File partDir = new File(outFile.getParentFile(), "tmp_" + System.currentTimeMillis());
            if (!partDir.exists() && !partDir.mkdirs()) {
                cb.onError("无法创建临时目录");
                return;
            }

            int ok = downloadSegments(segs, headers, partDir, cancelled, cb);
            if (cancelled.get()) {
                wipe(partDir);
                cb.onError("已取消");
                return;
            }
            if (ok < segs.size() * 0.9) {
                wipe(partDir);
                cb.onError("分片下载失败过多（" + ok + "/" + segs.size() + "）");
                return;
            }

            cb.onProgress("合并分片", 80);
            File tsFile = new File(outFile.getParentFile(), outFile.getName() + ".ts");
            concat(partDir, segs.size(), tsFile);
            wipe(partDir);

            cb.onProgress("转封装为 mp4", 90);
            File mp4 = new File(outFile.getParentFile(), outFile.getName() + ".mp4");
            boolean remuxed = false;
            try {
                if (mp4.exists()) mp4.delete();
                remuxed = remux(tsFile, mp4);
            } catch (Throwable ignored) {
            }

            if (remuxed && mp4.length() > 1024) {
                tsFile.delete();
                cb.onProgress("完成", 100);
                cb.onSuccess(mp4);
            } else {
                if (mp4.exists()) mp4.delete();
                cb.onProgress("完成（保留 ts）", 100);
                cb.onSuccess(tsFile);
            }
        } catch (OutOfMemoryError e) {
            cb.onError("内存不足，该地址可能不是清单而是视频文件");
        } catch (Exception e) {
            cb.onError("下载异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 直链文件

    private static void downloadFile(String url, Map<String, String> headers, File outFile,
                                     AtomicBoolean cancelled, Callback cb) {
        File mp4 = new File(outFile.getParentFile(), outFile.getName() + ".mp4");
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            File tmp = new File(outFile.getParentFile(), outFile.getName() + ".part");
            try {
                Response resp = client().newCall(buildRequest(url, headers)).execute();
                try {
                    if (!resp.isSuccessful()) continue;
                    ResponseBody body = resp.body();
                    if (body == null) continue;
                    long totalLen = body.contentLength();
                    InputStream in = body.byteStream();
                    FileOutputStream fos = new FileOutputStream(tmp);
                    long done = 0;
                    int lastPct = -1;
                    try {
                        byte[] buf = new byte[128 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if (cancelled.get()) throw new IllegalStateException("cancelled");
                            fos.write(buf, 0, n);
                            done += n;
                            if (totalLen > 0) {
                                int pct = (int) (done * 90 / totalLen);
                                if (pct != lastPct && pct % 2 == 0) {
                                    lastPct = pct;
                                    cb.onProgress("下载文件 " + pct + "%", pct);
                                }
                            }
                        }
                        fos.flush();
                    } finally {
                        try {
                            fos.close();
                        } catch (Exception ignored) {
                        }
                        try {
                            in.close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (tmp.length() <= 0) continue;
                    if (mp4.exists()) mp4.delete();
                    if (!tmp.renameTo(mp4)) {
                        copyFile(tmp, mp4);
                        tmp.delete();
                    }
                    cb.onProgress("完成", 100);
                    cb.onSuccess(mp4);
                    return;
                } finally {
                    resp.close();
                }
            } catch (Exception ignored) {
                tmp.delete();
            }
        }
        cb.onError("文件下载失败");
    }

    /** URL 自身是否已带明确后缀 / 文件下载语义 */
    private static boolean looksLikeMediaUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        String path = u;
        String query = "";
        int q = u.indexOf('?');
        if (q > 0) {
            path = u.substring(0, q);
            query = u.substring(q);
        }
        String[] exts = {".mp4", ".mkv", ".flv", ".avi", ".mov", ".m4v", ".webm", ".rmvb", ".wmv"};
        for (String e : exts) {
            if (path.endsWith(e)) return true;
        }
        // 常见的「签名直链」形态：query 里带 attachment / filename=xxx.mp4
        if (query.contains("attachment") || query.contains("filename=") || query.contains("response-content-disposition")) {
            for (String e : exts) {
                if (query.contains(e)) return true;
            }
        }
        return false;
    }

    private static void copyFile(File from, File to) throws Exception {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 网络

    private static Request buildRequest(String url, Map<String, String> headers) {
        Request.Builder rb = new Request.Builder().url(url);
        boolean hasUa = false;
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (e.getKey().equalsIgnoreCase("Host")) continue;
                if (e.getKey().equalsIgnoreCase("User-Agent")) hasUa = true;
                rb.header(e.getKey(), e.getValue());
            }
        }
        if (!hasUa) rb.header("User-Agent", "okhttp/3.15");
        return rb.build();
    }

    /** 读取文本，超过 limit 直接返回 null（避免 OOM） */
    private static String fetchText(String url, Map<String, String> headers, int limit) {
        try {
            Response resp = client().newCall(buildRequest(url, headers)).execute();
            try {
                ResponseBody body = resp.body();
                if (body == null) return null;
                long len = body.contentLength();
                if (len > limit) return null;
                InputStream in = body.byteStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int total = 0, n;
                try {
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > limit) return null;
                        bos.write(buf, 0, n);
                    }
                } finally {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
                return bos.toString("UTF-8");
            } finally {
                resp.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean fetchToFile(String url, Map<String, String> headers, File out) {
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try {
                Response resp = client().newCall(buildRequest(url, headers)).execute();
                try {
                    if (!resp.isSuccessful()) continue;
                    ResponseBody body = resp.body();
                    if (body == null) continue;
                    InputStream in = body.byteStream();
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                        fos.flush();
                    } finally {
                        try {
                            fos.close();
                        } catch (Exception ignored) {
                        }
                        try {
                            in.close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (out.length() > 0) return true;
                } finally {
                    resp.close();
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 分片

    /** 清单诊断片段：首行 + 行数 + 总长，方便判断源返回了什么 */
    private static String snippet(String s) {
        if (s == null) return "内容为空";
        String flat = s.replace('\r', ' ').replace('\n', '|').trim();
        if (flat.length() > 160) flat = flat.substring(0, 160) + "…";
        int lines = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') lines++;
        return "行数=" + lines + " 长度=" + s.length() + " 内容=" + flat;
    }

    private static List<String> parseSegments(String playlist, String base) {
        List<String> list = new ArrayList<>();
        String nl = playlist.contains("\r\n") ? "\r\n" : "\n";
        for (String line : playlist.split(nl, -1)) {
            String s = line.trim();
            if (s.isEmpty() || s.charAt(0) == '#') continue;
            String full = M3u8Purifier.resolve(base, s);
            if (full != null && !full.isEmpty()) list.add(full);
        }
        return list;
    }

    private static int downloadSegments(List<String> segs, Map<String, String> headers,
                                        File partDir, AtomicBoolean cancelled, Callback cb) {
        AtomicInteger done = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int i = 0; i < segs.size(); i++) {
            final int idx = i;
            pool.submit(() -> {
                if (cancelled.get()) return;
                File f = new File(partDir, String.format(Locale.US, "%05d.part", idx));
                if (fetchToFile(segs.get(idx), headers, f)) success.incrementAndGet();
                int d = done.incrementAndGet();
                if (d % 3 == 0 || d == segs.size()) {
                    int pct = 1 + (int) (78.0 * d / segs.size());
                    cb.onProgress("下载分片 " + d + "/" + segs.size(), pct);
                }
            });
        }
        pool.shutdown();
        try {
            while (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                if (cancelled.get()) pool.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            pool.shutdownNow();
        }
        return success.get();
    }

    private static void concat(File partDir, int count, File tsFile) throws Exception {
        if (tsFile.exists()) tsFile.delete();
        FileOutputStream fos = new FileOutputStream(tsFile);
        byte[] buf = new byte[256 * 1024];
        try {
            for (int i = 0; i < count; i++) {
                File f = new File(partDir, String.format(Locale.US, "%05d.part", i));
                if (!f.exists() || f.length() == 0) continue;
                FileInputStream in = new FileInputStream(f);
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                } finally {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            fos.flush();
        } finally {
            try {
                fos.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void wipe(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    // ------------------------------------------------------------------ 转封装

    private static boolean remux(File ts, File mp4) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(ts.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            List<Integer> srcTracks = new ArrayList<>();
            muxer = new MediaMuxer(mp4.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            List<Integer> dstTracks = new ArrayList<>();

            for (int i = 0; i < trackCount; i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue;
                int dst = muxer.addTrack(f);
                srcTracks.add(i);
                dstTracks.add(dst);
            }
            if (srcTracks.isEmpty()) return false;

            muxer.start();
            int n = srcTracks.size();
            long[] lastPts = new long[n];
            long[] lastSrc = new long[n];
            long[] shift = new long[n];
            for (int i = 0; i < n; i++) {
                lastPts[i] = -1;
                lastSrc[i] = -1;
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);

            while (true) {
                long bestTime = Long.MAX_VALUE;
                int best = -1;
                for (int i = 0; i < n; i++) {
                    extractor.selectTrack(srcTracks.get(i));
                    long t = extractor.getSampleTime();
                    if (t >= 0 && t < bestTime) {
                        bestTime = t;
                        best = i;
                    }
                }
                if (best < 0) break;

                extractor.selectTrack(srcTracks.get(best));
                buf.clear();
                int size = extractor.readSampleData(buf, 0);
                if (size < 0) break;

                long src = extractor.getSampleTime();
                long pts;
                if (lastSrc[best] >= 0 && src < lastSrc[best]) {
                    shift[best] += lastSrc[best] - src + 1000;
                }
                pts = src + shift[best];
                if (lastPts[best] >= 0 && pts <= lastPts[best]) pts = lastPts[best] + 1000;

                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = pts;
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(dstTracks.get(best), buf, info);

                lastPts[best] = pts;
                lastSrc[best] = src;
                extractor.advance();
            }

            muxer.stop();
            return true;
        } catch (Throwable e) {
            LOG.e(e);
            return false;
        } finally {
            try {
                if (muxer != null) muxer.release();
            } catch (Exception ignored) {
            }
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }
}
