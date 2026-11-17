package com.kukuqi.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ConvertUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.kukuqi.tvbox.osc.R;
import com.kukuqi.tvbox.osc.bean.VodInfo;
import com.lihang.ShadowLayout;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */
public class SeriesAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {

    /** 每集右侧的下载按钮回调 */
    public interface OnDownloadClickListener {
        void onDownload(VodInfo.VodSeries series);
    }

    private boolean isGird;
    private OnDownloadClickListener downloadClickListener;

    public void setOnDownloadClickListener(OnDownloadClickListener listener) {
        this.downloadClickListener = listener;
    }

    public SeriesAdapter(boolean isGird) {
        super(R.layout.item_series, new ArrayList<>());
        this.isGird = isGird;
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
        ShadowLayout sl = helper.getView(R.id.sl);
        TextView tvSeries = helper.getView(R.id.tvSeries);
        sl.setSelected(item.selected);
        tvSeries.setText(item.name);

        View ivDownload = helper.getView(R.id.ivDownload);
        if (ivDownload != null) {
            ivDownload.setVisibility(downloadClickListener == null ? View.GONE : View.VISIBLE);
            ivDownload.setOnClickListener(v -> {
                if (downloadClickListener != null) downloadClickListener.onDownload(item);
            });
        }

        View root = helper.getView(R.id.itemRoot);
        if (!isGird) {// 详情页横向展示时固定宽度
            if (root != null) {
                ViewGroup.LayoutParams rp = root.getLayoutParams();
                rp.width = ConvertUtils.dp2px(120);
                root.setLayoutParams(rp);
            }
            ViewGroup.LayoutParams layoutParams = sl.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            sl.setLayoutParams(layoutParams);
        } else {
            if (root != null) {
                ViewGroup.LayoutParams rp = root.getLayoutParams();
                rp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                root.setLayoutParams(rp);
            }
        }
    }

    public void setGird(boolean gird) {
        isGird = gird;
    }

}