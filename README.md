# 培训机构试听课排班转化退费与课消服务（training-crm）

覆盖培训机构从 **线索 → 试听排班 → 多次改约容量回收/候补 → 试听反馈 → 报名转化 → 排课课消 → 请假/转班/停课 → 退费 → 校长履约轨迹** 的完整业务闭环。

- 技术栈：Kotlin 1.9 + Spring Boot 3.3 + Spring Data JPA（H2 内存库）+ Redis 7（分布式锁 / 幂等）+ springdoc-openapi（Swagger UI）
- 构建：Maven（`pom.xml`），JDK 17

## 一、本地启动

### 方式 A：Docker Compose 一键启动（推荐，无需本机 Redis/JDK）

```bash
docker compose up --build
# 应用 http://localhost:8080
# Swagger UI  http://localhost:8080/swagger-ui.html
# OpenAPI 文档 http://localhost:8080/v3/api-docs
```

Compose 会同时启动 Redis 7 与应用（健康检查通过后应用才启动）。

### 方式 B：本机直接运行

```bash
# 1. 启动 Redis（默认 localhost:6379，可用 REDIS_HOST/REDIS_PORT 覆盖）
redis-server --daemonize yes

# 2. 启动应用（二选一）
mvn spring-boot:run
# 或
mvn package -DskipTests && java -jar target/training-crm-1.0.0.jar
```

应用监听 `http://localhost:8080`。H2 为内存库，重启后数据重置并重新播种；H2 控制台：`/h2-console`（JDBC URL `jdbc:h2:mem:trainingdb`，用户 `sa`，空密码）。

## 二、测试账号（角色）

鉴权为轻量请求头模式（无口令）：

| 请求头 | 说明 |
|---|---|
| `X-Role` | 必填：`ADMIN`（教务/管理员）、`CONSULTANT`（顾问）、`TEACHER`（老师）、`PRINCIPAL`（校长/顾问主管） |
| `X-Actor` | 可选，操作人姓名，写入履约轨迹（支持中文） |
| `X-Idempotency-Key` | 可选，线索创建/退费申请的幂等键 |

- `/api/principal/**` 仅 `PRINCIPAL` / `ADMIN` 可访问，其余角色 403；缺 `X-Role` 返回 401。
- 第 3 次及以上试听改约仅 `PRINCIPAL` / `ADMIN` 可审批，顾问调用返回 403（`FORBIDDEN`）。

种子数据（启动时自动写入）：

| 数据 | 内容 |
|---|---|
| 校区 | #1 中关村校区、#2 望京校区 |
| 顾问 | #1 王顾问（中关村）、#2 李顾问（望京） |
| 老师 | #1 张老师（数学，**跨校区共享** 中关村+望京）、#2 刘老师（英语，中关村）、#3 陈老师（数学/物理，望京）；空档均为工作日 18:00-21:00、周末 09:00-18:00 |
| 教室 | A101(4人)/A102(8人)@中关村、B201(6人)@望京 |
| 课包 | #1 数学思维提高班课包（48课时 ¥9600，教材费 ¥300，赠课4）；#2 英语听说基础课包（48课时 ¥8800，教材费 ¥200，赠课2） |
| 班级 | #1 数学提高1班（中关村，张老师，容量4） |

可调参数（`application.yml`）：`app.trial.near-start-hours=24`（距开课不足该小时数算"临近开课"）、`app.trial.supervisor-approval-count=3`（第几次改约起需主管审批）、`app.trial.hot-teacher-weekly-slots=3`（未来 7 天试听位达到该数视为热门老师）。

## 三、核心业务规则

**试听排班匹配**（`POST /api/trials/book`）：自动匹配 ①老师科目=线索目标科目 ②老师在线索校区可授课（多校区共享）③老师空档覆盖整个时段 ④老师/教室时段无冲突；教材版本按科目默认，试听水平=学生基础水平。老师+时段、教室+时段用 Redis 锁防并发双订。

**多次改约的容量回收**（`POST /api/trials/{id}/reschedule`，必带 `reason`）：

