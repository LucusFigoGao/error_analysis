# RCA Agent

读运维故障材料（告警、日志、工单、交易量报表），定位根因，输出分析报告。
基于 AgentScope Java 2.x 的 `HarnessAgent`，模型走 DashScope 的 qwen3.6-27b

## 跑起来

```bash
export DASHSCOPE_API_KEY=sk-xxxx
mvn clean spring-boot:run
```

另开一个终端：

```bash
curl -X POST http://localhost:9001/api/analyze/case01
```

想看中间调了哪些工具：

```bash
curl -N -X POST http://localhost:9001/api/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"分析 case01"}'
```

报告落在 `workspace/reports/` 下。

## 目录

```
src/main/java/com/example/rca/
├── AgentApplication.java
├── config/AgentConfig.java        模型 + workspace + toolkit
├── controller/RcaController.java  本地入口，比赛模板到手后换成 AguiController
└── tools/
    ├── DocumentTools.java         list_case_files / read_document
    ├── AnalysisTools.java         analyze_metric_tables / parse_exception / parse_alerts
    └── ReportTools.java           write_report

workspace/                         agent 的知识与产物
├── AGENTS.md                      主流程 SOP，每轮自动注入系统提示
├── skills/                        放好即生效，无需注册
│   ├── incident-timeline/         时间线对齐规则
│   ├── change-correlation/        变更关联
│   ├── impact-quantification/     影响面量化
│   └── fault-playbooks/           专项判读手册索引
│       └── references/
│           └── db-connection-pool.md
└── reports/                       输出

cases/case01/                      故障材料
```

## 设计要点

**为什么不做成一个大 prompt。** 故障判读的知识随组织持续积累，新增一类故障应该是往
`fault-playbooks/references/` 扔一个 markdown，不改代码、不改 prompt。
AgentScope 的 skill 机制原生支持按需加载：模型先只看到每个 skill 的 description，
需要时自己调 `load_skill_through_path` 拉全文，所以手册再多也不会撑爆上下文。

**tool 只做模型做不好的事。** 表格算术核对、连接池参数抽取、告警时间排序，
这三件事模型顺着读容易出错，交给确定性代码。推理、取舍、表达留给模型。

`analyze_metric_tables` 在示例案例上能抓出文档本身的三处数值矛盾，这是纯 prompt 方案发现不了的。

## 待办

- [ ] 证据编号与引用校验（verify_citations），防止报告里出现没有出处的数字
- [ ] 补 mq-consumer、cache-redis、gateway-timeout 等 playbook
- [ ] 换成比赛模板的 AguiController + Nacos 配置
- [ ] 工具注册到信雅达平台插件
