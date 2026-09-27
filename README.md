# 培训机构试听课排班转化退费与课消服务（training-crm）

覆盖培训机构从 **线索 → 试听排班 → 试听反馈 → 报名转化 → 排课课消 → 请假/转班/停课 → 退费 → 校长履约轨迹** 的完整业务闭环。

- 技术栈：Kotlin 1.9 + Spring Boot 3.3 + Spring Data JPA（H2 内存库）+ Redis 7（分布式锁 / 幂等）
- 构建：Maven（`pom.xml`），JDK 17

## 一、本地启动

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
| `X-Role` | 必填：`ADMIN`（教务/管理员）、`CONSULTANT`（顾问）、`TEACHER`（老师）、`PRINCIPAL`（校长） |
| `X-Actor` | 可选，操作人姓名，写入履约轨迹（支持中文） |
| `X-Idempotency-Key` | 可选，线索创建/退费申请的幂等键 |

- `/api/principal/**` 仅 `PRINCIPAL` / `ADMIN` 可访问，其余角色 403；缺 `X-Role` 返回 401。

种子数据（启动时自动写入）：

| 数据 | 内容 |
|---|---|
| 校区 | #1 中关村校区、#2 望京校区 |
| 顾问 | #1 王顾问（中关村）、#2 李顾问（望京） |
| 老师 | #1 张老师（数学，**跨校区共享** 中关村+望京）、#2 刘老师（英语，中关村）、#3 陈老师（数学/物理，望京）；空档均为工作日 18:00-21:00、周末 09:00-18:00 |
| 教室 | A101(4人)/A102(8人)@中关村、B201(6人)@望京 |
| 课包 | #1 数学思维提高班课包（48课时 ¥9600，教材费 ¥300，赠课4）；#2 英语听说基础课包（48课时 ¥8800，教材费 ¥200，赠课2） |
| 班级 | #1 数学提高1班（中关村，张老师，容量4） |

## 三、核心业务规则

**试听排班匹配**（`POST /api/trials/book`）：自动匹配 ①老师科目=线索目标科目 ②老师在线索校区可授课（多校区共享）③老师空档覆盖整个时段 ④老师/教室时段无冲突；教材版本按科目默认，试听水平=学生基础水平。老师+时段、教室+时段用 Redis 锁防并发双订。

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

**校长履约轨迹**（`GET /api/principal/students/{id}/timeline`）：学员从线索进入、试听体验、报名承诺、实际上课到退费结束的全部事件流 + 合同/分期/发票/提成/渠道费/赠品/投诉财务摘要；`GET /api/principal/overview` 为分校区、分顾问经营概览与渠道成本。

## 四、主要 API

| 模块 | 接口 |
|---|---|
| 基础数据 | `POST/GET /api/setup/campuses|classrooms|teachers|consultants|packages|classes`，`POST /api/setup/teachers/{id}/availability` |
| 线索 | `POST /api/leads`（幂等）、`POST /api/leads/{id}/campus`（试听后换校区）、`POST /api/leads/{id}/promises`（家长承诺）、`POST /api/leads/{id}/lost` |
| 试听 | `POST /api/trials/book`、`/{id}/reschedule`（多次改约记风险）、`/{id}/attend`（参加活动发赠品）、`/{id}/absent`、`/{id}/teacher-leave`、`/{id}/feedback` |
| 转化 | `POST /api/conversions`（合同+分期+发票+提成+承诺+一致性校验）、`POST /api/conversions/installments/{id}/pay` |
| 排课课消 | `POST /api/courses/classes/{id}/enroll`（容量校验/插班标记）、`POST /api/courses/lessons`、`POST /api/courses/lessons/{id}/complete`、`POST /api/courses/lessons/{id}/teacher-leave`、`GET /api/courses/contracts/{id}/consumption` |
| 学员异动 | `POST /api/students/{id}/leave`、`POST /api/students/leave-requests/{id}/approve|reject`、`POST /api/students/{id}/transfer`（转班/跨校区）、`POST /api/students/{id}/suspend|resume`、`POST /api/students/{id}/complaints` |
| 退费 | `GET /api/refunds/preview/{studentId}`、`POST /api/refunds`（幂等）、`POST /api/refunds/{id}/approve|reject` |
| 校长 | `GET /api/principal/students/{id}/timeline`、`GET /api/principal/overview` |

## 五、验证方式（已实际执行）

```bash
bash scripts/verify.sh        # 需应用已启动；BASE 环境变量可改地址
```

> 请在**全新启动**的应用上运行（H2 为内存库，重启即重置为种子数据）；脚本会自行清理其使用的 Redis 幂等键。

脚本用 curl+jq 跑通 3 条学员主线共 90 项断言：

1. **小明**：线索(幂等)→承诺→试听(时段冲突拦截/改约3次触发风险/老师请假/重约/到课发赠品)→反馈→报名(一致性拦截→强制确认,3条告警)→3期分期回款→入班→3节课(2到课1缺席扣课时)→请假批准返还课时→跨校区转班→停课/复课→投诉→退费试算(已上2课时,单价187.5,基础退费8625,教材费-300,优惠回收-600,投诉赔付+200,提成冻结450,**应退7925**)→审批打款→提成追回；
2. **小红**：英语线索→试听缺席→重约→转化(无告警)→2期分期只回1期→退费**以已收4400为上限**、未付分期减免；
3. **小刚**：2付费+1赠课小包→插班(中途入班标记)→4节课→**课消付费2+赠课1**、第4节余额不足告警；
4. 另验证：鉴权 401/403、排课老师时段冲突、校长轨迹 28 类事件齐全、经营概览。

**最近一次执行结果（2026-09-25，本机）：`通过 90 项，失败 0 项`，VERIFY_RESULT: PASS。**
