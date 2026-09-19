package com.example.rca.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分析类工具：做模型系统性做不好的事（算术核对、参数抽取、时间排序）。
 */
public class AnalysisTools {

    private final Path caseRoot;

    public AnalysisTools(String caseRoot) {
        this.caseRoot = Paths.get(caseRoot).toAbsolutePath().normalize();
    }

    // ==================== 指标表核对 ====================

    @Tool(
            name = "analyze_metric_tables",
            description =
                    "核对 docx 里交易量/成功率类表格的数值自洽性，并与历史行做同比。"
                            + "会报出成功率与「成功量/总量」对不上的行、跨行重复的可疑数值，"
                            + "以及首行相对历史均值的跌幅。引用任何表格数字之前必须先调它。",
            readOnly = true)
    public String analyzeMetricTables(
            @ToolParam(name = "path", description = "docx 相对路径，例如 case01/交易量.docx") String path) {
        try {
            Path file = resolve(path);
            if (!Files.isRegularFile(file)) {
                return "错误：文件不存在 " + path;
            }
            StringBuilder out = new StringBuilder();
            try (InputStream in = Files.newInputStream(file);
                    XWPFDocument doc = new XWPFDocument(in)) {
                List<XWPFTable> tables = doc.getTables();
                if (tables.isEmpty()) {
                    return "该文档没有表格。";
                }
                for (int i = 0; i < tables.size(); i++) {
                    out.append("### 表格 ").append(i).append("\n");
                    out.append(analyzeOne(tables.get(i)));
                    out.append("\n");
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    /** 一个「总量/成功量/成功率」三元组构成一个指标组，一张表里可能有多组（合并表头）。 */
    private record Group(String label, int totalCol, int successCol, int rateCol) {}

    private String analyzeOne(XWPFTable table) {
        List<List<String>> grid = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell c : row.getTableCells()) {
                cells.add(c.getText() == null ? "" : c.getText().trim());
            }
            grid.add(cells);
        }
        if (grid.size() < 2) {
            return "表格行数不足，跳过。\n";
        }

        // 表头可能占两行（合并单元格），取最后一个含有「总交易量」的行作为表头行
        int headerRow = 0;
        for (int r = 0; r < Math.min(3, grid.size()); r++) {
            if (grid.get(r).stream().anyMatch(s -> s.contains("总交易量"))) {
                headerRow = r;
            }
        }
        List<String> header = grid.get(headerRow);

        List<Group> groups = new ArrayList<>();
        int total = -1, success = -1;
        for (int c = 0; c < header.size(); c++) {
            String h = header.get(c);
            if (h.contains("总交易量")) {
                total = c;
                success = -1;
            } else if (h.contains("成功交易量")) {
                success = c;
            } else if (h.contains("成功率") && total >= 0 && success >= 0) {
                String label = labelFor(grid, headerRow, c);
                groups.add(new Group(label, total, success, c));
                total = -1;
                success = -1;
            }
        }
        if (groups.isEmpty()) {
            return "未识别出「总交易量/成功交易量/成功率」列，跳过。表头：" + String.join(" | ", header) + "\n";
        }

        StringBuilder sb = new StringBuilder();
        for (Group g : groups) {
            sb.append("指标组：").append(g.label()).append("\n");
            List<String> issues = new ArrayList<>();
            List<Double> rates = new ArrayList<>();
            List<String> rowNames = new ArrayList<>();
            Map<Long, List<String>> successSeen = new HashMap<>();

            for (int r = headerRow + 1; r < grid.size(); r++) {
                List<String> row = grid.get(r);
                if (row.size() <= Math.max(g.rateCol(), g.successCol())) continue;
                String rowName = row.get(0);
                Long tot = parseLong(row.get(g.totalCol()));
                Long suc = parseLong(row.get(g.successCol()));
                Double stated = parsePercent(row.get(g.rateCol()));
                if (tot == null || suc == null || stated == null || tot == 0) continue;

                double computed = suc * 100.0 / tot;
                rowNames.add(rowName);
                rates.add(computed);
                successSeen.computeIfAbsent(suc, k -> new ArrayList<>()).add(rowName);

                sb.append(String.format(
                        "  %s: 总量=%d 成功=%d 文档成功率=%.2f%% 实算=%.2f%%%n",
                        rowName, tot, suc, stated, computed));

                if (Math.abs(computed - stated) > 0.05) {
                    issues.add(String.format(
                            "  [不一致] %s 的成功率写 %.2f%%，但 %d/%d=%.2f%%，差 %.2f 个百分点",
                            rowName, stated, suc, tot, computed, Math.abs(computed - stated)));
                }
            }

            for (Map.Entry<Long, List<String>> e : new TreeMap<>(successSeen).entrySet()) {
                if (e.getValue().size() > 1) {
                    issues.add(String.format(
                            "  [可疑重复] 成功交易量 %d 在多行出现：%s，疑似复制粘贴错误",
                            e.getKey(), String.join("、", e.getValue())));
                }
            }

            if (rates.size() >= 2) {
                double baseline = rates.subList(1, rates.size()).stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0);
                double cur = rates.get(0);
                sb.append(String.format(
                        "  同比：本期 %s = %.2f%%，历史 %d 期均值 = %.2f%%，跌幅 %.2f 个百分点%n",
                        rowNames.get(0), cur, rates.size() - 1, baseline, baseline - cur));
            }

            if (issues.isEmpty()) {
                sb.append("  数值自洽，无异常。\n");
            } else {
                sb.append("  ** 发现问题 **\n");
                issues.forEach(s -> sb.append(s).append("\n"));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 合并表头场景下，去上一行找这一列所属的分组名。 */
    private String labelFor(List<List<String>> grid, int headerRow, int col) {
        if (headerRow > 0) {
            List<String> up = grid.get(headerRow - 1);
            if (col < up.size() && !up.get(col).isEmpty()) {
                return up.get(col);
            }
        }
        return "默认";
    }

    // ==================== 异常/连接池参数抽取 ====================

    private static final Pattern DRUID =
            Pattern.compile(
                    "wait millis (\\d+), active (\\d+), maxActive (\\d+), creating (\\d+), createErrorCount (\\d+)");
    private static final Pattern EXCEPTION = Pattern.compile("([\\w.]+(?:Exception|Error))");
    private static final Pattern TS =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)");

    @Tool(
            name = "parse_exception",
            description =
                    "从日志文本里抽取异常链和数据库连接池参数（wait millis / active / maxActive / creating / createErrorCount）。"
                            + "看到 Java 异常堆栈时必须先调它拿到结构化参数，再去查对应 playbook 判读，不要直接凭印象下结论。",
            readOnly = true)
    public String parseException(
            @ToolParam(name = "text", description = "原始日志文本（可以是整段堆栈）") String text) {
        StringBuilder sb = new StringBuilder();

        Matcher ts = TS.matcher(text);
        if (ts.find()) {
            sb.append("日志时间: ").append(ts.group(1)).append("\n");
        }

        Set<String> chain = new LinkedHashSet<>();
        Matcher ex = EXCEPTION.matcher(text);
        while (ex.find()) {
            chain.add(ex.group(1));
        }
        if (!chain.isEmpty()) {
            sb.append("异常链: ").append(String.join(" -> ", chain)).append("\n");
            sb.append("根异常: ").append(new ArrayList<>(chain).get(chain.size() - 1)).append("\n");
        }

        Matcher d = DRUID.matcher(text);
        if (d.find()) {
            long waitMillis = Long.parseLong(d.group(1));
            long active = Long.parseLong(d.group(2));
            long maxActive = Long.parseLong(d.group(3));
            long creating = Long.parseLong(d.group(4));
            long createErrorCount = Long.parseLong(d.group(5));
            sb.append("\n[数据库连接池参数]\n");
            sb.append("  waitMillis=").append(waitMillis).append("\n");
            sb.append("  active=").append(active).append("\n");
            sb.append("  maxActive=").append(maxActive).append("\n");
            sb.append("  creating=").append(creating).append("\n");
            sb.append("  createErrorCount=").append(createErrorCount).append("\n");
            sb.append("  活跃占用率=")
                    .append(maxActive == 0 ? "n/a" : String.format("%.1f%%", active * 100.0 / maxActive))
                    .append("\n");
            sb.append(
                    "\n提示：这组参数的判读规则在 skill fault-playbooks 的"
                            + " references/db-connection-pool.md 里，先加载它再下结论。\n");
        }

        return sb.length() == 0 ? "未从文本中识别出异常或连接池参数。" : sb.toString();
    }

    // ==================== 告警解析与时间线 ====================

    @Tool(
            name = "parse_alerts",
            description =
                    "把告警文本解析成结构化条目并按时间升序排列，输出每条的时间、级别、系统、关键字、策略ID，"
                            + "以及相邻告警的时间间隔。排时间线前必须先调它，不要靠肉眼读告警文本。",
            readOnly = true)
    public String parseAlerts(
            @ToolParam(name = "text", description = "告警原文，可含多条") String text) {
        Pattern time = Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
        Pattern level = Pattern.compile("，(\\d)级，");
        Pattern system = Pattern.compile("\\[([^\\[\\]]{1,20}系统)\\]");
        Pattern keyword = Pattern.compile("关键字\\[\\s*([^\\]]+?)\\s*\\]");
        Pattern policy = Pattern.compile("策略ID[：:]\\s*(\\d+)");

        List<String[]> items = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher mt = time.matcher(line);
            if (!mt.find()) continue;

            String t = mt.group(1);
            String lv = find(level, line, "未标级别");
            String sys = find(system, line, "未标系统");
            String kw = find(keyword, line, "");
            String pid = find(policy, line, "");
            items.add(new String[] {t, lv, sys, kw, pid, line});
        }
        if (items.isEmpty()) {
            return "未解析出任何告警条目。";
        }
        items.sort((a, b) -> a[0].compareTo(b[0]));

        StringBuilder sb = new StringBuilder("按时间升序的告警条目：\n\n");
        for (int i = 0; i < items.size(); i++) {
            String[] it = items.get(i);
            sb.append(String.format("[%d] %s  %s级  %s%n", i + 1, it[0], it[1], it[2]));
            if (!it[3].isEmpty()) sb.append("     关键字: ").append(it[3]).append("\n");
            if (!it[4].isEmpty()) sb.append("     策略ID: ").append(it[4]).append("\n");
            if (i > 0) {
                sb.append("     距上一条: ").append(gapSeconds(items.get(i - 1)[0], it[0])).append("\n");
            }
        }
        sb.append(
                "\n提示：告警是滞后信号，最早告警的系统不一定是根因系统。"
                        + "定位前请加载 skill incident-timeline 的判定规则。\n");
        return sb.toString();
    }

    private static String find(Pattern p, String s, String dft) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : dft;
    }

    private static String gapSeconds(String a, String b) {
        try {
            var fa = java.time.LocalDateTime.parse(a.replace(' ', 'T'));
            var fb = java.time.LocalDateTime.parse(b.replace(' ', 'T'));
            long sec = java.time.Duration.between(fa, fb).getSeconds();
            return sec / 60 + " 分 " + sec % 60 + " 秒";
        } catch (Exception e) {
            return "n/a";
        }
    }

    // ==================== 工具方法 ====================

    private static Long parseLong(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[,，\\s]", "");
        return t.matches("\\d+") ? Long.parseLong(t) : null;
    }

    private static Double parsePercent(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("([\\d.]+)\\s*%").matcher(s);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private Path resolve(String relative) {
        Path p = caseRoot.resolve(relative).normalize();
        if (!p.startsWith(caseRoot)) {
            throw new IllegalArgumentException("路径越界: " + relative);
        }
        return p;
    }
}
