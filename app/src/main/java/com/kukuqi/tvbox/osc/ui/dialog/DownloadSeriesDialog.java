package com.kukuqi.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ToastUtils;
import com.kukuqi.tvbox.osc.R;
import com.kukuqi.tvbox.osc.bean.VodInfo;
import com.kukuqi.tvbox.osc.service.DownloadService;
import com.kukuqi.tvbox.osc.ui.adapter.SeriesAdapter;
import com.kukuqi.tvbox.osc.ui.widget.GridSpacingItemDecoration;
import com.kukuqi.tvbox.osc.util.Utils;
import com.lxj.xpopup.core.BottomPopupView;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;

/**
 * 下载面板：底部半遮挡弹窗，列出全部集数，点哪一集就加入下载队列。
 */
public class DownloadSeriesDialog extends BottomPopupView {

    private final String sourceKey;
    private final VodInfo vodInfo;
    private final List<VodInfo.VodSeries> list;

    public DownloadSeriesDialog(@NonNull @NotNull Context context, String sourceKey, VodInfo vodInfo) {
        super(context);
        this.sourceKey = sourceKey;
        this.vodInfo = vodInfo;
        this.list = vodInfo == null ? null : vodInfo.seriesMap.get(vodInfo.playFlag);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_download_series;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        View openDir = findViewById(R.id.tvOpenDir);
        if (openDir != null) openDir.setOnClickListener(v -> openDownloadDir());

        if (list == null || list.isEmpty()) {
            ToastUtils.showShort("当前线路没有剧集");
            dismiss();
            return;
        }

        RecyclerView rv = findViewById(R.id.rv);
        if (rv == null) return;

        int span = Utils.getSeriesSpanCount(list);
        rv.setLayoutManager(new GridLayoutManager(getContext(), span));
        rv.addItemDecoration(new GridSpacingItemDecoration(span, 20, true));

        SeriesAdapter adapter = new SeriesAdapter(true);
        adapter.setNewData(list);
        rv.setAdapter(adapter);

        rv.postDelayed(() -> {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).selected) {
                    rv.smoothScrollToPosition(i);
                    break;
                }
            }
        }, 300);

        adapter.setOnItemClickListener((a, view, position) -> {
            VodInfo.VodSeries series = list.get(position);
            DownloadService.Task task = new DownloadService.Task();
            task.sourceKey = sourceKey;
            task.playFlag = vodInfo.playFlag;
            task.seriesUrl = series.url;
            task.vodName = vodInfo.name;
            task.seriesName = series.name;
            DownloadService.enqueue(getContext(), task);
            ToastUtils.showShort("已加入下载队列：" + series.name);
            dismiss();
        });
    }

    /** 用文件管理器打开下载目录 */
    private void openDownloadDir() {
        File dir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS + File.separator + "TVBox");
        if (!dir.exists()) dir.mkdirs();
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse("file://" + dir.getAbsolutePath()), "resource/folder");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            ToastUtils.showShort("目录：" + dir.getAbsolutePath());
        }
    }
}
