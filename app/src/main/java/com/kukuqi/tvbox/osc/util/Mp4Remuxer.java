package com.kukuqi.tvbox.osc.util;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Mp4Muxer;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * ts → mp4 转封装，用 media3 官方的 Mp4Muxer。
 *
 * <p>为什么不用 Android 自带的 MediaMuxer：
 * MediaMuxer 没有写 ctts 的 API，而 H.264 的 B 帧会让显示顺序（PTS）天然非单调。
 * 硬塞进去的结果是音频轨在第一个 PTS 回退样本就异常中断（无声）、
 * 视频帧显示顺序错乱（抽帧）、样本表索引对不上（拖进度条卡死）。
 *
 * <p>media3 的 Mp4Muxer 会按 PTS 正确生成 ctts，且默认开启
 * {@code AnnexBToAvccConverter}，能把 ts 里的 AnnexB 样本自动转成 AVCC。
 */
public final class Mp4Remuxer {

    /** 单样本上限，正常 1080p 关键帧远小于此值 */
    private static final int BUF_SIZE = 4 * 1024 * 1024;
    /** 循环保险，防止 extractor 到末尾后 position 不动导致死循环 */
    private static final int MAX_SAMPLES = 8_000_000;

    private Mp4Remuxer() {
    }

    /**
     * 把 ts 转封装成 mp4。
     *
     * @return true 表示封装成功（调用方仍需自行校验产物）
     */
    public static boolean remux(File ts, File mp4) {
        if (ts == null || !ts.exists() || ts.length() <= 0) return false;
        MediaExtractor extractor = new MediaExtractor();
        FileOutputStream fos = null;
        Mp4Muxer muxer = null;
        try {
            extractor.setDataSource(ts.getAbsolutePath());

            fos = new FileOutputStream(mp4);
            muxer = new Mp4Muxer.Builder(fos)
                    // 拷贝模式：muxer 不持有我们的 ByteBuffer，可以安全复用同一块内存
                    .setSampleCopyingEnabled(true)
                    .setSampleBatchingEnabled(false)
                    // 非流式输出：把 moov 写在文件末尾，兼容性最好
                    .setAttemptStreamableOutputEnabled(false)
                    .build();

            List<Integer> srcIndex = new ArrayList<>();
            List<Integer> dstId = new ArrayList<>();
            boolean hasVideo = false;
            boolean hasAudio = false;
            int trackCount = extractor.getTrackCount();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat mf = extractor.getTrackFormat(i);
                if (mf == null) continue;
                String mime = mf.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                boolean video = mime.startsWith("video/");
                boolean audio = mime.startsWith("audio/");
                if (!video && !audio) continue;
                // 拼接的 ts 每段都带自己的 PMT，extractor 可能报出重复轨道，
                // 只保留第一条视频和第一条音频
                if (video && hasVideo) continue;
                if (audio && hasAudio) continue;
                Format format = toFormat(mf, video);
                if (format == null) continue;
                int id = muxer.addTrack(format);
                srcIndex.add(i);
                dstId.add(id);
                if (video) hasVideo = true;
                if (audio) hasAudio = true;
            }
            if (srcIndex.isEmpty()) {
                LOG.i("remux: 没有可用轨道");
                return false;
            }

            ByteBuffer buf = ByteBuffer.allocateDirect(BUF_SIZE);
            for (int t = 0; t < srcIndex.size(); t++) {
                extractor.selectTrack(srcIndex.get(t));
                int count = 0;
                int guard = 0;
                while (guard++ < MAX_SAMPLES) {
                    buf.clear();
                    int size = extractor.readSampleData(buf, 0);
                    if (size < 0) break;
                    long pts = extractor.getSampleTime();
                    int flags = 0;
                    if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        flags |= C.BUFFER_FLAG_KEY_FRAME;
                    }
                    buf.position(0);
                    buf.limit(size);
                    muxer.writeSampleData(dstId.get(t), buf, new BufferInfo(pts, size, flags));
                    count++;
                    if (!extractor.advance()) break;
                }
                LOG.i("remux 轨道" + srcIndex.get(t) + " 样本数=" + count);
                if (count <= 0) {
                    LOG.i("remux: 轨道无样本，放弃");
                    return false;
                }
            }

            muxer.close();
            muxer = null;
            return true;
        } catch (Throwable e) {
            LOG.e(e);
            return false;
        } finally {
            if (muxer != null) {
                try {
                    muxer.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** android.media.MediaFormat → media3 Format */
    private static Format toFormat(MediaFormat mf, boolean video) {
        try {
            String mime = mf.getString(MediaFormat.KEY_MIME);
            Format.Builder b = new Format.Builder().setSampleMimeType(mime);
            if (video) {
                if (mf.containsKey(MediaFormat.KEY_WIDTH)) {
                    b.setWidth(mf.getInteger(MediaFormat.KEY_WIDTH));
                }
                if (mf.containsKey(MediaFormat.KEY_HEIGHT)) {
                    b.setHeight(mf.getInteger(MediaFormat.KEY_HEIGHT));
                }
                if (mf.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    try {
                        b.setFrameRate(mf.getInteger(MediaFormat.KEY_FRAME_RATE));
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                if (mf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    b.setSampleRate(mf.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                }
                if (mf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    b.setChannelCount(mf.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                }
            }
            List<byte[]> init = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String key = "csd-" + i;
                if (!mf.containsKey(key)) continue;
                ByteBuffer bb = mf.getByteBuffer(key);
                if (bb == null) continue;
                ByteBuffer dup = bb.duplicate();
                byte[] arr = new byte[dup.remaining()];
                dup.get(arr);
                if (arr.length > 0) init.add(arr);
            }
            if (!init.isEmpty()) b.setInitializationData(init);
            return b.build();
        } catch (Throwable e) {
            LOG.e(e);
            return null;
        }
    }
}
