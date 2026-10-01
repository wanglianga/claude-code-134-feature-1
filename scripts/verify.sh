#!/usr/bin/env bash
# 端到端验证：线索 → 试听排班 → 改约容量回收/候补补位/主管审批 → 反馈 → 报名转化
#           → 排课课消 → 请假/转班/停课 → 退费 → 校长履约轨迹与转化统计
# 前置：应用已在 ${BASE:-http://localhost:8080} 运行，Redis 可用。
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
PASS=0; FAIL=0

# 清理本脚本使用的幂等键（H2 为内存库，应用重启后旧幂等键会指向已不存在的数据）
redis-cli del idem:lead:lead-xm-001 idem:refund:refund-xm-001 > /dev/null 2>&1 || true

check() { # $1=描述 $2=实际 $3=期望
  if [[ "$2" == "$3" ]]; then PASS=$((PASS+1)); echo "  ✔ $1";
  else FAIL=$((FAIL+1)); echo "  ✘ $1  [期望=$3 实际=$2]"; fi
}
check_true() { # $1=描述 $2=实际(非空即真)
  if [[ -n "$2" && "$2" != "null" && "$2" != "false" ]]; then PASS=$((PASS+1)); echo "  ✔ $1";
  else FAIL=$((FAIL+1)); echo "  ✘ $1  [实际=$2]"; fi
}
post() { # $1=path $2=role $3=body $4..=额外header
  local path=$1 role=$2 body=$3; shift 3
  curl -s -X POST "$BASE$path" -H "X-Role: $role" -H "Content-Type: application/json" "$@" -d "$body"
}
get() { curl -s "$BASE$1" -H "X-Role: $2"; }

SUN=$(date -d "next Sunday" +%F)
SAT2=$(date -d "$(date -d 'next Saturday' +%F) +7 days" +%F)
SAT3=$(date -d "$(date -d 'next Saturday' +%F) +14 days" +%F)

# 动态挑选老师空档内 3 个互不重叠的近期槽位（工作日 18-21 / 周末 09-18），
# 保证改约流程在任意运行时刻都能跑通；并输出各槽位是否"临近开课"(<=24h)的期望值。
eval "$(python3 - <<'PY'
from datetime import datetime, timedelta, time as dtime
now = datetime.now()
cands = []
d0 = (now + timedelta(minutes=45)).date()
for i in range(5):
    day = d0 + timedelta(days=i)
    dow = day.isoweekday()
    ends = 21 if dow <= 5 else 18
    starts = [18] if dow <= 5 else [9]
    for h in starts:
        s = datetime.combine(day, dtime(h, 0))
        e = datetime.combine(day, dtime(ends, 0))
        if s >= now + timedelta(minutes=40):
            cands.append((s, e))
cands.sort()
slots = []
def busy(slot):
    s, e = slot
    for a, b in slots:
        if s < b and a < e:
            return True
    return False
# 贪心：每个窗口内从窗口起点+30min 排起，必要时顺延 30min
for ws, we in cands:
    cur = ws + timedelta(minutes=30)
    while len(slots) < 3:
        s = max(cur, ws + timedelta(minutes=30))
        e = s + timedelta(hours=1)
        if e > we:
            break
        slot = (s, e)
        if not busy(slot):
            slots.append(slot)
        cur = s + timedelta(minutes=30)
def f(dt):
    return dt.strftime('%Y-%m-%dT%H:%M:%S')
t0, t1, t2 = slots[:3]
print(f'T0="{f(t0[0])}"; T0E="{f(t0[1])}"')
print(f'T1="{f(t1[0])}"; T1E="{f(t1[1])}"')
print(f'T2="{f(t2[0])}"; T2E="{f(t2[1])}"')
print(f'EXPECT_NEAR1={"true" if (t0[0]-now).total_seconds()<=24*3600 else "false"}')
print(f'EXPECT_NEAR2={"true" if (t1[0]-now).total_seconds()<=24*3600 else "false"}')
print(f'EXPECT_NEAR3={"true" if (t2[0]-now).total_seconds()<=24*3600 else "false"}')
print(f'EXPECT_NEAR_COUNT={sum(1 for t in (t0,t1,t2) if (t[0]-now).total_seconds()<=24*3600)}')
PY
)"

echo "== 0. 鉴权 =="
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/setup/campuses")
check "缺少 X-Role 返回 401" "$CODE" "401"
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/principal/overview" -H "X-Role: CONSULTANT")
check "顾问访问校长视图返回 403" "$CODE" "403"

