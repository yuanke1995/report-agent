# 测试流程

按这个流程跑一遍，覆盖项目的全部核心能力。每项都写了**预期结果**，
看到预期结果就是通过；没达到就是有问题，可以把现象发给我排查。

前置条件（一次性准备）：
- MySQL 已建好业务库和元数据库（`db/01_business_schema.sql`、`db/02_business_data.sql` 已执行）
- Redis Stack 已启动：`redis-stack-server --port 6379`
- `src/main/resources/application-local.yml` 已配置真实 API Key（参考同目录 `.example` 文件）
- 重新打包（改过配置后必须）：`mvn -DskipTests package`

---

## 1. 启动

```bash
java -jar target/report-agent.jar
```

预期：
- 日志出现 `语义层加载完成：6 张表 / 13 个指标 / 7 条 join 路径 / 3 个报表模板 / 8 条 golden 示例`
- 日志出现 `[语义层] 校验通过，与数据库 report_demo 对齐（0 项告警）`
- 日志出现 `Started ReportAgentApplication`
- 无 `[FAIL-LOUD] 当前使用的是占位 API Key` 告警（有的话说明 key 没配进去）

---

## 2. 模板路径（准确率最高的主链路）

浏览器打开 `http://127.0.0.1:8091/report-agent/`，点击推荐问题
**「最近半年每个月的销售额」**。

预期：
- 回答气泡先出现**执行轨迹**：`执行报表模板 → 完成`
- 出现**表格**：6 行（2026-03 到 2026-08，销售额约 3755 万 ~ 4418 万）
- 出现**折线图**（月份为横轴）
- 回答文本给出趋势解读（不是罗列数字）
- 点击「查看 SQL」展开能看到模板 SQL

> 同链路再试：「各区域销售对比」应出 7 个大区的柱状图。

## 3. NL2SQL 兜底（模型自己写 SQL）

输入框提问（不在 3 个模板范围内的问题）：

```
各品牌在8月的销售额排行
```

预期：
- 执行轨迹可能有**两步**：先试模板 → 再用 `execute_sql`（模型自己写 SQL）
- 表格 10 行（各品牌销售额，Vela 第一约 654 万）
- 回答按排名给出解读
- 全程 10 秒内完成（超过 90 秒说明思考模式没关掉）

> 再试：「金卡客户的消费总额」（验证客户等级英文枚举映射 + join 客户表）。
> 预期单行结果：约 4.37 亿（金额口径，数字以实际为准）。

## 4. 错误自修正（SqlGuard + ReAct 循环）

浏览器输入框提问：

```
查一下订单的支付金额
```

预期（这个用例故意触发：模型写 SQL → Guard 校验）：
- 执行轨迹出现**失败步骤**，错误信息形如
  `SQL 未通过安全校验（N 处问题）` 或 `列 xxx 不存在...`
- 随后**重试成功**（失败 → 修正 → 完成）
- 表格出现结果

> 想看 Guard 拒绝细节：应用日志里搜 `[SQL]` 和 `SqlValidation`。

## 5. 轮次上限（防死循环）

把配置中心的轮次上限临时调小（MySQL 执行，改完即生效不用重启）：

```sql
UPDATE report_agent.r_agent_config SET cfg_value='3' WHERE cfg_key='agent.maxSteps';
```

然后提问一个模糊问题（如 `帮我分析一下`）。预期：3 轮后回答
`已分析 3 轮仍未得出最终结论，为控制成本已停止...`，而不是无限循环。

测完恢复：

```sql
UPDATE report_agent.r_agent_config SET cfg_value='8' WHERE cfg_key='agent.maxSteps';
```

## 6. 澄清反问（口径歧义）

提问：

```
上个月销售情况怎么样
```

预期：模型识别「销售额」存在含不含退款的口径分歧，反问用户
（回答区出现澄清问题），而不是擅自选一个口径。

## 7. 反馈闭环

在任意一条回答下方点击「👍 有用」。预期按钮变蓝，MySQL 验证：

```sql
SELECT message_id, user_id, rating FROM report_agent.r_feedback ORDER BY feedback_id DESC LIMIT 1;
```

能看到刚提交的 rating=1 记录。

## 8. 鉴权与只读防护

```bash
# 无 token 应 401
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8091/report-agent/api/semantic/overview

# 业务库账号写操作应被拒（数据库层防线）
mysql -h 127.0.0.1 -ureport_ro -preport_ro_pwd report_demo -e "DELETE FROM fact_order WHERE order_id=1;"
# 预期：ERROR 1142 ... command denied
```

## 9. NL2SQL 评测（准确率基线）

需要真实 API Key（已配置）：

```bash
java -jar target/report-agent.jar --eval
```

预期输出：`评测结果：通过 N/20（N%）`。这是准确率基线，之后改 prompt、
改语义层、换模型，都靠它对比是变好还是变坏。

> 离线自检（不调模型，验证评测链路本身）：`--eval-self`，预期 20/20。

## 10. 单元测试

```bash
mvn test
```

预期：66 个测试全部通过（语义层 / SqlGuard / 模板 SQL 真实执行 /
Schema Linking / 参数校验 / 工具注册）。

---

## 常见问题

| 现象 | 原因与处理 |
|---|---|
| 启动报 `OpenAI API key must be set` | 没配 key，或改配置后没重新打包 |
| `[FAIL-LOUD] 当前使用的是占位 API Key` | key 没生效，检查 application-local.yml 与打包 |
| 请求 90 秒后报 `EOF reached while reading` | 思考模式没关（网关超时），确认代码里 `enable_thinking: false` 生效 |
| `[语义层] 校验...` 报错拒绝启动 | 语义层 YAML 与库结构不一致，按报错清单修 YAML 或库 |
| 前端表格/图表不显示 | 确认访问的是打包后的页面（`/report-agent/`），不是旧缓存 |
