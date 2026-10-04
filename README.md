# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## 海水养殖台账（aqua-1）

模块包路径 `com.evops.aqua`，已打通：建池 → 投苗 → 日常记录 → 饲料/用药 → 抽检 → 换池 → 出池。成本核算留待后续版本。

- 养殖池档案：水深、盐度、所属海区（`POST /api/aqua/ponds`）
- 投苗批次：苗种来源、入池数量、计划出池日；批次编号全局唯一（`POST /api/aqua/batches`）
- 饲料、用药、日常、抽检：按池按批留痕（`/api/aqua/records/**`），批次不在该池或已出池会被拒绝
- 换池：`POST /api/aqua/batches/{id}/transfer` 自动生成转移记录并更新批次所在池
- 出池：`POST /api/aqua/batches/{id}/harvest` 生成出池记录，批次封存
- 原始投苗单不可删除：无 DELETE 接口，表上无删除标记
- 批次全链路追溯：`GET /api/aqua/batches/{id}/trace`

接口需先登录：`POST /api/auth/login`（bootstrap / bootstrap）。

验收：`mvn test -Dtest=AquaLedgerAcceptanceTest`，从养殖池档案入口追到投苗批次落库，逐检查点输出报告，失败即停并给出停点。

## 水质采样计划与缺测补录（aqua-2）

模块包路径 `com.evops.aqua.wq`，在养殖台账之上打通：规则版本 → 采样计划 → 按池任务 → 草稿/提交 → 主管审核 → 健康曲线 → 补录追加。

- 指标规则版本化：不同养殖阶段配置采样频率（相对投苗日的天区间+间隔天数）与指标正常范围（`POST /api/aqua/wq/rules/versions`）；已发布版本不可改，调整只能发新版本
- 采样计划按池生成任务：有在养批次以投苗日为锚点、空池以计划开始日为锚点，排期截止到批次计划出池日（`POST /api/aqua/wq/plans`）
- 新版本规则只作用于未开始（PENDING）任务：`POST /api/aqua/wq/plans/{id}/apply-version` 仅重指/重排未开始任务，草稿及以后的任务保留生成时的规则快照
- 采样员可暂存草稿（`POST /api/aqua/wq/tasks/{id}/draft`），草稿不校验；正式提交时必测指标必须全覆盖，缺测项必须填写缺测原因（`/submit`）
- 补录只能追加不能覆盖原值：审核通过的任务可追加 SUPPLEMENT 补录（`/tasks/{id}/supplements`），原测行永久保留；补录需主管单独审核（`POST /api/aqua/wq/supplements/{id}/review`）
- 主管审核后才进入池塘健康曲线（`GET /api/aqua/wq/ponds/{pondId}/curve`），缺测项不产生曲线点；补录审核通过只更新曲线点，测量原值不动
- 所有修改保留证据：规则发布、计划排期与版本重指、草稿修改、提交、审核、补录、曲线写入均落 `t_wq_change_evidence`（`GET /api/aqua/wq/tasks/{id}/evidence`）
- 幂等防重复：写请求携带 `Idempotency-Key` 请求头，重复请求回放首次响应（响应头 `Idempotency-Replay: true`），不会产生第二条计划/任务/测量/补录结果

验收：`mvn test -Dtest=WaterQualitySamplingAcceptanceTest`，12 个检查点覆盖规则版本化、按池排期、草稿与缺测拦截、审核入曲线、补录只追加、重复请求幂等与证据链。



