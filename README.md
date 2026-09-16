# 经营指标分析与异常归因辅助系统

第三个简历项目的可运行学习实现。提供完整Java后端、网页、ECharts图表、演示数据生成器、自动化测试、可执行JAR和Windows运行脚本。

技术栈：Java17、Spring Boot3.5.7、Spring AI1.1.0、Spring Security、Spring JDBC、H2/MySQL、可选Redis、ECharts5.6.0。

本工程根据简历描述新编写，不是企业源码。所有经营数据为虚构样本，适合运行、学习和二次开发，不代表真实上线成果。

## 1. 最快运行：只需要JDK17

1. 把ZIP完整解压，例如放在 `D:\projects\metrics-agent`，找到 `run.cmd` 所在目录。
2. 在PowerShell输入 `java -version`，确认当前Java是 **17**，不是11。
3. 双击 `run.cmd`，或者运行：

```powershell
cd D:\projects\metrics-agent
.\run.cmd
```

4. 等待 `Started MetricsApplication` 和 `DATA_READY`。首次启动会自动生成约两万条订单及关联退款。
5. 浏览器打开 **http://127.0.0.1:8082**。
6. 账号选择 `demo · 全区域分析员`，初始密码 **`Demo123!`**。

默认无需安装MySQL、Redis、Python、Node.js，也不需要模型密钥。ECharts已随包提供，不通过CDN加载。默认H2文件数据库会将数据和历史报告保存在项目根目录的 `data` 文件夹中。

端口使用8082，与第二个项目的8081区分。启动窗口需要保持打开；停止时按Ctrl+C。

## 2. 先体验这个分析例子

输入：

> 最近7天净实收为什么下降，按区域分析

操作顺序：

1. 点击“生成分析计划”。
2. 核对指标是净实收，周期为 **2026-08-25至2026-08-31**，对比周期为 **2026-08-18至2026-08-24**。
3. 点击“执行分析”。
4. 查看当前/前期数值、总体变化、趋势图、各区域变化贡献。
5. 在某区域右侧点击“筛选重查”，系统会把该区域加为筛选并切换下一维度；核对计划后再次执行。
6. 展开“可核验依据”，检查E1至E4对应的固定SQL、参数与聚合结果。
7. 点击“下载完整报告”得到Markdown，或“导出明细CSV”得到可用Excel打开的分类结果。
8. 在历史报告中重新打开已保存结果。

其他例子：

- `最近7天华东取消率，按渠道分析`
- `2026-07-29至2026-08-04退款额，按品类分析`
- `最近14天订单量，按状态分析`
- `最近7天履约率，按区域分析`

**日期说明：** 数据固定为2026-05-04至2026-08-31，共120天。“最近N天”“本周”以样本末日2026-08-31作为参考，不是电脑今天。“本周”因此仅代表该参考周已覆盖的日期。显式日期优先；对比固定采用紧邻的前一等长周期，本版没有去年同比。

默认规则模式能识别这些指标、维度、单值筛选和日期格式，不是通用自然语言模型。难以解析的问题可直接在“分析计划”表单中选择字段。

## 3. 三个演示账号

| 账号 | 业务数据权限 | 其他能力 |
| --- | --- | --- |
| demo | 华东、华北、华南 | 分析并查看本人报告 |
| east | 仅华东 | 无法通过改请求查询华北，也无法打开demo报告 |
| admin | 华东、华北、华南 | 额外查看分析调用记录，报告仍按本人隔离 |

初始密码均为 `Demo123!`。可在启动前设置 `$env:DEMO_PASSWORD="你设置的新密码"`，三个演示账号将统一采用该密码。密码在服务端以BCrypt编码保存；此处没有正式的注册或用户管理模块。

## 4. 在IDEA中查看源码和运行

1. IDEA → Open → 选择 `metrics-agent` 目录，按Maven工程导入 `pom.xml`。
2. Project SDK、Maven Importer、Maven Runner均设置为 **JDK17**。
3. 等待依赖下载完成。
4. 运行 `src/main/java/com/guo/metrics/MetricsApplication.java` 的main方法。
5. Working directory设置为项目根目录，避免H2数据库生成在别处。

不要同时从IDEA和 `run.cmd` 启动同一个项目实例。

命令行从源码编译：

```powershell
.\mvnw.cmd clean package
java -Dfile.encoding=UTF-8 -jar .\target\metrics-agent.jar
```

项目带Maven Wrapper，首次编译需要联网下载Maven3.9.9及依赖；已安装Maven时可用 `mvn clean package`。首次只想看效果，直接运行随包JAR即可。

`app/metrics-agent.jar` 是预编译包；重新构建后 `run.cmd` 优先运行 `target/metrics-agent.jar`。修改源码后必须重新编译、重启。

## 5. 启用真实模型

停止应用，在同一个PowerShell窗口设置环境变量再启动：

```powershell
$env:AI_ENABLED="true"
$env:AI_API_KEY="你的模型服务密钥"
$env:AI_BASE_URL="https://api.openai.com"
$env:AI_MODEL="gpt-4o-mini"
.\run.cmd
```

地址和模型名是OpenAI兼容接口示例，必须替换为你账号可用的模型。服务需要支持 `/v1/chat/completions`、Tool Calling和JSON结构化输出。BASE_URL填写根地址，不要重复添加 `/v1/chat/completions`。

真实模式有两个步骤：

- **计划解析**：Spring AI把问题转成指标、日期、维度和筛选条件；后台再执行相同白名单/权限/范围校验。
- **证据解释**：Spring AI通过只读工具读取本次报告的聚合证据，再输出定性总结与待验证假设。模型不能访问别人的报告，也不能生成并执行任意SQL。

