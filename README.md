# 企业报表智能体

受控式 Text-to-SQL：**模板优先 + NL2SQL 兜底**，所有查询经 SqlGuard 语法树校验后由只读账号执行。

## 为什么不做裸 NL2SQL

Spider 2.0 基准（真实企业库，动辄上千列）上 GPT-4o 的执行准确率只有约 10%，而同一个模型在学术玩具库 Spider 1.0 上有 86%。更关键的是 BIRD 基准的一组消融数据：榜首系统 AskData 拿到 81.95 分，去掉人工编写的业务知识提示后掉到 67.41 —— **14 分的落差全部来自业务口径知识的注入**。

所以本项目的重心不在"让模型写 SQL"，而在建一个**受控的语义层**：字段业务含义、枚举值中文映射、指标口径边界、批准的 join 路径，全部版本化、可评审、可测试。SQL 生成只是最后一步。

## 技术栈

| 项 | 选择 |
|---|---|
| Java / Spring Boot | 17 / 3.5.15 |
| AI 框架 | Spring AI 1.1.8 |
| 元数据库 | MySQL（会话/消息/审计/配置，读写） |
| 业务库 | MySQL（被查询的目标库，**只读账号**） |
| 向量库 | Redis Stack（阶段 4 Schema Linking 用） |
| SQL 校验 | JSqlParser 5.3（语法树白名单校验） |
| 模型 | OpenAI 兼容网关（默认阿里云 DashScope） |

## 目录结构

```
report-agent/
├── db/                          业务库建表与模拟数据生成
│   ├── 01_business_schema.sql
│   └── 02_business_data.sql
├── src/main/resources/
│   ├── schema.sql               元数据库建表（随服务启动自动执行）
│   └── semantic-model/          ★ 语义层，人工维护、需要业务评审
│       ├── metrics.yml          指标口径（含 requiredFilters 与 caveats）
│       ├── joins.yml            批准的 join 路径 + 明确禁止的连接
│       ├── tables/*.yml         表与字段的业务描述、枚举值中文映射
│       └── templates/*.yml      参数化报表模板
└── src/main/java/com/wisesoft/agent/
    ├── semantic/                语义层加载与校验
    ├── config/                  双数据源、鉴权、启动自检
    ├── service/                 配置中心、限流、会话、问答
    └── controller/
```

## 本地启动

### 1. 准备数据库

```bash
# 业务库：建表 + 生成 3 万订单 / 7.5 万明细的模拟数据，同时创建只读账号 report_ro
mysql -h 127.0.0.1 -uroot < db/01_business_schema.sql
mysql -h 127.0.0.1 -uroot < db/02_business_data.sql

# 元数据库：建库即可，表由服务启动时自动创建
mysql -h 127.0.0.1 -uroot -e "CREATE DATABASE IF NOT EXISTS report_agent DEFAULT CHARACTER SET utf8mb4;"
```

模拟数据用确定性伪随机（CRC32 哈希取模）生成，任何机器跑出来完全一致 —— 评测集的 golden 结果才有意义。

### 2. 启动 Redis

```bash
redis-stack-server --port 6379
```

阶段 1~3 只用到 Redis 的限流与配置广播，普通 Redis 即可；阶段 4 的向量库需要 Redis Stack（RediSearch 模块）。

### 3. 配置密钥

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
# 填入真实的 API Key，或设置环境变量：
export AI_API_KEY=sk-xxx
```

没有真实 key 时服务仍能启动（用占位值），但启动日志会打 `[FAIL-LOUD]` 告警，且任何模型调用都会返回 401。

### 4. 运行

```bash
mvn -DskipTests package
java -jar target/report-agent.jar
```

- 服务地址：http://127.0.0.1:8091/report-agent
- Swagger：http://127.0.0.1:8091/report-agent/swagger-ui.html
- 所有 `/api/**` 接口需要请求头 `X-Trusted-Token`（本地默认 `local-dev-token`）

## 安全设计

报表智能体访问的是真实经营数据，防护是分层的，任何一层被绕过还有下一层：

1. **语义层白名单** —— 表、列、join 路径都必须在 YAML 中登记过
2. **SqlGuard AST 校验** —— JSqlParser 解析成语法树后校验，不用正则（正则挡不住注释注入、大小写变体、嵌套子查询）
3. **模板路径走命名参数绑定** —— 不做字符串拼接，天然免疫注入
4. **只读数据库账号** —— `report_ro` 只有 SELECT 权限，写操作在数据库层被拒
5. **连接级 readOnly 标记** —— HikariCP 连接池再打一层
6. **强制行数与超时上限** —— 防止全表扫描撑爆内存
7. **权限走 ToolContext** —— Spring AI 保证其内容不发给模型，租户过滤由代码强制注入

## 语义层为什么要校验

语义层是人工维护的，而库结构会变。两者一旦漂移，症状是 SQL 生成莫名其妙失败或算错，且很难归因。所以启动时就对齐：

- **拒绝启动**：YAML 引用了库里不存在的表/列、join 引用了不存在的表、指标引用了不存在的 join、模板 SQL 的命名参数与声明不一致
- **告警放行**：库里存在但 YAML 没描述的列（不影响正确性，但这些列对 Schema Linking 是盲区）

```bash
mvn test   # 语义层解析、同义词解析、join 白名单、枚举映射的单元测试
```

## 前端

```bash
cd web
npm install
npm run dev        # 开发模式（代理 /report-agent 到 127.0.0.1:8091）
npm run build      # 产物打进后端 jar 的 static/ 目录
```

浏览器打开 `http://127.0.0.1:8091/report-agent/`（后端直接提供前端静态资源）。
页面包含：SSE 流式问答、Agent 执行轨迹（每步进行中/成功/失败）、结果表格、
ECharts 图表（时间列自动折线图）、SQL 折叠面板、👍/👎 反馈（差评回流评估集）。

## NL2SQL 评测

```bash
# 完整评测：每题让模型生成 SQL，执行后与标准 SQL 的结果集对比（需要可用的模型 key）
java -jar target/report-agent.jar --eval

# 自检模式：不调模型，验证评测链路与对比逻辑（离线可跑，当前 20/20 通过）
java -jar target/report-agent.jar --eval-self
```

评测集在 `eval/goldens.yml`（20 题，覆盖区域/客户/商品/时间/比率/渠道维度），
标准 SQL 全部人工验证过。这是回归基线：改 prompt、改语义层、换模型之后，
跑一遍就知道准确率是变好还是变坏。

## 实施进度

- [x] **阶段 1 · 底座** —— 模拟业务库、语义层、双数据源、鉴权限流、配置中心、SSE 骨架
- [x] **阶段 2 · Tool Calling** —— Spring AI `@Tool` 工具集，模板路径打通（mock 端到端验证）
- [x] **阶段 3 · ReAct 循环** —— 手写 observe→think→act 循环、SqlGuard AST 校验、错误自修正、轮次上限
- [x] **阶段 4 · NL2SQL 兜底** —— Schema Linking（同义词 + 关键词召回，向量接口留位）、golden few-shot、20 题评测集
- [x] **阶段 5 · 前端与闭环** —— Vue 3 + AntDV + ECharts（SSE 流式/执行轨迹/表格/图表/SQL 面板/反馈按钮）
