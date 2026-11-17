# patch08：修复「mp4 直链被当清单读」导致的 OOM

## 改了什么

只改一个文件：`app/src/main/java/com/kukuqi/tvbox/osc/util/M3u8Downloader.java`

### 1. 三路类型预判，不再无脑当 m3u8
下载前先判断地址到底是什么：

| 顺序 | 判据 | 处理 |
|---|---|---|
| ① | URL 路径以 `.mp4/.mkv/.flv/.avi/.mov/.m4v/.webm/.rmvb/.wmv` 结尾 | 直接当文件下 |
| ② | query 里带 `attachment` / `filename=` / `response-content-disposition` 且含视频后缀（libvio、S3 签名直链就是这种） | 直接当文件下 |
| ③ | 按文本读，**最多读 2 MB** 就停 | 像 m3u8 才走分片流程；读超限或读失败 → 回退当文件下 |

原来用 `body.string()` 无上限读取，遇到 150 MB 的 mp4 直接
`OutOfMemoryError: Failed to allocate a 156996944 byte allocation`，就是这个原因。

### 2. 新增直链文件下载
`downloadFile()`：流式写 `xxx.mp4.part` → 完成后重命名为 `xxx.mp4`，
按 Content-Length 报进度，失败重试 2 次。

### 3. 清单诊断信息
清单解析出 0 个分片时，错误消息里会带上
`行数=… 长度=… 内容=…`（前 160 字符），
下次遇到「清单里没有分片」能直接看出源到底返回了什么。

### 4. resolve 返回空保护
分片 URL 解析失败时跳过，不再往列表塞 null。

## 覆盖方式

```
app/src/main/java/com/kukuqi/tvbox/osc/util/M3u8Downloader.java
```

直接覆盖同名文件，其余文件不动。

## 预期效果

- **libvio**（cmecloud 签名 mp4）：不再 OOM，直接下载 mp4
- **tiktok 源**（`filename=1.mp4`）：不再报「不是有效的 m3u8」，直接下载
- **抖音 m3u8**：如果清单正常，正常走分片流程；如果还是报「没有分片」，错误消息里会打印源返回的真实内容，把它发我就能定位
- **需要解析器的源**（`/p/2090-2-1.html` 这种网页地址）：仍然跳过 —— 这是另一件事，见回复说明