1. 每次改约完整留痕（`TrialRescheduleRecord`）：原/新老师、原/新教室、原/新时段、**改约原因、是否临近开课（≤24h）、顾问确认情况（consultantConfirmed/confirmedBy）、第几次改约**；
2. 改约一经执行立即**释放原老师档期与原教室容量**，并按候补入队顺序（FIFO）自动补位：第一个仍有效的候补线索生成新试听课（`TRIAL_WAITLIST_PROMOTED`），被释放的试听位是否被占用回写到本次改约记录；
3. **第 3 次改约起需顾问主管审批**：提交时只挂起审批单（`approvalRequired=true`），原老师/教室档期保持占用不释放，避免热门老师试听位被长期空占；主管（`PRINCIPAL/ADMIN`）批准后才执行容量回收与候补补位，驳回则试听保留原时段。审批期间重复提交改约被拦截；
4. 若释放的试听位已被候补学生占用，**原家长再次选择同一时段时返回 409 `SLOT_TAKEN_REQUEUE`，提示需重新排队候补**；
5. **学生到课后老师可查看改约历史与家长关注点**（`GET /api/trials/{id}/briefing`）：历次改约原因、临近开课改约次数、主管审批次数、容量被候补补位次数、学生基础信息与试听反馈；
6. **试听反馈影响该线索转化统计**（`GET /api/principal/conversion-stats`）：按测评分数段（<60/60-74/75-89/≥90）、按改约次数（0/1-2/≥3 次主管审批）、风险提醒、临近开课改约、容量被候补占用后重新排队等维度统计转化率；报名转化时额外落 `TRIAL_SIGNAL_ON_CONVERSION` 事件；
7. **顾问端容量变化转化风险视图**（`GET /api/consultants/risks?consultantId=`）：待主管审批、多次改约、临近开课、热门老师档期（近 7 天试听位≥3）、释放位被候补占用需重新排队等风险，按 HIGH/MEDIUM/LOW 排序。

**候补排队**（`POST /api/trials/waitlist`）：目标老师时段已满时登记（指定老师/教室/时段）；该时段仍有空位时返回 `SLOT_AVAILABLE` 引导直接预约；可取消（`/waitlist/{id}/cancel`）。他人改约释放容量时按 FIFO 自动补位。

**课消规则**（`POST /api/courses/lessons/{id}/complete`）：到课扣 1 课时；缺席照常扣课时（`app.absent-consumes-lesson`）；已批准请假不扣课时，事后批准会返还已扣课时；扣课顺序按合同 `consumeOrder`（先付费 PAID_FIRST / 先赠课 GIFT_FIRST），付费课时耗尽后扣赠课，余额不足记告警事件。

**退费计算**（`GET /api/refunds/preview/{studentId}` 试算 / `POST /api/refunds` 申请）：

```
课时单价   = 实付金额 / 付费课时数
基础退费   = 剩余付费课时 × 单价            （赠课不退现）
赠课抵扣   = GIFT_FIRST 时已消耗赠课 × 单价 （PAID_FIRST 时为 0）
教材费     = 教材已发放则全额扣除
优惠回收   = 已上课时占比 < 课包阈值(默认50%) 时全额回收优惠
投诉赔付   = 未结投诉数 × 200（app.complaint-compensation）
应退金额   = max(0, 基础退费 - 教材费 - 优惠回收 - 赠课抵扣 + 投诉赔付)，并以已收款为上限
```

退费申请即 **冻结顾问提成**，审批打款后提成 **追回**、未收分期 **减免**、合同/学员/报班状态联动关闭；驳回则解冻提成。

**一致性校验**（`POST /api/conversions`）：报名时比对试听反馈与合同承诺——建议班型≠课包班型、建议水平≠课包水平、风险提醒与「保过/保分」类承诺冲突，任一命中即拒绝（`CONSISTENCY_WARNING`），需 `forceConfirm=true` 强制确认并留痕告警。

**校长履约轨迹**（`GET /api/principal/students/{id}/timeline`）：学员从线索进入、试听体验（含改约审批/容量回收/候补补位）、报名承诺、实际上课到退费结束的全部事件流 + 财务摘要；`GET /api/principal/overview` 为分校区、分顾问经营概览与渠道成本。

## 四、主要 API

