package com.kukuqi.tvbox.osc.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * m3u8 清单净化：剔除夹在正片里的广告分片。
 *
 * <p>纯字符串 + 时长分析，不依赖任何 Android API，可以用 javac 单独编译做单元测试。
 *
 * <p>判定顺序：
 * <ol>
 *   <li>分片 URL 分组投票：按「目录 + 命名规律」归一化成组，占比 ≥60% 的组视为正片，其余整段删除；</li>
 *   <li>后备：#EXT-X-DISCONTINUITY 切块，孤立短块（3~120 秒且少于 15 片）视为广告；</li>
 *   <li>兜底：命中可疑域名后缀（.vip/.bet/.top...）的分片删除；</li>
 *   <li>保险丝：要删的时长超过总时长 20%，或删完剩余分片少于 3 片，整份放行不做修改。</li>
 * </ol>
 */
public final class M3u8Purifier {

    /** 正片组占比低于这个值就认为分不清，转后备规则 */
    private static final double DOMINANT_RATIO = 0.60;
    /** 删除时长超过总时长这个比例就整份放行 */
    private static final double MAX_DROP_RATIO = 0.20;
    /** 净化后至少要保留的分片数 */
    private static final int MIN_KEEP = 3;

    private static final String[] SUSPICIOUS_TLDS = {
            ".vip", ".bet", ".top", ".xyz", ".cyou", ".icu", ".club"
    };

    private static final Pattern P_EXTINF = Pattern.compile("#EXTINF:\\s*([\\d.]+)");
    private static final Pattern P_EXT = Pattern.compile("\\.\\w+$");
    private static final Pattern P_TAIL_NUM = Pattern.compile("\\d+$");
    private static final Pattern P_BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern P_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");

    private M3u8Purifier() {
    }

    /** 净化报告，便于日志/调试输出 */
    public static final class Report {
        public int total;
        public int dropped;
        public String main = "";
        public double ratio;
        public String reason = "";
        public final Map<String, Integer> groups = new LinkedHashMap<>();

        @Override
        public String toString() {
            return "total=" + total + " dropped=" + dropped + " main=" + main
                    + " ratio=" + String.format(Locale.US, "%.2f", ratio)
                    + " reason=" + reason + " groups=" + groups;
        }
    }

    private static final class Seg {
        int line;          // 分片 URL 所在行号
        int extinf = -1;   // 它上面最近的 #EXTINF 行号
        String url;        // 绝对地址
        double dur;        // 时长（秒），未知为 0
    }