echo "== 1. 线索（幂等 + 渠道费 + 家长承诺）=="
LEAD_BODY='{"studentName":"小明","parentPhone":"13711111111","grade":"四年级","subject":"MATH","baseLevel":"INTERMEDIATE","availableTime":"周六上午","sourceChannel":"抖音","consultantId":1,"campusId":1,"joinedActivity":true}'
R1=$(post /api/leads CONSULTANT "$LEAD_BODY" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: lead-xm-001")
LEAD_A=$(echo "$R1" | jq -r '.lead.id')
check "线索创建成功" "$(echo "$R1" | jq -r '.lead.status')" "NEW"
check "首次创建非幂等重放" "$(echo "$R1" | jq -r '.idempotentReplay')" "false"
R1B=$(post /api/leads CONSULTANT "$LEAD_BODY" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: lead-xm-001")
check "相同幂等键重放返回同一线索" "$(echo "$R1B" | jq -r '.lead.id')" "$LEAD_A"
check "重放标记为幂等" "$(echo "$R1B" | jq -r '.idempotentReplay')" "true"
post /api/leads/$LEAD_A/promises CONSULTANT '{"content":"保过期末数学90分","promisedBy":"王顾问"}' -H "X-Actor: 王顾问" > /dev/null
check "家长承诺登记" "$(get /api/leads/$LEAD_A ADMIN | jq -r '.status')" "NEW"

echo "== 2. 试听排班 + 改约容量回收 + 候补补位 + 第3次主管审批 =="
TRIAL=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_A,\"startTime\":\"$T0\",\"endTime\":\"$T0E\"}" -H "X-Actor: 王顾问")
TRIAL_A=$(echo "$TRIAL" | jq -r '.id')
check "自动匹配张老师(共享校区)" "$(echo "$TRIAL" | jq -r '.teacherId')" "1"
check "教材版本默认人教版数学" "$(echo "$TRIAL" | jq -r '.textbookVersion')" "人教版数学"
check "试听水平=学生基础水平" "$(echo "$TRIAL" | jq -r '.level')" "INTERMEDIATE"

# 另一线索想约同一热门时段：直接预约无老师 → 加入候补
LEAD_X=$(post /api/leads CONSULTANT '{"studentName":"候补测试","parentPhone":"13700000000","grade":"五年级","subject":"MATH","baseLevel":"BASIC","availableTime":"周六上午","sourceChannel":"地推","consultantId":1,"campusId":1}' -H "X-Actor: 王顾问" | jq -r '.lead.id')
CONFLICT=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_X,\"startTime\":\"$T0\",\"endTime\":\"$T0E\"}" -H "X-Actor: 王顾问")
check "同时段直接预约被拦截" "$(echo "$CONFLICT" | jq -r '.error')" "NO_TEACHER"
WL=$(post /api/trials/waitlist CONSULTANT "{\"leadId\":$LEAD_X,\"campusId\":1,\"teacherId\":1,\"startTime\":\"$T0\",\"endTime\":\"$T0E\"}" -H "X-Actor: 王顾问")
WL_ID=$(echo "$WL" | jq -r '.id')
check "候补登记成功(WAITING)" "$(echo "$WL" | jq -r '.status')" "WAITING"
# 仍有空位的时段候补应被引导直接预约
WL_AVAIL=$(post /api/trials/waitlist CONSULTANT "{\"leadId\":$LEAD_X,\"campusId\":1,\"teacherId\":1,\"startTime\":\"${SUN}T12:00:00\",\"endTime\":\"${SUN}T13:00:00\"}" -H "X-Actor: 王顾问")
check "有空位时候补提示直接预约" "$(echo "$WL_AVAIL" | jq -r '.error')" "SLOT_AVAILABLE"

# 第 1 次改约：释放原老师/教室档期 → 候补自动补位
RSC1=$(post /api/trials/$TRIAL_A/reschedule CONSULTANT \
  "{\"startTime\":\"$T1\",\"endTime\":\"$T1E\",\"reason\":\"家长临时出差，需调整时间\",\"consultantConfirmed\":true}" -H "X-Actor: 王顾问")
check "第1次改约直接执行" "$(echo "$RSC1" | jq -r '.rescheduled')" "true"
check "无需主管审批" "$(echo "$RSC1" | jq -r '.approvalRequired')" "false"
check "改约计数=1" "$(echo "$RSC1" | jq -r '.trial.rescheduleCount')" "1"
check "释放档期被候补补位" "$(echo "$RSC1" | jq -r '.record.slotTakenByWaitlist')" "true"
check "补位的是候补学生" "$(echo "$RSC1" | jq -r '.record.promotedLeadId')" "$LEAD_X"
check "改约原因已记录" "$(echo "$RSC1" | jq -r '.record.reason')" "家长临时出差，需调整时间"
check "顾问确认情况已记录" "$(echo "$RSC1" | jq -r '.record.consultantConfirmed')" "true"
check "临近开课标记(第1次)" "$(echo "$RSC1" | jq -r '.record.nearStart')" "$EXPECT_NEAR1"
# 候补线索已自动生成试听课并进入已约状态
check "候补线索自动补位成试听" "$(get /api/leads/$LEAD_X ADMIN | jq -r '.status')" "TRIAL_SCHEDULED"
WL_STATUS=$(curl -s "$BASE/api/leads/$LEAD_X/events" -H "X-Role: ADMIN" | jq -r '.waitlist[] | select(.id=='"$WL_ID"') | .status')
check "候补记录状态=PROMOTED" "$WL_STATUS" "PROMOTED"

