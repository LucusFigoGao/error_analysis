package com.example.rca.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 输出类工具：把最终报告落到 workspace/reports 下。
 */
public class ReportTools {

    private final Path reportDir;

    public ReportTools(String workspacePath) {
        this.reportDir = Paths.get(workspacePath).toAbsolutePath().normalize().resolve("reports");
    }

    @Tool(
            name = "write_report",
            description =
                    "把最终的故障分析报告写成 markdown 文件。只在报告全文写好、且每条结论都标注了证据来源之后调用一次。",
            readOnly = false)
    public String writeReport(
            @ToolParam(name = "case_id", description = "案例编号，例如 case01") String caseId,
            @ToolParam(name = "content", description = "报告全文 markdown") String content) {
        try {
            Files.createDirectories(reportDir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path out = reportDir.resolve(caseId + "-" + stamp + ".md");
            Files.writeString(out, content, StandardCharsets.UTF_8);
            return "报告已写入: " + out;
        } catch (Exception e) {
            return "错误：" + e.getMessage();
        }
    }
}
