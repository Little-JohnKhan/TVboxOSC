# patch11：用 media3 的 Mp4Muxer 输出真正的 mp4

## 一句话

把 ts → mp4 的转封装从 `android.media.MediaMuxer` 换成 **media3 官方 `Mp4Muxer`**。
它支持 ctts（B 帧），这才是之前「无声 + 抽帧 + 拖进度条卡死」的根因。

## 改了什么

### 1. `app/build.gradle` 加一行依赖

```gradle
implementation "androidx.media3:media3-muxer:1.8.0"
```

放在 `implementation libs.media` 下面。media3-common 会由它自动传递引入。

### 2. 新增 `util/Mp4Remuxer.java`

用 `Mp4Muxer` 做转封装，几个关键设置：

| 设置 | 原因 |
|---|---|
| `setSampleCopyingEnabled(true)` | muxer 不持有我们的 ByteBuffer，可以安全复用同一块内存 |
| `setSampleBatchingEnabled(false)` | 立即落盘，避免缓冲语义歧义 |
| `setAttemptStreamableOutputEnabled(false)` | moov 写在文件末尾，兼容性最好 |

`AnnexBToAvccConverter` 默认启用，能自动把 ts 里的 AnnexB 样本转成 AVCC，
所以 `csd-0` 缺失也能工作。

另外保留了两条保险：
- 拼接 ts 会带重复 PMT，extractor 可能报出重复轨道 → 只保留第一条视频 + 第一条音频
- `advance()` 返回 false 就 break，并用 800 万样本上限兜底（之前卡 40 分钟那个死循环）

### 3. `M3u8Downloader` 接入

`REMUX_TO_MP4` 改为 `true`，转封装改调 `Mp4Remuxer.remux()`，
旧的手写 MediaMuxer 实现整段删除。

**失败兜底不变**：转封装失败、或产物过不了 `verifyMp4` 校验（无视频轨 / 读不出样本 / 时长异常），
就删掉 mp4、交付 `.ts`。不会再给你一个打不开的文件。

### 4. `PlayFragment` 修 base 地址 bug

原来：

```java
int ilast = url.lastIndexOf('/');
M3u8Purifier.purify(content, url.substring(0, ilast + 1), report);
```

播放地址如果是 TVBox 自己的本地转发地址
`http://127.0.0.1:9979/proxy?do=m3u8&url=...`，`lastIndexOf('/')` 会得到
`http://127.0.0.1:9979/`，于是相对分片被拼成 `http://127.0.0.1:9979/mEzM-00001.ts`，
根本下不到。现在统一走 `cleanBase()`，先把本地代理地址解包成真实源地址。

### 5. 净化未命中时 `/m3u8` 也有内容

之前 `purify()` 返回 null（没识别到广告）时，`m3u8Content` 就一直是 null，
浏览器打开 `http://127.0.0.1:9979/m3u8` 只看到几行 `#` 注释，看着像空文件。

现在未命中时会把**绝对化后的原清单**挂上去，外部下载器仍能拿到一份可直接用的清单
（这一点正是你测 1DM 时缺的那块）。

### 6. `RemoteServer` 支持多集清单

新增 `m3u8Map`，路由支持 `/m3u8?id=xxx`，不带 id 时退回最近一次结果。
为以后批量推 1DM 留好接口。

## 覆盖的文件

```
app/build.gradle
app/src/main/java/com/kukuqi/tvbox/osc/util/Mp4Remuxer.java        （新增）
app/src/main/java/com/kukuqi/tvbox/osc/util/M3u8Downloader.java
app/src/main/java/com/kukuqi/tvbox/osc/server/RemoteServer.java
app/src/main/java/com/kukuqi/tvbox/osc/ui/fragment/PlayFragment.java
```

## 编译注意

这次多了 media3 依赖，Gradle 会去 Google Maven 拉包。Actions 编译时间会比之前长一点。

如果编译报依赖解析失败，把报错发我，我换成本地 jar 方案。

## 装完怎么验

```
rm -rf "/storage/emulated/0/Download/TVBox/强风吹拂"
```

重新下一集，看：

| 情况 | 说明 |
|---|---|
| 产出 `.mp4`，有声音、不抽帧、进度条能拖 | 成了 |
| 产出 `.ts` | media3 也没转成，但文件可用；把 logcat 里 `remux` / `校验 mp4` 那几行发我 |

日志里会打印：

```
remux 轨道0 样本数=xxxx
remux 轨道1 样本数=xxxx
校验 mp4: 轨=2 时长=1364s 可读=true 结果=true
```