    /**
     * 净化主入口。
     *
     * @param content 原始 m3u8 文本
     * @param baseUrl 用于拼接相对路径的基准地址（一般是清单自身 URL）
     * @param report  可为 null，传入则回填统计信息
     * @return 净化后的文本；返回 null 表示「无需净化或无法判断，请按原样播放」
     */
    public static String purify(String content, String baseUrl, Report report) {
        if (content == null) return null;
        String text = content.trim();
        if (!text.startsWith("#EXTM3U")) return null;

        String nl = content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = content.split(nl, -1);

        // 1. 收集分片
        List<Seg> segs = new ArrayList<>();
        int lastExtinf = -1;
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.isEmpty()) continue;
            if (s.charAt(0) == '#') {
                if (s.startsWith("#EXTINF:")) lastExtinf = i;
                continue;
            }
            Seg seg = new Seg();
            seg.line = i;
            seg.extinf = lastExtinf;
            seg.url = resolve(baseUrl, s);
            seg.dur = durationOf(lastExtinf >= 0 ? lines[lastExtinf] : null);
            segs.add(seg);
            lastExtinf = -1;
        }
        if (segs.size() < MIN_KEEP) return null;

        // 2. 分组投票
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (Seg seg : segs) {
            String key = groupKey(seg.url);
            Integer c = counter.get(key);
            counter.put(key, c == null ? 1 : c + 1);
        }
        String main = null;
        int mainCount = 0;
        for (Map.Entry<String, Integer> e : counter.entrySet()) {
            if (e.getValue() > mainCount) {
                mainCount = e.getValue();
                main = e.getKey();
            }
        }
        double ratio = mainCount / (double) segs.size();

        Report rep = report != null ? report : new Report();
        rep.total = segs.size();
        rep.main = main == null ? "" : main;
        rep.ratio = ratio;
        rep.groups.putAll(counter);

        List<Integer> drop = new ArrayList<>();

        if (counter.size() > 1 && ratio >= DOMINANT_RATIO) {
            for (Seg seg : segs) {
                if (!groupKey(seg.url).equals(main)) drop.add(seg.line);
            }
            rep.reason = "多数派判定(占比 " + String.format(Locale.US, "%.0f%%", ratio * 100) + ")";
        } else {
            // 3. 后备：按 DISCONTINUITY 切块找孤立短块
            List<Integer> breaks = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().startsWith("#EXT-X-DISCONTINUITY")) breaks.add(i);
            }
            if (breaks.size() >= 2) {
                List<Integer> bounds = new ArrayList<>();
                bounds.add(0);
                bounds.addAll(breaks);
                bounds.add(lines.length);
                for (int b = 0; b < bounds.size() - 1; b++) {
                    int from = bounds.get(b), to = bounds.get(b + 1);
                    List<Seg> block = new ArrayList<>();
                    double dur = 0;
                    for (Seg seg : segs) {
                        if (seg.line >= from && seg.line < to) {
                            block.add(seg);
                            dur += seg.dur;
                        }
                    }
                    if (block.isEmpty()) continue;
                    if (dur >= 3 && dur <= 120 && block.size() < 15 && block.size() < segs.size()) {
                        for (Seg seg : block) drop.add(seg.line);
                    }
                }
                rep.reason = "DISCONTINUITY 切块启发式(" + breaks.size() + " 处断裂点)";
            } else {
                rep.reason = "分不清：广告与正片命名/目录相同";
                return null;
            }
        }

        // 4. 可疑域名兜底
        for (Seg seg : segs) {
            if (isSuspicious(seg.url) && !drop.contains(seg.line)) drop.add(seg.line);
        }

        if (drop.isEmpty()) {
            rep.reason += "，未发现可剔除分片";
            return null;
        }

        // 5. 保险丝
        double totalDur = 0, dropDur = 0;
        for (Seg seg : segs) {
            totalDur += seg.dur;
            if (drop.contains(seg.line)) dropDur += seg.dur;
        }
        if (totalDur > 0 && dropDur / totalDur > MAX_DROP_RATIO) {
            rep.reason += "，删除时长占比过高(" + String.format(Locale.US, "%.0f%%", dropDur / totalDur * 100) + ")已放行";
            return null;
        }
        // 片数保险丝：一次删掉一半以上分片，多半是把正片当广告了
        if (drop.size() * 2 >= segs.size()) {
            rep.reason += "，删除片数占比过高已放行";
            return null;
        }
        if (segs.size() - drop.size() < MIN_KEEP) {
            rep.reason += "，剩余分片过少已放行";
            return null;
        }

        // 6. 生成新清单：删分片行 + 对应 EXTINF，其余分片地址绝对化
        boolean[] kill = new boolean[lines.length];
        for (Seg seg : segs) {
            if (!drop.contains(seg.line)) continue;
            kill[seg.line] = true;
            if (seg.extinf >= 0) kill[seg.extinf] = true;
        }
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < lines.length; i++) {
            if (kill[i]) continue;
            String s = lines[i].trim();
            if (!s.isEmpty() && s.charAt(0) != '#') {
                sb.append(resolve(baseUrl, s));
            } else {
                sb.append(lines[i]);
            }
            if (i < lines.length - 1) sb.append(nl);
        }
        rep.dropped = drop.size();
        return sb.toString();
    }

    /**
     * 如果是多码率主清单，返回带宽最高的子清单地址；否则返回 null。
     */
    public static String pickVariant(String content, String baseUrl) {
        if (content == null) return null;
        String[] lines = content.split("\\r?\\n", -1);
        String bestUrl = null;
        int bestBw = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue;
            int bw = 0;
            Matcher m = P_BANDWIDTH.matcher(line);
            if (m.find()) {
                try {
                    bw = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            for (int j = i + 1; j < lines.length; j++) {
                String nxt = lines[j].trim();
                if (nxt.isEmpty()) continue;
                if (nxt.charAt(0) != '#') {
                    if (bw > bestBw) {
                        bestBw = bw;
                        bestUrl = resolve(baseUrl, nxt);
                    }
                    break;
                }
            }
        }
        return bestUrl;
    }

    // ---------------------------------------------------------------- 内部工具

    private static double durationOf(String extinfLine) {
        if (extinfLine == null) return 0;
        Matcher m = P_EXTINF.matcher(extinfLine);
        if (!m.find()) return 0;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 把分片 URL 归一化成「组」：
     * <pre>
     * https://cdn.a.com/v/00001.ts   -> cdn.a.com/v/
     * https://cdn.z.com/ad/x1.ts     -> cdn.z.com/ad/x
     * https://cdn.a.com/v/ad001.ts   -> cdn.a.com/v/ad
     * </pre>
     */
    static String groupKey(String url) {
        String u = url;
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        int h = u.indexOf("://");
        int slash;
        if (h >= 0) {
            slash = u.indexOf('/', h + 3);
        } else {
            slash = u.indexOf('/');
        }
        String host = h >= 0 ? u.substring(0, slash < 0 ? u.length() : slash) : "";
        String path = slash < 0 ? "" : u.substring(slash);
        int lastSlash = path.lastIndexOf('/');
        String dir = lastSlash < 0 ? "" : path.substring(0, lastSlash + 1);
        String name = lastSlash < 0 ? path : path.substring(lastSlash + 1);
        name = P_EXT.matcher(name).replaceAll("");
        name = P_TAIL_NUM.matcher(name).replaceAll("");
        return host + dir + name;
    }

    private static boolean isSuspicious(String url) {
        String u = url.toLowerCase(Locale.US);
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        for (String tld : SUSPICIOUS_TLDS) {
            if (u.endsWith(tld)) return true;
            // 域名以该后缀结尾（如 xxx.vip/path）
            int end = u.indexOf('/', u.indexOf("://") + 3);
            String host = end > 0 ? u.substring(0, end) : u;
            if (host.endsWith(tld)) return true;
        }
        return false;
    }

    /**
     * 手写 URL 拼接：java.net.URL 对不规范路径容易抛异常，这里按 RFC 3986 的常用子集处理。
     */
    public static String resolve(String base, String ref) {
        if (ref == null) return "";
        String r = ref.trim();
        if (r.isEmpty()) return r;
        if (P_SCHEME.matcher(r).find()) return r;

        if (base == null || base.isEmpty()) return r;
        String b = base.trim();
        Matcher sm = P_SCHEME.matcher(b);
        if (!sm.find()) return r;
        String scheme = sm.group();

        if (r.startsWith("//")) return scheme.substring(0, scheme.length() - 2) + r;

        int hostEnd = b.indexOf('/', scheme.length());
        String origin = hostEnd < 0 ? b : b.substring(0, hostEnd);

        if (r.startsWith("/")) return origin + r;

        int lastSlash = b.lastIndexOf('/');
        String dir = lastSlash > scheme.length() ? b.substring(0, lastSlash + 1) : origin + "/";
        return dir + r;
    }
}
