package com.kukuqi.tvbox.osc.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * m3u8 清单净化：剔除夹在正片里的广告分片。
 *
 * <p>纯字符串 + 时长分析，不依赖任何 Android API，可以用 javac 单独编译做单元测试。
 *
 * <p>判定顺序（命中即停，不重复判断）：
 * <ol>
 *   <li><b>源配置 rules</b>：hosts 匹配当前清单域名，用源方实测的正则剔除；</li>
 *   <li><b>块结构异常</b>：按 #EXT-X-DISCONTINUITY 切块，片数/时长显著小于中位数的块判为广告
 *       （应对分片名为随机 hash、看不出目录规律的源）；</li>
 *   <li><b>目录多数派</b>：按「目录 + 命名规律」分组投票，占比 ≥60% 的组视为正片；</li>
 *   <li><b>保险丝</b>：删除量过大或剩余分片过少时整份放行。</li>
 * </ol>
 */
public final class M3u8Purifier {

    /** 正片组占比低于这个值就认为分不清，转后备规则 */
    private static final double DOMINANT_RATIO = 0.60;
    /** 启发式判定时，删除时长超过总时长这个比例就整份放行 */
    private static final double MAX_DROP_RATIO = 0.20;
    /** 规则命中时放宽到这个比例（源方规则可能确实要删很多） */
    private static final double MAX_DROP_RATIO_BY_RULE = 0.50;
    /** 净化后至少要保留的分片数 */
    private static final int MIN_KEEP = 3;

    /** 块结构判定：块片数不超过中位数的这个比例 */
    private static final double BLOCK_COUNT_RATIO = 0.60;
    /** 块结构判定：块时长上限（秒） */
    private static final double BLOCK_MAX_SECONDS = 35;
    /** 块结构判定：至少要有这么多块才敢下结论 */
    private static final int BLOCK_MIN_COUNT = 3;

    private static final String[] SUSPICIOUS_TLDS = {
            ".vip", ".bet", ".top", ".xyz", ".cyou", ".icu", ".club"
    };

    private static final Pattern P_EXTINF = Pattern.compile("#EXTINF:\\s*([\\d.]+)");
    private static final Pattern P_EXT = Pattern.compile("\\.\\w+$");
    private static final Pattern P_TAIL_NUM = Pattern.compile("\\d+$");
    private static final Pattern P_BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern P_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");
    private static final Pattern P_PURE_NUM = Pattern.compile("^\\d+(\\.\\d+)?$");

    /** 由配置解析出来的广告规则，全进程共享 */
    private static volatile List<AdRule> sRules = Collections.emptyList();

    private M3u8Purifier() {
    }

    /** 安装规则（配置加载时调用） */
    public static void setRules(List<AdRule> rules) {
        sRules = rules == null ? Collections.<AdRule>emptyList() : rules;
    }

    public static List<AdRule> getRules() {
        return sRules;
    }

    /** 一条源方广告规则 */
    public static final class AdRule {
        public final String name;
        public final List<String> hosts;
        public final List<String> regex;
        /** 只保留能作用于 m3u8 的表达式 */
        public final List<String> m3u8Regex;

        public AdRule(String name, List<String> hosts, List<String> regex) {
            this.name = name == null ? "" : name;
            this.hosts = hosts == null ? Collections.<String>emptyList() : hosts;
            this.regex = regex == null ? Collections.<String>emptyList() : regex;
            List<String> keep = new ArrayList<>();
            for (String r : this.regex) {
                if (isM3u8Rule(r)) keep.add(r.trim());
            }
            this.m3u8Regex = keep;
        }

        /** 是否含有可作用于 m3u8 的表达式 */
        public boolean usable() {
            return !hosts.isEmpty() && !m3u8Regex.isEmpty();
        }

        /**
         * 过滤出 m3u8 相关的表达式：
         * 纯时长数字（15.1666）、含 #EXTINF / #EXT-X / .ts 的片段。
         * 排除嗅探特征（item_id=、is_play_url=）与网页脚本。
         */
        private static boolean isM3u8Rule(String r) {
            if (r == null) return false;
            String s = r.trim();
            if (s.isEmpty()) return false;
            if (P_PURE_NUM.matcher(s).matches()) return true;
            if (s.contains("#EXT")) return true;
            if (s.contains(".ts")) return true;
            return false;
        }
    }

