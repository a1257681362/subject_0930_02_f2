package com.evops.aqua.wq;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.entity.Pond;
import com.evops.aqua.entity.SeedlingBatch;
import com.evops.aqua.mapper.PondMapper;
import com.evops.aqua.mapper.SeedlingBatchMapper;
import com.evops.aqua.wq.entity.WqChangeEvidence;
import com.evops.aqua.wq.entity.WqHealthCurvePoint;
import com.evops.aqua.wq.entity.WqIdempotentRequest;
import com.evops.aqua.wq.entity.WqMeasurement;
import com.evops.aqua.wq.entity.WqSamplingPlan;
import com.evops.aqua.wq.entity.WqSamplingTask;
import com.evops.aqua.wq.mapper.WqChangeEvidenceMapper;
import com.evops.aqua.wq.mapper.WqHealthCurvePointMapper;
import com.evops.aqua.wq.mapper.WqIdempotentRequestMapper;
import com.evops.aqua.wq.mapper.WqMeasurementMapper;
import com.evops.aqua.wq.mapper.WqSamplingPlanMapper;
import com.evops.aqua.wq.mapper.WqSamplingTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 水质采样计划与缺测补录验收（aqua-2）。
 * 主线：默认规则 → 新版本规则 → 按池排期 → 草稿 → 缺测拦截 → 提交 → 主管审核入曲线
 *      → 补录仅追加 → 重复请求幂等（不产生第二条结果）→ 证据链。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WaterQualitySamplingAcceptanceTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PondMapper pondMapper;
    @Autowired private SeedlingBatchMapper batchMapper;
    @Autowired private WqSamplingPlanMapper planMapper;
    @Autowired private WqSamplingTaskMapper taskMapper;
    @Autowired private WqMeasurementMapper measurementMapper;
    @Autowired private WqHealthCurvePointMapper curveMapper;
    @Autowired private WqChangeEvidenceMapper evidenceMapper;
    @Autowired private WqIdempotentRequestMapper idempotentMapper;

    private final List<String> report = new ArrayList<>();
    private String sessionCookie;

    private Long pondId1;
    private Long pondId2;
    private Long batchId;
    private Long v1Id;
    private Long v2Id;
    private Long v3Id;
    private Long planId;
    private Long draftTaskId;

    @Test
    void acceptanceWaterQualitySamplingAndSupplement() {
        checkpoint("CP0", "登录认证", () -> {
            Map<String, Object> body = new HashMap<>();
            body.put("username", "bootstrap");
            body.put("password", "bootstrap");
            ResponseEntity<String> resp = call(HttpMethod.POST, "/api/auth/login", body, null);
            assertSuccess(json(resp), "登录");
            List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
            check(cookies != null && !cookies.isEmpty(), "登录成功但未下发会话Cookie");
            sessionCookie = cookies.get(0).split(";")[0];
        });

        checkpoint("CP1", "前置：建池与投苗（排期锚点=投苗日）", () -> {
            pondId1 = createPond("WQ-P01", "水质1号池", "28.5");
            pondId2 = createPond("WQ-P02", "水质2号池（空池）", "29.0");

            Map<String, Object> body = new HashMap<>();
            body.put("batchNo", "WB20261003-001");
            body.put("pondId", pondId1);
            body.put("species", "南美白对虾");
            body.put("seedSource", "漳州东山育苗场");
            body.put("quantity", 100000);
            body.put("stockDate", "2026-10-03");
            body.put("plannedHarvestDate", "2026-11-20");
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/batches", body, null));
            assertSuccess(resp, "投苗");
            batchId = resp.path("data").path("id").asLong();
        });

        checkpoint("CP2", "系统内置 v1 默认指标规则（分阶段频率+指标范围）", () -> {
            JsonNode resp = json(call(HttpMethod.GET, "/api/aqua/wq/rules/versions/latest", null, null));
            assertSuccess(resp, "查询最新规则版本");
            v1Id = resp.path("data").path("id").asLong();
            check("v1".equals(resp.path("data").path("versionNo").asText()),
                    "内置规则版本号应为 v1：" + resp.path("data").path("versionNo").asText());

            JsonNode detail = json(call(HttpMethod.GET, "/api/aqua/wq/rules/versions/" + v1Id, null, null));
            assertSuccess(detail, "查询规则明细");
            check(detail.path("data").path("stages").size() == 3, "v1 应有3个养殖阶段");
            check(detail.path("data").path("metrics").size() == 15, "v1 应有15条阶段指标规则");
        });

        checkpoint("CP3", "发布 v2 新版本：调整阶段采样频率（已发布版本不可变，只能发新版）", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/wq/rules/versions",
                    ruleVersionBody("v2", "频率加密版本", new int[][]{
                            {0, 10, 2}, {11, 30, 4}, {31, 48, 6}
                    }), null));
            assertSuccess(resp, "发布v2规则");
            v2Id = resp.path("data").path("id").asLong();
            check(v2Id != v1Id, "新版本应获得新主键");

            JsonNode dup = json(call(HttpMethod.POST, "/api/aqua/wq/rules/versions",
                    ruleVersionBody("v2", "重复版本号", new int[][]{{0, 10, 2}}), null));
            check(!dup.path("success").asBoolean(true), "重复规则版本号未被拦截");
        });

        checkpoint("CP4", "创建采样计划：按池按阶段频率生成任务（有批次锚定投苗日，空池锚定计划开始日）", () -> {
            Map<String, Object> body = new HashMap<>();
            body.put("planNo", "WQP-2026-001");
            body.put("planName", "秋冬季水质采样计划");
            body.put("startDate", "2026-10-03");
            body.put("endDate", "2026-11-20");
            body.put("pondIds", Arrays.asList(pondId1, pondId2));
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/wq/plans", body, null));
            assertSuccess(resp, "创建采样计划");
            planId = resp.path("data").path("id").asLong();
            check(v2Id == resp.path("data").path("ruleVersionId").asLong(),
                    "未指定版本时应使用最新已发布版本 v2");

            List<WqSamplingTask> pond1Tasks = tasksOf(planId, pondId1, null);
            List<WqSamplingTask> pond2Tasks = tasksOf(planId, pondId2, null);
            // v2：育苗期 0,2,4,6,8,10；中期 11,15,19,23,27；育肥期 31,37,43
            check(pond1Tasks.size() == 14, "1号池应生成14条任务，实际=" + pond1Tasks.size());
            check(pond2Tasks.size() == 14, "2号空池应同样按14条排期，实际=" + pond2Tasks.size());
            check(pond1Tasks.stream().allMatch(t -> WqSamplingTask.STATUS_PENDING.equals(t.getStatus())),
                    "新生成任务必须全部为未开始 PENDING");
            check(pond1Tasks.stream().allMatch(t -> t.getRuleVersionId().equals(v2Id)),
                    "新任务规则版本快照应为 v2");
            WqSamplingTask first = pond1Tasks.get(0);
            check(LocalDate.of(2026, 10, 3).equals(first.getPlannedDate()),
                    "首个采样日应为投苗当日：" + first.getPlannedDate());
            check("SEEDLING".equals(first.getStageCode()), "首日应处于育苗期：" + first.getStageCode());
            check(first.getBatchId().equals(batchId), "1号池任务应关联在养批次");
            check(pond2Tasks.get(0).getBatchId() == null, "空池任务不应强绑批次");
        });

        checkpoint("CP5", "采样员暂存草稿：可只填部分指标，任务进入 DRAFT", () -> {
            draftTaskId = tasksOf(planId, pondId1, WqSamplingTask.STATUS_PENDING).get(0).getId();
            Map<String, Object> body = new HashMap<>();
            body.put("measurements", Arrays.asList(
                    metric("SALINITY", "29.2", null),
                    metric("TEMP", "26.1", null)));
            JsonNode resp = json(call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + draftTaskId + "/draft", body, null));
            assertSuccess(resp, "暂存草稿");

            WqSamplingTask task = taskMapper.selectById(draftTaskId);
            check(WqSamplingTask.STATUS_DRAFT.equals(task.getStatus()), "草稿状态应为 DRAFT");
            Long draftRows = measurementMapper.selectCount(new QueryWrapper<WqMeasurement>()
                    .eq("task_id", draftTaskId).eq("record_type", WqMeasurement.TYPE_ORIGINAL));
            check(draftRows == 2L, "草稿应只落2条指标，实际=" + draftRows);
        });

        checkpoint("CP6", "指标规则新版本只作用于未开始任务：草稿任务保留 v2，PENDING 按 v3 重排", () -> {
            JsonNode published = json(call(HttpMethod.POST, "/api/aqua/wq/rules/versions",
                    ruleVersionBody("v3", "频率调整版本", new int[][]{
                            {0, 10, 3}, {11, 30, 5}, {31, 48, 7}
                    }), null));
            assertSuccess(published, "发布v3规则");
            v3Id = published.path("data").path("id").asLong();

            JsonNode applied = json(call(HttpMethod.POST,
                    "/api/aqua/wq/plans/" + planId + "/apply-version?ruleVersionId=" + v3Id, null, null));
            assertSuccess(applied, "计划应用v3版本");

            WqSamplingTask draft = taskMapper.selectById(draftTaskId);
            check(draft.getRuleVersionId().equals(v2Id),
                    "已开始（草稿）任务规则版本不得被新版本改动，应为 v2，实际=" + draft.getRuleVersionId());
            check(WqSamplingTask.STATUS_DRAFT.equals(draft.getStatus()), "草稿任务状态不应被重排影响");
            Long draftRows = measurementMapper.selectCount(new QueryWrapper<WqMeasurement>()
                    .eq("task_id", draftTaskId));
            check(draftRows == 2L, "草稿数据不应丢失，实际行数=" + draftRows);

            // v3 育苗期间隔3天：第2天(10-05)任务应移除，第3天(10-06)应新增并指向 v3
            check(tasksOf(planId, pondId1, null).stream()
                            .noneMatch(t -> LocalDate.of(2026, 10, 5).equals(t.getPlannedDate())),
                    "v3 下10-05不再采样，旧未开始任务应被移除");
            List<WqSamplingTask> day06 = tasksOf(planId, pondId1, null).stream()
                    .filter(t -> LocalDate.of(2026, 10, 6).equals(t.getPlannedDate()))
                    .collect(Collectors.toList());
            check(day06.size() == 1, "v3 下10-06应生成1条任务");
            check(day06.get(0).getRuleVersionId().equals(v3Id), "新排期任务必须指向 v3");
            check(WqSamplingTask.STATUS_PENDING.equals(day06.get(0).getStatus()), "新排期任务应为未开始");

            // 1号池：1条草稿(day0) + 育苗(3,6,9) + 中期(11,16,21,26) + 育肥(31,38,45) = 11
            check(tasksOf(planId, pondId1, null).size() == 11,
                    "1号池重排后应为11条，实际=" + tasksOf(planId, pondId1, null).size());
            // 空池2号：day0 未开始可重排 → 育苗(0,3,6,9) + 4 + 3 = 11
            check(tasksOf(planId, pondId2, null).size() == 11,
                    "2号池重排后应为11条，实际=" + tasksOf(planId, pondId2, null).size());
        });

        checkpoint("CP7", "提交校验：缺测项不说明原因必须被拒绝；说明原因后提交成功", () -> {
            // 氨氮缺测且未填原因 → 拒绝
            Map<String, Object> bad = new HashMap<>();
            bad.put("measurements", Arrays.asList(
                    metric("SALINITY", "29.2", null),
                    metric("TEMP", "26.1", null),
                    metric("PH", "8.10", null),
                    metric("DO", "6.8", null),
                    metric("NH3N", null, "  ")));
            JsonNode rejected = json(call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + draftTaskId + "/submit", bad, null));
            check(!rejected.path("success").asBoolean(true), "缺测未说明原因的提交应被拦截");
            check(rejected.path("message").asText().contains("缺测"),
                    "拦截提示应指明缺测：" + rejected.path("message").asText());
            // 事务回滚：仍是草稿2行
            Long rows = measurementMapper.selectCount(new QueryWrapper<WqMeasurement>()
                    .eq("task_id", draftTaskId));
            check(rows == 2L, "被拒提交不应落库新增指标，实际行数=" + rows);

            Map<String, Object> good = new HashMap<>();
            good.put("sampledAt", "2026-10-03T08:30:00");
            good.put("measurements", Arrays.asList(
                    metric("SALINITY", "29.2", null),
                    metric("TEMP", "26.1", null),
                    metric("PH", "8.10", null),
                    metric("DO", "6.8", null),
                    metric("NH3N", null, "采样瓶意外破裂，当日无备用水样")));
            JsonNode ok = json(call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + draftTaskId + "/submit", good, null));
            assertSuccess(ok, "带缺测原因的提交");
            WqSamplingTask task = taskMapper.selectById(draftTaskId);
            check(WqSamplingTask.STATUS_SUBMITTED.equals(task.getStatus()),
                    "提交后状态应为 SUBMITTED，实际=" + task.getStatus());
            check(task.getSubmittedAt() != null, "提交时间应留痕");

            // 审核前曲线不得有点
            Long curveBeforeReview = curveMapper.selectCount(new QueryWrapper<WqHealthCurvePoint>()
                    .eq("pond_id", pondId1));
            check(curveBeforeReview == 0L, "主管审核前数据不得进入健康曲线，实际点数=" + curveBeforeReview);

            // 提交后原测冻结：再次走草稿/提交接口改值必须被拒
            JsonNode frozen = json(call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + draftTaskId + "/draft", good, null));
            check(!frozen.path("success").asBoolean(true), "已提交任务不应允许再编辑覆盖");
        });

        checkpoint("CP8", "主管审核通过后才进入池塘健康曲线（缺测项不产生点）", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/wq/tasks/" + draftTaskId + "/review",
                    review(true, "数据真实有效，同意入曲线"), null));
            assertSuccess(resp, "主管审核通过");
            WqSamplingTask task = taskMapper.selectById(draftTaskId);
            check(WqSamplingTask.STATUS_APPROVED.equals(task.getStatus()), "审核后状态应为 APPROVED");

            List<WqHealthCurvePoint> points = curveMapper.selectList(new QueryWrapper<WqHealthCurvePoint>()
                    .eq("pond_id", pondId1).eq("task_id", draftTaskId));
            check(points.size() == 4, "4个有值指标应入曲线（缺测氨氮无点），实际=" + points.size());
            check(points.stream().allMatch(p -> "ORIGINAL".equals(p.getSource())),
                    "首次入曲线来源应为 ORIGINAL");
            check(points.stream().noneMatch(p -> "NH3N".equals(p.getMetricCode())),
                    "缺测项不得产生曲线点");
        });

        checkpoint("CP9", "缺测补录只能追加不能覆盖原值，补录审核后才更新曲线", () -> {
            // 对缺测的氨氮补录
            Map<String, Object> sup = new HashMap<>();
            sup.put("metricCode", "NH3N");
            sup.put("value", "0.15");
            sup.put("supplementReason", "次日同点位补测，天气与采样工况一致");
            JsonNode resp = json(call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + draftTaskId + "/supplements", sup, null));
            assertSuccess(resp, "追加补录");
            Long supplementId1 = resp.path("data").path("id").asLong();

            // 原测行保持缺测：值仍为空、缺测原因仍在——补录不覆盖原值
            WqMeasurement originalNh3n = measurementMapper.selectOne(new QueryWrapper<WqMeasurement>()
                    .eq("task_id", draftTaskId).eq("metric_code", "NH3N")
                    .eq("record_type", WqMeasurement.TYPE_ORIGINAL));
            check(originalNh3n.getMetricValue() == null, "补录不得覆盖原测值，原测必须仍为空");
            check(originalNh3n.getMissingReason() != null && originalNh3n.getMissingReason().contains("采样瓶"),
                    "原测缺测原因必须保留");
            check(WqMeasurement.REVIEW_APPROVED.equals(originalNh3n.getReviewStatus()),
                    "原测行审核状态不应被补录改变");

            // 待审核补录不允许重复追加
            JsonNode dup = json(call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + draftTaskId + "/supplements", sup, null));
            check(!dup.path("success").asBoolean(true), "已有待审核补录时应拒绝再次追加");

            // 补录审核前曲线无氨氮点
            Long nh3nPoints = curveMapper.selectCount(new QueryWrapper<WqHealthCurvePoint>()
                    .eq("task_id", draftTaskId).eq("metric_code", "NH3N"));
            check(nh3nPoints == 0L, "补录审核前不得进入曲线");

            // 审核通过 → 曲线新增氨氮点，来源 SUPPLEMENT，但原测行依旧不动
            JsonNode approved = json(call(HttpMethod.POST,
                    "/api/aqua/wq/supplements/" + supplementId1 + "/review",
                    review(true, "补测过程可信"), null));
            assertSuccess(approved, "补录审核通过");
            WqHealthCurvePoint nh3nPoint = curveMapper.selectOne(new QueryWrapper<WqHealthCurvePoint>()
                    .eq("task_id", draftTaskId).eq("metric_code", "NH3N"));
            check(nh3nPoint != null, "补录审核通过后曲线应出现氨氮点");
            check(new BigDecimal("0.15").compareTo(nh3nPoint.getMetricValue()) == 0,
                    "曲线氨氮点应为补录值0.15：" + nh3nPoint.getMetricValue());
            check("SUPPLEMENT".equals(nh3nPoint.getSource()), "曲线点来源应标记为 SUPPLEMENT");
            check(measurementMapper.selectById(originalNh3n.getId()).getMetricValue() == null,
                    "曲线更新后测量原值行仍不得被修改");

            // 第二次补录：只追加，形成补录链；审核通过后曲线点更新为新值，历史补录行保留
            Map<String, Object> sup2 = new HashMap<>();
            sup2.put("metricCode", "NH3N");
            sup2.put("value", "0.12");
            sup2.put("supplementReason", "第三日实验室复检得到更稳定读数");
            JsonNode resp2 = json(call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + draftTaskId + "/supplements", sup2, null));
            assertSuccess(resp2, "第二次追加补录");
            Long supplementId2 = resp2.path("data").path("id").asLong();
            check(!supplementId2.equals(supplementId1), "补录必须新增行而非复用旧行");
            json(call(HttpMethod.POST, "/api/aqua/wq/supplements/" + supplementId2 + "/review",
                    review(true, "同意采用复检值"), null));
            WqHealthCurvePoint updated = curveMapper.selectOne(new QueryWrapper<WqHealthCurvePoint>()
                    .eq("task_id", draftTaskId).eq("metric_code", "NH3N"));
            check(new BigDecimal("0.12").compareTo(updated.getMetricValue()) == 0,
                    "曲线点应更新为第二次补录值0.12：" + updated.getMetricValue());
            List<WqMeasurement> allNh3n = measurementMapper.selectList(new QueryWrapper<WqMeasurement>()
                    .eq("task_id", draftTaskId).eq("metric_code", "NH3N").orderByAsc("id"));
            check(allNh3n.size() == 3, "氨氮应保留 原测+2条补录 共3行，实际=" + allNh3n.size());
            check(allNh3n.get(0).getMetricValue() == null, "最早原测值始终不被覆盖");
            check(new BigDecimal("0.15").compareTo(allNh3n.get(1).getMetricValue()) == 0
                            && new BigDecimal("0.12").compareTo(allNh3n.get(2).getMetricValue()) == 0,
                    "两条补录值应按时间原样保留（只追加）");

            // 驳回补录：曲线保持原值
            Map<String, Object> sup3 = new HashMap<>();
            sup3.put("metricCode", "NH3N");
            sup3.put("value", "0.01");
            sup3.put("supplementReason", "存疑样本，申请替换");
            JsonNode resp3 = json(call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + draftTaskId + "/supplements", sup3, null));
            Long supplementId3 = resp3.path("data").path("id").asLong();
            JsonNode rejectNoComment = json(call(HttpMethod.POST,
                    "/api/aqua/wq/supplements/" + supplementId3 + "/review",
                    review(false, "  "), null));
            check(!rejectNoComment.path("success").asBoolean(true), "驳回补录必须填写意见");
            json(call(HttpMethod.POST, "/api/aqua/wq/supplements/" + supplementId3 + "/review",
                    review(false, "样本来源不可追溯，驳回"), null));
            WqHealthCurvePoint afterReject = curveMapper.selectOne(new QueryWrapper<WqHealthCurvePoint>()
                    .eq("task_id", draftTaskId).eq("metric_code", "NH3N"));
            check(new BigDecimal("0.12").compareTo(afterReject.getMetricValue()) == 0,
                    "补录驳回后曲线应保持0.12不变：" + afterReject.getMetricValue());
        });

        checkpoint("CP10", "重复请求幂等：先提交有效样本，再重复请求，两次返回不能产生第二条结果", () -> {
            // 10-1 采样计划重复请求：同一 Idempotency-Key 创建计划两次，只产生一份计划和一套任务
            Map<String, Object> planBody = new HashMap<>();
            planBody.put("planNo", "WQP-2026-002");
            planBody.put("planName", "幂等校验计划");
            planBody.put("startDate", "2026-10-03");
            planBody.put("endDate", "2026-10-13");
            planBody.put("pondIds", Arrays.asList(pondId1));
            String planKey = "wq-plan-wqp2026002-0001";
            ResponseEntity<String> plan1 = call(HttpMethod.POST, "/api/aqua/wq/plans", planBody, planKey);
            ResponseEntity<String> plan2 = call(HttpMethod.POST, "/api/aqua/wq/plans", planBody, planKey);
            JsonNode pj1 = json(plan1);
            JsonNode pj2 = json(plan2);
            assertSuccess(pj1, "首次创建计划");
            assertSuccess(pj2, "重复创建计划应回放成功");
            check(pj1.path("data").path("id").asLong() == pj2.path("data").path("id").asLong(),
                    "两次返回必须是同一条计划");
            check(!"true".equalsIgnoreCase(plan1.getHeaders().getFirst("Idempotency-Replay")),
                    "首次请求不应是回放");
            check("true".equalsIgnoreCase(plan2.getHeaders().getFirst("Idempotency-Replay")),
                    "第二次请求必须回放首次响应");
            Long planCount = planMapper.selectCount(new QueryWrapper<WqSamplingPlan>()
                    .eq("plan_no", "WQP-2026-002"));
            check(planCount == 1L, "重复请求不得产生第二条计划，实际=" + planCount);
            Long plan2Id = pj1.path("data").path("id").asLong();
            check(tasksOf(plan2Id, pondId1, null).size() == 4,
                    "计划任务只能生成一套（v3育苗期0,3,6,9共4条）");
            Long keyRows = idempotentMapper.selectCount(new QueryWrapper<WqIdempotentRequest>()
                    .eq("request_key", planKey));
            check(keyRows == 1L, "同一幂等键只能有一条请求记录，实际=" + keyRows);

            // 10-2 缺测补录重复请求：同一 Idempotency-Key 补录两次，只追加一条
            Map<String, Object> sup = new HashMap<>();
            sup.put("metricCode", "NH3N");
            sup.put("value", "0.13");
            sup.put("supplementReason", "周度平行样复核");
            String supKey = "wq-supplement-" + draftTaskId + "-0001";
            ResponseEntity<String> s1 = call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + draftTaskId + "/supplements", sup, supKey);
            ResponseEntity<String> s2 = call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + draftTaskId + "/supplements", sup, supKey);
            check(json(s1).path("data").path("id").asLong() == json(s2).path("data").path("id").asLong(),
                    "补录重复请求必须回放同一条补录记录");
            check("true".equalsIgnoreCase(s2.getHeaders().getFirst("Idempotency-Replay")),
                    "第二次补录请求必须命中回放");
            Long supCount = measurementMapper.selectCount(new QueryWrapper<WqMeasurement>()
                    .eq("task_id", draftTaskId).eq("record_type", WqMeasurement.TYPE_SUPPLEMENT));
            check(supCount == 4L, "补录重复请求不得产生第二条结果，期望4条（CP9三条+本条），实际=" + supCount);

            // 10-3 有效采样样本重复提交：先提交有效样本，再重复请求，任务不产生第二套测量值
            WqSamplingTask pending = tasksOf(planId, pondId1, WqSamplingTask.STATUS_PENDING).stream()
                    .filter(t -> LocalDate.of(2026, 10, 6).equals(t.getPlannedDate()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("找不到10-06未开始任务"));
            Map<String, Object> submitBody = new HashMap<>();
            submitBody.put("sampledAt", "2026-10-06T08:00:00");
            submitBody.put("measurements", Arrays.asList(
                    metric("SALINITY", "30.1", null),
                    metric("TEMP", "25.8", null),
                    metric("PH", "8.05", null),
                    metric("DO", "7.1", null),
                    metric("NH3N", "0.08", null)));
            String submitKey = "wq-submit-" + pending.getId() + "-0001";
            ResponseEntity<String> t1 = call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + pending.getId() + "/submit", submitBody, submitKey);
            ResponseEntity<String> t2 = call(HttpMethod.POST,
                    "/api/aqua/wq/tasks/" + pending.getId() + "/submit", submitBody, submitKey);
            check(json(t1).path("data").path("id").asLong() == json(t2).path("data").path("id").asLong(),
                    "重复提交必须返回同一个任务");
            check("true".equalsIgnoreCase(t2.getHeaders().getFirst("Idempotency-Replay")),
                    "第二次采样提交必须回放首次响应");
            check(WqSamplingTask.STATUS_SUBMITTED.equals(taskMapper.selectById(pending.getId()).getStatus()),
                    "有效样本提交后任务应为 SUBMITTED");
            Long valueRows = measurementMapper.selectCount(new QueryWrapper<WqMeasurement>()
                    .eq("task_id", pending.getId()).eq("record_type", WqMeasurement.TYPE_ORIGINAL));
            check(valueRows == 5L, "重复请求不得产生第二套测量值，期望5行实际=" + valueRows);
        });

        checkpoint("CP11", "所有修改保留证据：规则发布/排期重指/草稿/提交/审核/补录/曲线全程可查", () -> {
            List<WqChangeEvidence> all = evidenceMapper.selectList(
                    new QueryWrapper<WqChangeEvidence>().orderByAsc("id"));
            Set<String> actions = all.stream().map(WqChangeEvidence::getAction).collect(Collectors.toSet());
            for (String expected : Arrays.asList("PUBLISH", "CREATE", "APPLY_RULE_VERSION",
                    "SAVE_DRAFT", "SUBMIT", "APPROVE",
                    "SUPPLEMENT_APPEND", "SUPPLEMENT_APPROVE", "SUPPLEMENT_REJECT",
                    "CURVE_WRITE", "CURVE_UPDATE")) {
                check(actions.contains(expected), "证据链缺少动作：" + expected);
            }
            // 补录证据必须保留修改前后的内容（原值 vs 追加值）
            WqChangeEvidence supplementEvidence = all.stream()
                    .filter(e -> "SUPPLEMENT_APPEND".equals(e.getAction())
                            && e.getDetail() != null && e.getDetail().contains("氨氮"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("缺少氨氮补录证据"));
            check(supplementEvidence.getBeforeJson() != null && supplementEvidence.getAfterJson() != null,
                    "补录证据必须同时保留修改前原测与修改后补录内容");
            check(supplementEvidence.getOperator() != null, "证据必须记录操作人");

            // 证据接口可按任务查询
            JsonNode resp = json(call(HttpMethod.GET,
                    "/api/aqua/wq/tasks/" + draftTaskId + "/evidence", null, null));
            assertSuccess(resp, "查询任务证据链");
            check(resp.path("data").size() >= 5, "任务证据链应包含草稿/提交/审核/补录等多条记录");
        });

        report.add("验收结论：全部检查点通过——采样计划按池排期、草稿/缺测规则、补录只追加、"
                + "新版本只作用于未开始任务、审核入曲线、证据链与重复请求幂等均成立");
        printReport();
    }

    // ------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------

    private List<WqSamplingTask> tasksOf(Long planId, Long pondId, String status) {
        return taskMapper.selectList(new QueryWrapper<WqSamplingTask>()
                .eq("plan_id", planId)
                .eq("pond_id", pondId)
                .eq(status != null, "status", status)
                .orderByAsc("planned_date"));
    }

    private Long createPond(String code, String name, String salinity) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("pondCode", code);
        body.put("pondName", name);
        body.put("seaArea", "东山湾海区");
        body.put("waterDepthM", "1.80");
        body.put("salinityPpt", salinity);
        JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/ponds", body, null));
        assertSuccess(resp, "建池[" + code + "]");
        return resp.path("data").path("id").asLong();
    }

    private Map<String, Object> ruleVersionBody(String versionNo, String remark, int[][] stages) {
        List<Map<String, Object>> stageList = new ArrayList<>();
        String[][] names = {{"SEEDLING", "育苗期"}, {"MIDGROWTH", "养成中期"}, {"FATTENING", "育肥期"}};
        String[][] metrics = {
                {"SALINITY", "盐度", "ppt", "25", "35"},
                {"TEMP", "水温", "℃", "18", "32"},
                {"PH", "pH", "", "7.5", "8.8"},
                {"DO", "溶解氧", "mg/L", "5", "12"},
                {"NH3N", "氨氮", "mg/L", "0", "0.2"}
        };
        for (int i = 0; i < stages.length; i++) {
            Map<String, Object> stage = new HashMap<>();
            stage.put("stageCode", names[i][0]);
            stage.put("stageName", names[i][1]);
            stage.put("startDay", stages[i][0]);
            stage.put("endDay", stages[i][1]);
            stage.put("intervalDays", stages[i][2]);
            List<Map<String, Object>> metricList = new ArrayList<>();
            for (String[] m : metrics) {
                Map<String, Object> metric = new HashMap<>();
                metric.put("metricCode", m[0]);
                metric.put("metricName", m[1]);
                metric.put("unit", m[2]);
                metric.put("minValue", m[3]);
                metric.put("maxValue", m[4]);
                metric.put("required", true);
                metricList.add(metric);
            }
            stage.put("metrics", metricList);
            stageList.add(stage);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("versionNo", versionNo);
        body.put("remark", remark);
        body.put("stages", stageList);
        return body;
    }

    private Map<String, Object> metric(String code, String value, String missingReason) {
        Map<String, Object> m = new HashMap<>();
        m.put("metricCode", code);
        m.put("value", value);
        m.put("missingReason", missingReason);
        return m;
    }

    private Map<String, Object> review(boolean approved, String comment) {
        Map<String, Object> m = new HashMap<>();
        m.put("approved", approved);
        m.put("comment", comment);
        return m;
    }

    private void checkpoint(String id, String name, CheckpointBody body) {
        try {
            body.run();
            String line = "通过 " + id + " " + name;
            report.add(line);
            System.out.println("[验收] " + line);
        } catch (Throwable t) {
            String stop = "停点 " + id + "「" + name + "」失败，原因：" + t.getMessage();
            report.add(stop);
            report.add("验收结论：未通过，后续检查点未执行");
            printReport();
            fail("验收" + stop);
        }
    }

    private void printReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n============ 水质采样计划与缺测补录验收报告 ============\n");
        for (String line : report) {
            sb.append(line).append('\n');
        }
        sb.append("=======================================================\n");
        System.out.println(sb);
    }

    private ResponseEntity<String> call(HttpMethod method, String url, Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sessionCookie != null) {
            headers.add(HttpHeaders.COOKIE, sessionCookie);
        }
        if (idempotencyKey != null) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private void assertSuccess(JsonNode node, String what) {
        if (!node.path("success").asBoolean(false)) {
            throw new IllegalStateException(what + "接口返回失败：" + node.path("message").asText());
        }
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private interface CheckpointBody {
        void run() throws Exception;
    }
}
