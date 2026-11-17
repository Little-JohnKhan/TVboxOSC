package com.kukuqi.tvbox.osc.ui.adapter;

import android.graphics.Color;
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
import java.util.Set;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */
public class SeriesAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {
    private boolean isGird;
    /** 已加入下载队列的分片地址，用于在集数后面打勾 */
    private Set<String> pickedUrls;

    public SeriesAdapter(boolean isGird) {
        super(R.layout.item_series, new ArrayList<>());
        this.isGird = isGird;
    }

    public void setPickedUrls(Set<String> urls) {
        this.pickedUrls = urls;
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
        ShadowLayout sl = helper.getView(R.id.sl);
        TextView tvSeries = helper.getView(R.id.tvSeries);
        sl.setSelected(item.selected);
        boolean picked = pickedUrls != null && item.url != null && pickedUrls.contains(item.url);
        tvSeries.setText(picked ? item.name + " ✓" : item.name);

        if (!isGird){// 详情页横向展示时固定宽度
            ViewGroup.LayoutParams layoutParams = sl.getLayoutParams();
            layoutParams.width = ConvertUtils.dp2px(120);
            sl.setLayoutParams(layoutParams);
        }
    }

    public void setGird(boolean gird) {
        isGird = gird;
    }

}