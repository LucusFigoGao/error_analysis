# RCA Agent

读运维故障材料（告警、日志、工单、交易量报表），定位根因，输出带证据清单的分析报告。基于 AgentScope Java 2.x 的 `HarnessAgent`。核心设计是**三层 skill + 确定性 tool**：判读知识写在 markdown 里按需加载，算术核对和参数抽取交给代码，推理和表达留给模型。

## 快速开始

```bash
export DASHSCOPE_API_KEY=sk-xxxx
mvn clean spring-boot:run          # 默认 local profile，端口 9001

chmod +x rca.sh
./rca.sh case01                    # 分析案例，看正文 + 工具序列
```

报告落在 `workspace/reports/` 下。

### rca.sh 用法

```bash
./rca.sh case01                    # 完整输出
./rca.sh -t case01                 # 只看工具调用序列，调流程时用这个
./rca.sh -r case01                 # 原始 JSON，排查协议问题
./rca.sh -m "只看告警怎么排时间线"   # 自由提问
```

环境变量覆盖：`RCA_HOST` / `RCA_PREFIX` / `RCA_USER_ID`。

- 脚本只依赖 bash、curl、sed
- 跑完会自检四个关键工具有没有被调到，**`load_skill_through_path` 缺席就说明 skill 没生效**。

## 目录结构

```
src/main/java/com/example/rca/
├── AgentApplication.java
├── config/AgentConfig.java          模型通道切换 + workspace 校验 + toolkit
├── controller/AguiController.java   AG-UI 协议端点，交付给平台用
└── tools/
    ├── DocumentTools.java           list_case_files / read_document
    ├── AnalysisTools.java           analyze_metric_tables / parse_exception / parse_alerts
    └── ReportTools.java             write_report

src/main/resources/
├── application.yml                  公共配置
├── application-local.yml            本地：DashScope 直连
└── application-server.yml           服务器：内网 OpenAI 兼容端点

workspace/                           agent 的知识与产物
├── AGENTS.md                        L1 主流程 SOP，每轮自动注入系统提示
├── skills/                          放好即生效，无需注册
│   ├── incident-timeline/           L2 时间线对齐规则
│   ├── change-correlation/          L2 变更关联
│   ├── impact-quantification/       L2 影响面量化
│   └── fault-playbooks/             L3 专项判读手册索引
│       └── references/
│           └── db-connection-pool.md
├── reports/                         输出（gitignore）
├── state/                           会话持久化（gitignore）
└── .skills-cache/                   框架缓存（gitignore）

cases/case01/                        故障材料
rca.sh                               调用脚本
```

## 配置

### profile 切换

| | local | server |
|---|---|---|
| 模型通道 | DashScope 直连 | 内网 OpenAI 兼容端点 |
| 模型 | qwen3.6-27b | Qwen3.6-35B-A3B |
| 路径 | 相对 `./workspace` | 绝对 `/opt/rca/...` |
| 端口 | 9001 | 分配给你的 |

```bash
mvn spring-boot:run                                        # local
java -jar target/rca-agent-1.0.0.jar --spring.profiles.active=server
```

服务器上也可以用启动参数覆盖路径，`java -jar` 的工作目录不一定是你以为的那个：

```bash
java -jar rca-agent.jar --spring.profiles.active=server \
  --agentscope.workspace=/opt/rca/workspace \
  --rca.case-root=/opt/rca/cases
```

### 交付前必须改的三个值

1. **`agui.path-prefix`**（`application.yml`）。现在是 `/api`，会和其他选手冲突报 404。
   要换成比赛方分配给你的标识，格式参考别人的 `/lyx3034998/api`、`/cjy6648332/api`。
   完整端点是 `<path-prefix>/ag-ui`。
2. **`server.port`**（`application-server.yml`）。9066 是别人的，每人一个。
3. **`agentscope.model.base-url` 和 `name`**（`application-server.yml`）。
   填的是从别人配置里看到的样例，用之前跟比赛方核一遍。

注册智能体时「API端点地址」填完整 URL：`http://<分配的IP>:<端口>/<你的标识>/api/ag-ui`。