    /** 净化报告，便于日志/调试输出 */
    public static final class Report {
        public int total;
        public int dropped;
        public String main = "";
        public double ratio;
        public String reason = "";
        public String rule = "";
        public final Map<String, Integer> groups = new LinkedHashMap<>();

        @Override
        public String toString() {
            return "total=" + total + " dropped=" + dropped
                    + (rule.isEmpty() ? "" : " rule=" + rule)
                    + " reason=" + reason
                    + " groups=" + groups;
        }
    }

    private static final class Seg {
        int line;          // 分片 URL 所在行号
        int extinf = -1;   // 它上面最近的 #EXTINF 行号
        String url;        // 绝对地址
        double dur;        // 时长（秒），未知为 0
    }

    private static final class Block {
        int from;          // 起始行（含）
        int to;            // 结束行（不含）
        int count;
        double dur;
        double firstDur;
        final List<Seg> segs = new ArrayList<>();
    }

    // ------------------------------------------------------------------ 主入口

    /**
     * 净化主入口。
     *
     * @return 净化后的文本；返回 null 表示「无需净化或无法判断，请按原样播放」
     */
    public static String purify(String content, String baseUrl, Report report) {
        if (content == null) return null;
        String body = stripBom(content);
        if (!body.trim().startsWith("#EXTM3U")) return null;

        String nl = body.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = body.split(nl, -1);

        List<Seg> segs = collectSegs(lines, baseUrl);
        if (segs.size() < MIN_KEEP) return null;

        Report rep = report != null ? report : new Report();
        rep.total = segs.size();

        // ---- ① 源配置规则
        AdRule rule = matchRule(baseUrl, segs);
        Set<Integer> kill = new HashSet<>();
        Map<Integer, Seg> byLine = new HashMap<>();
        for (Seg s : segs) byLine.put(s.line, s);

        if (rule != null) {
            boolean ok = applyRule(rule, body, lines, segs, byLine, kill);
            if (ok && !kill.isEmpty()) {
                rep.rule = rule.name;
                rep.reason = "源规则";
                if (acceptWithFuse(lines, segs, byLine, kill, rep, MAX_DROP_RATIO_BY_RULE)) {
                    return build(lines, segs, byLine, kill, baseUrl, nl, rep);
                }
                kill.clear();
                rep.reason = "源规则删除量过大已放行";
                return null;
            }
            kill.clear();
        }

        // ---- ② 块结构异常
        kill = detectByBlocks(lines, segs, byLine);
        if (!kill.isEmpty()) {
            rep.reason = "块结构异常（片数/时长显著偏小）";
            if (acceptWithFuse(lines, segs, byLine, kill, rep, MAX_DROP_RATIO)) {
                return build(lines, segs, byLine, kill, baseUrl, nl, rep);
            }
            rep.reason += "，删除量过大已放行";
            return null;
        }

        // ---- ③ 目录多数派
        kill = detectByMajority(segs, rep);
        if (!kill.isEmpty()) {
            rep.reason = "目录多数派（" + String.format(Locale.US, "%.0f%%", rep.ratio * 100) + "）";
            if (acceptWithFuse(lines, segs, byLine, kill, rep, MAX_DROP_RATIO)) {
                return build(lines, segs, byLine, kill, baseUrl, nl, rep);
            }
            rep.reason += "，删除量过大已放行";
            return null;
        }

        if (rep.reason.isEmpty()) rep.reason = "未发现可识别特征";
        return null;
    }

    // ------------------------------------------------------------------ 分片收集

    private static List<Seg> collectSegs(String[] lines, String baseUrl) {
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
        return segs;
    }

    // ------------------------------------------------------------------ ① 规则

