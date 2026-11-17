# patch13：修 `DIRECTORY_DOWNLOAD` 编译错误（并说明它引发的连锁问题）

## 报错

```
DownloadService.java:185: error: cannot find symbol
    Environment.DIRECTORY_DOWNLOAD + File.separator + ROOT + ...
    symbol: variable DIRECTORY_DOWNLOAD
    location: class Environment
```

正确的常量名是 `DIRECTORY_DOWNLOADS`，少一个 S。

## 为什么会这样（我的失误）

这个 bug 在 patch04fix 里修过。但我在做 patch09 时，从工作目录里拿基线文件拿错了一份
（那是 patch04 的未修复版本），于是把 `DIRECTORY_DOWNLOAD` 又带回了 patch09 的 zip 里。

## 更重要的连锁后果

`patch09` 因为这一行编译不过，**所以你从 patch09 开始就没有成功装过新包**，
一直在跑 patch07 的旧版。这意味着：

| 补丁 | 实际状态 |
|---|---|
| patch09（解本地 proxy 直连源站，提速 17 倍） | **从没生效过** |
| patch10（默认交付 ts，避开 MediaMuxer） | **从没生效过** |
| patch11（media3 转 mp4） | 编译没过 |
| patch12（依赖对齐） | 编译没过 |

所以你看到的现象——「28 分钟下一集」「84MB 的 mp4 无声抽帧」——全部是 **patch04/07 时代的老逻辑**：
走本地 proxy 中转（慢）、PTS 被错误 shift（坏文件）。这些在 patch09/patch10 里本来就修了，
只是包没装上。

## 这次改了什么

基于 **patch07 的正确版本**（`DIRECTORY_DOWNLOADS`、纯静态队列、不用前台服务）重新做，并加回两处：

1. **解析重试**：`CoreBridge.player()` 拿到空地址时重试 3 次（间隔 1.2s / 2.4s），
   解决「地址为空」直接放弃
2. **入队去重**：同一集的 seriesUrl 已在队列里就跳过，避免重复下

## 覆盖的文件

```
app/src/main/java/com/kukuqi/tvbox/osc/service/DownloadService.java
```

## 装完之后

这一版装上去，patch09 + patch10 + patch11 + patch12 的改动才是**第一次真正跑起来**。
所以预期变化会比之前大：

| 项目 | 之前（patch07） | 现在 |
|---|---|---|
| 下载速度 | 走本地 proxy，85MB 要 28 分钟 | 直连源站 4 并发，约 1~2 分钟 |
| 产物 | 半截 mp4，无声 + 抽帧 | 完整 mp4（media3 转封装）或完整 ts |
| 解析失败 | 直接放弃 | 重试 3 次 |
| 重复点选 | 重复排队 | 自动跳过 |

**记得先清掉旧文件：**

```
rm -rf "/storage/emulated/0/Download/TVBox/强风吹拂"
```