# 原家长再次选择已被候补占用的时段 → 必须重新排队
REQUEUE=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_A,\"startTime\":\"$T0\",\"endTime\":\"$T0E\",\"teacherId\":1}" -H "X-Actor: 王顾问")
check "原时段已被候补占用需重新排队" "$(echo "$REQUEUE" | jq -r '.error')" "SLOT_TAKEN_REQUEUE"

# 第 2 次改约：释放的档期无候补，正常回收
RSC2=$(post /api/trials/$TRIAL_A/reschedule CONSULTANT \
  "{\"startTime\":\"$T2\",\"endTime\":\"$T2E\",\"reason\":\"孩子学校有活动\",\"consultantConfirmed\":false}" -H "X-Actor: 王顾问")
check "第2次改约直接执行" "$(echo "$RSC2" | jq -r '.rescheduled')" "true"
check "改约计数=2" "$(echo "$RSC2" | jq -r '.trial.rescheduleCount')" "2"
check "本次释放无候补补位" "$(echo "$RSC2" | jq -r '.record.slotTakenByWaitlist')" "false"
check "未确认标记=false" "$(echo "$RSC2" | jq -r '.record.consultantConfirmed')" "false"

# 第 3 次改约：需顾问主管审批，审批期间保留原档期不释放
TFAR="${SUN}T17:00:00"; TFARE="${SUN}T18:00:00"
RSC3=$(post /api/trials/$TRIAL_A/reschedule CONSULTANT \
  "{\"startTime\":\"$TFAR\",\"endTime\":\"$TFARE\",\"reason\":\"家长时间实在难协调，希望换周日傍晚\",\"consultantConfirmed\":true}" -H "X-Actor: 王顾问")
check "第3次改约需主管审批" "$(echo "$RSC3" | jq -r '.approvalRequired')" "true"
check "审批前不执行改约" "$(echo "$RSC3" | jq -r '.rescheduled')" "false"
check "审批单状态=PENDING" "$(echo "$RSC3" | jq -r '.approval.status')" "PENDING"
check "临近开课标记(申请时)" "$(echo "$RSC3" | jq -r '.approval.nearStart')" "$EXPECT_NEAR3"
check "审批期间试听保留原时段" "$(echo "$RSC3" | jq -r '.trial.startTime')" "$T2"
check "改约计数仍为2" "$(echo "$RSC3" | jq -r '.trial.rescheduleCount')" "2"
APV_ID=$(echo "$RSC3" | jq -r '.approval.id')
# 顾问无权审批
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/trials/reschedule-approvals/$APV_ID/approve" \
  -H "X-Role: CONSULTANT" -H "Content-Type: application/json" -d '{"note":"我自己批"}')
check "顾问审批返回 403" "$CODE" "403"
PENDING=$(get /api/trials/reschedule-approvals/pending PRINCIPAL)
check "主管看到待审批单" "$(echo "$PENDING" | jq -r --arg id "$APV_ID" 'map(select(.id==($id|tonumber))) | length')" "1"
# 重复提交第3次改约被拦截
DUP=$(post /api/trials/$TRIAL_A/reschedule CONSULTANT \
  "{\"startTime\":\"$TFAR\",\"endTime\":\"$TFARE\",\"reason\":\"重复提交\"}" -H "X-Actor: 王顾问")
check "待审批期间重复改约被拦截" "$(echo "$DUP" | jq -r '.error')" "RESCHEDULE_APPROVAL_PENDING"
# 主管批准 → 执行改约与容量回收
APV=$(post /api/trials/reschedule-approvals/$APV_ID/approve PRINCIPAL '{"note":"同意，提醒顾问这是最后一次改约"}' -H "X-Actor: 校长")
check "主管批准后改约执行" "$(echo "$APV" | jq -r '.rescheduled')" "true"
check "审批单状态=APPROVED" "$(echo "$APV" | jq -r '.approval.status')" "APPROVED"
check "改约计数=3" "$(echo "$APV" | jq -r '.trial.rescheduleCount')" "3"
check "新时段生效" "$(echo "$APV" | jq -r '.trial.startTime')" "$TFAR"
check "记录主管审批留痕" "$(echo "$APV" | jq -r '.record.supervisorApproved')" "true"
check "审批人记录" "$(echo "$APV" | jq -r '.record.approvedBy')" "校长"
# 改约原因为空时被拦截
NO_REASON=$(post /api/trials/$TRIAL_A/reschedule CONSULTANT \
  "{\"startTime\":\"${SUN}T12:30:00\",\"endTime\":\"${SUN}T13:30:00\",\"reason\":\"\"}" -H "X-Actor: 王顾问")
