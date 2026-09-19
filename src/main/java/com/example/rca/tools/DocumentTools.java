package com.example.rca.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 采集归一化类工具：把异构的故障材料统一成 markdown 交给模型。
 */
public class DocumentTools {

    private final Path caseRoot;

    public DocumentTools(String caseRoot) {
        this.caseRoot = Paths.get(caseRoot).toAbsolutePath().normalize();
    }

    @Tool(
            name = "list_case_files",
            description = "列出某个故障案例目录下的全部材料文件。分析任何案例前先调它，不要凭空猜文件名。",
            readOnly = true)
    public String listCaseFiles(
            @ToolParam(name = "case_id", description = "案例编号，例如 case01") String caseId) {
        try {
            Path dir = resolve(caseId);
            if (!Files.isDirectory(dir)) {
                return "错误：案例目录不存在 " + caseId;
            }
            List<String> lines = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                for (Path p : stream.sorted().collect(Collectors.toList())) {
                    if (Files.isRegularFile(p) && !p.getFileName().toString().startsWith(".")) {
                        lines.add(String.format(
                                "%s/%s  (%d bytes)",
                                caseId, p.getFileName(), Files.size(p)));
                    }
                }
            }
            return lines.isEmpty() ? "目录为空" : String.join("\n", lines);
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    @Tool(
            name = "read_document",
            description =
                    "读取一份故障材料并归一化为 markdown。支持 .docx（正文 + 表格）、.md、.txt、.log。"
                            + "路径相对案例根目录，例如 case01/交易量.docx。",
            readOnly = true)
    public String readDocument(
            @ToolParam(name = "path", description = "相对路径，例如 case01/log.md") String path) {
        try {
            Path file = resolve(path);
            if (!Files.isRegularFile(file)) {
                return "错误：文件不存在 " + path;
            }
            String name = file.getFileName().toString().toLowerCase();
            if (name.endsWith(".docx")) {
                return readDocx(file);
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }

    private String readDocx(Path file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(file);
                XWPFDocument doc = new XWPFDocument(in)) {

            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText() == null ? "" : p.getText().trim();
                if (!text.isEmpty()) {
                    sb.append(text).append("\n\n");
                }
            }

            List<XWPFTable> tables = doc.getTables();
            for (int i = 0; i < tables.size(); i++) {
                sb.append("### 表格 ").append(i).append("\n\n");
                sb.append(tableToMarkdown(tables.get(i))).append("\n");
            }
        }
        return sb.toString();
    }

    static String tableToMarkdown(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell c : rows.get(r).getTableCells()) {
                String t = c.getText() == null ? "" : c.getText().trim().replace("|", "/");
                cells.add(t);
            }
            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (r == 0) {
                sb.append("|").append(" --- |".repeat(cells.size())).append("\n");
            }
        }
        return sb.toString();
    }

    /** 防目录穿越：所有路径必须落在 caseRoot 内。 */
    private Path resolve(String relative) {
        Path p = caseRoot.resolve(relative).normalize();
        if (!p.startsWith(caseRoot)) {
            throw new IllegalArgumentException("路径越界: " + relative);
        }
        return p;
    }
}
