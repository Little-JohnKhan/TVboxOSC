package com.kukuqi.tvbox.osc.util;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
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
 * m3u8 下载器：拉清单 → 净化 → 逐片下载 → 拼接 → 转封装成 mp4。
 *
 * <p>不依赖 Activity，可在 Service 里跑。转封装失败时保留 .ts。
 */
public final class M3u8Downloader {

    public interface Callback {
        /** 阶段说明 + 进度（0~100） */
        void onProgress(String stage, int percent);

        void onSuccess(File file);

        void onError(String message);
    }

    /** 并发下载线程数：太多容易被源站限速 */
    private static final int THREADS = 2;
    private static final int RETRY = 2;
    private static final long CONNECT_TIMEOUT = 20;
    private static final long READ_TIMEOUT = 45;

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
     * 下载一集。
     *
     * @param m3u8Url 播放地址（m3u8）
     * @param headers 源站需要的头（可为空）
     * @param outFile 目标文件，调用方保证父目录存在；扩展名会被自动决定（.mp4 或 .ts）
     */
    public static void download(String m3u8Url, Map<String, String> headers, File outFile, Callback cb) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        try {
            cb.onProgress("解析清单", 0);
            String content = fetchText(m3u8Url, headers);
            if (content == null || !M3u8Purifier.looksLikeM3u8(content)) {
                cb.onError("不是有效的 m3u8");
                return;
            }

            String base = m3u8Url;
            String variant = M3u8Purifier.pickVariant(content, base);
            if (variant != null) {
                base = variant;
                content = fetchText(variant, headers);
                if (content == null) {
                    cb.onError("子清单拉取失败");
                    return;
                }
            }

            M3u8Purifier.Report report = new M3u8Purifier.Report();
            String clean = M3u8Purifier.purify(content, base, report);
            String playlist = clean != null ? clean : M3u8Purifier.stripBom(content);
            LOG.i("下载净化: " + report);

            List<String> segs = parseSegments(playlist, base);
            if (segs.isEmpty()) {
                cb.onError("清单里没有分片");
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
        } catch (Exception e) {
            cb.onError("下载异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 网络

    private static String fetchText(String url, Map<String, String> headers) {
        try {
            Request.Builder rb = new Request.Builder().url(url);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    if (e.getKey().equalsIgnoreCase("Host")) continue;
                    rb.header(e.getKey(), e.getValue());
                }
            }
            if (headers == null || !headers.containsKey("User-Agent")) rb.header("User-Agent", "okhttp/3.15");
            Response resp = client().newCall(rb.build()).execute();
            try {
                ResponseBody body = resp.body();
                if (body == null) return null;
                return body.string();
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
                Request.Builder rb = new Request.Builder().url(url);
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        if (e.getKey().equalsIgnoreCase("Host")) continue;
                        rb.header(e.getKey(), e.getValue());
                    }
                }
                if (headers == null || !headers.containsKey("User-Agent")) rb.header("User-Agent", "okhttp/3.15");
                Response resp = client().newCall(rb.build()).execute();
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

    private static List<String> parseSegments(String playlist, String base) {
        List<String> list = new ArrayList<>();
        String nl = playlist.contains("\r\n") ? "\r\n" : "\n";
        for (String line : playlist.split(nl, -1)) {
            String s = line.trim();
            if (s.isEmpty() || s.charAt(0) == '#') continue;
            list.add(M3u8Purifier.resolve(base, s));
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
                File f = new File(partDir, String.format(java.util.Locale.US, "%05d.part", idx));
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
                File f = new File(partDir, String.format(java.util.Locale.US, "%05d.part", i));
                if (!f.exists() || f.length() == 0) continue;
                java.io.FileInputStream in = new java.io.FileInputStream(f);
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

    /**
     * ts → mp4 转封装（不重编码）。时间戳被重排成严格递增，
     * 避免删掉广告段后 PTS 跳变导致「能播但拖不动」。
     */
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
                    // 时间倒退（拼接处），补一个帧间隔
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