check "改约原因必填" "$(echo "$NO_REASON" | jq -r '.error')" "INVALID_ARGUMENT"

# 老师临时请假 → 试听取消，线索回到可预约
TL=$(post /api/trials/$TRIAL_A/teacher-leave TEACHER '{"reason":"张老师突发疾病"}' -H "X-Actor: 张老师")
check "老师请假后试听状态" "$(echo "$TL" | jq -r '.status')" "CANCELLED_TEACHER_LEAVE"
check "线索回到可预约状态" "$(get /api/leads/$LEAD_A ADMIN | jq -r '.status')" "NEW"

# 缺席流程（另一新线索）：预约→缺席→回到NEW
LEAD_X2=$(post /api/leads CONSULTANT '{"studentName":"缺席测试","parentPhone":"13700000009","grade":"二年级","subject":"MATH","baseLevel":"BASIC","availableTime":"周日上午","sourceChannel":"地推","consultantId":1,"campusId":1}' -H "X-Actor: 王顾问" | jq -r '.lead.id')
TRIAL_X2=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_X2,\"startTime\":\"${SUN}T09:00:00\",\"endTime\":\"${SUN}T10:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
ABS=$(post /api/trials/$TRIAL_X2/absent CONSULTANT '{}' -H "X-Actor: 王顾问")
check "缺席标记" "$(echo "$ABS" | jq -r '.status')" "ABSENT"
check "缺席后线索可重新预约" "$(get /api/leads/$LEAD_X2 ADMIN | jq -r '.status')" "NEW"

# 重新预约 → 到课 → 参加活动发赠品
TRIAL_A2=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_A,\"startTime\":\"${SUN}T10:00:00\",\"endTime\":\"${SUN}T11:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/trials/$TRIAL_A2/attend CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
check "到课后状态" "$(get /api/trials/$TRIAL_A2 ADMIN | jq -r '.status')" "ATTENDED"

# 老师到课简报：改约历史 + 家长关注点 + 临近开课/审批/补位统计
BRIEF=$(get /api/trials/$TRIAL_A2/briefing TEACHER)
check "简报含改约历史3条" "$(echo "$BRIEF" | jq -r '.rescheduleCount')" "3"
check_true "简报列出家长关注点" "$(echo "$BRIEF" | jq -r '.parentFocus[0]')"
check "家长关注点含改约原因" "$(echo "$BRIEF" | jq -r '[.parentFocus[] | contains("出差")] | any')" "true"
check "简报含主管审批次数=1" "$(echo "$BRIEF" | jq -r '.supervisorApprovedCount')" "1"
check "简报含容量补位次数=1" "$(echo "$BRIEF" | jq -r '.slotReclaimedCount')" "1"
check "简报临近开课次数(按动态期望)" "$(echo "$BRIEF" | jq -r '.nearStartChangeCount')" "$EXPECT_NEAR_COUNT"
check_true "简报含学生姓名" "$(echo "$BRIEF" | jq -r '.student.name')"
# 线索事件流可查候补与改约留痕
EV_X=$(get /api/leads/$LEAD_X/events ADMIN)
check "候补学生事件流含候补登记" "$(echo "$EV_X" | jq -r '[.auditEvents[].type] | map(select(.=="TRIAL_WAITLIST_JOINED")) | length')" "1"
check "候补学生事件流含自动补位" "$(echo "$EV_X" | jq -r '[.auditEvents[].type] | map(select(.=="TRIAL_WAITLIST_PROMOTED")) | length')" "1"

# 顾问端容量转化风险：小明的线索改约3次且释放容量曾被候补占用 → HIGH
RISKS=$(get "/api/consultants/risks?consultantId=1" CONSULTANT)
check "顾问风险视图含小明" "$(echo "$RISKS" | jq --arg id "$LEAD_A" 'map(select(.leadId==($id|tonumber))) | length')" "1"
check "小明风险等级=HIGH" "$(echo "$RISKS" | jq -r --arg id "$LEAD_A" '.[] | select(.leadId==($id|tonumber)) | .riskLevel')" "HIGH"
check "风险视图提示重新排队" "$(echo "$RISKS" | jq -r --arg id "$LEAD_A" '.[] | select(.leadId==($id|tonumber)) | [.risks[] | contains("重新排队")] | any')" "true"

echo "== 3. 试听反馈与转化一致性校验 =="
FB=$(post /api/trials/$TRIAL_A2/feedback TEACHER '{"performance":"课堂专注度一般，计算能力强但概念薄弱","assessmentScore":62,"suitableClassType":"ONE_ON_ONE","suggestedLevel":"BASIC","riskWarning":"基础薄弱，不建议保分承诺"}' -H "X-Actor: 张老师")
check "反馈提交成功" "$(echo "$FB" | jq -r '.suitableClassType')" "ONE_ON_ONE"
check "线索进入试听完成" "$(get /api/leads/$LEAD_A ADMIN | jq -r '.status')" "TRIAL_DONE"

