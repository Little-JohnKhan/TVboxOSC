# patch12：修 media3 依赖冲突（Duplicate class）

## 报错原因

```
> Task :app:checkDebugDuplicateClasses FAILED
Duplicate class androidx.media3.common.AdOverlayInfo found in modules
  lib-common-release.aar -> jetified-lib-common-release-runtime
  and media3-common-1.8.0.aar -> jetified-media3-common-1.8.0-runtime
```

`engine/core/libs/` 里本来就有一整套 media3 的本地 aar：

```
lib-common-release.aar        = media3-common    1.11.0
lib-container-release.aar     = media3-container 1.11.0
lib-exoplayer-release.aar     = media3-exoplayer
lib-extractor-release.aar     = media3-extractor
...
```

我在 patch11 里加的 `media3-muxer:1.8.0` 会把 Maven 上的 `media3-common:1.8.0` 一起拉进来，
于是 `androidx.media3.common.*` 就有了两份 —— 一份来自本地 aar（1.11.0），一份来自 Maven（1.8.0）。

## 改法

只改 `app/build.gradle` 一个文件。

### 1. muxer 版本对齐到 1.11.0，并排掉会和本地 aar 撞车的两个模块

```gradle
implementation("androidx.media3:media3-muxer:1.11.0") {
    exclude group: 'androidx.media3', module: 'media3-common'
    exclude group: 'androidx.media3', module: 'media3-container'
}
```

- 版本必须用 **1.11.0**，和本地 aar 完全一致（我读 `MediaLibraryInfo.class` 确认过是 1.11.0）
- 排掉的两个模块由本地 aar 提供，不会缺类
- `Mp4Muxer` 的 API 在 1.8.0 和 1.11.0 之间没有变化，`Mp4Remuxer.java` 一行都不用改

### 2. 补 Guava

```gradle
implementation 'com.google.guava:guava:33.0.0-android'
```

`Mp4Muxer` 内部用了 `com.google.common.collect.ImmutableList` 和 `com.google.common.base.Supplier`，
而本地 `lib-common-release.aar` 里只有 `androidx/media3/common/`，**不含 Guava**，必须单独引。

### 3. 排掉 listenablefuture

```gradle
configurations.configureEach {
    ...
    exclude group: 'com.google.guava', module: 'listenablefuture'
}
```

`listenablefuture` 是个空实现 jar，和 guava 里的 `com.google.common.util.concurrent.ListenableFuture`
同名。很多项目同时存在这两个，是 Android 上的经典 Duplicate class 来源。

## 覆盖的文件

```
app/build.gradle
```

其他文件不动，patch11 的内容继续用。

## 验证依据

| 项目 | 值 | 怎么确认的 |
|---|---|---|
| 本地 media3-common 版本 | 1.11.0 | 读 `lib-common-release.aar` 里 `MediaLibraryInfo.class` 的版本串 |
| 本地 aar 是否含 Guava | 不含 | 解包后 `cls/com/` 目录为空，只有 `androidx/media3/common/` |
| muxer 1.11.0 API | 与 1.8.0 一致 | 反编译对比 `Build`/`addTrack`/`writeSampleData`/`close` |
| muxer 是否依赖 Guava | 是，25 个类引用 | 反编译统计 `com/google/common` 引用 |

## 如果这次还报错

两种可能，都会在报错里写明，直接发我：

1. **`Duplicate class com.google.common...`** → 说明某个本地 aar 里也打了 Guava，我改成从 muxer 依赖里再排掉 guava、换用别的实现
2. **`Could not find com.google.guava:guava:33.0.0-android`** → 换 `32.1.3-android`

如果 media3 这条路成本还是太高，我还有兜底方案：自己写一个精简 MP4 封装器（处理 stts/ctts/stsc/stsz/stco/stss + avcC/esds），
零依赖零冲突，约 500 行，我可以在沙箱用 ffmpeg 逐项验证 box 结构再交给你。
