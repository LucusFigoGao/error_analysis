---
name: fault-playbooks
description: 专项故障判读手册的索引。按症状选择要读的手册，覆盖数据库连接池、消息队列、缓存、网关超时等。看到具体异常或技术症状、要判断故障性质时必须加载，并接着读对应的 references 文件。
---

# 专项故障判读手册

本 skill 只是索引。**真正的判读规则在 `references/` 下，必须读完对应文件才能下结论。**

在读完对应手册之前，不要输出任何关于根因的判断。这些手册里写的大多是反直觉的规则，
凭常识推断有很大概率反向。

## 症状到手册

| 观察到的症状 | 读哪本 |
|---|---|
| `GetConnectionTimeoutException`、`CannotGetJdbcConnection`、连接池满、拿不到数据库连接、Druid/HikariCP 相关报错 | `references/db-connection-pool.md` |
| 消息堆积、消费延迟、重复消费、MQ 相关异常 | `references/mq-consumer.md`（待补充） |
| 缓存击穿、Redis 超时、连接数打满 | `references/cache-redis.md`（待补充） |
| 网关 504、上游超时、限流拒绝 | `references/gateway-timeout.md`（待补充） |
| Full GC 频繁、OOM、内存泄漏 | `references/gc-memory.md`（待补充） |

## 找不到对应手册怎么办

如果症状不在上表里：

1. 说明这类故障还没有沉淀手册，**在报告里显式写出来**
2. 基于通用推理给结论，但置信度最高只能标「中」
3. 在「待验证项」里写清楚需要人工确认哪些东西

不要因为没有手册就假装有把握。

## 怎么用手册

手册的核心通常是一张「参数组合到故障性质」的判读表。用法是：

1. 先用 `parse_exception` 等工具拿到结构化参数
2. 在判读表里精确匹配参数组合，不要凭印象跳到常见结论
3. 匹配到的那一行给出的是**故障性质**，不是根因，接着按它指的排查方向继续找