# 承诺「保过」+ 班型/水平不匹配 → 直接报名应被拦截
CONV_BODY="{\"leadId\":$LEAD_A,\"coursePackageId\":1,\"discountAmount\":600,\"installments\":[{\"amount\":3000,\"dueDate\":\"$SAT2\"},{\"amount\":3000,\"dueDate\":\"$SAT2\"},{\"amount\":3000,\"dueDate\":\"$SAT3\"}],\"invoiceTitle\":\"小明家长\",\"promises\":[\"保过期末数学90分\"],\"materialsDelivered\":true}"
BLOCKED=$(post /api/conversions CONSULTANT "$CONV_BODY" -H "X-Actor: 王顾问")
check "承诺与反馈不一致被拦截" "$(echo "$BLOCKED" | jq -r '.error')" "CONSISTENCY_WARNING"
CONV=$(post /api/conversions CONSULTANT "$(echo "$CONV_BODY" | jq -c '. + {forceConfirm:true}')" -H "X-Actor: 王顾问")
STU_A=$(echo "$CONV" | jq -r '.student.id')
CT_A=$(echo "$CONV" | jq -r '.contract.id')
check "强制确认后转化成功" "$(echo "$CONV" | jq -r '.contract.status')" "ACTIVE"
check "一致性告警条数=3" "$(echo "$CONV" | jq -r '.consistencyWarnings | length')" "3"
check "实付金额=9600-600" "$(echo "$CONV" | jq -r '.contract.finalAmount')" "9000"
check "赠课=课包4+额外0" "$(echo "$CONV" | jq -r '.contract.giftLessons')" "4"
check "提成计提=9000*5%" "$(echo "$CONV" | jq -r '.commission.amount')" "450"
check "发票已开" "$(echo "$CONV" | jq -r '.invoice.status')" "ISSUED"
for IID in $(echo "$CONV" | jq -r '.installments[].id'); do
  post /api/conversions/installments/$IID/pay CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
done
check "3期分期全部回款" "$(echo "$CONV" | jq -r '.installments | length')" "3"

echo "== 4. 入班 / 排课 / 课消 / 请假 / 转班 / 停课 =="
EN=$(post /api/courses/classes/1/enroll CONSULTANT "{\"studentId\":$STU_A,\"contractId\":$CT_A}" -H "X-Actor: 王顾问")
check "入班成功" "$(echo "$EN" | jq -r '.status')" "ACTIVE"
check "非插班" "$(echo "$EN" | jq -r '.midTermInsertion')" "false"

L1=$(post /api/courses/lessons ADMIN "{\"clazzId\":1,\"startTime\":\"${SAT2}T10:00:00\",\"endTime\":\"${SAT2}T11:00:00\",\"topic\":\"分数运算\"}" -H "X-Actor: 教务" | jq -r '.id')
L2=$(post /api/courses/lessons ADMIN "{\"clazzId\":1,\"startTime\":\"${SAT2}T11:00:00\",\"endTime\":\"${SAT2}T12:00:00\",\"topic\":\"应用题专题\"}" -H "X-Actor: 教务" | jq -r '.id')
L3=$(post /api/courses/lessons ADMIN "{\"clazzId\":1,\"startTime\":\"${SAT2}T14:00:00\",\"endTime\":\"${SAT2}T15:00:00\",\"topic\":\"几何初步\"}" -H "X-Actor: 教务" | jq -r '.id')
check "排课成功" "$([[ "$L1" != "null" && "$L2" != "null" && "$L3" != "null" ]] && echo ok)" "ok"
BUSY=$(post /api/courses/lessons ADMIN "{\"clazzId\":1,\"startTime\":\"${SAT2}T10:30:00\",\"endTime\":\"${SAT2}T11:30:00\",\"topic\":\"冲突课\"}" -H "X-Actor: 教务")
check "老师时段冲突排课被拦截" "$(echo "$BUSY" | jq -r '.error')" "TEACHER_BUSY"

post /api/courses/lessons/$L1/complete TEACHER '{}' -H "X-Actor: 张老师" > /dev/null
post /api/courses/lessons/$L2/complete TEACHER '{}' -H "X-Actor: 张老师" > /dev/null
post /api/courses/lessons/$L3/complete TEACHER "{\"absentStudentIds\":[$STU_A]}" -H "X-Actor: 张老师" > /dev/null
CONS=$(get /api/courses/contracts/$CT_A/consumption ADMIN)
check "课消：2到课+1缺席=扣3付费课时" "$(echo "$CONS" | jq -r '.consumedPaid')" "3"