金额、比例、变化贡献和图表始终由SQL/Java计算。模型解释的证据ID无效、结构不完整或混入数字时会回退规则说明；页面会显示实际模式。字段数值及其口径不交给模型“心算”。

开启模型会把你的问题或已授权聚合证据发送给指定模型服务。密钥只放环境变量；`.env.example` 是说明模板，Spring Boot不会自动读取 `.env`。

恢复离线模式：设置 `$env:AI_ENABLED="false"` 后重启。

## 6. 切换MySQL8（可选）

本机已有mysql80服务时，确认MySQL已启动，用管理员账号执行 `infra/create-mysql.sql`。该SQL创建独立库 `metrics_agent`，不使用你之前项目的 `tlias` 或 `zzyl`。

```powershell
$env:SPRING_PROFILES_ACTIVE="mysql"
$env:MYSQL_HOST="localhost"
$env:MYSQL_PORT="3306"
$env:MYSQL_DATABASE="metrics_agent"
$env:MYSQL_USER="metrics"
$env:MYSQL_PASSWORD="local-metrics-pass"
.\run.cmd
```

启动自动创建表、视图和演示数据。切换数据库不会迁移旧H2中的报告。

改回H2：`Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue`，然后重启。

本工程用Spring JDBC展示受控SQL生成过程，没有使用MyBatis。演示数据库账号为了初始化表/视图/数据拥有写权限；模型入口只允许固定聚合SELECT，不能把它描述成数据库账户层面的只读权限方案。

## 7. Redis缓存（可选）

```powershell
$env:REDIS_ENABLED="true"
$env:REDIS_HOST="localhost"
$env:REDIS_PORT="6379"
.\run.cmd
```

默认使用最多256项的本机内存缓存；Redis启用后先查询Redis，失败回退本机缓存或数据库。正常结果缓存5分钟，空结果缓存1分钟。

缓存Key包含完整计划、登录用户、授权区域和数据版本。不能只用“收入”或问题文本作Key，否则容易把不同口径或不同权限的数据混在一起。命中缓存后本次聚合SQL次数显示0，但报告保留原始计算证据。

模型解释每次仍独立生成，不把解释文本作为计算结果缓存。报告本身持久保存。

## 8. Docker方式（可选）

如果安装了Docker Desktop，可运行：

```powershell
docker compose -f .\infra\compose.yml up -d
```

容器MySQL映射到 **3308**，Redis映射到 **6381**，与本机服务及第二个项目区分。使用容器时相应设置 `MYSQL_PORT=3308`、`REDIS_PORT=6381`。默认运行不需要Docker。

## 9. 测试

自动集成测试：

```powershell
.\mvnw.cmd test
```

应用已启动时执行真实HTTP验收（Python3.10及以上，全部使用标准库）：

```powershell
python .\scripts\smoke_test.py
```

若Windows提示找不到python，而已装Python3.10，可以用：

```powershell
py -3.10 .\scripts\smoke_test.py
```

脚本会创建演示报告，验证财务口径、贡献加总、缓存、导出与权限。其他端口运行时设置 `$env:METRICS_URL="http://127.0.0.1:8083"`；改过演示密码时设置 `METRICS_PASSWORD`。

`fake_model.py` 是用于协议联调的本地模拟服务，不是真实模型。要体验协议桩：先在另一个终端运行 `py -3.10 scripts/fake_model.py`，再设置 `AI_ENABLED=true`、`AI_API_KEY=test-only`、`AI_BASE_URL=http://127.0.0.1:8098`、`AI_MODEL=test-stub` 后启动Java应用。该桩固定返回示例计划，只用于验证接口与工具调用。

## 10. 常见问题

| 现象 | 处理 |
| --- | --- |
| Java版本不兼容 | 命令行和IDEA分别确认JDK17，不能使用JDK11运行此工程。 |
| 8082已被占用 | 停掉重复实例，或设置 `$env:SERVER_PORT="8083"` 后启动并打开8083。 |
| H2数据库被锁定 | 同一data目录只能启动一个实例；先关闭旧进程。 |
| Maven下载失败 | 检查网络、代理与Maven仓库设置；随包JAR可先直接运行。 |
| MySQL拒绝连接 | 检查服务、端口、数据库用户、密码及授权；使用独立metrics用户。 |
| 模型失败或解释回退 | 查看页面实际模式；检查密钥、模型权限和接口兼容性。规则模式仍可完成指标分析。 |
| 日期范围被拒绝 | 当前周期1至31天，并确保前一等长周期也在样本范围中。 |
| 比例显示“不可计算” | 分母为0，这是有意保留的信息，不应伪装成0%。 |
| 贡献率超过100%或为负 | 不同分类可能相互抵消，这不是计算错误。 |
| 页面403 | 可能是权限不足或CSRF令牌过期，刷新重新登录。 |

默认仅监听127.0.0.1。正式部署还需真实账户与数据接入、索引/执行计划优化、数据库迁移、登录限流及运维措施，本包没有声称实现生产规模治理。

## 11. 包内文档

- `docs/项目讲解与面试准备.md`：模块职责、调用路径、指标和贡献公式、可以如何讲项目。
- `docs/接口说明.md`：API、请求示例、错误语义。
- `docs/验收记录.md`：已验证与尚未验证的范围。
- `docs/样例分析报告.md`、`docs/样例分析结果.json`、`docs/样例趋势图.svg`：实际运行生成的样例。

建议阅读源码顺序：`MetricCatalog → PlanValidator → QueryEngine → AnalysisEngine → PlannerService → NarrativeService → ReportService → ApiController → static/app.js`。