### 关于 nacos

不需要。平台已改成 URL 直接绑定后端，服务发现关掉了；配置中心只是可选便利，
自己的 `application.yml` 够用，少一个启动期外部依赖更稳。
比赛方明确要求从 nacos 拉配置时再加，届时问清 data-id。

### API key

不要写进任何配置文件。本地走 `DASHSCOPE_API_KEY` 环境变量，
服务器的内网端点不校验 key（别人配置里是 `your-api-key` 占位符），给个非空值即可。

## 设计说明

### 为什么不做成一个大 prompt

故障判读的知识随组织持续积累。新增一类故障应该是往 `fault-playbooks/references/`
扔一个 markdown，不改代码、不改 prompt。AgentScope 的 skill 机制原生支持渐进加载：
模型先只看到每个 skill 的 description，需要时自己调 `load_skill_through_path` 拉全文，
所以手册再多也不会撑爆上下文。

三层的分工：

- **L1 `AGENTS.md`**：只管流程顺序和调度，不含专业知识，常驻注入
- **L2 `skills/*/SKILL.md`**：这一行通用的经验，比如「告警是滞后指标，别拿告警时间当故障起点」
- **L3 `references/*.md`**：具体到某一类故障的判读表，最厚也最值钱

### 为什么要 tool

只做模型系统性做不好的事：

- `analyze_metric_tables` 核对表格算术。在示例案例上抓出文档自身的三处数值矛盾
  （成功率与「成功量/总量」对不上、跨行重复的复制粘贴错误），这是纯 prompt 方案发现不了的。
  能正确处理 docx 的两行合并表头。
- `parse_exception` 抽取 Druid 连接池参数。判读的关键在于
  `active=0 且 createErrorCount 很大` 意味着**建连持续失败**而非池耗尽，
  两者的处置方向完全相反。
- `parse_alerts` 按时间排序并算间隔。示例案例里下游系统比根因系统早 8 分钟告警，
  肉眼读很容易定位反。

这三个工具的返回末尾都会提示下一步该加载哪个 skill，比在 AGENTS.md 里泛泛写流程有力。

### 启动时校验工作区

`AgentConfig` 会检查 `AGENTS.md` 和 `skills/` 是否存在，缺了直接启动失败。

针对的是一个很阴险的失败模式：部署时只传了 jar 忘了传 `workspace/`，
**agent 照样能起来、照样能回答**，但流程约束和判读手册全没了，
报告安静退化成泛泛而谈，等发现质量下降已经查半天了。

## 已验证

示例案例 `case01`（TS系统数据库连接池建连失败）完整跑通，18 轮工具调用，耗时约 100 秒。
产出报告正确识别出：

- 故障性质是建连持续失败而非连接池耗尽，并显式排除了另外两种可能
- 根因关联到故障前 22 小时的改密工单（人工改密 + 密码存储方式为应用配置文件）
- 置信度标为「中」，范围核实列入待验证项，没有硬下结论
- 文档自身的三处数值矛盾和一处告警口径矛盾

四个 skill 全部被加载并在报告中被引用。

## 待办

- [ ] 工具调用序列里第 10 到 15 步模型绕过 skill 机制用 `read_file` / `glob_files` / `execute`
      自己翻文件系统。两个修法：收敛内置工具白名单，或把 playbook 从 `references/`
      提成独立 skill 让它能被直接加载。`execute` 能跑 shell，交付前要考虑关掉。
- [ ] 加 `load_case` 工具一次返回全部材料，避免模型只读一份就开始推理
- [ ] 给 `write_report` 加门禁：根因待验证但没调过 `parse_exception` 就拒绝写入
- [ ] 证据引用校验（verify_citations），防止报告出现没有出处的数字
- [ ] 补 mq-consumer、cache-redis、gateway-timeout 等 playbook
- [ ] 建评测集：每个案例标注（根因组件、根因类型、关键证据集），
      做消融对比「有无 playbook」的根因定位准确率
- [ ] 工具注册到信雅达平台插件（同插件内工具必须同域名，本项目单服务天然满足）