# 事后请假批准 → 返还缺席所扣课时
LR=$(post /api/students/$STU_A/leave CONSULTANT "{\"lessonId\":$L3,\"reason\":\"当天发烧\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/students/leave-requests/$LR/approve ADMIN '{}' -H "X-Actor: 教务" > /dev/null
CONS=$(get /api/courses/contracts/$CT_A/consumption ADMIN)
check "请假批准后返还课时(3→2)" "$(echo "$CONS" | jq -r '.consumedPaid')" "2"

# 转班（跨校区：中关村数学提高1班 → 望京数学提高2班）
post /api/setup/classes ADMIN '{"name":"数学提高2班","campusId":2,"subject":"MATH","level":"INTERMEDIATE","classType":"REGULAR","teacherId":3,"classroomId":3,"capacity":6,"textbookVersion":"人教版数学","scheduleDesc":"每周日"}' > /dev/null
TR=$(post /api/students/$STU_A/transfer CONSULTANT '{"toClazzId":2,"reason":"家搬到望京，换校区"}' -H "X-Actor: 王顾问")
check "跨校区转班成功" "$(echo "$TR" | jq -r '.toClazzId')" "2"

# 停课 → 复课
post /api/students/$STU_A/suspend CONSULTANT "{\"reason\":\"出国游学\",\"startDate\":\"$SAT3\",\"endDate\":\"$SAT3\"}" -H "X-Actor: 王顾问" > /dev/null
check "停课状态" "$(get /api/principal/students/$STU_A/timeline PRINCIPAL | jq -r '.student.status')" "SUSPENDED"
post /api/students/$STU_A/resume CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
check "复课状态" "$(get /api/principal/students/$STU_A/timeline PRINCIPAL | jq -r '.student.status')" "ACTIVE"

echo "== 5. 退费（投诉赔付 + 优惠回收 + 教材费 + 提成冻结）=="
post /api/students/$STU_A/complaints CONSULTANT '{"content":"家长投诉换老师未提前通知"}' -H "X-Actor: 王顾问" > /dev/null
PV=$(get /api/refunds/preview/$STU_A ADMIN)
# 已上2课时，单价=9000/48=187.5，基础退费=46*187.5=8625；教材费-300；优惠回收-600；投诉赔付+200 → 7925
check "试算：已上付费课时=2" "$(echo "$PV" | jq -r '.consumedPaidLessons')" "2"
check "试算：课时单价=187.5" "$(echo "$PV" | jq -r '.unitPrice')" "187.5"
check "试算：基础退费=8625" "$(echo "$PV" | jq -r '.baseRefund')" "8625"
check "试算：教材费扣300" "$(echo "$PV" | jq -r '.materialFeeDeducted')" "300"
check "试算：优惠回收600" "$(echo "$PV" | jq -r '.discountRecovered')" "600"
check "试算：投诉赔付+200" "$(echo "$PV" | jq -r '.complaintCompensation')" "200"
check "试算：提成冻结450" "$(echo "$PV" | jq -r '.commissionFrozen')" "450"
check "试算：应退=7925" "$(echo "$PV" | jq -r '.refundAmount')" "7925"

RQ=$(post /api/refunds CONSULTANT "{\"studentId\":$STU_A,\"reason\":\"家长对服务不满意\"}" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: refund-xm-001")
RF_A=$(echo "$RQ" | jq -r '.id')
check "退费申请创建" "$(echo "$RQ" | jq -r '.status')" "REQUESTED"
RQ2=$(post /api/refunds CONSULTANT "{\"studentId\":$STU_A,\"reason\":\"重复提交\"}" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: refund-xm-001")
check "退费申请幂等(同一单据)" "$(echo "$RQ2" | jq -r '.id')" "$RF_A"
post /api/refunds/$RF_A/approve PRINCIPAL '{}' -H "X-Actor: 校长" > /dev/null
RF=$(get /api/refunds/$RF_A ADMIN)
check "退费已打款" "$(echo "$RF" | jq -r '.status')" "PAID"
check "退费金额=7925" "$(echo "$RF" | jq -r '.refundAmount')" "7925"
TL_A=$(get /api/principal/students/$STU_A/timeline PRINCIPAL)
check "退费后学员状态" "$(echo "$TL_A" | jq -r '.student.status')" "REFUNDED"
check "合同状态=REFUNDED" "$(echo "$TL_A" | jq -r '.contracts[0].status')" "REFUNDED"
check "提成已追回" "$(echo "$TL_A" | jq -r '.contracts[0].commissions[0].status')" "CLAWED_BACK"

echo "== 6. 第二学员：分期上限与减免 =="
LEAD_B=$(post /api/leads CONSULTANT '{"studentName":"小红","parentPhone":"13722222222","grade":"三年级","subject":"ENGLISH","baseLevel":"BASIC","availableTime":"周日下午","sourceChannel":"官网","consultantId":1,"campusId":1}' -H "X-Actor: 王顾问" | jq -r '.lead.id')
TRIAL_B=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_B,\"startTime\":\"${SUN}T14:00:00\",\"endTime\":\"${SUN}T15:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/trials/$TRIAL_B/attend CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
post /api/trials/$TRIAL_B/feedback TEACHER '{"performance":"发音标准，互动积极","assessmentScore":85,"suitableClassType":"REGULAR","suggestedLevel":"BASIC"}' -H "X-Actor: 刘老师" > /dev/null
CONV_B=$(post /api/conversions CONSULTANT "{\"leadId\":$LEAD_B,\"coursePackageId\":2,\"installments\":[{\"amount\":4400,\"dueDate\":\"$SAT2\"},{\"amount\":4400,\"dueDate\":\"$SAT2\"}],\"materialsDelivered\":false}" -H "X-Actor: 王顾问")
STU_B=$(echo "$CONV_B" | jq -r '.student.id'); CT_B=$(echo "$CONV_B" | jq -r '.contract.id')
check "无告警直接转化" "$(echo "$CONV_B" | jq -r '.consistencyWarnings | length')" "0"
INST_B1=$(echo "$CONV_B" | jq -r '.installments[0].id')
post /api/conversions/installments/$INST_B1/pay CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
post /api/setup/classes ADMIN '{"name":"英语基础1班","campusId":1,"subject":"ENGLISH","level":"BASIC","classType":"REGULAR","teacherId":2,"classroomId":2,"capacity":8,"textbookVersion":"新概念青少版","scheduleDesc":"每周日"}' > /dev/null
EN_B=$(post /api/courses/classes/3/enroll CONSULTANT "{\"studentId\":$STU_B,\"contractId\":$CT_B}" -H "X-Actor: 王顾问")
check "英语班入班" "$(echo "$EN_B" | jq -r '.status')" "ACTIVE"
# 只回款4400 → 退费以已收为上限；未付第二期减免
RQ_B=$(post /api/refunds CONSULTANT "{\"studentId\":$STU_B,\"reason\":\"搬家\"}" -H "X-Actor: 王顾问")
check "退费上限=已收4400" "$(echo "$RQ_B" | jq -r '.refundAmount')" "4400"
RF_B=$(echo "$RQ_B" | jq -r '.id')
post /api/refunds/$RF_B/approve PRINCIPAL '{}' -H "X-Actor: 校长" > /dev/null
TL_B=$(get /api/principal/students/$STU_B/timeline PRINCIPAL)
check "未付分期被减免" "$(echo "$TL_B" | jq -r '.contracts[0].installments[1].status')" "WAIVED"

echo "== 7. 第三学员：赠课消耗与课时不足 =="
PKG_C=$(post /api/setup/packages ADMIN '{"name":"数学体验小包","subject":"MATH","level":"INTERMEDIATE","classType":"REGULAR","totalLessons":2,"price":200,"materialFee":0,"textbookVersion":"人教版数学","defaultGiftLessons":1,"discountKeepThreshold":0.5}' | jq -r '.id')
LEAD_C=$(post /api/leads CONSULTANT '{"studentName":"小刚","parentPhone":"13733333333","grade":"四年级","subject":"MATH","baseLevel":"INTERMEDIATE","availableTime":"周日下午","sourceChannel":"转介绍","consultantId":1,"campusId":1}' -H "X-Actor: 王顾问" | jq -r '.lead.id')
TRIAL_C=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_C,\"startTime\":\"${SUN}T16:00:00\",\"endTime\":\"${SUN}T17:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/trials/$TRIAL_C/attend CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
post /api/trials/$TRIAL_C/feedback TEACHER '{"performance":"基础扎实","assessmentScore":88,"suitableClassType":"REGULAR","suggestedLevel":"INTERMEDIATE"}' -H "X-Actor: 张老师" > /dev/null
CONV_C=$(post /api/conversions CONSULTANT "{\"leadId\":$LEAD_C,\"coursePackageId\":$PKG_C,\"installments\":[{\"amount\":200,\"dueDate\":\"$SAT2\"}]}" -H "X-Actor: 王顾问")
STU_C=$(echo "$CONV_C" | jq -r '.student.id'); CT_C=$(echo "$CONV_C" | jq -r '.contract.id')
INST_C=$(echo "$CONV_C" | jq -r '.installments[0].id')
post /api/conversions/installments/$INST_C/pay CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
EN_C=$(post /api/courses/classes/1/enroll CONSULTANT "{\"studentId\":$STU_C,\"contractId\":$CT_C}" -H "X-Actor: 王顾问")
check "中途入班记为插班" "$(echo "$EN_C" | jq -r '.midTermInsertion')" "true"
for SLOT in "10:00:00/10:50:00" "11:00:00/11:50:00" "14:00:00/14:50:00" "15:00:00/15:50:00"; do
  ST=${SLOT%/*}; ET=${SLOT#*/}
  LID=$(post /api/courses/lessons ADMIN "{\"clazzId\":1,\"startTime\":\"${SAT3}T${ST}\",\"endTime\":\"${SAT3}T${ET}\",\"topic\":\"体验课$ST\"}" -H "X-Actor: 教务" | jq -r '.id')
  post /api/courses/lessons/$LID/complete TEACHER '{}' -H "X-Actor: 张老师" > /dev/null
done
CONS_C=$(get /api/courses/contracts/$CT_C/consumption ADMIN)
check "赠课消耗：付费2+赠课1" "$(echo "$CONS_C" | jq -r '[.consumedPaid,.consumedGift] | join(",")')" "2,1"
TL_C=$(get /api/principal/students/$STU_C/timeline PRINCIPAL)
check_true "课时不足有告警事件" "$(echo "$TL_C" | jq -r '[.events[].type] | map(select(.=="CONSUMPTION_INSUFFICIENT")) | length')"

echo "== 8. 校长履约轨迹、转化统计与顾问风险 =="
EV=$(echo "$TL_A" | jq -r '[.events[].type] | unique | join(",")')
for E in LEAD_CREATED CHANNEL_FEE PROMISE_RECORDED TRIAL_BOOKED TRIAL_RESCHEDULED TRIAL_RESCHEDULE_RISK \
         TRIAL_RESCHEDULE_APPROVAL_REQUIRED TRIAL_RESCHEDULE_APPROVED TRIAL_SLOT_RECLAIMED \
         TRIAL_TEACHER_LEAVE GIFT_GRANTED TRIAL_ATTENDED FEEDBACK_SUBMITTED CONSISTENCY_WARNING \
         CONVERTED TRIAL_SIGNAL_ON_CONVERSION CONTRACT_CREATED INSTALLMENT_PAID INVOICE_ISSUED \
         COMMISSION_ACCRUED ENROLLED CONSUMPTION CONSUMPTION_REVERSED LEAVE_APPROVED TRANSFERRED \
         SUSPENDED RESUMED COMPLAINT_FILED REFUND_REQUESTED COMMISSION_FROZEN REFUND_PAID COMMISSION_CLAWED_BACK; do
  check_true "轨迹含事件 $E" "$(echo "$EV" | grep -o "$E")"
done
if [[ "$EXPECT_NEAR3" == "1" ]]; then
  check_true "轨迹含临近开课改约事件" "$(echo "$EV" | grep -o 'TRIAL_NEAR_START_RESCHEDULE')"
fi
check "轨迹含赠品记录" "$(echo "$TL_A" | jq -r '.gifts[0].status')" "GRANTED"
check "轨迹含渠道费(抖音300)" "$(echo "$TL_A" | jq -r '.channelFees[0].amount')" "300"
OV=$(get /api/principal/overview PRINCIPAL)
check "概览含2个校区" "$(echo "$OV" | jq -r '.campuses | length')" "2"
check_true "概览含渠道成本" "$(echo "$OV" | jq -r '.channelCosts["抖音"]')"
# 试听反馈影响转化统计
STATS=$(get /api/principal/conversion-stats PRINCIPAL)
check_true "转化统计返回总体数据" "$(echo "$STATS" | jq -r '.overall.trialDone')"
check "统计：至少3条线索完成试听" "$(echo "$STATS" | jq -r '.overall.trialDone >= 3')" "true"
check "统计：3条均转化" "$(echo "$STATS" | jq -r '.overall.converted >= 3')" "true"
check "统计：多次改约(>=3)分组有转化" "$(echo "$STATS" | jq -r '.byRescheduleCount.reschedule_ge_3_supervisor.converted >= 1')" "true"
check "统计：60-74分数段有转化(小明62)" "$(echo "$STATS" | jq -r '.byAssessmentScore.score_60_74.converted >= 1')" "true"
check "统计：候补补位数>=1" "$(echo "$STATS" | jq -r '.waitlist.promoted >= 1')" "true"
check "统计：容量回收分组有样本" "$(echo "$STATS" | jq -r '.slotReclaimedRequeueImpact.trialDone >= 1')" "true"
# 风险视图：重新拉取快照——此时张老师未来7天试听位已≥3（候补补位+小明/小红/小刚），应标记热门
RISKS_NOW=$(get "/api/consultants/risks?consultantId=1" CONSULTANT)
RISK_X=$(echo "$RISKS_NOW" | jq -r --arg id "$LEAD_X" '.[] | select(.leadId==($id|tonumber)) | .hotTeacher')
check "候补学生档期标记热门老师" "$RISK_X" "true"

echo ""
echo "==================================="
echo "通过 $PASS 项，失败 $FAIL 项"
[[ $FAIL -eq 0 ]] && echo "VERIFY_RESULT: PASS" || { echo "VERIFY_RESULT: FAIL"; exit 1; }