    /** 找到 hosts 匹配当前清单的规则 */
    private static AdRule matchRule(String baseUrl, List<Seg> segs) {
        List<AdRule> rules = sRules;
        if (rules.isEmpty()) return null;
        String url = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.US);
        if (url.isEmpty() && !segs.isEmpty()) url = segs.get(0).url.toLowerCase(Locale.US);
        for (AdRule r : rules) {
            if (!r.usable()) continue;
            for (String host : r.hosts) {
                if (host == null || host.isEmpty()) continue;
                if (url.contains(host.toLowerCase(Locale.US))) return r;
            }
        }
        return null;
    }

    /**
     * 应用规则，把要删的行写进 kill。
     *
     * @return 规则是否至少命中了一处
     */
    private static boolean applyRule(AdRule rule, String body, String[] lines, List<Seg> segs,
                                     Map<Integer, Seg> byLine, Set<Integer> kill) {
        // 行首偏移，用于把正则匹配到的字符区间换算成行号
        int[] lineStart = new int[lines.length];
        int off = 0;
        String nl = body.contains("\r\n") ? "\r\n" : "\n";
        for (int i = 0; i < lines.length; i++) {
            lineStart[i] = off;
            off += lines[i].length() + nl.length();
        }

        boolean hit = false;
        for (String r : rule.m3u8Regex) {
            // 纯时长：删掉 EXTINF 为该值的分片
            if (P_PURE_NUM.matcher(r).matches()) {
                double target;
                try {
                    target = Double.parseDouble(r);
                } catch (NumberFormatException e) {
                    continue;
                }
                for (Seg s : segs) {
                    if (s.dur > 0 && Math.abs(s.dur - target) < 0.005) {
                        mark(kill, byLine, s);
                        hit = true;
                    }
                }
                continue;
            }
            // 其他：按正则匹配整份文本，命中的行全部标记删除
            try {
                Pattern p = Pattern.compile(r, Pattern.MULTILINE);
                Matcher m = p.matcher(body);
                while (m.find()) {
                    if (m.end() <= m.start()) break;
                    int first = lineOf(lineStart, m.start(), lines.length);
                    int last = lineOf(lineStart, Math.max(m.start(), m.end() - 1), lines.length);
                    for (int i = first; i <= last; i++) {
                        Seg s = byLine.get(i);
                        if (s != null) {
                            mark(kill, byLine, s);
                            hit = true;
                        } else {
                            kill.add(i);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return hit;
    }

    /** 标记删除一个分片：分片行 + 它上面的 EXTINF */
    private static void mark(Set<Integer> kill, Map<Integer, Seg> byLine, Seg seg) {
        kill.add(seg.line);
        if (seg.extinf >= 0) kill.add(seg.extinf);
    }

    private static int lineOf(int[] lineStart, int offset, int lineCount) {
        if (offset <= 0) return 0;
        int lo = 0, hi = lineCount - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStart[mid] <= offset) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    // ------------------------------------------------------------------ ② 块结构

    /**
     * 按 #EXT-X-DISCONTINUITY 切块，找出「片数 / 时长显著小于中位数」的块。
     *
     * <p>适用场景：分片名是随机 hash（如 dytt-hot），目录投票失效，
     * 但广告是单独转码的一段，块片数（2~3 片）和时长（4~20 秒）明显小于正片块（5 片 / 20 秒以上）。
     */
    private static Set<Integer> detectByBlocks(String[] lines, List<Seg> segs, Map<Integer, Seg> byLine) {
        Set<Integer> kill = new HashSet<>();
        List<Integer> breaks = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("#EXT-X-DISCONTINUITY")) breaks.add(i);
        }
        if (breaks.isEmpty()) return kill;

        List<Block> blocks = new ArrayList<>();
        int cursor = 0;
        for (int b = 0; b <= breaks.size(); b++) {
            int from = cursor;
            int to = b < breaks.size() ? breaks.get(b) : lines.length;
            cursor = to + 1;
            Block block = new Block();
            block.from = from;
            block.to = to;
            for (Seg s : segs) {
                if (s.line >= from && s.line < to) {
                    block.segs.add(s);
                    block.count++;
                    block.dur += s.dur;
                    if (block.segs.size() == 1) block.firstDur = s.dur;
                }
            }
            if (block.count > 0) blocks.add(block);
        }
        if (blocks.size() < BLOCK_MIN_COUNT) return kill;

        List<Integer> counts = new ArrayList<>();
        List<Double> durs = new ArrayList<>();
        for (Block b : blocks) {
            counts.add(b.count);
            durs.add(b.dur);
        }
        double medCount = medianInt(counts);
        double medDur = medianDouble(durs);

        // 片数阈值：中位数的 60%，但至少给 3 片（防止整片都是 2 片的极端情况被全删）
        double countLimit = Math.max(3.0, medCount * BLOCK_COUNT_RATIO);
        double durLimit = Math.min(BLOCK_MAX_SECONDS, medDur);

        Set<Long> candidates = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            boolean smallCount = b.count <= countLimit;
            boolean smallDur = b.dur > 0 && b.dur <= durLimit;
            if (smallCount && smallDur) candidates.add((long) i);
        }
        if (candidates.isEmpty()) return kill;

        // 候选必须能连成段（单块也算），且总时长不超过全片 20%
        double totalDur = 0;
        for (Seg s : segs) totalDur += s.dur;
        double candDur = 0;
        List<Block> selected = new ArrayList<>();
        for (Long idx : candidates) {
            Block b = blocks.get(idx.intValue());
            selected.add(b);
            candDur += b.dur;
        }
        if (totalDur > 0 && candDur / totalDur > MAX_DROP_RATIO) return new HashSet<Integer>();

        for (Block b : selected) {
            for (Seg s : b.segs) mark(kill, byLine, s);
        }
        return kill;
    }

    private static double medianInt(List<Integer> v) {
        if (v.isEmpty()) return 0;
        List<Integer> c = new ArrayList<>(v);
        Collections.sort(c);
        int n = c.size();
        return n % 2 == 1 ? c.get(n / 2) : (c.get(n / 2 - 1) + c.get(n / 2)) / 2.0;
    }

    private static double medianDouble(List<Double> v) {
        if (v.isEmpty()) return 0;
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        int n = c.size();
        return n % 2 == 1 ? c.get(n / 2) : (c.get(n / 2 - 1) + c.get(n / 2)) / 2.0;
    }

    // ------------------------------------------------------------------ ③ 多数派

    private static Set<Integer> detectByMajority(List<Seg> segs, Report rep) {
        Set<Integer> kill = new HashSet<>();
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
        rep.main = main == null ? "" : main;
        rep.ratio = ratio;
        rep.groups.putAll(counter);

        if (counter.size() <= 1 || ratio < DOMINANT_RATIO) return kill;
        for (Seg seg : segs) {
            if (!groupKey(seg.url).equals(main)) {
                kill.add(seg.line);
                if (seg.extinf >= 0) kill.add(seg.extinf);
            }
        }
        return kill;
    }

    // ------------------------------------------------------------------ 保险丝 + 生成

    private static boolean acceptWithFuse(String[] lines, List<Seg> segs, Map<Integer, Seg> byLine,
                                          Set<Integer> kill, Report rep, double maxDropRatio) {
        int dropCount = 0;
        double totalDur = 0, dropDur = 0;
        for (Seg s : segs) {
            totalDur += s.dur;
            if (kill.contains(s.line)) {
                dropCount++;
                dropDur += s.dur;
            }
        }
        if (dropCount == 0) return false;
        if (segs.size() - dropCount < MIN_KEEP) return false;
        if (totalDur > 0 && dropDur / totalDur > maxDropRatio) return false;
        // 片数保险丝：一次删掉一半以上，多半是判错了
        if (dropCount * 2 >= segs.size()) return false;
        rep.dropped = dropCount;
        return true;
    }

    private static String build(String[] lines, List<Seg> segs, Map<Integer, Seg> byLine,
                                Set<Integer> kill, String baseUrl, String nl, Report rep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (kill.contains(i)) continue;
            String s = lines[i].trim();
            if (!s.isEmpty() && s.charAt(0) != '#') {
                sb.append(resolve(baseUrl, s));
            } else {
                sb.append(lines[i]);
            }
            if (i < lines.length - 1) sb.append(nl);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 主清单 / 工具

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

    /** 剥掉 BOM / 零宽字符后判断是不是 m3u8。源站经常带 \uFEFF，直接用 startsWith 会漏判。 */
    public static boolean looksLikeM3u8(String content) {
        return content != null && stripBom(content).trim().startsWith("#EXTM3U");
    }

    /** 去掉 UTF-8 BOM 与零宽字符 */
    public static String stripBom(String s) {
        if (s == null || s.isEmpty()) return "";
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '\uFEFF' || s.charAt(i) == '\u200B' || s.charAt(i) == '\u0000')) i++;
        return i == 0 ? s : s.substring(i);
    }

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
            int end = u.indexOf('/', u.indexOf("://") + 3);
            String host = end > 0 ? u.substring(0, end) : u;
            if (host.endsWith(tld)) return true;
        }
        return false;
    }

    /** 手写 URL 拼接：java.net.URL 对不规范路径容易抛异常 */
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