| 模块 | 接口 |
|---|---|
| 基础数据 | `POST/GET /api/setup/campuses|classrooms|teachers|consultants|packages|classes`，`POST /api/setup/teachers/{id}/availability` |
| 线索 | `POST /api/leads`（幂等）、`GET /api/leads/{id}/events`（改约/候补/反馈事件流）、`POST /api/leads/{id}/campus`、`/{id}/promises`、`/{id}/lost` |
| 试听 | `POST /api/trials/book`、`/{id}/reschedule`（原因/临近开课/顾问确认；第3次起主管审批）、`POST /api/trials/waitlist`、`/waitlist/{id}/cancel`、`GET /api/trials/reschedule-approvals/pending`、`POST /api/trials/reschedule-approvals/{id}/approve|reject`、`GET /api/trials/{id}/briefing`（老师到课简报）、`/{id}/attend`、`/{id}/absent`、`/{id}/teacher-leave`、`/{id}/feedback` |
| 顾问风险 | `GET /api/consultants/risks?consultantId=`（容量变化与转化风险） |
| 转化 | `POST /api/conversions`（合同+分期+发票+提成+承诺+一致性校验）、`POST /api/conversions/installments/{id}/pay` |
| 排课课消 | `POST /api/courses/classes/{id}/enroll`（容量校验/插班标记）、`POST /api/courses/lessons`、`POST /api/courses/lessons/{id}/complete`、`POST /api/courses/lessons/{id}/teacher-leave`、`GET /api/courses/contracts/{id}/consumption` |
| 学员异动 | `POST /api/students/{id}/leave`、`POST /api/students/leave-requests/{id}/approve|reject`、`POST /api/students/{id}/transfer`、`POST /api/students/{id}/suspend|resume`、`POST /api/students/{id}/complaints` |
| 退费 | `GET /api/refunds/preview/{studentId}`、`POST /api/refunds`（幂等）、`POST /api/refunds/{id}/approve|reject` |
| 校长 | `GET /api/principal/students/{id}/timeline`、`/overview`、`/conversion-stats`（试听反馈影响转化统计） |
| 文档 | `GET /swagger-ui.html`（Swagger UI）、`GET /v3/api-docs`（OpenAPI 3.x） |

## 五、验证方式（已实际执行）

```bash
# 本地开发验证
redis-server --daemonize yes
mvn spring-boot:run                 # 或 docker compose up --build
bash scripts/verify.sh              # 需应用已启动；BASE 环境变量可改地址
```

> 请在**全新启动**的应用上运行（H2 为内存库，重启即重置为种子数据）；脚本会自行清理其使用的 Redis 幂等键。脚本动态挑选当前时间之后的老师空档槽位，任意时刻运行均可跑通。

脚本用 curl+jq 跑通 3 条学员主线及容量回收主线，共 **146 项断言**，重点新增覆盖：

1. **小明**：线索(幂等)→承诺→试听预约→**另一线索同档期满 → 候补排队 → 家长第1次改约释放张老师档期 → 候补自动补位（PROMOTED）→ 原家长再选该时段被 409 `SLOT_TAKEN_REQUEUE` 拦截要求重新排队**→ 第2次改约正常回收（无候补）→ **第3次改约挂起主管审批（顾问审批 403、待审批列表可见、审批期间保留原档期、重复提交拦截）→ 主管批准后改约执行（计数=3、审批留痕）**→ 老师请假 → 重约到课发赠品 → **老师简报含 3 条改约历史/家长关注点/临近开课/审批与补位统计**→ 反馈(62分,有风险)→报名(一致性拦截→强制确认,3条告警)→3期回款→入班→课消/请假返还→转班→停课/复课→投诉→退费 **应退7925** →打款→提成追回；
2. **小红**：英语线索→试听→反馈(85分,无告警)→转化→2期只回1期→退费**以已收4400为上限**、未付分期减免；
3. **小刚**：2付费+1赠课小包→插班→4节课→**课消付费2+赠课1**、第4节余额不足告警；
4. 另验证：鉴权 401/403、空改约原因拦截、有空位候补引导直接预约、排课时段冲突、**顾问风险视图 HIGH（重新排队提示）与热门老师标记**、**转化统计（分数段/改约≥3组/候补补位/容量回收分组）**、校长轨迹新增事件齐全（`TRIAL_RESCHEDULE_APPROVAL_REQUIRED/APPROVED`、`TRIAL_SLOT_RECLAIMED`、`TRIAL_WAITLIST_JOINED/PROMOTED`、`TRIAL_SIGNAL_ON_CONVERSION` 等）、经营概览。

**最近一次执行结果（2026-10-01，本机，全新启动应用）：`通过 146 项，失败 0 项`，VERIFY_RESULT: PASS。**
