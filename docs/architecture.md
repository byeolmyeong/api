---
type: architecture
tags: [서버 아키텍처, API 계약, 관리자 API, 차트 계산, 해석 생성, 로그인, 의존 규칙, ArchUnit, 용어]
status: active
last_reviewed: 2026-11-12
---
# 서버 아키텍처

> 상태: 활성 · 기준 2026-11-11 · 무엇을 만드는지의 원본은 앱 저장소의 `../byeolmyeong-app/docs/release-1.0.0.md` 다. 2026-11-11 결정 반영: docs/decisions/003-follow-app-decisions-2026-11-11.md

## 서버가 하는 일
- 차트 계산: 출생 정보를 받아 행성 위치·하우스·각도·추가 지점(버텍스 포함, 키론 제외)을 계산한다. 세션이 있어야 받는다. 결정: docs/decisions/002-chart-calculation.md, docs/decisions/003-follow-app-decisions-2026-11-11.md
- 출생 시각 변환과 출생지: 국내 시·군·구 목록을 갖고, 현지 시각을 당시 표준시·서머타임 이력으로 UTC 로 바꾼다.
- 로그인: 구글·애플·카카오 토큰을 검증하고 자체 세션을 준다(앱은 토큰, 웹은 httpOnly 쿠키). 익명 계정은 없다. 처음 온 사람은 가입권을 받고 출생 정보와 함께 가입하며, 서버가 출생일로 만 14세 이상인지 판정해 미만이면 계정을 만들지 않는다. 결정: docs/decisions/021-social-login-signup-cookie.md
- 글 생성: 계산한 차트로 Claude API(결정 006)를 호출해 요약 해석·나 탭 풀이·심층 리포트·궁합 글·방향성 제시를 만들고 저장한다. 계산은 AI 에게 맡기지 않는다.
- 변곡점: 정해진 마디(토성 사각·귀환·두 번째 귀환, 천왕성 대립)와 변화·기회·운명적 만남 구간을 계산하고, 구간마다 기간·영역·크기를 붙인다. 시기 풀이(`GET /timing-report`, 결정 033)와 옛 심층 리포트의 시기 영역(`Report.timing`)으로 준다. 그달에 걸친 구간은 이번 달(`GET /timing/month`, 결정 032)의 "이번 달 내내" 가 된다. 규칙: 앱 저장소 release-1.0.0 F4. 서버가 고른 것: docs/decisions/008-report-timing.md
- 궁합: 직접 입력과 초대 링크. 두 차트를 겹치는 계산과 나 기준 점수(40~100, 관계 유형별)는 서버에서만 한다. 나 기준으로 여러 명과 1:1 매칭한 결과를 랭킹으로 돌려준다(인원 제한 없음). 규칙: 앱 저장소 release-1.0.0 F5.
- 공유 조회, 요청 속도 제한(남용 방지), 푸시 알림 발송, 계정 삭제.

## 모듈 (패키지) — 기능별. 결정: docs/decisions/004-module-boundaries-archunit.md
- `chart` — 계산. 순수 함수만 둔다(입력: UTC 시각·위경도 → 출력: 차트·변곡점 구간·궁합 점수·일 방식 막대·그날 · 그달의 지나가는 별). DB·HTTP·AI·Spring 을 모른다. 이유: 계산 정확도가 제품의 신뢰이고 기준값 테스트로 혼자 검증돼야 한다.
- `birth` — 출생지 목록과 시각 변환(`java.time` 의 시간대 이력), 그리고 저장된 출생 정보를 읽고 쓰는 한 곳(`BirthProfileService`). 저장된 출생 정보에서 차트 입력과 "같은 차트" 를 가리는 해시(`ChartInput`)를 얻는 곳도 여기다. 해석·리포트·궁합이 모두 소유자의 출생 정보를 쓰는데 기능 패키지는 서로 의존할 수 없어서 여기에 둔다.
- `web` — HTTP 공통: 오류 응답 모양(`ErrorResponse`·`BadRequestException`·`NotFoundException`), 출생 정보 요청 검증(`BirthRequestParser`), 요청 속도 제한, CORS(앱의 주소와 관리 화면의 주소를 경로로 나눈다), 지금의 `POST /charts` 컨트롤러. 감사 기록을 남겨 달라는 이벤트의 타입(`AuditEvent`·`AuditAction`·`AuditActor`)과 관리자에게 사용자를 가리키는 말(`ownerRef`)도 여기 둔다 — 기능 패키지와 `auth` 가 모두 써야 하는데 둘 다 쓸 수 있는 곳이 여기뿐이다(결정 015). 앱 버전을 읽고 견주는 순수 함수(`parseAppVersion`, 결정 016)도 여기 있다 — 최소 버전을 넣는 `admin` 과 요청의 버전을 보는 `appconfig` 가 같이 쓴다. "지금" 을 주는 시계 빈(`ClockConfig`, 한국 시간대 — 테스트가 고정 시계로 바꿔 끼운다)도 여기 있다. `auth` 를 모른다.
- `auth` — 세션 발급·검증, 세션 검사(`SessionInterceptor`: 로그인 전에 쓰는 곳을 "메서드 + 경로"(`PUBLIC_ENDPOINTS`)로 정하고, 거기 없는 요청은 모두 세션 필요 — 같은 경로라도 열지 않은 메서드는 세션이 있어야 한다. HEAD 는 GET 과 같이 연다. `/admin/**` 는 보지 않는다 — 관리자 세션 검사가 맡는다), 소유자를 얻는 한 곳(`CurrentOwner`), 개발용 로그인(`POST /auth/dev`, 결정 005), 계정 삭제(`DELETE /account` — 지우기 전에 카카오 연결 끊기·애플 토큰 철회), 구글·애플·카카오 토큰 검증·가입권·만 14세 판정·쿠키 세션·세션 만료·애플 서버 알림(결정 021).
- `profile` — 내 출생 정보 저장·조회의 HTTP 입구(`GET`·`PUT /profile`). 저장 자체는 `birth.BirthProfileService` 가 한다.
- `generation` — 글 생성 공통: 공급자를 가리는 인터페이스 `TextGenerator`(지시문·차트 글·JSON 구조를 받아 JSON 을 돌려준다)와 그 Claude 구현, 계산한 것을 프롬프트 글로 옮기는 것(차트 `chartFacts`, 시기 한 줄 `timelineFact`, 두 차트 사이의 연결 `synastryFactsText`), 근거(`Basis.kt`: 옛 근거 말 `basisVocabulary`, 근거 후보 `basisCandidates` → 구조 `BasisItem`·`Reason`, 이 풀이에 쓰인 별 `starsOf` — 결정 031). 시기의 표의 글(`TimingThemes.kt` — 마디 · 구간의 제목 · 본문, 리포트 · 시기 풀이 · 이번 달이 같은 이름을 쓴다, 결정 032 · 033). 이번 달 문구표(`MonthPhrases.kt` — 지나가는 별 × 내 별 × 결의 칩 · 문구, 날짜와 조합의 CRC32 로 고른다 · 이번 달과 그날의 하늘이 같이 쓴다, 결정 032 · 034). 해석·리포트·궁합 글·방향성 제시가 같이 쓰는데 기능 패키지는 서로 의존할 수 없어서 여기에 둔다. `chart` 만 안다(소유자·저장·HTTP 를 모른다).
- `reading` — 요약 해석 생성·저장. 요약 해석에만 있는 것(지시문, 돌려받을 구조, 문단·강점·보완점·근거 개수 검증, 고른 근거 id 를 구조로 바꾸기)을 갖고, 생성은 `generation.TextGenerator` 로 한다. 저장 종류는 `SUMMARY_V2`(결정 030) — 옛 `SUMMARY` 행은 읽지 않는다.
- `report` — 자세한 풀이와 옛 심층 리포트. 자세한 풀이 두 가지 — 나 탭 풀이(`POST`·`GET /me-report`, 결정 029 · 030 — 조각은 영역 다섯, 일 영역에는 `chart` 로 계산한 일 방식을 함께)와 시기 풀이(`POST`·`GET /timing-report`, 결정 033 — 조각은 마디 · 구간 9줄 묶음 약 넷) — 는 `detail_reports`(kind `ME` · `TIMING`)에 두고 생성 고리 하나(`DetailReportService`: 받을 수 있나(`EntitlementPolicy` — 결제 시작 시각 전이면 모두, 뒤면 상품을 가졌는지, 결정 056) → 자리 잡기 → 생성 한도 한 번 → 조각을 일꾼에게 한꺼번에 → 살아 있다는 표시 · 그만두기 · 모두 검증을 통과하면 저장)를 같이 쓴다. 요청은 202 로 바로 답한다. 완성되면 `MeReportDone` · `TimingReportDone` 이벤트를 낸다. 옛 심층 리포트(`POST /reports`, 계획 S⑦ 에서 뺀다)는 요청은 자리만 잡고 202 로 답하고, 글은 뒤에서 영역(성격·관계·일·감정)마다 따로 생성해 네 영역이 모두 검증을 통과하면 저장한다. 완성되면 `ReportDone` 이벤트를 낸다. 시기 영역은 같은 생성 안에서 뒤쪽 일꾼이 `chart` 로 마디·구간을 계산하고(평생 한 번에 약 0.3초) 표의 글을 붙인 뒤, 다가오는 줄에만 모델의 글을 받아 네 영역과 함께 저장한다. "지금 나의 구간" 은 저장하지 않고 읽을 때 오늘 날짜로 가린다(결정 008).
- `synastry` — 직접 입력 궁합: 나 기준 점수(`chart` 계산), 키워드·한 줄 요약 생성, 목록·랭킹, 궁합 리포트(202 로 답하고 뒤에서 영역마다 쓴다). 상대의 출생 정보·행성 위치는 요청 안에서만 쓰고 저장하지 않는다(결정 010). 초대·수락은 아직 없다(태스크 9 의 나머지).
- `direction` — 방향성 제시(해 볼 만한 것 3개). 주제는 이성·미래·궁합이다. 방향성 제시에만 있는 것(지시문, 돌려받을 구조, 항목 개수·근거·조언형·숫자 검증, 미래 주제의 "지금의 시기" 가리기)을 갖고, 생성은 `generation.TextGenerator` 로 한다. 미래 주제의 시기는 `chart` 로 직접 계산한다(`report` 의 저장본을 읽지 않는다). 궁합 주제는 상대의 출생 정보를 요청으로 다시 받아 그 안에서만 쓰고(결정 010), 글이 붙는 궁합의 소유자·관계 유형·점수·그때의 내 차트 해시는 `synastries` 표에서 읽기만 한다(`synastry` 의 클래스를 쓰지 않는다). 결정: docs/decisions/009-direction-storage-and-advice-check.md, docs/decisions/011-direction-future-and-synastry.md
- `inquiry` — 문의하기와 내 문의. 문의 글을 검증해 저장하고(보낸 앱의 헤더도), 내 문의 목록 · 한 건을 답과 함께 준다(답은 `admin` 이 쓴다 — 같은 테이블만 본다). 메일 발송은 없다. 계정별 시간당 제한을 여기서 건다. 결정: docs/decisions/013-inquiries-stored-on-server.md · 039-inquiry-answers.md
- `admin` — 관리자 API(`/admin/**`). 앱 사용자와 완전히 따로인 관리자 계정·로그인·세션(`AdminSessionInterceptor`·`CurrentAdmin`), 첫 관리자 만들기, 관리자 관리, 문의 관리(찾기 · 작성자 · 상태 · 메모 · 답 · 임시 저장 · 처리한 관리자 — 처음 답하면 `InquiryAnswered` 를 낸다, 결정 039), 현황 수치(개수 · 비율만)와 접속 차트(결정 043), FAQ 편집, 앱 설정 편집(최소 버전·강제 업데이트, 결정 016). 한 일은 `AdminAudit` 으로 감사 기록에 알린다(결정 015). 다른 기능의 테이블은 자기 SQL 로 읽는다. 다른 기능 패키지를 모른다(`auth` 도 쓰지 않는다). 결정: docs/decisions/014-admin-back-office.md
- `audit` — 감사 기록(결정 015). 다른 기능과 `auth` 가 낸 `web.AuditEvent` 를 듣고(`AuditRecorder`, 커밋 뒤·자기 트랜잭션·실패는 삼킨다) `audit_logs` 에 넣기만 한다(1년 지난 행은 날마다 한 번 지운다 — 결정 043 의 8). 관리자가 읽는 경로(`GET /admin/audit-logs`)도 여기 있다 — 경로가 `/admin/` 아래라 관리자 세션 검사가 걸리지만 `admin` 의 코드는 쓰지 않는다. 다른 기능은 이 패키지를 모른다.
- `appconfig` — 앱 설정의 공개 조회(`GET /app-config`)와 점검·강제 업데이트(`AppUpdateInterceptor`: 점검이 켜져 있으면 모든 요청을 503 으로 — 결정 017, 그다음 앱이 보낸 플랫폼·버전 헤더를 보고 낮은 버전을 426 으로 막는다. 세션 검사보다 먼저, `/admin/**`·`/health`·`/app-config` 는 뺀다). `app_configs`·`app_maintenance` 를 읽기만 하고 잠깐 캐시한다. 값을 쓰는 것은 `admin` 이다(결정 016).
- `faq` — 자주 묻는 질문의 공개 조회(`GET /faqs`, 세션 없음). `faqs` 테이블을 읽기만 한다. 글을 쓰는 것은 `admin` 이고 두 패키지는 코드로 서로를 모른다(결정 014).
- `share` — 공유 카드 8종(요약 해석·궁합 점수·랭킹·다음 변곡점, 공유 v2 의 내 별명·나 탭 영역·그날의 하늘·다음 큰 변화 — 결정 036): 만들기·공개 조회·지우기. 공유하는 순간의 공개 글만 `shares` 에 따로 저장하고(스냅샷) 공개 조회는 그 표만 읽는다. 카드의 글은 그 글을 가진 기능에게 이벤트(`ReadingCardRequested` 등)로 묻는다. 이름을 이니셜로, 날짜를 해 + 계절로 줄이는 것도 여기서 한다. 결정: docs/decisions/012-share-cards-snapshot.md
- `push` — 기기 토큰과 알림 발송.
- `notification` — 알림함(`GET /notifications` · `POST /notifications/{id}/read`, 결정 035). 완성 이벤트(별명 지음 · 나 탭 풀이 · 시기 풀이 · 궁합 리포트 · 문의 답변)를 커밋 뒤에 듣고 한 줄씩 넣는다. 문구는 앱이 갖는다. 30일 지난 줄은 날마다 지운다.
- `sky` — 시기 탭의 이번 달(`GET /timing/month`, 결정 032). 요청 때마다 `chart` 로 계산하고(그달의 지나가는 별 · 평생 시기 구간) `generation` 의 문구표 · 시기 표에서 글을 골라 준다. 저장하지 않고 모델을 부르지 않으며 생성 한도에 세지 않는다 — 계정별 분당 요청 수만 센다. 그날의 하늘(`POST /day-sky`, 결정 034)도 여기에 둔다 — 서버가 근거 · (눈여겨볼 날이면) 제목 · 칩을 정하고 모델이 흐름 · 실천(+ 제목)을 쓴 글을 소유자 · 차트 · 날짜마다 한 번 저장한다(`day_skies`).

## 디렉터리 구조
```
src/main/kotlin/com/byeolmyeong/api/
  chart/       Positions · Houses · Aspects(+ 상승점·중천과의 각도) · Points · Rulers(다스리는 별, 전통 지배성) · Timeline(마디·구간) · SynastryScore · WorkStyle(일 방식 막대 셋, 결정 038) · Transits(그날 · 그달 지나가는 별 — 눈여겨볼 날 · 그날의 하늘 근거, 결정 032 · 034)   ← 순수 계산
  birth/       Places · BirthTime · BirthProfile(Service·Repository · ChartInput) · PlacesController
  web/         ErrorResponse · BirthRequest(Parser: 검증, 요청의 출생 정보 → 차트 입력) · RateLimiter · CorsConfig · ChartController · AuditEvent(감사 기록 이벤트·행동 코드·행위자) · OwnerRef · AppVersion(버전 읽기·견주기) · ClockConfig(시계 빈)
  auth/        AuthController · AuthService · AccountRepository · SessionRepository · Session(CurrentOwner · SessionInterceptor) · SessionCookie(웹 쿠키 bm_session, 결정 021) · ProviderToken(Provider · ProviderTokenVerifier · 공급자 셋의 검증기 설정) · KakaoOAuth(+ KakaoOAuthClient — 웹 인가 코드 교환 · 탈퇴 때 연결 끊기) · LoginService(+ SignupTicketRepository · AppleTokenRepository — 로그인 · 가입권 · 가입) · AppleSignIn(+ AppleSignInClient — 애플 토큰 교환 · 철회 · 서버 알림 확인) · Age(만 14세 · 한국 날짜) · AuthErrors(로그인 · 가입 오류 응답)
  profile/     ProfileController
  generation/  TextGenerator(인터페이스) · ClaudeTextGenerator · ChartFacts · TimelineFacts(시기 한 줄의 글) · TimingThemes(시기의 표의 글 — 구간 이름의 단일 출처) · MonthPhrases(이번 달 문구표 — 눈여겨볼 날의 칩 · 문구, 결정 032) · SynastryFacts(두 차트 사이 연결의 글) · Basis · Advice(실천 한 줄의 검사)
  reading/     ReadingController · ReadingService · ReadingRepository · ReadingDraft(지시문·구조·검증)
  report/      ReportController · ReportService(+ 일꾼 설정 ReportExecutorConfig · 닫을 때 기다리기 ReportExecutorDrain) · ReportRepository · Report(모양·지시문·구조·검증) · ReportTiming(시기: 모양·모델에게 맡길 줄·연도 대조·지금) · ReportDone(이벤트) · EntitlementPolicy(받을 수 있나, 결정 029 · 056) · PurchaseStart(결제 시작 시각) · EntitlementService(+ EntitlementRepository — 상품을 가졌나 · 1.0.0 수령자 채우기, 결정 056) · RevenueCat(인터페이스 · RevenueCatClient — 구독자 조회) · DetailReportService(자세한 풀이의 생성 고리 — 나 탭 · 시기가 같이 쓴다) · DetailReportRepository(`detail_reports`) · MeReportController · MeReportService · MeReport(나 탭 풀이: 모양·지시문·구조·검증) · MeReportDone(이벤트) · TimingReportController · TimingReportService · TimingReport(시기 풀이: 묶음·지시문·구조·검증·이름 고정·근거·대표 구간) · TimingReportDone(이벤트)
  synastry/    SynastryController · SynastryService · SynastryRepository · Synastry(모양·지시문·구조·검증) · SynastryReport(리포트의 모양·지시문·구조·검증) · SynastryDeleted(이벤트) · (초대 때) InviteService · InviteAccepted(이벤트)
  direction/   DirectionController · DirectionService · DirectionRepository · Direction(모양·지시문·구조·검증) · DirectionFuture(미래: 지금의 시기·대상) · DirectionSynastry(궁합: 재료·지시문)
  inquiry/     InquiryController(문의하기 · 내 문의) · InquiryService(계정별 제한) · InquiryRepository · Inquiry(모양·검증 · 옛 앱 갈래 · 보낸 앱)
  admin/       Admin(로그인한 관리자·거절·본문 읽기) · AdminSession(CurrentAdmin · AdminSessionInterceptor) · AdminPasswords(bcrypt) · AdminAuthController · AdminAuthService(+ AdminSessionRepository) · AdminAccountController(+ 첫 관리자 AdminBootstrap) · AdminAccountService(+ AdminAccountRepository) · AdminInquiryController · AdminInquiryService(+ AdminInquiryRepository — `inquiries` 를 자기 SQL 로 읽고 처리 칸 · 답 칸만 쓴다) · InquiryAnswered(이벤트) · AdminStats(Controller · Service · Repository — 다른 기능의 테이블을 개수만 읽는다 · 접속 차트) · AdminFaqController · AdminFaqService(+ AdminFaqRepository) · AdminAppConfigController(+ AdminAppConfigService · AdminAppConfigRepository) · AppConfigChanged(이벤트) · AdminEntitlementAllowlist(Controller · Service · Repository — 결제 없이 여는 계정, 결정 056) · AdminAudit(감사 기록에 알린다)
  audit/       AuditRecorder(이벤트를 듣는다) · AuditService(+ AuditRepository — 넣고 읽고, 1년 지난 행을 날마다 지운다) · AuditLogController(`GET /admin/audit-logs`)
  appconfig/   AppConfigController(+ AppConfigService: 캐시·막을지 판정 · AppConfigRepository) · AppUpdateInterceptor(+ AppUpdateConfig: 순서·빼는 경로)
  faq/         FaqController(+ FaqService · FaqRepository — 공개한 것만 읽는다)
  share/       ShareController · ShareService · ShareRepository · ShareCard(카드의 모양·이니셜·계절) · ShareCardRequested(이벤트 넷)
  push/        PushDevice(PushDeviceController · PushDeviceService · PushDeviceRepository — 기기 토큰, 결정 042) · ExpoPush(인터페이스 · ExpoPushClient — Expo 푸시 호출, 끄기 스위치) · PushService(PushService · PushRepository · PushMessages 문구표 · PushEvents 이벤트 리스너 · push- 일꾼)
  notification/ Notification(NotificationController · NotificationService — 이벤트 리스너 · 30일 지우기 · NotificationRepository)
  sky/         TodaySkyMorning(TodaySkyMorningService — 5분마다 받는 시간 30분 전 ~ 60분 뒤 미리 만들기(빈 생성 일꾼 스레드에서 이어 만든다 — 결정 052) · 받는 시간에 TodaySkyDue · TodaySkyMorningRepository) · MonthSkyController · MonthSkyService(계정별 분당 한도) · MonthSky(모양 · 고를 달 · 이번 달 내내 고르기) · IntervalCache(차트 해시마다 평생 구간을 몇 분 기억) · DaySkyController · DaySkyService(저장본 · 진행 중 생성 기다리기 · 생성 한도) · DaySky(모양 · 날짜 읽기 · 서버가 정하는 것 · 지시문 · 검증) · DaySkyRepository
src/main/resources/  application.properties · db/migration(Flyway) · prompts/(글 생성 프롬프트)
src/test/kotlin/...  기능별 테스트 · ArchitectureTest(의존 규칙)
```
- 기능 안의 계층은 파일 이름 끝(`Controller`·`Service`·`Repository`)으로 나눈다. 하위 디렉터리(controller/·service/)는 만들지 않는다.
- 컨트롤러는 요청 검증과 응답 변환만, 서비스는 소유자 검사와 흐름, 리포지토리는 저장만 한다. 계산은 `chart` 를 부른다.

## 의존 규칙 (ArchUnit 테스트 `ArchitectureTest.kt` 가 강제한다)
| 규칙 | 이유 |
|---|---|
| `chart` 는 Kotlin 표준·java.time·astronomy-engine 외에는 의존하지 않는다(Spring·DB·다른 패키지 금지) | 계산은 홀로 검증된다 |
| `birth` 는 `chart` 외 다른 기능 패키지에 의존하지 않는다 | 시각 변환도 순수하게 둔다 |
| 기능 패키지(`profile`·`reading`·`report`·`synastry`·`direction`·`inquiry`·`admin`·`faq`·`audit`·`appconfig`·`share`·`push`·`sky`·`notification`)는 서로 직접 의존하지 않는다. 기능 사이 연결은 Spring 이벤트(`ReportDone`·`InviteAccepted`, 나 탭 풀이 · 시기 풀이가 완성됐다는 `report.MeReportDone` · `report.TimingReportDone`(알림함이 듣는다 — 결정 029 · 035), 궁합이 지워졌다는 `synastry.SynastryDeleted`(`direction` 이 그 궁합의 글을 지운다 — 결정 019), 공유 카드의 글을 묻는 `share` 의 `ReadingCardRequested`·`ScoreCardRequested`·`RankingCardRequested`·`NextTurnCardRequested` — 결정 012, 공유 v2 의 `NicknameCardRequested`(reading)·`MeAreaCardRequested`·`NextBigCardRequested`(report)·`DaySkyCardRequested`(sky) — 결정 036)로만 한다. 관리자가 앱 설정을 고쳤다는 `admin.AppConfigChanged`(`appconfig` 가 캐시를 비운다 — 결정 016)와 관리자가 문의에 처음 답했다는 `admin.InquiryAnswered`(알림함 S⑤ · 푸시 S⑩ 가 듣는다 — 결정 039)와 출생 정보가 바뀌었다는 `profile.BirthProfileChanged`(`reading` · `report` · `sky` 가 옛 차트의 저장분을 지운다 — 결정 041), 별명을 처음 지었다는 `reading.ReadingCreated` 와 궁합 리포트가 완성됐다는 `synastry.SynastryReportDone`(알림함이 듣는다 — 결정 035), 오늘의 하늘 알림을 보낼 때가 됐다는 `sky.TodaySkyDue`(`push` 가 보낸다 — 결정 042 의 6)도 허용된 이벤트다. 감사 기록은 기능의 이벤트가 아니라 공통 패키지의 `web.AuditEvent` 로 알린다(`auth` 도 내야 해서 — 결정 015). 관리자의 탈퇴 대행도 공통 패키지의 `web.AccountDeletionRequested` 로 `auth` 에 알린다(공통 `auth` 는 기능의 이벤트 클래스를 알 수 없다 — 결정 043 의 1). 관리자가 광고 푸시를 보냈다는 `admin.PushEventRequested` · push 가 다 보냈다는 `admin.PushEventSent`(결정 043 의 6)도 허용된 이벤트다. | 기능 하나를 고칠 때 다른 기능이 같이 깨지지 않게. 관리자가 실패한 자세한 풀이를 다시 만들게 했다는 `admin.GenerationRetryRequested`(`report` 가 듣고 다시 시작한다 — 결정 043 의 1)도 허용된 이벤트다 |
| 기능 패키지는 `chart`·`birth`·`auth`(소유자 얻기)·`web`(공통)·`generation`(글 생성 공통) 에만 의존할 수 있다 | 공통으로 쓰는 것만 아래로 둔다 |
| `generation` 은 `chart` 외 다른 패키지에 의존하지 않는다 | 글 생성 공통은 차트만 안다. 누구의 글인지·어디에 저장하는지는 기능이 맡는다 |
| `auth`·`web`·`generation` 은 기능 패키지에 의존하지 않는다 | 공통이 기능을 알면 순환이 생긴다 |
| `*Repository` 는 같은 패키지의 `*Service` 에서만 부른다(컨트롤러에서 부르지 않는다) | 소유자 검사를 서비스 한 곳에서 한다 |
| 패키지 사이 순환 의존 0 | |
| 글 생성(Claude API)·소셜 로그인 같은 외부 호출은 인터페이스 하나 뒤에만 둔다. Claude SDK 를 아는 클래스는 `generation.ClaudeTextGenerator` 하나다 | 공급자를 바꿀 수 있게 |

## 용어 (코드·문서·화면에서 같은 말을 쓴다)
| 화면 말 | 코드 이름 | 뜻 |
|---|---|---|
| 요약 해석 | `Reading` | 버튼 하나로 받는 짧은 해석 |
| 심층 리포트 | `Report` | 성격·관계·일·감정·시기 다섯 영역(옛 경로, 계획 S⑦ 에서 뺀다) |
| 나 탭 풀이(자세한 풀이) | `MeReport` | 나 탭의 성격·연애·관계·일·감정 다섯 영역(결정 029 · 030) |
| 나의 일 방식 | `WorkStyle { together, flexible, fast }` | 일 영역의 막대 셋. 서버가 차트로 계산한 10~90(결정 038) |
| 시기 풀이(자세한 풀이) | `TimingReport` | 시기 탭의 자세한 풀이 — 마디 · 구간 + 맡긴 구간의 설명 · 자세히 · 실천 · 근거(결정 029 · 033) |
| 지금 나의 시기 | `current.mainId` | 오늘 걸친 구간 가운데 대표 하나(변화 큰 > 작은 > 운명적 만남 > 기회, 같으면 늦게 시작한 것). 읽을 때 서버가 정한다(결정 033) |
| 마디 | `Milestone(kind = SATURN_SQUARE \| SATURN_RETURN \| URANUS_OPPOSITION \| SECOND_SATURN_RETURN)` | 누구에게나 오는 변곡점(토성 귀환 등) |
| 변화·기회·운명적 만남 구간 | `Interval(kind = CHANGE \| OPPORTUNITY \| FATED)` | 사람마다 다른 변곡점 기간 |
| 나 기준 점수 | `Synastry.score` | 상대 행성이 내 개인 행성에 만드는 각도로 계산한 40~100 |
| 랭킹 | `Ranking` | 나 기준으로 여러 명과 1:1 궁합을 점수순으로 |
| 방향성 제시 | `Direction` | 해 볼 만한 것 3개 |
| 이렇게 풀이한 이유 | `Reason { when, items: BasisItem[] }` | 문단 하나의 근거 구조. 서버가 만든 근거 후보 id 에서 모델이 고르고 서버가 구조로 바꾼다. 문장은 앱이 만든다(결정 031) |
| 공유 카드 | `ShareCard(kind = READING \| SCORE \| RANKING \| NEXT_TURN \| NICKNAME \| ME_AREA \| DAY_SKY \| NEXT_BIG)` | 스토리용 카드 |
| 이번 달 | `MonthSky { themes, days }` | 시기 탭의 이번 달 — "이번 달 내내" 줄(`themes`)과 눈여겨볼 날(`days`, 달력의 점). 서버가 요청 때 계산(결정 032) |
| 그날의 하늘 · 오늘의 하늘 | `DaySky` | 달력에서 누른 하루의 제목 · 흐름 셋 · 이유 · 실천 셋. 오늘의 하늘은 오늘 날짜의 그날의 하늘(같은 글). 소유자 · 차트 · 날짜마다 한 번 만들어 저장(결정 034) |

## API 계약 (앱 저장소와 맞춘다. 바꾸면 이 표를 같은 커밋에서 고친다)
| 메서드·경로 | 인증 | 요청 | 응답 |
|---|---|---|---|
| `GET /places` · `GET /places?q=` | 없음 | 없음 · 검색어 | `[{ id, name, lat, lon }]` |
| `GET /faqs` | 없음 | 없음 | `[{ id, question, answer }]` 공개한 것만 순서대로 · 하나도 없으면 `[]` · 아래 "자주 묻는 질문" |
| `GET /app-config` | 없음 | 없음 | 200 `{ ios: PlatformConfig, android: PlatformConfig, maintenance: { active, message } }` · 아래 "앱 설정과 강제 업데이트" |
| `POST /charts` | 세션 | `{ date, time \| null, placeId \| null }` · `placeId` 는 `time` 이 있을 때만 필수 | `Chart` |
| `POST /auth/dev` | 없음 | 없음 · 또는 `{ cookie: true }` | `{ token }` · `cookie` 면 본문 `{}` 와 쿠키 · 개발 설정에서만, 꺼져 있으면 404 (결정 005). 켜는 것은 `DEPLOY_ENV` 가 local·staging 일 때만 — 그 밖에서 켜면 서버가 시작하지 않는다(결정 022) |
| `POST /auth/login` | 없음 | `{ provider: GOOGLE \| APPLE \| KAKAO, idToken?, code?, redirectUri?, authorizationCode?, cookie? }` · `idToken` 이나 (`code` + `redirectUri`) 중 하나 — `code` 는 KAKAO 웹만 · `authorizationCode` 는 APPLE 만(웹은 그 로그인의 `redirectUri` 를 함께) | 200 `{ status: SIGNED_IN, token? }`(계정이 있다 · `cookie` 면 `token` 없이 쿠키) · 200 `{ status: SIGNUP_REQUIRED, signupToken, expiresAt }`(계정이 없다 · 30분 · 1회용) · 아래 "로그인과 가입" |
| `POST /auth/signup` | 없음 | `{ signupToken, date, time \| null, placeId \| null, nickname, avatar, termsVersion, privacyVersion, pushConsent?, marketingConsent?, cookie? }` · 출생 정보 규칙은 `PUT /profile` 과 같다 · `nickname` 필수: 앞뒤 공백을 뗀 한글(가~힣) · 영문만 2~10자, 숫자 · 공백 · 기호 · 이모지 안 됨, 중복 안 됨(영문 대소문자는 같은 이름) · `avatar` 필수: 프로필 그림 번호, 정수 1~8(결정 028) · `termsVersion` · `privacyVersion` 필수: 앱이 보여 준 이용약관 · 개인정보 처리방침 버전, 각각 서버의 지금 버전(이용약관 `"2026-11-20"` · 처리방침 `"2026-11-26"` — 처리방침은 제품 분석을 더한 판, 앱 결정 081)이나 함께 받는 옛 버전(이용약관 `"2026-11-19"` · 처리방침 `"2026-11-20"` · `"2026-11-19"` — 새 버전을 보내는 앱이 나오면 뺀다)과 글자 그대로 같아야 한다 — 섞여도 된다(결정 026 · 045) · `pushConsent` · `marketingConsent` 선택(기본 false, 결정 027 · 025) · 동의 시각은 보내지 않는다(서버 시각) | 201 `{ token? }` · 판정 순서: 가입권 401 `SIGNUP_EXPIRED` → 약관 버전이 없거나 받는 버전 밖이면 400 `TERMS_REQUIRED` → 닉네임이 틀리면 400 `INVALID_NICKNAME` → 프로필이 없거나 1~8 밖이면 400 `INVALID_AVATAR` → 출생 정보 400 → 만 14세 미만 403 `UNDER_AGE`(계정·출생 정보·가입권 0건) · 닉네임을 다른 계정이 쓰면 409 `NICKNAME_TAKEN` · 400 · 409 는 모두 가입권을 그대로 둔다(고쳐서 같은 가입권으로 다시 보낸다) · 아래 "로그인과 가입" |
| `POST /auth/nickname-check` | 없음(가입권) | `{ signupToken, nickname }` · 닉네임 규칙은 `POST /auth/signup` 과 같다 | 200 `{ available: boolean }`(영문 대소문자는 같은 이름) · 가입권이 없거나 썼거나 만료면 401 `SIGNUP_EXPIRED` · 규칙에 어긋나면 400 `INVALID_NICKNAME` · 로그인 · 가입과 같은 속도 제한(결정 025) |
| `GET /auth/session` · `DELETE /auth/session` | 세션 | 없음 | 204 · 204(이 세션만 지운다 · 쿠키도 지운다) |
| `GET /auth/analytics-id` | 세션 | 없음 | 200 `{ analyticsId }` — 분석 도구용 사용자 id: 계정 id 의 SHA-256 앞 32자(16진수, 같은 계정은 늘 같은 값, 저장하지 않는다, 계정 id 는 내보내지 않는다) · 세션이 없으면 401 · 정지 계정은 403(열린 경로가 아니다) · `GET /auth/session` 의 204 는 그대로 — 더하기만 해서 합치는 순서 제약이 없다(앱 결정 040 · 081) |
| `POST /auth/apple/notifications` | 없음(애플 서명) | `{ payload }` — 애플이 서명한 JWT | 200 · 애플 알림이 아니면 400 `INVALID_NOTIFICATION`(본문이 JSON 이 아니면 400 `INVALID_REQUEST`) · 점검 중에도 받는다 · 아래 "로그인과 가입" |
| `GET /profile` · `PUT /profile` | 세션 | `{ date, time \| null, placeId \| null }` · `placeId` 는 `time` 이 있을 때만 필수 | 같은 형태 · `time` 이 `null` 이면 `placeId` 도 `null` · 저장한 적 없으면 `GET` 은 404 `NO_PROFILE` · `PUT` 의 400 은 `POST /charts` 와 같다 · 한국 날짜 기준 만 14세 미만 날짜면 `PUT` 은 403 `UNDER_AGE` 이고 있던 것은 그대로다(결정 021) · `PUT` 으로 출생 정보가 **바뀌면**(처음 저장 · 같은 값 제외) 그 계정의 요약 해석 · 나 탭 풀이 · 시기 풀이 · 그날의 하늘 저장분 가운데 새 차트가 아닌 것을 지운다 — 다음 요청이 새 출생 정보로 다시 만든다(별명은 앱이 `POST /readings` 를 다시 부른다). 공유 링크는 그대로 열린다(결정 041) |
| `GET /account` | 세션 | 없음 | `{ nickname \| null, email \| null, provider: GOOGLE \| APPLE \| KAKAO \| DEV, marketingConsent: boolean, marketingConsentAt: ISO-8601 \| null, pushConsent: boolean, pushConsentAt: ISO-8601 \| null, avatar: 1~8 \| null, suspended: boolean, todaySkyPush: boolean, todaySkyTime: "HH:mm" }` · `suspended` 는 이용 정지 여부(결정 044 — 사유는 주지 않는다) · `todaySkyPush` 는 오늘의 하늘 알림 스위치 — 직접 정한 적 없으면 `pushConsent` 와 같다(푸시 동의가 꺼져 있으면 켜져 있어도 보내지 않는다 — 앱은 흐리게 보인다), `todaySkyTime` 은 받는 한국 시각(기본 `"08:00"`, 결정 042) · 옛 계정은 닉네임·이메일·프로필이 null(결정 025 · 027 · 028) |
| `PATCH /account` | 세션 | `{ nickname?, marketingConsent?, pushConsent?, avatar?, todaySkyPush?, todaySkyTime? }` · 보낸 칸만 바꾼다(null 은 보내지 않은 것) · 닉네임 · 프로필 규칙은 `POST /auth/signup` 과 같다 · `todaySkyTime` 은 `"HH:mm"` 5분 단위(결정 042) | `GET /account` 와 같은 모양 · 닉네임이 틀리면 400 `INVALID_NICKNAME`, 프로필이 1~8 밖이면 400 `INVALID_AVATAR`, 받는 시간이 5분 단위 `HH:mm` 이 아니면 400 `INVALID_TODAY_SKY_TIME`, 닉네임을 다른 계정이 쓰면 409 `NICKNAME_TAKEN`(모두 같이 보낸 칸도 바꾸지 않는다 · 내 닉네임의 대소문자만 바꾸는 건 된다) · 동의(마케팅 · 푸시 각각)를 켜면 그때의 일시, 끄면 null, 같은 값이면 일시 그대로 · 웹 CORS 는 PATCH 를 허용한다(결정 025 · 027 · 028) |
| `GET /account/nickname-check?nickname=` | 세션 | 쿼리 `nickname` · 규칙은 `POST /auth/nickname-check` · `PATCH /account` 와 같다 | 200 `{ available: boolean }`(영문 대소문자는 같은 이름 · 내 지금 닉네임이면 true) · 틀리거나 없으면 400 `INVALID_NICKNAME`(결정 051) |
| `DELETE /account` | 세션 | 없음 | 204 · 카카오 계정은 연결 끊기, 애플 계정은 토큰 철회를 먼저 부른다(실패해도 지운다) · 웹의 쿠키도 지운다 |
| `POST /readings` | 세션 | 없음(프로필의 출생 정보를 쓴다) | `Reading`(요약 해석 v2 — 결정 030 · 031) · 저장한 출생 정보가 없으면 404 `NO_PROFILE` · 글을 만들지 못하면 502 `GENERATION_FAILED`(저장하지 않는다) · 생성하는 사이에 `PUT /profile` 로 출생 정보가 바뀌었으면 옛 차트의 글은 저장하지 않고 별명 알림(알림함 · 푸시)도 내지 않으며 같은 502 다 — 다시 부르면 새 차트로 만든다(결정 041) · 처음 만들 때는 수십 초가 걸릴 수 있고, 같은 차트는 저장된 글을 바로 준다 · 새로 생성해야 할 때 계정별 생성 한도를 넘으면 429 `RATE_LIMITED`(저장본은 세지 않는다, 결정 022·023) |
| `POST /reports` | 세션 | 없음(프로필의 출생 정보를 쓴다) | 202 `{ status: WRITING \| DONE \| FAILED }` · 글을 기다리지 않고 바로 답한다 · 저장한 출생 정보가 없으면 404 `NO_PROFILE` · 아래 "리포트 상태" · 새로 생성해야 할 때 계정별 생성 한도를 넘으면 429 `RATE_LIMITED`(저장본은 세지 않는다, 결정 022·023) · 새로 시작할 때 자세한 풀이와 같은 판정(426 `UPDATE_REQUIRED` · 402 `PURCHASE_REQUIRED` · 503 `PURCHASE_CHECK_FAILED` — 아래 "자세한 풀이 상품", 결정 056 의 12. 앱은 부르지 않는 옛 경로) |
| `GET /reports/me` | 세션 | 없음 | 200 `{ status: NONE \| WRITING \| FAILED \| DONE, report? }` · 지금 프로필의 차트 기준 · `report` 는 `DONE` 일 때만(`sections` 와 `timing` 이 언제나 함께 온다) · 저장한 출생 정보가 없으면 `NONE` |
| `GET /timing/month?month=YYYY-MM` | 세션 | `month` 선택 — 없거나 빈 값이면 한국 날짜의 이번 달 | 200 `MonthSky` · 형식이 틀리면 400 `INVALID_MONTH` · 지난달 · 이번 달 · 다음 달(한국 날짜) 밖이면 400 `MONTH_OUT_OF_RANGE` · 저장한 출생 정보가 없으면 404 `NO_PROFILE` · 계정별 분당 `month-per-minute`(60)을 넘으면 429 `RATE_LIMITED` · 저장하지 않고 모델 · 생성 한도를 쓰지 않는다 · 시기 풀이 없이 열린다 · 아래 "이번 달" |
| `POST /day-sky` | 세션 | `{ date?: "YYYY-MM-DD" }` — 본문이 없거나 `date` 가 없거나 null 이면 한국 날짜의 오늘(오늘의 하늘) | 200 `DaySky` · 형식이 틀리면(빈 글자 · 없는 날 포함) 400 `INVALID_DATE` · 지난달 1일 ~ 다음 달 말일(한국 날짜) 밖이면 400 `DATE_OUT_OF_RANGE` · 본문이 JSON 객체가 아니면 400 `INVALID_REQUEST` · 저장한 출생 정보가 없으면 404 `NO_PROFILE` · 글을 만들지 못하면 502 `GENERATION_FAILED`(저장하지 않는다 · 다시 보내면 된다) · 처음 만들 때는 수십 초가 걸릴 수 있고, 같은 차트 · 같은 날은 저장된 글을 바로 준다 · 새로 생성해야 할 때 계정별 생성 한도를 넘으면 429 `RATE_LIMITED`(저장본은 세지 않는다, 결정 022·023) · 아래 "그날의 하늘" |
| `GET /notifications` | 세션 | 없음 | 200 `{ unread, items[{ id, kind, createdAt, read, synastryId?, inquiryId? }] }` · 최근 30일 · 새것부터 · 100개까지 · `kind` = `READING_DONE`(별명 지음) · `ME_REPORT_DONE` · `TIMING_REPORT_DONE` · `SYNASTRY_REPORT_DONE`(궁합 리포트 — 1.0.0 앱은 숨김) · `INQUIRY_ANSWERED`(문의 답변 — `inquiryId` 로 답변 보기) · `synastryId` · `inquiryId` 는 그 종류일 때만 있고 아니면 키가 없다 · `unread` 는 30일 안의 안 읽은 수(홈의 종 점) · 문구는 앱이 갖는다(결정 035) |
| `POST /notifications/{id}/read` | 세션 | 없음 | 204(이미 읽었어도) · 남의 것 · 없는 것 · UUID 가 아닌 값은 404 `NOT_FOUND`(결정 035) |
| `POST /me-report` | 세션 | 없음(프로필의 출생 정보를 쓴다) | 202 `{ status: WRITING \| DONE \| FAILED }` · 글을 기다리지 않고 바로 답한다 · 저장한 출생 정보가 없으면 404 `NO_PROFILE` · 아래 "나 탭 풀이" · 새로 생성해야 할 때 계정별 생성 한도를 넘으면 429 `RATE_LIMITED`(저장본은 세지 않는다, 결정 022·023) · 새로 시작할 때 `X-App-Version` 이 1.1.0 미만이면 426 `UPDATE_REQUIRED`(상품이 있어도) · 상품이 없으면 402 `PURCHASE_REQUIRED` · 상품을 묻지 못하면 503 `PURCHASE_CHECK_FAILED`(셋 다 결제 시작 시각 뒤에만, 행을 만들지 않는다 — 아래 "자세한 풀이 상품") |
| `POST /timing-report` | 세션 | 없음(프로필의 출생 정보를 쓴다) | 202 `{ status: WRITING \| DONE \| FAILED }` · 상태 규칙 · 오류는 `POST /me-report` 와 같다(404 `NO_PROFILE` · 429 `RATE_LIMITED` · 426 `UPDATE_REQUIRED` · 402 `PURCHASE_REQUIRED` · 503 `PURCHASE_CHECK_FAILED`) · 아래 "시기 풀이" |
| `GET /timing-report` | 세션 | 없음 | 200 `{ status: NONE \| WRITING \| FAILED \| DONE, report?: TimingReport }` · 지금 프로필의 차트 기준(출생 정보를 고치면 `NONE`) · `report` 는 `DONE` 일 때만 · 저장한 출생 정보가 없으면 `NONE` · `POST` 가 429 였으면 `FAILED` · 아래 "시기 풀이" |
| `GET /me-report` | 세션 | 없음 | 200 `{ status: NONE \| WRITING \| FAILED \| DONE, report?: MeReport }` · 지금 프로필의 차트 기준(출생 정보를 고치면 `NONE`) · `report` 는 `DONE` 일 때만 · 저장한 출생 정보가 없으면 `NONE` · `POST` 가 429 `RATE_LIMITED` 였으면 행이 `FAILED` 로 남아 `FAILED`(다시 `POST` 하면 새로 시작) · 아래 "나 탭 풀이" |
| `GET /entitlements` | 세션 | 없음 | 200 `{ detail: boolean }` — 자세한 풀이를 받을 수 있나(받기의 판정과 같은 값: 결제 시작 시각 전이면 모두 `true`, 뒤면 결제 없이 여는 계정 · 1.0.0 수령자 · 산 계정이 `true` — RevenueCat 에 있으면 이때 `STORE` 로 적는다) · RevenueCat 을 묻지 못하면 503 `PURCHASE_CHECK_FAILED` · 앱은 결제 시트 · 마이의 "샀음" 표시에만 쓰고 받기는 여전히 402 를 따른다 · 결정 056 의 8 |
| `POST /entitlements/confirm` | 세션 | 없음 또는 `{ noticeVersion? }` — 결제 시트가 보여 준 고지(청약철회 안내)의 판, 영문 · 숫자 · `.` `_` `-` 1~40자(틀리면 400 `INVALID_NOTICE_VERSION`, 글자가 아니면 400 `INVALID_REQUEST` — RevenueCat 을 묻기 전에) · 앱이 결제 · 구매 복원 직후 부른다 · 헤더 `X-App-Version`(있으면 받기와 같이 본다) | 200 `{ detail: true, me: WRITING \| DONE \| FAILED, timing: WRITING \| DONE \| FAILED }` — RevenueCat 을 담아 둔 답 없이 물어 스토어 구매가 있으면 `STORE` 로 적고 나 탭 풀이 · 시기 풀이를 함께 시작한 뒤 각 상태(받기와 같은 길 — 이미 완성 · 쓰는 중이면 그대로) · 200 `{ detail: false }` — 스토어 구매가 없다(결제 없이 여는 계정 · 1.0.0 수령자도 스토어 구매가 없으면 `false` — 앱은 "복원할 구매가 없어요") · RevenueCat 을 묻지 못하면 503 `PURCHASE_CHECK_FAILED`(아무것도 적지 않는다) · 계정마다 분당 6번(`byeolmyeong.rate-limit.entitlement-confirm-per-minute`)을 넘으면 429 `RATE_LIMITED`(묻지 않는다) · 구매를 적은 뒤 시작에서 404 `NO_PROFILE` · 429 `RATE_LIMITED` 가 나면 그 답이지만 구매는 적혀 있다 — 다시 부르면 같고 남은 풀이를 시작한다 · **앱은 결제 · 복원 뒤 이 확인이 200 이 될 때까지 다시 부른다**(받기 · `GET /entitlements` 에서 `STORE` 가 새로 적혀도 다른 풀이를 저절로 시작하지 않는다 — 두 풀이를 함께 시작하는 곳은 여기뿐) · 결정 056 의 7 |
| `POST /synastry/direct` | 세션 | `{ name, relation: LOVE \| FRIEND \| WORK \| FAMILY, date, time \| null, placeId? }` (상대의 출생 정보) | 200 `Synastry` + `overlay` · 아래 "궁합(직접 입력)" · 새로 생성해야 할 때 계정별 생성 한도를 넘으면 429 `RATE_LIMITED`(저장본은 세지 않는다, 결정 022·023) |
| `POST /synastry/invites` | 세션 | `{ name }` | `{ inviteId, expiresAt }` |
| `GET /invites/{inviteId}` | 없음 | 없음 | `{ inviterName, status }` |
| `POST /invites/{inviteId}/accept` | 세션 | `{ name }` | `Synastry` |
| `GET /synastry` · `GET /synastry/{id}` | 세션 | 없음 | `[Synastry]` 새것부터 · `Synastry` · 둘 다 `overlay` 없음 · 남의 것·없는 것 404 `NOT_FOUND` |
| `GET /synastry/ranking?relation=` | 세션 | 관계 유형(필수) | `[{ id, name, score, scoreLabel?, keywords[3] }]` 점수가 높은 순(같으면 먼저 만든 것), 인원 제한 없음 · 유형이 없거나 틀리면 400 `INVALID_RELATION` |
| `DELETE /synastry/{id}` | 세션 | 없음 | 204 · 남의 것·없는 것·UUID 가 아닌 값 404 `NOT_FOUND` · 아래 "궁합 지우기" |
| `POST /directions` | 세션 | `{ topic: LOVE }` · `{ topic: FUTURE }` (둘 다 프로필의 출생 정보를 쓴다) · `{ topic: SYNASTRY, synastryId, date?, time? \| null, placeId? }` (`date`·`time`·`placeId` 는 **상대의** 출생 정보이고 그 궁합의 글을 처음 만들 때만 필요하다) | 200 `Direction` · `topic` 이 없거나 받는 값이 아니면 400 `INVALID_TOPIC` · 본문이 JSON 이 아니면 400 `INVALID_REQUEST` · 저장한 출생 정보가 없으면 404 `NO_PROFILE` · 글을 만들지 못하면 502 `GENERATION_FAILED`(저장하지 않는다) · 처음 만들 때는 수십 초가 걸릴 수 있고, 만든 글이 있으면 바로 준다 · `SYNASTRY` 에만 있는 오류와 주제별 규칙은 아래 "방향성 제시" · 새로 생성해야 할 때 계정별 생성 한도를 넘으면 429 `RATE_LIMITED`(저장본은 세지 않는다, 결정 022·023) |
| `POST /synastry/{id}/report` | 세션 | `{ date, time \| null, placeId? }` (상대의 출생 정보를 다시 보낸다) | 202 `{ status: WRITING \| DONE \| FAILED }` · 아래 "궁합 리포트" · 새로 생성해야 할 때 계정별 생성 한도를 넘으면 429 `RATE_LIMITED`(저장본은 세지 않는다, 결정 022·023) |
| `GET /synastry/{id}/report` | 세션 | 없음 | 200 `{ status: NONE \| WRITING \| FAILED \| DONE, report? }` · `report` 는 `DONE` 일 때만 · 남의 것·없는 것 404 `NOT_FOUND` |
| `POST /inquiries` | 세션 | `{ category: BUG \| IDEA \| ACCOUNT \| ETC, body }` · 글 10~1000자 · 옛 앱(`replyEmail` 키를 보냄)은 옛 종류 · 2000자까지 받고 이메일은 무시(결정 039) | 201 `{ id }` · 아래 "문의하기" |
| `GET /inquiries` | 세션 | 없음 | 200 `[InquiryItem]` 내 문의 전부, 새것부터 · 없으면 `[]` · 아래 "문의하기" |
| `GET /inquiries/{id}` | 세션 | 없음 | 200 `InquiryItem` · 남의 것 · 없는 것 · UUID 가 아닌 값 404 `NOT_FOUND` |
| `POST /shares` | 세션 | `{ kind: READING }` · `{ kind: SCORE, synastryId, myName }` · `{ kind: RANKING, relation, showNames? }` · `{ kind: NEXT_TURN }` · 공유 v2 `{ kind: NICKNAME }` · `{ kind: ME_AREA, area }` · `{ kind: DAY_SKY, date }` · `{ kind: NEXT_BIG }` | 200 `{ shareId, card }` · `card` 는 `ShareCard` · 공유할 글이 없거나 남의 것이면 404 `NOT_FOUND` · 아래 "공유" |
| `POST /shares/preview` | 세션 | `POST /shares` 와 같다 | 200 `{ card }`(만들 때와 같은 카드) · **공유 행 · `shareId` · 감사 기록이 없다**(화면에 들어올 때 미리 받기) · 400 · 404 도 `POST /shares` 와 같다(결정 051) |
| `DELETE /shares/{shareId}` | 세션 | 없음 | 204 · 남의 것·없는 것 404 `NOT_FOUND` |
| `GET /shared/{shareId}` | 없음 | 없음 | 200 `ShareCard`(요약 해석·궁합 점수·랭킹·다음 변곡점 카드 중 하나, 글과 숫자만) · 없는 식별자 404 `NOT_FOUND` · 429 `RATE_LIMITED` |
| `POST /push/devices` | 세션 | `{ expoToken, platform: IOS \| ANDROID }` · 헤더 `X-App-Version`(있으면 함께 둔다) · 웹은 부르지 않는다 | 204 · 같은 토큰이면 계정 · 플랫폼 · 앱 버전 · 마지막 등록 시각을 고친다(다른 계정이 보내면 그 계정으로 옮긴다) · 토큰이 `ExponentPushToken[…]` · `ExpoPushToken[…]`(200자까지)가 아니면 400 `INVALID_PUSH_TOKEN` · 플랫폼이 둘 밖이면 400 `INVALID_PLATFORM`(결정 042) |
| `DELETE /push/devices/{expoToken}` | 세션 | 없음 · 토큰은 경로에 URL 인코딩해 넣는다(`[` `]` → `%5B` `%5D`) · 앱은 로그아웃 때 부른다 · 정지된 계정도 부를 수 있다 | 204 · 남의 것 · 없는 것은 404 `NOT_FOUND`(결정 042) |

- 요청 본문: 값은 정확한 JSON 타입만 받는다(소유자 2026-11-19). 정수 칸에 실수 · 글자(`4.9` · `3.0` · `"3"`), 참거짓 칸에 숫자 · 글자(`1` · `"true"`)가 오면 본문을 읽지 못한 것으로 보고 400 `INVALID_REQUEST` 다 — 본문을 읽을 때 걸리므로 가입권 확인 같은 다른 검사보다 먼저다(세션 검사는 그보다 먼저). 실수 칸에 정수는 받는다. 설정 `spring.jackson.deserialization.accept-float-as-int=false` · `spring.jackson.mapper.allow-coercion-of-scalars=false`. 관리자 API 는 본문을 직접 검사해 원래부터 이렇다.
- 세션: 요청 헤더 `Authorization: Bearer <token>`, 헤더가 없으면 쿠키 `bm_session`(웹, 결정 021). 없거나 틀리거나 마지막 사용 후 90일이 지났으면 401 `UNAUTHORIZED`. 쿠키로 인증된 GET · HEAD 가 아닌 요청은 `Origin` 이 `ALLOWED_ORIGINS` 에 없으면 403 `FORBIDDEN_ORIGIN`. 표에서 인증이 "없음" 인 경로와 `GET /health` 말고는 모두 세션이 필요하다(새 경로는 기본이 세션 필요다). 점검 중에는 세션 검사보다 먼저 503 `MAINTENANCE` 가, 앱이 옛 버전이라 막힌 동안에는 426 `UPDATE_REQUIRED` 가 온다(아래 "앱 설정과 강제 업데이트"). **이용 정지된 계정**(결정 044)은 세션 검사 바로 뒤에 403 `ACCOUNT_SUSPENDED` 다 — 열린 곳은 `POST /inquiries` · `GET /inquiries` · `GET /inquiries/{id}` · `GET /account`(`suspended: true`) · `DELETE /auth/session`(로그아웃) · `DELETE /account` · `DELETE /push/devices/{expoToken}`(로그아웃 때 기기 지우기 — 결정 042) 일곱뿐이고 새 경로도 기본이 막힘이다. 로그인 · 가입은 그대로 세션을 준다(문의할 세션이 있어야 한다). 앱은 어디서든 이 403 이나 `GET /account` 의 `suspended: true` 를 받으면 정지 화면으로 간다(앱 결정 065 의 5). 토큰은 서버가 원문을 저장하지 않는다(해시만). `/admin/**` 는 이 표에 없다 — 아래 "관리자 API" 가 따로 다루고, 사용자 세션 토큰은 거기서 통하지 않는다(관리자 토큰도 이 표의 경로에서 통하지 않는다).
- 브라우저의 다른 출처 요청(CORS): 설정 `byeolmyeong.cors.allowed-origins`(환경 변수 `ALLOWED_ORIGINS`, 쉼표 구분)에 적힌 주소만 허용한다. 비어 있으면 허용하지 않는다. 허용 메서드 GET·POST·PUT·DELETE, 허용 헤더 `Authorization`·`Content-Type`. 자격 증명(쿠키 `bm_session`)을 허용한다(결정 021). `/admin/**` 는 이 주소들에 열리지 않는다 — 관리 화면의 주소(`ADMIN_ALLOWED_ORIGINS`)만 부를 수 있고, 그 주소는 이 표의 경로를 부를 수 없다("관리자 API" 의 "브라우저 출처").
- 로그인과 가입 (결정 021):
  - `POST /auth/login`: 공급자 ID 토큰의 서명(JWKS)·`iss`·`aud`·`exp` 를 서버가 확인한다. **웹 클라이언트(`GOOGLE_WEB_CLIENT_ID`)가 받은(`azp`) 구글 ID 토큰은 만료까지 한 번만 받는다** — 같은 토큰(`jti`)을 다시 보내거나 `jti` 가 없으면 401 `INVALID_ID_TOKEN`(웹 리디렉트의 토큰이 방문 기록에 남아서, 결정 047). 앱(iOS · 안드로이드, `azp` 가 앱 클라이언트) · `azp` 가 없는 토큰 · 애플 · 카카오는 그대로. 구글 로그인마다 `event=google_token_client azp=… aud=… once=…` 로 받은 클라이언트를 이름표(`WEB` · `IOS` · `ANDROID` · `OTHER` · `NONE`)로만 남긴다(앱 클라이언트는 `GOOGLE_IOS_CLIENT_IDS` · `GOOGLE_ANDROID_CLIENT_IDS`). KAKAO 웹은 `code` + `redirectUri` 를 보내고 서버가 카카오와 교환해 ID 토큰을 얻는다(`redirectUri` 는 `KAKAO_REDIRECT_URIS` 에 있어야 한다). APPLE 의 `authorizationCode` 는 서버가 바로 교환해 refresh token 을 둔다(탈퇴 때 철회용 · 5분 · 1회용이라 로그인 때 바꾼다 · 교환이 실패해도 로그인은 된다 · 웹은 로그인에 쓴 `redirectUri` 를 함께 보낸다 — 애플은 localhost 를 받지 않는다). 만 14세 미만으로 가입을 못 하면 받아 둔 애플 토큰도 철회한다. 계정에는 공급자와 공급자의 사용자 식별자, 그리고 공급자가 확인한 이메일(토큰의 `email` 을 받은 그대로, 구글 · 애플은 `email_verified` 참)을 둔다(결정 024). 새 사용자의 토큰에 확인된 이메일이 없으면 400 `EMAIL_REQUIRED` 이고 가입권을 만들지 않는다. 이미 있는 계정은 이메일이 없어도 로그인되고, 토큰에 있으면 고쳐 둔다. 이메일은 응답 · 로그 · 감사 기록에 넣지 않는다.
  - 오류: 400 `INVALID_REQUEST`(본문이 JSON 객체가 아니다 · `provider` 가 없거나 목록에 없다 · `idToken` 과 `code` 가 둘 다 없거나 둘 다 있다) · 401 `INVALID_ID_TOKEN`(서명·발급자·대상·만료 중 하나라도 틀리다 · 대상이 여럿인데 `azp` 가 우리가 아니다 · 모르는 키로 서명했다 · 웹 클라이언트가 받은 구글 토큰의 `jti` 를 이미 썼거나 `jti` 가 없다 — 결정 047, 선점 뒤 로그인이 다른 까닭으로 실패해도 그 토큰은 다시 못 쓴다) · 400 `EMAIL_REQUIRED`(처음 온 사람의 토큰에 확인된 이메일이 없다 — 결정 024) · 401 `INVALID_CODE`(카카오가 코드를 400 으로 거절했다 · 등록 안 된 `redirectUri`) · 502 `PROVIDER_UNAVAILABLE`(공급자의 키나 토큰 경로에 닿지 못했다 · 카카오가 401 · 403 — 우리 키 설정 문제) · 429 `RATE_LIMITED`(로그인 · 가입 · 닉네임 확인을 합친 주소별 분당 `auth-per-minute` 30).
  - `POST /auth/signup`: 가입권이 없거나 썼거나 만료면 401 `SIGNUP_EXPIRED`. `termsVersion` · `privacyVersion` 이 각각 서버 설정의 지금 버전(`byeolmyeong.legal.terms-version` · `privacy-version`)이나 함께 받는 옛 버전 목록(`byeolmyeong.legal.terms-previous-versions` · `privacy-previous-versions`, 쉼표 · 비어도 됨)에 없으면 400 `TERMS_REQUIRED` 이고 가입권은 남는다(결정 026 · 045 — 지금 버전이 비면 서버가 시작하지 않는다). 출생 정보가 틀리면 `POST /charts` 와 같은 400. 한국 시간 오늘 기준 만 나이가 14 미만이면 403 `UNDER_AGE` 이고 가입권을 지운다(계정·출생 정보·가입권 0건). 14 이상이면 계정·출생 정보·세션을 한 트랜잭션에 만들고, 동의한 두 버전(앱이 보낸 값 — 옛 버전이면 옛 버전, 결정 045)과 그 시각(서버 시각) · 푸시 수신 동의 일시(`pushConsent` 가 true 일 때, 아니면 null) · 프로필 번호를 계정에 둔다(감사 기록 `TERMS_AGREED`). 남은 가입권으로 다시 와서 계정이 이미 있으면 출생 정보 · 닉네임 · 약관 동의 · 수신 동의 · 프로필은 그대로 두고 세션만 준다. 같은 사람의 가입권 두 장이 동시에 와도 계정을 실제로 만든 요청 하나만 이것들을 채우고 `PROFILE_SAVED` · `TERMS_AGREED` 를 남긴다(다른 요청은 세션만).
  - `cookie: true` 이면 세션을 본문 대신 쿠키 `bm_session`(`HttpOnly; Secure; SameSite=Lax; Path=/`)으로 준다. `Secure` 는 `SESSION_COOKIE_SECURE=false` 로만 끈다(로컬 http).
  - `POST /auth/apple/notifications`: 본문 `{ payload }` 의 JWT 를 애플 키(RS256)로 확인하고 발급자 `https://appleid.apple.com` · 대상(`APPLE_CLIENT_IDS`)을 본다. `events`(객체 또는 JSON 글자)의 `type` 이 `consent-revoked` 면 그 계정의 세션 가운데 `event_time` 보다 먼저 생긴 것을 지우고(없으면 모두), `account-deleted`(문서에 따라 `account-delete`)면 계정을 지운다(철회는 부르지 않는다, 감사 기록 행위자 SYSTEM). 다른 종류와 모르는 사용자는 200 으로 받고 아무것도 하지 않는다. 같은 알림이 늦게 되풀이돼도 그 뒤에 로그인한 세션은 남는다. 애플 키를 받지 못하면 502. 점검(503) 중에도 받는다.
- `Chart` = `{ timeKnown, houseSystem: "PLACIDUS", moonSignMayChange?, positions[{ body, sign, degree, minute, longitude, house?, retrograde }], points[{ point, sign, degree, minute, longitude, house? }], houses?[12 × { house, sign, degree, minute, longitude }], angles?{ asc{ sign, degree, minute, longitude }, mc{ sign, degree, minute, longitude } }, aspects[{ a, b, type, orb, applying }] }`
  - `longitude` 는 황경 0 이상 360 미만(소수 둘째 자리, 도·분처럼 올리지 않고 버린다). 앱이 휠을 그릴 때 쓴다. `moonSignMayChange: true` 는 시각을 모르고 그날 안에 달 별자리가 바뀔 때만 보낸다. 상승점·중천은 `angles` 에만 온다. `houses` 는 1번부터 순서대로. `houseSystem` 은 시각을 몰라도 보낸다. (2026-11-11 확정·구현)
  - `body`: `SUN`·`MOON`·`MERCURY`·`VENUS`·`MARS`·`JUPITER`·`SATURN`·`URANUS`·`NEPTUNE`·`PLUTO`. `point`: `NORTH_NODE`·`LILITH`·`FORTUNE`·`VERTEX`. `sign`: `ARIES`…`PISCES`. `type`: `CONJUNCTION`·`SEXTILE`·`SQUARE`·`TRINE`·`OPPOSITION`.
  - `degree`(0~29)·`minute`(0~59)는 별자리 안 도수이고 올리지 않고 버린다. `house` 는 1~12. `orb` 는 도(소수 둘째 자리). `aspects` 는 행성 10개 사이만.
  - 시각이 `null`(모름)이면 `timeKnown: false` 이고 `house`·`houses`·`angles` 키와 `FORTUNE`·`VERTEX` 점이 없다(값이 `null` 인 키를 보내지 않는다).
- `POST /charts`·`PUT /profile` 요청: `date` 는 `YYYY-MM-DD`, `time` 은 `HH:mm` 또는 `null`, `placeId` 는 `GET /places` 의 `id`(정수).
- 태어난 시각을 모르면 태어난 곳을 받지 않는다(결정 007): `time` 이 `null`(또는 키 없음)이면 `placeId` 는 없어도 되고, 보내도 읽지 않으며(목록에 없는 id 여도 400 이 아니다) 저장하지 않는다. 그때 `GET`·`PUT /profile` 의 응답은 `placeId: null` 이다. `time` 이 있으면 `placeId` 는 필수이고 없거나 `null` 이거나 목록에 없으면 400 `UNKNOWN_PLACE` 다. 시각을 모르는 날의 기준 시각은 한국 표준시 정오다.
- 오류 응답: `{ error }`. `POST /charts` 는 400 `INVALID_REQUEST`(본문 형식)·`INVALID_DATE`(형식, 1900-01-01 이전, 오늘 이후)·`INVALID_TIME`(형식은 `HH:mm` 만 — 초가 붙으면 안 된다, 날짜가 오늘(한국 시각)이면 아직 오지 않은 시각)·`UNKNOWN_PLACE`. 출생 정보를 받는 다른 경로(`PUT /profile`·`POST /synastry/direct` 등)의 `INVALID_TIME` 도 같다. 요청 수 한도를 넘으면 429 `RATE_LIMITED`.
- 자세한 풀이 상품(결정 056, 앱 결정 101): 자세한 풀이(`POST /me-report` · `POST /timing-report`)를 **새로 시작하기 직전**(생성 한도를 세기 전) "받을 수 있나"(`report.EntitlementPolicy`)를 묻는다. 이미 완성됐거나 쓰는 중인 풀이의 `POST` 와 `GET` 은 묻지 않는다. 거절 · 실패면 행 · 감사 기록 · 생성 한도 셈이 없다.
  - 결제 시작 시각(설정 `byeolmyeong.purchase.starts-at`, 환경 변수 `PURCHASE_STARTS_AT`)이 없거나 아직이면 모두 받는다(1.0.0 과 같다). 값을 읽을 수 없거나, 값이 있는데 `REVENUECAT_SECRET_KEY` 가 비어 있으면 서버가 시작하지 않는다.
  - 그 뒤 **옛 앱의 새 받기는 426 `{ "error": "UPDATE_REQUIRED" }`**(앱 결정 109 의 3): 요청의 `X-App-Version` 이 읽을 수 있고 1.1.0 보다 낮으면 상품이 있어도 이 답이다(402 · 503 보다 먼저). 머리가 없는 요청(웹)과 읽을 수 없는 버전은 막지 않는다. 앱 전체 막기(아래 "앱 설정과 강제 업데이트" 의 426 — 모든 경로)와 따로 이 두 `POST` 의 새로 시작에만 있다 — 1.0.0 앱은 이 426 을 받으면 업데이트 필요 화면을 띄우고, 이미 받은 풀이 보기 · 다른 기능은 그대로다.
  - 그 뒤면 차례대로: 계정 이메일이 결제 없이 여는 목록(관리자 API "결제 없이 여는 계정")에 있다(대소문자 무관) → 받는다 · `entitlements` 에 그 계정의 줄(`LEGACY` · `STORE`)이 있다 → 받는다 · RevenueCat 에 상품이 있다 → `STORE` 로 적고 받는다 · 아니면 **402 `PURCHASE_REQUIRED`**(앱은 결제 시트, 웹은 "앱에서 받기" 안내). 상품은 하나라 나 탭 · 시기를 가리지 않고, 출생 정보를 바꿔도 그대로다(다시 받기 무료).
  - RevenueCat 은 REST v1 `GET /v1/subscribers/{계정 id}` 의 `subscriber.entitlements` 에 상품(설정 `byeolmyeong.purchase.revenuecat-entitlement`, 기본 `detail`)이 있고 `expires_date` 가 `null` 이거나 뒤면 "있음". 답은 계정마다 1분 서버 메모리에 둔다. 비밀 키 없음 · 연결 실패 · 시간 초과(3초 · 5초) · 2xx 아님 · 읽을 수 없는 답이면 **503 `PURCHASE_CHECK_FAILED`**(열어 주지도, 사라고 하지도 않는다 — 다시 누르면 다시 묻는다).
- `GET /places?q=`: 검색어를 공백으로 나눈 낱말이 이름에 모두 든 곳을 20개까지. `q` 가 없으면 전체 목록(229곳)을 준다. 앱의 출생지 고르기 화면이 쓴다. `name` 은 `시·도 시·군·구` 꼴이고 세종만 `세종특별자치시` 한 낱말이다.
- `Reading` = `{ headline, sections[{ title, body, reason: Reason, basis[] }], strengths[3], cautions[2], createdAt, shareId }`(결정 030 · 031, 2026-11-20 S①). 문단은 시각을 알면 5 · 모르면 4. `reason` 은 그 문단의 "이렇게 풀이한 이유"(`when` 은 언제나 `BIRTH`, `items` 1~2). `createdAt` 은 그 요약 해석을 만든 때(UTC `YYYY-MM-DDTHH:mm:ssZ`, 저장 행의 `created_at`) — 홈의 "별명을 받은 지 N일". `basis[]` 는 옛 근거 낱말로 `reason` 에서 서버가 만든다(아래 "글 생성 규칙" 의 근거에 쓸 수 있는 말, 겹침 없이) — 옛 앱이 읽는 동안만 보내고 계획 S⑦ 에서 뺀다. `shareId` 는 **더 쓰지 않는다**(결정 012): 공유 주소가 아니고 `GET /shared/{그 값}` 은 404 다. 공유는 `POST /shares` 로 만든다. 계획 S⑦ 에서 뺀다.
  - 옛 모양(근거 낱말만 · 보완점 3 · `createdAt` 없음)으로 저장된 요약 해석(`readings.kind = SUMMARY`)은 읽지 않는다: 그 계정의 다음 `POST /readings` 가 새 모양으로 새로 만든다(생성 한도 한 번). 옛 행은 지우거나 고치지 않는다.
- `Reason` = `{ when: BIRTH | NOW | DAY, items: [BasisItem](1~2) }`(결정 031). `BIRTH` 는 태어날 때(요약 해석 · 나 탭 풀이), `NOW` 는 지금 지나는 시기, `DAY` 는 그날. `TRANSIT` 은 `NOW` · `DAY` 에만, 나머지 종류는 `BIRTH` 에만 나온다. 값이 없는 키는 보내지 않는다.
- `BasisItem` 은 `kind` 로 나뉜다(글자는 `Chart` 의 `body` · `sign` · `type` 과 같다, `Angle` = `"ASC"` | `"MC"`):
  - `{ kind: PLACEMENT, body: Body | Angle, sign, house? }` — 놓인 별자리 · 하우스(상승점 · 중천은 `house` 없음)
  - `{ kind: CLUSTER, house, bodies: [Body] }` — 한 하우스에 천체 3개 이상
  - `{ kind: ASPECT, a: Body | Angle, b: Body | Angle, type }` — 태어날 때 두 점의 각도. `a` 가 닿는 쪽(Body 순서에서 뒤의 별 — "토성이 해와"), 상승점 · 중천은 늘 `b`
  - `{ kind: RULER, body: Body | "ASC", ruler: Body }` — `body` 가 놓인 별자리를 다스리는 별(전통 지배성). `body` 는 해 · 달 · 상승점
  - `{ kind: TRANSIT, mover, target?: Body | Angle | "VERTEX", type?, house? }` — 지나가는 별(시기 풀이 · 그날, 계획 S③ · S④)
  - 시각을 모르면 `house` 키 · `CLUSTER` · `ASC` · `MC` · `VERTEX` 가 나오지 않는다. 지점(북쪽 교점 · 릴리스 · 행운점 · 버텍스)은 출생 근거가 아니다.
- 궁합 지우기 `DELETE /synastry/{id}` (2026-11-12 구현, 결정 019): 소유자만 지운다. 204(본문 없음). 남의 것·없는 것·이미 지운 것·UUID 가 아닌 값은 모두 404 `NOT_FOUND` 다. 되돌릴 수 없다.
  - 함께 지워지는 것: 그 궁합, 그 궁합의 리포트(쓰는 중이어도 — 뒤에서 끝난 글은 저장되지 않는다), 그 궁합에 붙은 방향성 제시(`topic: SYNASTRY`). 지운 뒤 `GET /synastry`·`GET /synastry/ranking` 에서 바로 빠지고 `GET /synastry/{id}`·`GET /synastry/{id}/report`·그 `synastryId` 의 `POST /directions`·`POST /shares` 는 404 다.
  - **그대로 남는 것**: 이미 만든 공유 카드(궁합 점수·랭킹)는 공유한 순간의 스냅샷이라(결정 012) 궁합을 지워도 `GET /shared/{shareId}` 로 계속 열린다. 없애려면 `DELETE /shares/{shareId}` 로 따로 지운다 — 앱은 궁합을 지울 때 이것을 알린다.
- 궁합(직접 입력) (2026-11-11 구현, 결정 010. 초대로 연결된 궁합은 아직 없다)
  - `Synastry` = `{ id, name, relation: LOVE | FRIEND | WORK | FAMILY, score, scoreLabel?, headline, keywords[3], reportStatus: NONE | WRITING | FAILED | DONE, createdAt, overlay? }`. `id` 는 UUID, `name` 은 상대를 부를 이름, `score` 는 나 기준 40~100 의 정수(서버 계산), `headline` 은 한 줄 요약, `createdAt` 은 UTC `YYYY-MM-DDTHH:mm:ssZ`. 값이 없는 키는 보내지 않는다(`null` 로 보내지 않는다).
  - `scoreLabel`: 점수가 60 미만이면 `"다른 결의 사이"`, 60 이상이면 키가 없다. 앱은 이 키가 있으면 점수 옆에 그대로 붙인다(60 을 앱에서 다시 판정하지 않는다). 그때 키워드와 한 줄 요약은 끌리는 점부터 쓰여 있다.
  - `overlay`(겹친 휠) = `{ mine[{ body, longitude }], mineAsc?, theirs[{ body, longitude }], lines[{ mine, theirs, type }] }`. **`POST /synastry/direct` 의 응답에만 있다.** 상대의 행성 위치라 서버가 저장하지 않으므로 `GET` 에는 없다. `mine`·`theirs` 는 행성 10개(`body` 순서는 `Chart` 와 같다), `longitude` 는 `Chart` 와 같은 황경(소수 둘째 자리, 버림). `mineAsc` 는 내 상승점의 황경이고 내 태어난 시각을 알 때만 있다. `lines` 는 점수에 쓴 각도다: `mine` 은 `SUN`·`MOON`·`MERCURY`·`VENUS`·`MARS`·`ASC`(내 쪽), `theirs` 는 `body`(상대 쪽), `type` 은 `Chart` 의 각도 종류.
  - `POST /synastry/direct` 요청: `name` 은 앞뒤 공백을 뗀 1~20자, `date`·`time`·`placeId` 는 **상대의** 출생 정보이고 형식은 `POST /charts` 와 같다. `placeId` 는 `time` 이 있을 때만 필수이고 `time` 이 `null` 이면 없어도 된다(와도 읽지 않는다, 앱 결정 020). 나는 저장된 프로필이다. 같은 상대를 다시 보내면 새 궁합이 하나 더 생긴다(서버는 같은 사람인지 알 수 없다).
  - `POST /synastry/direct` 오류: 400 `INVALID_REQUEST`(본문 형식)·`INVALID_NAME`·`INVALID_RELATION`·`INVALID_DATE`·`INVALID_TIME`·`UNKNOWN_PLACE`(시각이 있는데 곳이 없거나 목록에 없다) · 404 `NO_PROFILE`(내 출생 정보를 저장한 적 없다) · 429 `RATE_LIMITED` · 502 `GENERATION_FAILED`(글을 만들지 못했다. 아무것도 저장하지 않는다. 다시 보내면 된다). 글을 받을 때까지 기다렸다 답하므로 수십 초가 걸릴 수 있다.
- 방향성 제시 (`LOVE` 2026-11-11, `FUTURE`·`SYNASTRY` 2026-11-12 구현 — 결정 009·011)
  - `Direction` = `{ topic: LOVE | FUTURE | SYNASTRY, title, items[3 × { title, body, basis[] }] }`. 주제가 달라도 모양은 같다. `title` 은 세 항목을 아우르는 한 줄 제목. `items` 는 정확히 3개이고, 항목의 `title` 은 "기"로 끝나는 실천 한 줄(지시문은 "보기" 로 쓰게 한다 — 결정 037), `body` 는 2~3문장, `basis` 는 1개 이상이고 `LOVE`·`FUTURE` 는 `Reading` 과 같은 말, `SYNASTRY` 는 `SynastryReport` 와 같은 내 쪽 요소의 말(`태양`·`달`·`수성`·`금성`·`화성`, 내 시각을 알면 `상승점`)이다. 공유 식별자는 없다. 다시 생성하는 방법은 없다.
  - `LOVE`(이성, 요약 해석 아래): 지금 프로필의 차트마다 한 편이다. 출생 정보를 고치면 새 글이 만들어진다(예전 글은 남아 있어 되돌리면 다시 나온다).
  - `FUTURE`(미래, 심층 리포트 "시기" 영역 끝): 지금 프로필의 차트로 서버가 계산한 "지금의 시기"(오늘 걸쳐 있는 구간·마디와 다음 구간·마디 — `Report.timing.current` 와 같은 날짜 기준)에 대한 글이다. 심층 리포트를 만들지 않았어도 받을 수 있다. **지금의 시기가 같은 동안 한 편**이다: 어느 구간·마디가 시작하거나 끝나 "지금" 이 달라진 날부터는 같은 요청이 새 글을 만들어 준다(두세 달에 한 번꼴, 그날 첫 요청이 수십 초). 앱은 화면을 열 때마다 그대로 `POST` 하면 된다. 글에는 연도·날짜·나이를 비롯한 숫자가 없다 — 언제인지는 앱이 `timing.current` 로 보여 준다. 시각을 몰라도 받는다(`timing` 과 같이 일부 구간이 빠진 채로).
  - `SYNASTRY`(궁합, 궁합 결과 아래): 직접 입력 궁합 한 건(`synastryId` = `Synastry.id`)에 대한, 나 쪽에서만 쓴 글이다. **궁합(`id`)마다 한 편**이다. 서버에 상대의 출생 정보가 없으므로(결정 010) 그 궁합의 글을 처음 만들 때 본문으로 다시 받는다: `date`·`time`·`placeId` 의 형식과 `placeId` 규칙은 `POST /synastry/direct` 와 같다(`time` 이 `null` 이면 `placeId` 는 없어도 된다). 서버는 그 자리에서 다시 계산해 글을 쓰고 출생 정보를 버린다. 저장하는 것은 글뿐이다.
    - 이미 만든 글이 있으면 `{ topic: SYNASTRY, synastryId }` 만으로 그 글을 준다(출생 정보는 와도 읽지 않는다). 글에는 점수를 비롯한 숫자와 부를 이름이 없다.
    - 앱: 계산 직후의 결과 화면은 입력 화면의 값을 그대로 실어 보낸다. 목록에서 다시 연 결과 화면은 `synastryId` 만 보내고, 400 `BIRTH_REQUIRED` 가 오면 상대의 출생 정보를 다시 입력받아 보낸다(궁합 리포트를 나중에 처음 요청할 때와 같은 입력이다).
    - `SYNASTRY` 의 오류(위 표의 것에 더해): 400 `INVALID_REQUEST`(`synastryId` 가 없다) · 404 `NOT_FOUND`(남의 궁합·없는 궁합·id 모양이 아닌 값) · 아래는 만든 글이 없을 때만 — 404 `NO_PROFILE` · 409 `PROFILE_CHANGED`(내 출생 정보가 그 궁합을 계산한 때와 다르다. 궁합을 새로 계산해야 한다) · 400 `BIRTH_REQUIRED`(`date` 가 없다) · 400 `INVALID_DATE`·`INVALID_TIME`·`UNKNOWN_PLACE` · 400 `BIRTH_MISMATCH`(보낸 출생 정보로 계산한 점수가 그 궁합의 점수와 다르다) · 502 `GENERATION_FAILED`. 확인하는 순서도 이 순서다.
- 궁합 리포트 (2026-11-11 구현, 결정 010)
  - `SynastryReport` = `{ sections[{ area: ATTRACTION | TALK | CONFLICT | LASTING, title, paragraphs[{ title, body, basis[] }] }] }`. `sections` 는 정확히 이 네 영역(끌림·대화·갈등·오래가기)이 이 순서로 오고, 영역마다 문단 3~4개다. `basis` 는 내 쪽 요소의 말뿐이다: `태양`·`달`·`수성`·`금성`·`화성`, 내 시각을 알면 `상승점`(문단마다 1개 이상).
  - `POST /synastry/{id}/report`: 서버에 상대의 출생 정보가 없으므로 본문으로 다시 받는다(형식·`placeId` 규칙은 `POST /synastry/direct` 와 같다). 계산 직후에는 앱이 입력 화면의 값을 그대로 보내고, 나중에 처음 요청할 때는 다시 입력받는다. 글을 기다리지 않고 202 로 답하고, 앱은 `GET /synastry/{id}/report` 를 몇 초 간격으로 다시 부른다(`Synastry.reportStatus` 도 같은 값이다).
  - 상태: `NONE` 요청한 적 없다(`GET` 만) · `WRITING` 쓰는 중 · `DONE` 완성 · `FAILED` 만들지 못했다(다시 `POST` 하면 새로 시작한다). 없거나 `FAILED` 면 생성을 시작하고 `WRITING`. 이미 `DONE`·`WRITING` 이면 다시 만들지 않고 그 상태를 준다(그때는 본문을 읽지 않으므로 없어도 된다). 뒤에서 일할 자리가 꽉 차면 `FAILED`. `WRITING` 인데 살아 있다는 표시(`active_at`, 서버가 15초마다 적는다)가 2분 넘게 끊겼으면 `FAILED` 로 보이고 `POST` 는 다시 시작한다. 줄에서 오래 기다리는 것만으로는 `FAILED` 가 되지 않는다(심층 리포트와 같다 — 아래 "리포트 상태"). 궁합(`id`)마다 리포트는 하나다.
  - `POST` 오류: 400 `INVALID_REQUEST`(본문이 없거나 형식이 틀림)·`INVALID_DATE`·`INVALID_TIME`·`UNKNOWN_PLACE`·`BIRTH_MISMATCH`(보낸 출생 정보로 계산한 점수가 그 궁합의 점수와 다르다 — 다른 사람의 정보다) · 404 `NOT_FOUND`(남의 것·없는 것)·`NO_PROFILE` · 409 `PROFILE_CHANGED`(내 출생 정보가 그 궁합을 계산한 때와 다르다. 궁합을 새로 계산해야 한다).
- 초대(아직 구현 전): 초대로 연결된 궁합에서는 `overlay.theirs` 를 내려보내지 않는다. 이유: 행성 위치로 생일을 역산할 수 있다. `GET /invites/{inviteId}` 의 `status` = `PENDING \| ACCEPTED \| EXPIRED`. 두 사람의 이름(`names[2]`)을 `Synastry` 에 더할지는 초대(태스크 9 의 나머지)에서 정한다. `Synastry` 에 `shareId` 는 두지 않는다(결정 012).
- `Report` = `{ sections[{ area: PERSONALITY \| RELATIONSHIP \| WORK \| EMOTION, title, paragraphs[{ title, body, basis[] }] }], timing }`. `sections` 는 정확히 이 네 영역이 이 순서로 오고, 영역마다 문단 4~6개다. `basis` 는 `Reading` 과 같은 말이다. `timing` 은 아래 "시기" 이고 `DONE` 인 리포트에는 언제나 있다(null 이 아니다). (2026-11-11 구현)
- 리포트 상태(`POST /reports` · `GET /reports/me`): `NONE` 지금 차트의 리포트를 만든 적이 없다(`GET` 만) · `WRITING` 쓰는 중 · `DONE` 완성 · `FAILED` 만들지 못했다(다시 `POST` 하면 새로 시작한다).
  - `POST`: 없거나 `FAILED` 면 생성을 시작하고 `WRITING`. 이미 `DONE` 이면 다시 만들지 않고 `DONE`. 쓰는 중이면 두 번째 생성을 시작하지 않고 `WRITING`. 뒤에서 일할 자리가 꽉 차 시작하지 못하면 `FAILED`(잠시 뒤 다시 `POST`).
  - 멈춘 생성(쓰는 도중 서버가 꺼진 경우): 생성을 맡은 서버는 살아 있는 동안 그 행에 살아 있다는 표시(`active_at`)를 15초마다 다시 적는다(줄에서 기다리는 것도). `WRITING` 인데 이 표시가 2분 넘게 끊겼으면 멈춘 것으로 본다: `GET` 은 `FAILED` 로 답하고, `POST` 는 다시 시작한다. 자리를 잡은 뒤 흐른 시간으로는 가리지 않는다 — 요청이 몰려 줄에서 오래 기다리는 리포트는 `WRITING` 그대로다.
  - 앱은 `POST` 뒤 `GET /reports/me` 를 몇 초 간격으로 다시 부른다. 실제 생성은 1분 안팎이다. 출생 정보를 고쳐 차트가 달라지면 `GET` 은 `NONE` 이 되고(예전 리포트는 남아 있어 되돌리면 다시 보인다) `POST` 로 새로 만든다.
- 이번 달 `GET /timing/month` (2026-11-20 구현, 결정 032 · 규칙 · 문구의 원본 docs/drafts/2026-11-20-month-phrases.md — 소유자 "초안 그대로 써"). 시기 탭의 "이번 달". 요청 때마다 계산하고 저장하지 않는다 — 같은 차트 · 같은 달은 언제나 같은 본문이다. 평생 시기 구간(요청당 약 0.26초)만 서버 메모리에 차트 해시마다 10분 기억한다(최대 500개, 가장 오래 안 쓴 것부터 잊음 — 시기 탭이 석 달을 잇달아 부른다).
  - `MonthSky` = `{ month: "YYYY-MM", timeKnown, themes[0~2], days[0~3] }`. `month` 는 계산한 달(앱은 처음 받은 달로 앞뒤 한 달을 정한다).
  - `themes`("이번 달 내내") = `[{ area: PEOPLE | WORK | MONEY | SELF, title, from, to }]` — 그 사람의 시기 구간(변화 · 기회 · 운명적 만남) 가운데 그달의 날과 10일 이상 겹치는 것을 우선순위(변화 큰 > 변화 작은 > 운명적 만남 > 기회, 같으면 그달과 더 많이 겹치는 것, 그다음 늦게 시작한 것)로 제목이 겹치지 않게 둘까지. `title` 은 시기의 표 제목(`generation.TimingThemes` — 시기 풀이의 구간 이름과 같은 말, 결정 033). `from` · `to` 는 그 구간의 한국 날짜(그달 밖으로 이어질 수 있다).
  - `days`(눈여겨볼 날 = 달력의 점) = `[{ date, category: PEOPLE | WORK | MIND, text, reason }]`, 날짜 순. `text` 는 "~날" 로 끝나는 한 줄, `reason` = `{ when: "DAY", items: [{ kind: "TRANSIT", mover, target, type }] }`(1개).
    - 지나가는 별(`mover`)은 해 · 수성 · 금성 · 화성 · 목성, 내 점(`target`)은 행성 10 + 시각을 알면 `ASC` · `MC`, 각도는 다섯. 그날 한국 정오의 위치로 잰다.
    - 조합마다 그달에서 가장 정확한 날(오차 1° 안)이 후보이고, 세기(각도 × 지나가는 별 × 내 점의 무게 × (1 − 오차 ÷ 2)) 8 이상을 세기 순으로 두 번 훑는다: 사흘 이상 떨어지고 칩(`category`)이 아직 없는 것 → 사흘 간격만 지켜 채운다. 셋까지. 지나가는 해 × 내 해의 합(생일)은 뺀다. 셋이 안 되는 달은 있는 만큼(0개도 오류가 아니다).
    - 칩 · 문구는 문구표(`generation.MonthPhrases`): 칸 = 지나가는 별 × 내 점 × 결(합 · 어울림 · 부딪힘), 칸마다 3~5줄, 번호 = CRC32("날짜|칸") mod 줄 수 — 같은 날 · 같은 조합은 늘 같은 문구, 한 달 안에 같은 글자면 뒤의 것을 다음 번호로.
  - 시각을 모르면 `timeKnown: false` 이고 `ASC` · `MC` 가 대상인 날과 상승점 · 중천 · 하우스 · 버텍스 구간이 없다.
  - 모델을 부르지 않고 생성 한도(결정 022 · 023)에 세지 않는다. 남용은 계정별 분당 `byeolmyeong.rate-limit.month-per-minute`(60)로 막는다(출생 정보가 없어 404 인 요청도 센다).
- 그날의 하늘 `POST /day-sky` (2026-11-20 구현, 결정 034). 시기 탭 달력에서 누른 날의 "그날의 하늘", 날짜 없이(또는 오늘 날짜로) 부르면 홈의 "오늘의 하늘" — 같은 글이다.
  - `DaySky` = `{ date: "YYYY-MM-DD", title, category: PEOPLE | WORK | MIND, flow: { people, work, mind }, reason, practices: [{ title } × 3], createdAt }`. 키는 이 순서. `createdAt` 은 그 글을 만든 때(UTC, 초까지 `YYYY-MM-DDTHH:mm:ssZ`).
  - `reason` = `{ when: "DAY", items: [{ kind: "TRANSIT", mover, target, type }] }`(1개, 결정 031). `mover` 는 해 · 수성 · 금성 · 화성 · 목성, 그날 그 다섯의 통과가 1° 안에 없으면 `MOON`. `target` 은 행성 10 + 시각을 알면 `ASC` · `MC`.
  - 그날이 그달(이번 달 `GET /timing/month`)의 눈여겨볼 날이면 `title` · `category` · `reason` 은 그달 `days[]` 의 그날 줄의 `text` · `category` · `reason` 과 같다(같은 말 — 그달의 문구표 고르기 결과에서 가져온다). 아니면 `reason` 은 그날 한국 정오에 1° 안 다섯 별의 통과 가운데 세기가 가장 큰 것, 없으면 그날의 달이 내 점과 이루는 가장 정확한 각도(`chart.daySkyTransit`), `category` 는 문구표의 칩 표(달은 칩 표에 줄이 없어 `MIND`), `title` 은 모델이 그 근거에 맞춰 쓴 "~날" 한 줄.
  - `flow`(사람 · 일 · 마음 한두 문장)와 `practices`(제목은 "기" 로 끝나는 실천, 결정 037)는 언제나 모델이 쓴다. 글에는 숫자가 없다.
  - 저장 · 한 번만: 소유자 · 차트 해시 · 날짜마다 한 번 만들어 저장하고(`day_skies`), 다시 부르면 그대로 준다(모델 · 생성 한도를 쓰지 않는다). 같은 사람 · 같은 날의 동시 요청은 하나만 만들고 나머지는 그 결과(실패 · 429 포함)를 기다린다. 출생 정보를 고치면 옛 차트의 행은 지워지고(결정 041) 새 차트로 새로 만든다.
  - 시각을 모르면 `ASC` · `MC` 가 대상인 근거가 없고, 지시문에 `report-time-unknown.md` 를 붙인다.
  - 아침에 미리 만들기 · 알림은 이 경로에 없다(푸시 계획). 서버 안에서 만들 때는 `sky.DaySkyService.daySky(ownerId, date, countQuota = false)` — 같은 생성 고리이고 생성 한도만 세지 않는다.
- 나 탭 풀이 (2026-11-20 구현, 결정 029 · 030 · 031 · 038). 앱 나 탭의 "자세한 풀이 받기". 옛 심층 리포트(`POST /reports`)와 따로 저장하고 따로 받는다(옛 `reports` 행은 읽지 않는다).
  - `MeReport` = `{ areas: [MeArea × 5] }` — 정확히 이 다섯 영역이 이 순서: `PERSONALITY`(성격) · `LOVE`(연애) · `RELATIONSHIP`(관계) · `WORK`(일) · `EMOTION`(감정).
  - `MeArea` = `{ area, title, keywords[3], stars[Star 1~3], paragraphs[{ title, body, reason: Reason }], glance?, practices?, reason?, closer?, workStyle?, shaken? }`. 키는 이 순서로 오고, 그 영역의 칸이 아니면 키가 없다(null 을 보내지 않는다).
    - `paragraphs`: `LOVE` 말고 정확히 4개(본문은 두 문장 안팎). `LOVE` 는 `[]`. 문단의 `reason.when` 은 언제나 `BIRTH`, `items` 는 1~2개.
    - `keywords`: 정확히 3개, 겹치지 않는다. `stars`: "이 풀이에 쓰인 별" — 그 영역 문단들의 `reason`(연애는 영역의 `reason`)에서 서버가 겹치지 않게 앞에서부터 1~3개.
    - `PERSONALITY` → `glance` = `{ shining[2], tiring[2] }`(빛나는 순간 · 지치는 순간, 짧은 구절)
    - `LOVE` → `practices` = `[{ title, body } × 3]`(`title` 은 "기" 로 끝나는 실천 한 줄, 결정 037) + `reason: Reason`(세 실천의 이유, `when: BIRTH`)
    - `RELATIONSHIP` → `closer` = `{ first, close, longtime }`(처음 만날 때 · 친해지면 · 오래된 사이, 짧은 한 줄)
    - `WORK` → `workStyle` = `{ together, flexible, fast }` — 각각 정수 10~90, 0 쪽 = 혼자서 · 계획대로 · 꼼꼼하게, 100 쪽 = 함께 · 그때그때 · 빠르게. 서버가 차트로 계산한 값(`chart.computeWorkStyle`, 결정 038 · 공식 docs/drafts/2026-11-20-work-style-formula.md)이고 풀이를 만들 때 함께 저장한다(읽을 때 다시 계산하지 않는다). 모델은 쓰지 않고, 글에는 숫자가 없다.
    - `EMOTION` → `shaken` = `{ when, comfort }`(흔들릴 때 · 다시 편해지는 곳)
  - `Star` = `{ body?: Body | Angle, sign?, house?, count? }` — 놓인 자리는 `body` + `sign`, 하우스 모임은 `house` + `count`(천체 수), 각도 · 다스리는 별은 앞쪽 `body` 하나. 값이 없는 키는 보내지 않는다(결정 031 의 6).
  - 시각을 모르면 `house` 키 · `CLUSTER` · `ASC` · `MC` 가 어디에도 나오지 않는다. `workStyle` 은 시각을 모르는 공식(하우스 · 상승점 · 중천을 뺀다)으로 계산해 시각을 알 때와 값이 다를 수 있다.
  - 상태와 생성은 위 "리포트 상태" 와 같은 규칙이다: `NONE` · `WRITING` · `DONE` · `FAILED`. `POST` 는 없거나 `FAILED` 면 시작하고(`WRITING`), `DONE` · `WRITING` 이면 그 상태를 그대로 주며, 일꾼 자리가 꽉 차 시작하지 못하면 `FAILED`. 살아 있다는 표시(`active_at`)가 2분 넘게 끊긴 `WRITING` 은 멈춘 것으로 보고 `GET` 은 `FAILED`, `POST` 는 다시 시작한다. 출생 정보를 고쳐 차트가 달라지면 `GET` 은 `NONE`(예전 풀이는 남아 있어 되돌리면 다시 보인다).
  - 새로 시작할 때만 "받을 수 있나"(결정 029 의 4)를 묻고 — 거절이면 402 `PURCHASE_REQUIRED` · 묻지 못하면 503 `PURCHASE_CHECK_FAILED` 이고 행을 만들지 않는다 — 위 "자세한 풀이 상품" — 자리를 잡은 뒤 생성 한도를 한 번 센다(영역이 다섯이어도, 다시 생성해도 한 번). 실제 생성은 1분 안팎이다. 앱은 `POST` 뒤 `GET /me-report` 를 몇 초 간격으로 다시 부른다.
  - 완성되면 `report.MeReportDone(ownerId)` 이벤트를 낸다(알림함 — 계획 S⑤ 가 듣는다). 이벤트에 글 · 출생 정보는 없다.
- 시기 풀이 `POST` · `GET /timing-report` (2026-11-20 구현, 결정 029 · 033). 시기 탭의 "자세한 풀이 받기". 옛 리포트의 시기와 따로 저장하고 따로 받는다(나 탭 풀이와도 따로).
  - `TimingReport` = 아래 "시기 `Report.timing`" 과 같은 모양(`{ timeKnown, milestones[4], intervals[], current }`, 날짜 · 마디 · 구간 · 지금의 규칙도 같다)에 칸이 더해진 것이다:
    - 구간의 `title` 은 언제나 시기의 표 제목(`generation.TimingThemes` — 이번 달 내내와 같은 말, 결정 033 의 3). 모델은 구간 제목을 쓰지 않는다.
    - 모델이 쓴 구간(풀이를 만든 날에 걸쳐 있거나 그날부터 10년 안에 시작하는 것, 30개까지)에만: `body`(설명, 모델) · `summary`(한 줄 — 시기 카드 · 지금 지나는 중) · `detail` = `{ changes, howTo, after }`(시기 자세히 01 무엇이 달라지나요 · 02 이렇게 지내 보세요 · 03 이 시기가 지나면) · `practices` = `[{ title } × 3]`(제목은 "기" 로 끝나는 실천, 결정 037) · `reason` = `{ when: "NOW", items: [{ kind: "TRANSIT", mover, target?, type?, house? }] }`(그 구간을 만든 지나가는 별 · 대상(`SUN` · `MOON` · `VENUS` · `ASC` · `MC` · `VERTEX`) · 각도, 하우스 통과면 `house` 5 · 7 · 11 — 서버가 계산값에서 만든다, 결정 031). 키 순서는 `id, kind, from, to, area, size?, title, body, summary, detail, practices, reason`.
    - 나머지 구간은 표의 제목 · 본문뿐이고 `summary` · `detail` · `practices` · `reason` 키가 없다.
    - 마디 4개는 지금처럼 `title` · `body` 를 모델이 쓴다(`year` · `age` · 날짜는 서버 값).
    - `current.mainId?` — 대표 구간의 `id`: 오늘(한국 날짜) 걸친 구간(`current.intervals`) 가운데 변화 큰 > 변화 작은 > 운명적 만남 > 기회, 같으면 늦게 시작한 것. 걸친 구간이 없으면 키가 없다. `current` 처럼 저장하지 않고 `GET` 할 때마다 정한다.
  - 시각을 모르면 `timeKnown: false` 이고 상승점 · 중천 · 하우스 · 버텍스가 대상인 구간이 없다(`FATED` 0개 · `MONEY` 없음) — 그래서 `reason` 에도 `ASC` · `MC` · `VERTEX` · `house` 가 나오지 않는다.
  - 상태와 생성은 "나 탭 풀이" 와 같은 규칙 · 같은 고리다(`DetailReportService`): `NONE` · `WRITING` · `DONE` · `FAILED`, 없거나 `FAILED` 면 시작, 일꾼 자리가 꽉 차면 `FAILED`, 살아 있다는 표시가 2분 끊기면 멈춤, 새로 시작할 때만 받을 수 있나 → 자리 → 생성 한도 한 번, 출생 정보를 고치면 `NONE`. 실제 생성은 1분 안팎(마디 · 구간 계산 약 0.3초 + 묶음 약 넷을 한꺼번에). 완성되면 `report.TimingReportDone(ownerId)` 이벤트(알림함 — 계획 S⑤).
  - 지금 나의 시기의 실천은 대표 구간의 `practices` 가 맡는다 — 앱은 `POST /directions {topic: FUTURE}` 를 더 부르지 않는다(계획 S⑦ 에서 뺀다, 결정 033 의 8).
- 시기 `Report.timing` = `{ timeKnown, milestones[4], intervals[], current }` (2026-11-11 구현, 결정 008). 규칙의 원본은 앱 저장소 release-1.0.0 F4.
  - 날짜는 모두 한국 달력의 `YYYY-MM-DD` 글자다(시각·시간대 없음). 글자를 그대로 비교해 거르고 정렬할 수 있다. 앱은 천체 계산을 하지 않는다: "앞뒤 10년 먼저" 는 `to >= 오늘-10년 && from <= 오늘+10년` 으로 거른다.
  - `timeKnown`(boolean): 출생 시각을 아는가. `false` 면 상승점·중천·하우스·버텍스가 대상인 구간이 계산되지 않아 `FATED` 구간이 0개이고 `MONEY` 영역이 나오지 않는다. 앱은 이 값으로 "출생 시각을 몰라 일부 구간을 뺐다" 는 안내를 띄운다. 안내 문구와 "시기는 행성의 주기를 계산한 것이고…" 문구는 앱이 갖는다.
  - `milestones` = 언제나 4개, 이 순서(시간 순서): `[{ kind: SATURN_SQUARE | SATURN_RETURN | URANUS_OPPOSITION | SECOND_SATURN_RETURN, date, year, age, from, to, size: BIG | SMALL, title, body }]`. `date` 는 처음 정확히 겹치는 날, `year`(정수)는 그 해, `age`(정수)는 그날의 만 나이. `from`~`to` 는 허용 오차 1° 안에 있는 기간(역행으로 여러 번 지나가면 처음 들어온 날부터 마지막으로 나간 날까지, 몇 주~열 달쯤)이고 `from <= date <= to`. `size` 는 토성 사각만 `SMALL`. null 인 필드는 없다.
  - `intervals` = `[{ id, kind: CHANGE | OPPORTUNITY | FATED, from, to, area: PEOPLE | WORK | MONEY | SELF, size?: BIG | SMALL, title, body }]`. `from` 순서(같으면 `to` 순서)로 온다. 태어난 뒤부터 만 80세 전까지 시작하는 구간 전부다(시각을 알면 약 190개, 모르면 약 100개). 서로 겹칠 수 있다. `id` 는 이 리포트 안에서만 겹치지 않는 글자(`i1`…)다. `size` 는 `CHANGE` 일 때만 있고 다른 종류에는 키가 없다. `from <= to`(하루짜리 구간은 같을 수 있다). 마지막 구간의 `to` 는 만 80세를 조금 넘을 수 있다.
    - `kind`: `CHANGE` 변화 구간(토성·천왕성·해왕성·명왕성 × 태양·달·상승점, 합·사분·충) · `OPPORTUNITY` 기회 구간(목성 × 태양·달·상승점·중천·금성 합·삼분, 목성의 5·7·11하우스 통과) · `FATED` 운명적 만남 구간(목성·토성·천왕성·해왕성·명왕성 × 버텍스 합, 시각을 알 때만).
    - `area`: `PEOPLE` 사람·인연 · `WORK` 일 · `MONEY` 돈 · `SELF` 나 자신. `FATED` 는 언제나 `PEOPLE`.
    - `size`: `BIG` 큰 변화(천왕성·해왕성·명왕성이 태양·달과 합·충) · `SMALL` 작은 변화(그 밖).
    - 무엇이 무엇에 닿아 생긴 구간인지(행성·각도)는 내려보내지 않는다.
  - `title`·`body`: "이 시기의 주제". 마디와 구간 모두 언제나 있다(빈 글 없음). 마디 4개와, 리포트를 만든 날에 걸쳐 있거나 그날부터 10년 안에 시작하는 구간(30개까지)은 모델이 쓴 글이고 나머지는 서버의 정해진 글이다. 앱은 둘을 구분하지 않는다. 지시문은 글에 연도·나이·점성술 용어·사건의 단정을 쓰지 못하게 하고, 서버가 걸러 내는 것은 그 줄의 기간 밖의 해다(서버의 정해진 글에는 숫자와 용어가 없다).
  - `current`(지금 나의 구간, 언제나 있다) = `{ date, intervals[], milestone?, nextInterval?, nextMilestone?, nearestMilestone }`. 저장하지 않고 `GET` 할 때마다 그날(한국 날짜)로 만든다. `date` 는 서버의 오늘. `intervals` 는 오늘에 걸쳐 있는 구간(`from <= date <= to`, 위 `intervals` 의 원소와 같은 모양·같은 `id`, 없으면 빈 배열)이고 "언제까지" 는 각 구간의 `to` 다. `milestone` 은 오늘이 그 `from`~`to` 안인 마디(없으면 키 없음). `nextInterval` 은 오늘 뒤에 처음 시작하는 구간, `nextMilestone` 은 `date` 가 오늘 뒤인 첫 마디(둘 다 없으면 키 없음 — 두 번째 토성 귀환 뒤에는 `nextMilestone` 이 없다). `nearestMilestone` 은 오늘에서 `date` 가 가장 가까운 마디의 `kind`(지났든 아니든, 언제나 있다 — 화면에서 강조할 마디).
  - 이 변경 전에 만든 리포트(`timing` 없음)는 만든 적 없는 것으로 본다: `GET` 은 `NONE`, `POST` 는 새로 만들어 덮어쓴다.
- 문의하기 `POST /inquiries` (2026-11-12 구현, 결정 013) · 내 문의 `GET /inquiries` · `GET /inquiries/{id}` (2026-11-20, 결정 039). 관리자는 "관리자 API" 의 `/admin/inquiries` 로 읽고 답한다(결정 014 · 039).
  - 요청(새 앱): `{ category, body }`. `category` 는 `BUG`(오류)·`IDEA`(제안)·`ACCOUNT`(계정)·`ETC`(기타) 중 하나. `body` 는 앞뒤 공백을 뗀 10~1000자(글자 수는 유니코드 코드 포인트로 센다: 한글 한 글자가 1, 이모지 한 개도 1 — JavaScript 의 `.length` 가 아니라 `[...body].length`). 가운데의 줄바꿈은 그대로 둔다. 이메일은 받지 않는다. 그 밖의 키는 읽지 않는다.
  - **옛 앱**: 본문에 `replyEmail` 키가 있으면(값 · 타입과 상관없이 — 옛 앱은 언제나 보낸다) 옛 규칙으로 받는다: 종류는 위 넷 + 옛 `READING` · `SYNASTRY` · `OTHER`, 글은 10~2000자. `replyEmail` 은 읽지도 저장하지도 않는다. 계획 S⑦ 에서 이 갈래를 닫는다(결정 039 의 2).
  - 앱이 보낸 `X-App-Platform`(`ios` · `android`, 대소문자 무시)과 `X-App-Version`(아래 버전 규칙)을 그 문의에 적어 둔다 — 관리자 화면의 작성자 칸. 없거나 읽을 수 없으면 비운다(웹).
  - 응답: 201 `{ id }`(UUID). 메일은 보내지 않는다.
  - 오류: 400 `INVALID_REQUEST`(본문이 JSON 객체가 아니거나, `category`·`body` 가 글자가 아닌 타입이다 — 숫자·배열·객체·불리언)·`INVALID_CATEGORY`(없거나 `null` 이거나 목록에 없다)·`INVALID_BODY`(없거나 `null` 이거나 길이가 틀리다) · 401 `UNAUTHORIZED` · 429 `RATE_LIMITED`. 여러 개가 틀리면 이 순서로 처음 걸린 것 하나를 준다. 오류 응답에 입력값은 없다.
  - 429: 한 계정이 한 시간(서버 시계의 정시부터 정시까지)에 5건까지 저장할 수 있고 여섯 번째부터 429 다. 검증에서 걸린 요청(400)은 세지 않는다. 같은 계정의 다른 기기도 같이 센다. 읽기(`GET`)는 세지 않는다.
  - `GET /inquiries`: 내 문의 전부(만든 때가 늦은 것부터, 같으면 id 가 큰 것부터)의 배열. 없으면 `[]`. `GET /inquiries/{id}`: 한 건 — 남의 것 · 없는 것 · UUID 가 아닌 값은 404 `NOT_FOUND`.
  - `InquiryItem = { id, category, body, status: OPEN | ANSWERED, createdAt, answer?: { body, answeredAt }, replyEmail? }`. 값이 없는 키는 보내지 않는다.
    - `status`: 답이 있으면 `ANSWERED`, 없으면 `OPEN`. 관리자의 처리 단계(`NEW` · `IN_PROGRESS` · `DONE`)는 보이지 않는다 — 답한 뒤 관리자가 단계를 바꿔도 `ANSWERED` 다.
    - `category`: 저장된 그대로다. 옛 문의는 옛 값(`READING` · `SYNASTRY` · `OTHER`)일 수 있다 — 앱은 모르는 종류를 "기타" 로 보인다.
    - `answer`: 관리자의 답. `body` 는 1~2000자의 꾸밈 없는 글자(줄바꿈이 있을 수 있다), `answeredAt` 은 처음 답한 때(답을 고쳐도 그대로). 답은 지워지지 않는다.
    - `replyEmail`: 옛 앱이 남긴 답장 이메일 — 그 칸이 있는 옛 문의에만 온다(앱이 "이메일로 보냈어요" 를 고른다, 앱 결정 058).
    - 관리자 메모 · 임시 저장 · 답한 관리자 · 앱 버전은 없다.
  - 답이 처음 저장되면 서버가 이벤트를 내고 알림함(계획 S⑤ — `INQUIRY_ANSWERED { inquiryId }`)과 푸시(S⑩)가 그것을 듣는다. 알림 줄은 그 묶음이 생긴 뒤에 나온다. 답을 고치면 다시 알리지 않는다.
- 자주 묻는 질문 `GET /faqs` (2026-11-12 구현, 결정 014): 세션 없이 읽는다(로그인 전 화면에서도). 관리자가 공개한 것만, 관리자가 정한 순서대로 온다. `id` 는 UUID, `question` 은 1~200자, `answer` 는 1~2000자의 꾸밈 없는 글자(줄바꿈이 들어 있을 수 있다. HTML·마크다운이 아니다). 아직 글이 없으면 `[]` 다(오류가 아니다). 응답 헤더 `Cache-Control: public, max-age=300`. 주소별 분당 120번을 넘으면 429 `RATE_LIMITED`. 같은 경로의 다른 메서드와 `/faqs/` 아래 경로는 없다. 글을 쓰는 곳은 "관리자 API" 의 `/admin/faqs` 다.
- 앱 설정과 강제 업데이트·점검 (2026-11-12 구현, 결정 016·017):
  - `GET /app-config`: 세션 없이 읽는다(로그인 전에도, 426 을 받은 뒤에도). `{ "ios": PlatformConfig, "android": PlatformConfig, "maintenance": { "active": boolean, "message": string | null } }`, `PlatformConfig = { "minVersion": string | null, "latestVersion": string | null, "storeUrl": string | null, "message": string | null, "blockNow": boolean }`. 값이 없는 키도 `null` 로 온다(이 응답은 키를 빼지 않는다). 처음에는 두 플랫폼 모두 `{ null, null, null, null, false }` 이고 `maintenance` 는 `{ "active": false, "message": null }` 다. 응답 헤더 `Cache-Control: public, max-age=60`. 주소별 분당 120번을 넘으면 429 `RATE_LIMITED`. 같은 경로의 다른 메서드는 없다.
    - `minVersion`: 이 플랫폼에서 써야 하는 가장 낮은 버전(아래 버전 규칙의 글자) 또는 `null`(정한 것이 없다). `latestVersion`: 지금 스토어의 최신 버전(같은 규칙) 또는 `null` — 앱이 자기 버전이 이보다 낮고 `minVersion` 이상이면 업데이트를 한 번 권한다(결정 040 · 앱 결정 059). 서버는 이 값으로 막지 않는다. `minVersion` 이 있으면 그보다 낮지 않다. `storeUrl`: 업데이트하러 갈 `https://` 주소 또는 `null`. `message`: 안내에 보여 줄 글(200자 이하, 꾸밈 없는 글자) 또는 `null` — 없으면 앱의 기본 글을 쓴다. `blockNow`: `true` 면 서버가 아래 426 으로 실제로 막는 중이다. `false` 인데 `minVersion` 이 자기 버전보다 높으면 앱은 권하기만 한다(막지 않는다).
  - **점검 503 `{ "error": "MAINTENANCE" }`** (결정 017): `maintenance.active` 가 `true` 인 동안 아래 세 곳을 뺀 **모든 경로**(없는 경로 포함, 메서드 무관)가 이 답이다. **웹에도 걸린다**(버전 헤더와 상관없다). 강제 업데이트(426)와 세션 검사(401)보다 먼저다 — 점검 중에는 옛 앱도, 토큰이 없는 요청도 503 을 받는다. 막힌 요청은 아무 일도 하지 않는다. 앱·웹은 어느 요청에서든 503 `MAINTENANCE` 를 받으면 `GET /app-config` 를 읽어 점검 화면(`maintenance.message` — 200자 이하의 꾸밈 없는 글자 또는 `null`, 없으면 기본 글)을 띄우고, `active` 가 `false` 로 돌아오면 푼다.
    - 막지 않는 곳: `GET /app-config` · `GET /health` · `/admin/**`. 공유 카드의 공개 조회(`GET /shared/{shareId}`)도 점검 중에는 503 이다.
    - 503 에도 CORS 허용 헤더가 붙는다. 브라우저의 사전 요청(OPTIONS)은 막지 않는다. `error` 가 `MAINTENANCE` 가 아닌 503 은 이 점검이 아니다.
  - **앱이 보내는 헤더**: 앱(iOS·Android)은 **모든 요청**에 `X-App-Platform: ios` 또는 `android`, `X-App-Version: <버전>` 을 보낸다. 웹 빌드는 둘 다 보내지 않는다(CORS 허용 헤더에 없어 브라우저에서 보내면 사전 요청이 거절된다).
  - **버전 규칙**: 점으로 나눈 1~3개의 0 이상 정수 — `"1"`·`"1.2"`·`"1.2.3"`. 빠진 자리는 0(`"1.2"` = `"1.2.0"`), 숫자로 견준다(`1.10.0` > `1.9.9`), 자리마다 숫자 9개까지. 그 밖(`"1.2.3.4"`·`"1.2.3-beta"`·`"v1.2"`·`"1.2.3+45"`)은 서버가 읽지 못한다 — 앱은 빌드 번호나 꼬리표를 붙이지 않은 값을 보낸다.
  - **426 `{ "error": "UPDATE_REQUIRED" }`**: 그 플랫폼의 `blockNow` 가 `true` 이고 `minVersion` 이 있고 요청의 `X-App-Version` 이 그보다 **낮으면**, 아래 세 곳을 뺀 **모든 경로**(없는 경로 포함, 메서드 무관)가 이 답이다. 세션 검사보다 먼저라 토큰이 없거나 틀려도 401 이 아니라 426 이다. 막힌 요청은 아무 일도 하지 않는다. 앱은 어느 요청에서든 426 을 받으면 `GET /app-config` 를 읽어 업데이트 화면(`message`·`storeUrl`)을 띄운다.
    - 막지 않는 곳: `GET /app-config` · `GET /health` · `/admin/**`(관리자 API 는 어떤 경우에도 잠기지 않는다).
    - 막지 않는 요청: 두 헤더 중 하나라도 없다 · `X-App-Platform` 이 `ios`·`android` 가 아니다(대소문자는 가리지 않는다) · `X-App-Version` 을 읽을 수 없다 · 버전이 `minVersion` 과 같거나 높다. 모르면 통과시킨다 — 이 장치는 보안이 아니라 정상적인 옛 앱에게 알리는 것이다.
    - 같은 426 이 자세한 풀이의 새 받기(`POST /me-report` · `POST /timing-report`)에서도 온다 — 결제 시작 뒤 1.1.0 미만 앱(위 "자세한 풀이 상품", 결정 056 의 5). 이쪽은 `blockNow` · `minVersion` 과 상관없고 그 요청만 막는다.
    - 426 에도 CORS 허용 헤더가 붙는다. 브라우저의 사전 요청(OPTIONS)은 막지 않는다.
  - 관리자가 값을 고치면 503·426 은 그 서버에서 바로, 서버가 여러 대면 길어야 5초(`byeolmyeong.app-config.cache-seconds`) 안에 적용된다. `GET /app-config` 의 응답은 60초 캐시될 수 있다. 값을 쓰는 곳은 "관리자 API" 의 `/admin/app-config` 다.
- 공유 (2026-11-12 구현, 결정 012. 규칙의 원본은 앱 저장소 release-1.0.0 F6)
  - `ShareCard` 는 `kind` 로 가리는 여덟 모양 가운데 하나다. 아래 적힌 키가 전부이고, 값이 없는 키는 보내지 않는다. 출생 정보·근거(`basis`)·본문·소유자·궁합 id 는 어느 카드에도 없다. 날짜는 그날의 하늘 카드의 그 날만 있다.
    - `{ kind: "READING", headline, keywords[3] }` — 요약 해석의 한 줄 총평과, 앞의 세 문단 제목.
    - `{ kind: "SCORE", names[2], score, scoreLabel?, headline }` — `names` 는 `[공유하는 사람, 상대]` 순서. `score`·`scoreLabel`·`headline` 은 `Synastry` 의 것과 같다(`scoreLabel` 은 60점 미만일 때만).
    - `{ kind: "RANKING", relation: LOVE | FRIEND | WORK | FAMILY, entries[1~3 × { name, score }] }` — 그 관계 유형의 랭킹(`GET /synastry/ranking` 과 같은 순서)에서 앞의 3명까지. `name` 은 기본이 이니셜이고, 공유할 때 `showNames: true` 였으면 적은 이름 그대로다.
    - `{ kind: "NEXT_TURN", year, season: SPRING | SUMMER | AUTUMN | WINTER, theme }` — 리포트 시기의 `current.nextMilestone`(오늘 뒤의 첫 마디)의 `date` 를 해와 계절로 줄인 것과 그 마디의 `title`. 3~5월 `SPRING`, 6~8월 `SUMMER`, 9~11월 `AUTUMN`, 12·1·2월 `WINTER`. `year`(정수)는 그 날의 달력 해다(2028년 1월도 12월도 2028 · `WINTER`). 앱이 "2027년 봄" 으로 적는다. (옛 카드 — 앱이 새로 만들지 않는다. 만든 것은 계속 열린다, 결정 036 의 5)
    - 공유 v2(결정 036 — 결정 012 의 "날짜 · 차트 요소를 넣지 않는다" 를 이 넷에 한해 대체). `nickname` 은 계정의 닉네임이고 옛 계정이라 없으면 키가 없다.
    - `{ kind: "NICKNAME", nickname?, sunSign, headline }` — 지금 차트의 요약 해석 v2 의 한 줄 별명(`Reading.headline`)과 그 차트의 해 별자리(`ARIES`…`PISCES`).
    - `{ kind: "ME_AREA", nickname?, area, title }` — 지금 차트의 완성된(`DONE`) 나 탭 풀이의 그 영역(`PERSONALITY` · `LOVE` · `RELATIONSHIP` · `WORK` · `EMOTION`). `title` 은 모든 영역에서 그 영역의 한 줄 요약(영역 제목, 결정 036 덧붙임 2026-11-23 — 그 전에 만든 공유는 저장된 카드 그대로).
    - `{ kind: "DAY_SKY", nickname?, date, title }` — 저장된 그날의 하늘(결정 034)의 제목과 그 날(`YYYY-MM-DD`, 한국 날짜).
    - `{ kind: "NEXT_BIG", nickname?, year, age, title }` — 지금 차트의 완성된 시기 풀이의 `current.nextMilestone`(오늘 뒤의 첫 마디)의 해 · 만 나이 · 제목(앱이 "2028년 · 만 29세 무렵" 으로 적는다).
  - `POST /shares` 요청: `kind` 는 필수. `SCORE` 는 `synastryId`(내 궁합의 `id`)와 `myName`(공유하는 사람을 부를 이름, 앞뒤 공백을 뗀 1~20자 — 서버에 내 이름이 없어 매번 받는다). `RANKING` 은 `relation`(필수)과 `showNames`(boolean, 없으면 `false`). `READING`·`NEXT_TURN`·`NICKNAME`·`NEXT_BIG` 은 다른 필드가 없다(지금 프로필의 차트 기준). `ME_AREA` 는 `area`(위 다섯 가운데 하나, 아니거나 없으면 400 `INVALID_AREA`), `DAY_SKY` 는 `date`(`YYYY-MM-DD` 그대로, 아니거나 없으면 400 `INVALID_DATE`). 그 종류가 쓰지 않는 필드는 읽지 않는다.
  - `POST /shares` 응답: 200 `{ shareId, card }`. `card` 는 `GET /shared/{shareId}` 가 주는 것과 같다(앱은 이것으로 이미지를 그리고 `shareId` 로 주소를 만든다). `shareId` 는 무작위 128비트를 URL 용 Base64 로 적은 22자(`A-Z a-z 0-9 - _`)다.
  - 스냅샷: 카드는 공유한 순간의 내용으로 저장되고 그 뒤 글·랭킹·프로필이 바뀌어도 바뀌지 않는다. 같은 내용의 카드를 다시 만들면 새로 만들지 않고 있던 `shareId` 를 준다. 내용이 달라졌으면(랭킹이 바뀜, `showNames`·`myName` 이 다름) 새 `shareId` 다. 예전 공유는 지우기 전까지 열린다. 만료는 없다.
  - `POST /shares` 오류: 400 `INVALID_REQUEST`(본문이 JSON 이 아님, `SCORE` 에 `synastryId` 없음)·`INVALID_KIND`·`INVALID_NAME`(`myName`)·`INVALID_RELATION`·`INVALID_AREA`·`INVALID_DATE` · 401 `UNAUTHORIZED` · 404 `NOT_FOUND`(공유할 것이 없다: 지금 차트의 요약 해석이 없다 / 남의 궁합·없는 궁합·id 모양이 아님 / 그 관계 유형의 궁합이 하나도 없다 / 지금 차트의 완성된(`DONE`) 리포트가 없거나 다음 마디가 없다 / 공유 v2: 지금 차트의 요약 해석 v2 가 없다 · 완성된 나 탭 풀이가 없다 · 그날의 하늘을 만들지 않았다 · 완성된 시기 풀이가 없거나 오늘 뒤의 마디가 없다). 모델을 부르지 않으므로 바로 답한다.
  - `DELETE /shares/{shareId}`: 내 공유를 지운다. 그 뒤 `GET /shared/{shareId}` 는 404 다. 같은 카드를 다시 만들면 새 `shareId` 가 나온다. 계정을 지우면(`DELETE /account`) 그 사람의 공유가 모두 사라진다.
  - `GET /shared/{shareId}`: 세션 없이 열린다. 저장된 `ShareCard` 를 그대로 준다. 없는(지워진) 식별자는 404 `NOT_FOUND`. 주소별 분당 한도를 넘으면 429 `RATE_LIMITED`. CORS 는 다른 경로와 같다(`ALLOWED_ORIGINS` 에 적힌 앱의 웹 주소만).
  - 이니셜(서버가 만든다): 한글 음절은 첫소리 글자로(지영 → `ㅈㅇ`, 쌍자음은 그대로), 이미 낱자인 한글은 그대로, 그 밖의 글자·숫자는 이어진 덩어리의 첫 글자만 대문자로(`Mike` → `M`, `김 Min` → `ㄱM`), 공백·기호·그림 문자는 뺀다. 남는 것이 없으면 `○`.

## 관리자 API (관리 화면 저장소 `byeolmyeong-admin` 과 맞춘다. 바꾸면 이 절을 같은 커밋에서 고친다. 결정 014·015·016·017·018·039·040·043·056)
관리 화면(웹)이 부르는 API 다. 앱은 부르지 않는다. 경로는 모두 `/admin/` 아래다.

### 공통
- 요청·응답 본문은 JSON(`Content-Type: application/json`, UTF-8). 본문이 없는 응답(204)은 빈 본문이다.
- 인증: `POST /admin/auth/login` 말고는 모두 요청 헤더 `Authorization: Bearer <token>` 이 필요하다. `token` 은 로그인 응답의 값이다. 헤더가 없거나, 토큰이 틀리거나, 세션이 끝났거나(로그인 뒤 12시간), 로그아웃했거나, 그 관리자가 꺼졌으면 **401 `{ "error": "UNAUTHORIZED" }`** 다. `/admin/` 아래의 없는 경로도 토큰 없이는 401 이다. 화면은 401 을 받으면 토큰을 버리고 로그인 화면으로 보낸다.
- 관리자 세션은 앱 사용자의 세션과 완전히 따로다: 앱 사용자의 토큰으로 `/admin/**` 를 부르면 401, 관리자 토큰으로 앱의 경로(`/profile` 등)를 부르면 401 이다.
- 세션 수명: 로그인한 때부터 12시간(응답의 `expiresAt`). 쓰는 동안 늘어나지 않고, 갱신 경로는 없다(다시 로그인한다). 한 관리자가 여러 곳에서 동시에 로그인할 수 있다. 서버는 토큰 원문을 저장하지 않는다(해시만). 쿠키는 쓰지 않는다.
- 오류 응답은 언제나 `{ "error": "<코드>" }` 하나다(입력값·설명 글 없음). 코드는 경로마다 아래에 적었다. 공통: 400 `INVALID_REQUEST`(본문이 없거나 JSON 객체가 아니거나, 필드의 타입이 틀리다 — 글자 자리에 숫자·배열·객체·불리언 등) · 401 `UNAUTHORIZED` · 403 `PASSWORD_CHANGE_REQUIRED`(임시 비밀번호로 들어온 관리자 — 아래 "로그인·세션") · 404 `NOT_FOUND`(경로의 id 가 UUID 가 아니거나 그런 것이 없다) · 429 `RATE_LIMITED`.
- 응답의 값이 없는 필드는 키를 빼지 않고 `null` 로 보낸다(앱 쪽 계약과 다르다). 요청에서 읽지 않는 키는 무시한다.
- 시각은 모두 UTC 의 `YYYY-MM-DDTHH:mm:ssZ` 글자다(예 `2026-11-12T04:05:06Z`). id 는 UUID 글자다.
- 글자 수는 유니코드 코드 포인트로 센다(한글 한 글자가 1, 이모지 한 개도 1 — JavaScript 의 `.length` 가 아니라 `[...text].length`).

### 경로
| 메서드·경로 | 요청 | 응답 |
|---|---|---|
| `POST /admin/auth/login` (인증 없음) | `{ loginId, password }` | 200 `{ token, expiresAt, admin: Admin }` |
| `POST /admin/auth/logout` | 없음 | 204 |
| `GET /admin/me` | 없음 | 200 `Admin` |
| `PUT /admin/me/password` | `{ currentPassword, newPassword }` | 204 |
| `GET /admin/admins` | 없음 | 200 `[AdminAccount]` 먼저 만든 관리자부터 |
| `POST /admin/admins` | `{ loginId }` | 201 `{ admin: AdminAccount, temporaryPassword }` |
| `PATCH /admin/admins/{id}` | `{ active }` | 200 `AdminAccount` |
| `POST /admin/admins/{id}/password-reset` | 없음 | 200 `{ temporaryPassword }` |
| `PUT /admin/admins/{id}/password`(화면은 쓰지 않는다 — S⑦ 정리 때 지운다) | `{ password }` | 204 |
| `GET /admin/inquiries?status=&category=&q=&page=&size=` | 쿼리(모두 선택) | 200 `{ items: [Inquiry], page, size, total }` 새것부터 |
| `GET /admin/inquiries/{id}` | 없음 | 200 `Inquiry` |
| `PATCH /admin/inquiries/{id}` | `{ status?, memo?, answer?, answerDraft? }` | 200 `Inquiry` |
| `GET /admin/users?q=&status=&provider=&joinedFrom=&joinedTo=&page=&size=` | 쿼리(모두 선택) | 200 `{ items: [UserRow], page, size, total }` 가입이 늦은 것부터 |
| `GET /admin/users/{id}` | 없음 | 200 `UserDetail` |
| `POST /admin/users/{id}/suspend` | `{ reason }` | 200 `UserDetail` |
| `POST /admin/users/{id}/unsuspend` | `{ reason }` | 200 `UserDetail` |
| `DELETE /admin/users/{id}` | `{ reason, confirmNickname }` | 204 |
| `POST /admin/users/{id}/regenerate` | `{ kind }` | 202(본문 없음) |
| `GET /admin/stats` | 없음 | 200 `Stats` |
| `GET /admin/stats/active?range=today\|week\|month` | 쿼리 `range`(꼭 있어야 한다) | 200 `ActiveChart` |
| `GET /admin/faqs` | 없음 | 200 `[Faq]` 보이는 순서대로(공개하지 않은 것 포함) |
| `POST /admin/faqs` | `{ question, answer, published? }` | 201 `Faq` |
| `PATCH /admin/faqs/{id}` | `{ question?, answer?, published? }` | 200 `Faq` |
| `DELETE /admin/faqs/{id}` | 없음 | 204 |
| `PUT /admin/faqs/order` | `{ ids: [id, …] }` | 200 `[Faq]` 새 순서의 전체 목록 |
| `GET /admin/audit-logs?actorType=&action=&from=&to=&actorRef=&q=&page=&size=` | 쿼리(모두 선택) | 200 `{ items: [AuditLog], page, size, total }` 새것부터 |
| `GET /admin/app-config` | 없음 | 200 `AppConfig`(두 플랫폼과 점검) |
| `PUT /admin/app-config/{platform}` | `{ minVersion, latestVersion, storeUrl, message, blockNow }`(다섯 키 — `latestVersion` 만은 빠지면 그대로) | 200 `PlatformConfig`(바뀐 플랫폼) |
| `PUT /admin/app-config/maintenance` | `{ active, message }`(두 키 모두) | 200 `Maintenance` |
| `GET /admin/push/audience` | 없음 | 200 `{ count }` |
| `GET /admin/push/status` | 없음 | 200 `PushStatus` |
| `GET /admin/push/events?page=&size=` | 쿼리(모두 선택) | 200 `{ items: [PushEvent], page, size, total }` 새것부터 |
| `POST /admin/push/events` | `{ title, body, target }` | 202 `{ id, recipients }` |
| `GET /admin/entitlement-allowlist` | 없음 | 200 `[AllowlistEntry]` 새것부터 |
| `POST /admin/entitlement-allowlist` | `{ email }` | 201 `AllowlistEntry` |
| `DELETE /admin/entitlement-allowlist/{id}` | 없음 | 204 |

### 로그인·세션
- `Admin` = `{ id, loginId, name, mustChangePassword }`. `AdminAccount` = `{ id, loginId, name, active, createdAt, lastLoginAt }` — `lastLoginAt` 은 마지막으로 로그인에 성공한 때 또는 `null`(한 번도 없음). 비밀번호와 그 해시는 어떤 응답에도 없다. 임시 비밀번호는 그것을 만든 응답(추가 · 재발급)에서만 한 번 나간다.
- `POST /admin/auth/login`: `loginId` 는 앞뒤 공백과 대소문자를 가리지 않는다. `password` 는 글자 그대로 본다.
  - 200 `{ token, expiresAt, admin }`. `token` 을 화면이 보관해 이후 요청의 `Authorization: Bearer <token>` 에 넣는다. `expiresAt` 은 이 세션이 끝나는 때다.
  - 401 `INVALID_CREDENTIALS`: 없는 아이디, 틀린 비밀번호, 꺼진 관리자 — 셋을 구분해 주지 않는다. 관리자가 한 명도 없는 서버에서도 이 답이다.
  - 400 `INVALID_REQUEST`: `loginId`·`password` 가 없거나 `null` 이거나 글자가 아니다.
  - 429 `RATE_LIMITED`: 로그인 시도는 10분(서버 시계의 10분 단위 창)에 주소(IP)별 20번까지다. 성공한 로그인도 센다. 넘으면 맞는 비밀번호여도 그 창이 끝날 때까지 429 다. **아이디별 잠금은 없다**(결정 043 의 5) — 한 아이디로 여러 번 틀려도 그 아이디가 막히지 않는다.
  - `admin.mustChangePassword: true` 면 서버가 만든 임시 비밀번호(추가 · 재발급)로 들어온 것이다. 바꾸기 전에는 `PUT /admin/me/password` · `POST /admin/auth/logout` · `GET /admin/me` 말고는 모두(없는 경로도) **403 `PASSWORD_CHANGE_REQUIRED`** 다. 화면은 이때 비밀번호 바꾸기 화면만 연다. `GET /admin/me` 도 같은 값을 준다.
- `POST /admin/auth/logout`: 이 요청의 토큰만 끝낸다(같은 관리자의 다른 세션은 그대로). 204. 그 뒤 그 토큰은 401 이다.
- `PUT /admin/me/password`: 자기 비밀번호를 바꾼다(남의 것은 "관리자 관리" 의 `POST /admin/admins/{id}/password-reset` 으로 임시 비밀번호를 재발급한다). 204. 바꾸면 이 요청의 세션은 그대로이고 **같은 관리자의 다른 세션은 모두 끝난다**.
  - 임시 비밀번호로 들어온 동안은 `currentPassword` 가 그 임시 비밀번호다. 바꾸면 `mustChangePassword` 가 풀려 이 세션으로 모든 경로를 쓸 수 있다.
  - 400 `INVALID_CURRENT_PASSWORD`(지금 비밀번호가 틀리다) · `INVALID_PASSWORD`(새 비밀번호가 규칙에 안 맞다) · `SAME_AS_TEMPORARY`(임시 비밀번호로 들어온 동안 새 비밀번호가 임시 비밀번호와 같다) · `INVALID_REQUEST`(둘 중 하나가 없거나 글자가 아니다) . 아이디별 제한은 없다(지금 비밀번호를 여러 번 틀려도 막히지 않는다 — 결정 043 의 5).
- 비밀번호 규칙: 10자 이상, UTF-8 로 72바이트 이하(영문·숫자·기호만이면 72자, 한글만이면 24자까지). 앞뒤 공백을 떼지 않는다. 그 밖의 조합 규칙은 서버에 없다(화면의 "영문 · 숫자 조합" 체크는 화면 규칙).

### 관리자 관리
- 역할 구분은 없다. 관리자는 누구나 다른 관리자를 만들고 끄고 켤 수 있다. 관리자를 지우거나 이름·아이디를 고치는 경로는 없다. 남의 비밀번호는 임시 비밀번호로 재발급한다(아래 `POST /admin/admins/{id}/password-reset`, 결정 043 의 5).
- `POST /admin/admins`: 본문 `{ loginId }` 만 읽는다(`name` · `password` 는 와도 무시한다). `loginId` 는 앞뒤 공백을 떼고 소문자로 바꾼 뒤 소문자·숫자·`.`·`_`·`-` 3~32자(첫 글자는 소문자나 숫자). 이름(`name`)은 이 아이디 그대로다. 201 `{ admin: AdminAccount, temporaryPassword }` — `admin.active: true`, `admin.lastLoginAt: null`.
  - `temporaryPassword`: 서버가 만든 임시 비밀번호 — 영문 대소문자 · 숫자 12자를 `xxxx-xxxx-xxxx` 로 묶은 14글자. **이 응답에서 한 번만** 보인다(다시 볼 길이 없다 — 화면은 복사 버튼과 함께 보이고 창을 닫으면 사라진다). 받은 관리자가 이것으로 로그인하면 `mustChangePassword: true` 다.
  - 400 `INVALID_LOGIN_ID`(없거나 `null` 이어도 이 코드) · `INVALID_REQUEST`(본문이 객체가 아니거나 `loginId` 가 글자가 아니다) · 409 `LOGIN_ID_TAKEN`(이미 있는 아이디 — 꺼진 관리자의 아이디도 다시 쓸 수 없다).
- `POST /admin/admins/{id}/password-reset`: **다른** 관리자에게 새 임시 비밀번호를 만들어 준다(본문 없음). 200 `{ temporaryPassword }`(위와 같은 꼴, 이 응답에서 한 번만).
  - 그 관리자의 세션이 모두 끝나고(다음 요청부터 401) 예전 비밀번호로는 로그인할 수 없다. 새 임시 비밀번호로 로그인하면 `mustChangePassword: true` 다. 요청한 관리자의 세션은 그대로다.
  - 꺼진 관리자에게도 된다. 켜지지는 않는다.
  - 409 `SELF_NOT_ALLOWED`(`id` 가 자기 자신 — 자기 것은 `PUT /admin/me/password`) · 404 `NOT_FOUND`(그런 관리자가 없거나 `id` 가 UUID 가 아니다). 순서: UUID 가 아니면 404 → 자기 자신이면 409 → 없는 관리자면 404.
  - 감사 기록 `ADMIN_PASSWORD_REISSUED`(누가 누구의 것을 — 비밀번호는 없다).
- `PATCH /admin/admins/{id}`: `{ "active": false }` 로 끄고 `{ "active": true }` 로 켠다. 200 바뀐 `AdminAccount`. 이미 그 상태여도 200 이다.
  - 끄면 그 관리자의 세션이 모두 지워져 그 사람의 다음 요청부터 401 이고, 로그인도 401 `INVALID_CREDENTIALS` 다. 다시 켜면 로그인할 수 있다(예전 토큰은 되살아나지 않는다). 그 관리자가 남긴 처리 기록은 그대로다.
  - 409 `CANNOT_DEACTIVATE_SELF`(자기 자신은 끌 수 없다) · 409 `LAST_ACTIVE_ADMIN`(마지막 남은 활성 관리자는 끌 수 없다 — 두 관리자가 동시에 서로를 끌 때만 나온다) · 400 `INVALID_REQUEST`(`active` 가 없거나 `null` 이거나 불리언이 아니다 — `"false"`·`0` 도 틀리다) · 404 `NOT_FOUND`.
- `PUT /admin/admins/{id}/password`: (결정 018 · 화면은 더 쓰지 않고 S⑦ 정리 때 지운다 — 위 재발급을 쓴다) **다른** 관리자의 비밀번호를 다시 정한다(비밀번호를 잊은 관리자에게 쓴다). 본문 `{ "password": string }`, 규칙은 위 "비밀번호 규칙" 과 같다. 204(본문 없음). 지금 비밀번호는 묻지 않는다.
  - **그 관리자의 세션이 모두 끝난다**(그 사람의 다음 요청부터 401). 예전 비밀번호로는 로그인할 수 없다. 요청한 관리자의 세션은 그대로다.
  - 새 비밀번호는 서버가 알려 주지 않는다 — 정한 관리자가 직접 전하고, 받은 관리자는 로그인해 `PUT /admin/me/password` 로 바꾼다(화면이 그렇게 안내한다).
  - 꺼진 관리자에게도 된다. 켜지지는 않는다(켜려면 `PATCH /admin/admins/{id}`).
  - 409 `USE_OWN_PASSWORD_CHANGE`: `id` 가 자기 자신이다 — 자기 비밀번호는 지금 비밀번호를 확인하는 `PUT /admin/me/password` 로만 바꾼다. 화면은 자기 줄에 이 버튼을 보이지 않는다.
  - 400 `INVALID_PASSWORD`(규칙에 안 맞다. 없거나 `null` 이어도 이 코드) · 400 `INVALID_REQUEST`(본문이 객체가 아니거나 `password` 가 글자가 아니다) · 404 `NOT_FOUND`(그런 관리자가 없거나 `id` 가 UUID 가 아니다). 순서: `id` 가 UUID 가 아니면 404 → 본문이 틀리면 400 → 자기 자신이면 409 → 없는 관리자면 404. 거절된 요청은 아무것도 바꾸지 않는다(세션도 그대로).
  - 감사 기록 `ADMIN_PASSWORD_RESET`(누가 누구의 것을 — 비밀번호는 없다).

### 사용자 (결정 043 의 1 · 044)
- 이메일 원문 · 출생 정보 값(날짜 · 시각 · 장소) · 세션 토큰은 어떤 응답에도 없다. 이메일은 서버가 가린 `emailMasked`(앞 4글자 + `****@도메인`, 없으면 `null`)뿐이다.
- `UserRow` = `{ id, nickname, profileNo, emailMasked, provider, joinedAt, lastSeenAt, reports: { me, timing }, status }`.
  - `id` 는 계정 id(문의의 `owner.userId` 와 같다). `nickname` · `profileNo`(프로필 그림 1~8) 는 옛 계정이면 `null`. `provider` 는 `KAKAO` · `APPLE` · `GOOGLE`(개발 · 테스트 계정은 `DEV` · `TEST`).
  - `lastSeenAt`: 마지막 접속(사용자 세션 요청이 10분에 한 번 적는다 — 결정 043 의 2) 또는 `null`.
  - `reports.me` · `reports.timing`: 나 탭 풀이 · 시기 풀이의 가장 최근 생성 상태 — `NONE`(없음) · `GENERATING`(쓰는 중) · `DONE` · `FAILED`. 쓰는 중인데 살아 있다는 표시가 2분 넘게 끊긴 것은 멈춘 것이라 `FAILED`(앱의 풀이 상태와 같다 — 재생성할 수 있다).
  - `status`: `ACTIVE` · `SUSPENDED`.
- `GET /admin/users`: 가입이 늦은 것부터(같으면 id 가 큰 것부터). 빈 값은 없는 것으로 본다.
  - `q`: 닉네임 일부 또는 이메일 앞부분(대소문자 무시 — 이메일 가운데 글자로는 찾지 않는다). `status`: `ACTIVE` · `SUSPENDED`, 그 밖은 400 `INVALID_STATUS`. `provider`: `KAKAO` · `APPLE` · `GOOGLE`, 그 밖은 400 `INVALID_PROVIDER`.
  - `joinedFrom` · `joinedTo`: 한국 날짜 `YYYY-MM-DD`(끝 날짜 포함). 하나만 와도 된다. 모양이 틀리거나 끝이 시작보다 앞이거나 366일을 넘으면 400 `INVALID_DATE_RANGE`.
  - `page`: 0 부터(기본 0). `size`: 10 · 20 · 50 · 100(기본 20). 그 밖은 400 `INVALID_PAGE`.
- `UserDetail` = `UserRow` 의 칸 모두 + `{ consents: { termsVersion, privacyVersion, pushAt, marketingAt }, birth: { registered, hasTime, hasPlace }, generations: [{ kind, status, at }], inquiries: [{ id, category, status, createdAt }], recent: [{ at, action }], suspension: { at, by: { id, name } | null, reason } | null, todaySky: { on, time }, devices: [{ platform, appVersion, lastSeenAt }] }`.
  - `generations`: `NICKNAME`(별명 — 저장되는 실패가 없어 `DONE` · `NONE` 뿐) · `REPORT_ME` · `REPORT_TIMING` 셋, `at` 은 상태가 바뀐 때 또는 `null`.
  - `inquiries`: 그 사람의 문의 새것부터 20개(`status` 는 관리자 문의의 상태). `recent`: 그 사람이 한 일과 그 사람에게 관리자가 한 일(감사 기록의 `action`) 새것부터 20개.
  - `suspension.reason`: 정지 사유(관리자끼리만 — 앱에는 보이지 않는다). `todaySky`: 오늘의 하늘 알림을 실제로 받는지(푸시 수신 동의 + 직접 끄지 않음) · 받는 한국 시각 `HH:mm`. `devices`: 등록 기기(플랫폼 · 앱 버전 · 마지막 등록 때) — 토큰은 주지 않는다(결정 042).
- `POST /admin/users/{id}/suspend { reason }` · `POST /admin/users/{id}/unsuspend { reason }`: 사유는 앞뒤 공백을 뗀 1~200자(아니면 400 `INVALID_REASON`). 200 바뀐 `UserDetail`. 이미 그 상태면 409 `ALREADY_SUSPENDED` · `NOT_SUSPENDED`. 정지해도 세션은 그대로다 — 그 사람의 다음 요청부터 403 `ACCOUNT_SUSPENDED`(문의 · 계정 보기 · 로그아웃 · 계정 삭제만 열림, 위 "API 계약" 의 세션 줄). 정지 · 해제 사유는 정지 내역(`account_suspensions`)에 남고 감사 기록에는 `USER_SUSPENDED` · `USER_UNSUSPENDED`(대상 `USER` id)만 남는다.
- `DELETE /admin/users/{id} { reason, confirmNickname }`: 사용자가 요청했을 때만 쓴다(사유에 요청 경로 — 확인용이고 저장하지 않는다). `confirmNickname` 이 그 사람의 닉네임과 글자 그대로(앞뒤 공백만 뗌) 같아야 하고 아니면 400 `CONFIRM_MISMATCH`. 닉네임이 없는 계정(가입 중 그만둔 계정 · 옛 계정)은 `confirmNickname` 이 빈 글자(공백만이어도 된다)여야 지운다 — 무언가 적었거나 키가 없거나 `null` 이면 400 `CONFIRM_MISMATCH`. 지우는 일은 앱의 `DELETE /account` 와 같다(카카오 연결 끊기 · 애플 토큰 철회 · 출생 정보 · 생성 내역 · 문의 · 세션 — `admin` 이 이벤트 `web.AccountDeletionRequested` 를 내고 `auth` 가 지운다). 204. 감사 기록은 `USER_DELETED_BY_ADMIN`(대상 `USER` id) 하나 — 사용자가 지운 것(`ACCOUNT_DELETED`)으로 남기지 않는다.
- `POST /admin/users/{id}/regenerate { kind }`: 가장 최근 생성이 실패(`FAILED`)한 것만 그 사람의 지금 차트로 다시 시작한다. `kind` 는 `NICKNAME` · `REPORT_ME` · `REPORT_TIMING`(그 밖이나 없으면 400 `INVALID_KIND`). 별명은 실패가 저장되지 않아(요약 해석은 요청 안에서 만든다) 늘 409 `NOT_FAILED`, 나 탭 풀이 · 시기 풀이도 가장 최근 `detail_reports` 행이 `FAILED` 가 아니면(완성 · 쓰는 중 · 만든 적 없음) 409 `NOT_FAILED`. 202(본문 없음) — 자리만 잡고 글은 뒤에서 쓴다. 결과는 사용자가 다시 눌렀을 때와 같이 간다(알림함 · 상세의 `generations`). 다시 만드는 일은 `admin` 이 이벤트 `admin.GenerationRetryRequested` 를 내고 `report` 가 요청 스레드에서 시작한다 — 그 사람의 생성 한도(결정 023)를 한 번 세고, 넘으면 429 `RATE_LIMITED`, 출생 정보가 지워졌으면 404 `NO_PROFILE`. 감사 기록 `GENERATION_RETRIED`(대상 `USER` id, `detail` 은 `kind`).
- 오류: 404 `NOT_FOUND`(없는 사용자 · id 가 UUID 가 아님) · 400 `INVALID_REQUEST`(본문이 객체가 아니거나 타입이 틀리다).

### 문의
- `Inquiry` = `{ id, category, body, replyEmail, createdAt, status, memo, handledBy, handledAt, ownerRef, owner, answer, answeredAt, answeredBy, answerDraft }`. 목록의 원소와 한 건 조회와 `PATCH` 의 응답이 모두 이 모양이다(목록에도 글 전체가 온다).
  - `category`: 새 앱은 `BUG`(오류)·`IDEA`(제안)·`ACCOUNT`(계정)·`ETC`(기타). 옛 문의 · 옛 앱의 문의는 `READING`(해석)·`SYNASTRY`(궁합)·`OTHER`(기타)일 수 있다(결정 039). 앱 사용자가 고른 값이다.
  - `body`: 문의 글(10~1000자 — 옛 앱 · 옛 문의는 2000자까지, 줄바꿈이 들어 있을 수 있다). 사용자가 쓴 글이므로 화면은 HTML 로 해석하지 않고 글자로만 보여 준다.
  - `replyEmail`: 옛 문의에 남은 답장 이메일 또는 `null`. 2026-11-20(결정 039) 뒤의 문의는 언제나 `null` 이다 — 답은 `answer` 로 앱 안에서 한다. 서버는 메일을 보내지 않는다.
  - `createdAt`: 문의를 보낸 때.
  - `status`: `NEW`(새 문의, 앱이 보내면 언제나 이 값) · `IN_PROGRESS`(처리 중) · `ANSWERED`(답을 저장함 — `answer` 를 저장해야만 된다) · `DONE`(끝남). `NEW` · `IN_PROGRESS` · `DONE` 은 어느 값에서 어느 값으로든 바꿀 수 있다.
  - `memo`: 관리자끼리 보는 메모 또는 `null`. 앱 사용자에게는 보이지 않는다.
  - `handledBy`: `{ id, name }` 또는 `null` — 상태 · 메모 · 답 · 임시 저장을 **마지막으로** 바꾼 관리자(`name` 은 표시 이름). `handledAt` 은 그때 또는 `null`. 아무도 손대지 않은 문의는 둘 다 `null` 이다. 이력은 남지 않는다(마지막 한 번만).
  - `ownerRef`: 보낸 계정을 가리키는 12자(16진수) 글자. 같은 계정이 보낸 문의 · 감사 기록의 사용자 행동과 값이 같아 묶어 볼 수 있다.
  - `owner`: 보낸 사람 `{ userId, nickname, provider, platform, appVersion, joinedAt }` 또는 `null`(계정이 없다 — 지금은 계정을 지우면 문의도 지워지므로 나오지 않는다). `userId` 는 계정 id(결정 043 의 `/admin/users/{id}` 와 같은 id), `nickname` 은 닉네임 또는 `null`(옛 계정), `provider` 는 로그인 수단(`GOOGLE` · `APPLE` · `KAKAO` · 개발용 `DEV`), `platform`(`ios` · `android`) · `appVersion` 은 문의를 보낼 때의 앱 헤더 또는 `null`(웹 · 2026-11-20 전의 문의), `joinedAt` 은 가입한 때. 이메일 · 출생 정보는 어떤 응답에도 없다.
  - `answer`: 사용자에게 보이는 답(1~2000자) 또는 `null`. `answeredAt` · `answeredBy { id, name }` 는 **처음** 답한 때와 관리자(답을 고쳐도 그대로 — 고친 사람은 `handledBy`) 또는 `null`.
  - `answerDraft`: 답의 임시 저장(2000자 이하) 또는 `null`. 앱에는 보이지 않는다. 답을 저장하면 비워진다.
- `GET /admin/inquiries`: 만든 때가 늦은 것부터(같으면 id 가 큰 것부터). 쿼리는 모두 없어도 되고, 빈 값(`status=`)은 없는 것으로 본다. 조건을 여러 개 주면 모두 맞는 것만 온다.
  - `status`: 위 네 값 중 하나. 그 상태만 준다. 그 밖의 값은 400 `INVALID_STATUS`(소문자도 틀리다).
  - `category`: 위 일곱 값 중 하나. 그 종류만 준다. 목록에 없는 값이면 오류가 아니라 빈 목록이다.
  - `q`: 찾기 — 앞뒤 공백을 뗀 값이 문의 글의 일부 **또는** 작성자 닉네임의 일부와 맞는 것(대소문자 무시, `%` · `_` 도 글자 그대로). 공백뿐이면 없는 것으로 본다.
  - `page`: 0 부터 세는 쪽 번호(기본 0). `size`: 한 쪽의 개수 1~100(기본 20). 정수가 아니거나 범위를 벗어나면 400 `INVALID_PAGE`.
  - 응답 `{ items, page, size, total }`: `page`·`size` 는 서버가 쓴 값, `total` 은 조건에 맞는 문의의 전체 수(쪽 수는 `Math.ceil(total / size)`). 마지막 쪽을 넘으면 `items` 가 빈 배열이다.
- `GET /admin/inquiries/{id}`: 404 `NOT_FOUND`(없거나, 계정이 지워져 함께 지워졌거나, id 가 UUID 가 아니다).
- `PATCH /admin/inquiries/{id}`: `status` · `memo` · `answer` · `answerDraft` 가운데 **보낸 키만** 바꾼다(하나는 있어야 한다). 200 바뀐 `Inquiry`. 바꿀 때마다 `handledBy` 가 이 요청의 관리자로, `handledAt` 이 지금으로 바뀐다(값이 예전과 같아도). 관리자는 토큰에서만 얻는다 — 본문의 다른 키(`handledBy`·`body` 등)는 읽지 않는다.
  - `status`: `NEW` · `IN_PROGRESS` · `DONE` 중 하나(`ANSWERED` 는 `answer` 로만 된다). `memo`: 앞뒤 공백을 뗀 2000자 이하의 글. `null` 이거나 빈 글·공백뿐인 글이면 메모를 지운다(`null` 이 된다).
  - `answer`: 앞뒤 공백을 뗀 1~2000자. 저장하면 상태가 `ANSWERED` 가 되고 `answerDraft` 가 비워진다. **처음 답일 때만** `answeredAt` · `answeredBy` 를 적고 앱 사용자에게 알린다(알림함 · 푸시 — 계획 S⑤ · S⑩, 두 관리자가 동시에 처음 답해도 한 번). 고치면 글만 바뀌고 다시 알리지 않는다. 답은 지울 수 없다. 답한 뒤 `status` 를 바꿔도 답은 남고 앱의 상태는 `ANSWERED` 그대로다.
  - `answerDraft`: 앞뒤 공백을 뗀 2000자 이하. `null` 이거나 빈 글·공백뿐이면 지운다. 상태 · 답 · 알림은 그대로다(결정 043 의 8 을 039 가 먼저 만들었다).
  - 400 `INVALID_REQUEST`(본문이 객체가 아니거나, 네 키가 모두 없거나, 타입이 틀리거나, `answer` 를 `status` 나 `answerDraft` 와 함께 보냈다) · `INVALID_STATUS`(`status` 키가 있는데 `null` 이거나 위 세 값이 아니다 — `ANSWERED` 도) · `INVALID_MEMO`(2000자를 넘는다) · `INVALID_ANSWER`(`answer` 가 `null` · 빈 글 · 공백뿐이거나 2000자를 넘는다, `answerDraft` 가 2000자를 넘는다) · 404 `NOT_FOUND`. 거절된 요청은 아무것도 바꾸지 않는다.
  - 예:
    ```
    PATCH /admin/inquiries/7c1d…
    { "answer": "확인했어요. 다음 판에서 고칠게요." }
    → 200 { …, "status": "ANSWERED", "answer": "확인했어요. 다음 판에서 고칠게요.", "answeredAt": "2026-11-20T09:00:00Z",
            "answeredBy": { "id": "…", "name": "root-admin" }, "answerDraft": null, … }
    ```
- 문의는 지울 수 없고 글·종류·이메일은 고칠 수 없다. 앱 사용자가 계정을 지우면 그 사람의 문의는 처리한 것까지 모두 함께 지워진다(목록에서 사라지고 한 건 조회는 404).

### 현황 수치
- `GET /admin/stats` → `Stats`. 부를 때마다 DB 에서 새로 센다(캐시 없음). 수는 0 이상의 정수다(비율 둘 · 시각 하나 · 아직 셀 수 없는 칸의 `null` 말고). 개수뿐이고 사람별 행·출생 정보·이메일·글은 없다.
  ```
  {
    "generatedAt": "2026-11-12T04:05:06Z",
    "accounts":        { "total": 0, "last7Days": 0, "last30Days": 0 },
    "profiles":        { "total": 0 },
    "readings":        { "total": 0 },
    "reports":         { "total": 0, "byStatus": { "DONE": 0, "WRITING": 0, "FAILED": 0 } },
    "synastries":      { "total": 0 },
    "synastryReports": { "total": 0, "byStatus": { "DONE": 0, "WRITING": 0, "FAILED": 0 } },
    "directions":      { "total": 0, "byTopic": { "LOVE": 0 } },
    "inquiries":       { "total": 0, "byStatus": { "NEW": 0, "IN_PROGRESS": 0, "ANSWERED": 0, "DONE": 0 } },
    "users":           { "total": 0, "joinedToday": 0, "joined7d": 0 },
    "nicknames":       { "total": 0 },
    "detailReports":   { "me": 0, "timing": 0, "generating": 0 },
    "failuresToday":   { "nickname": null, "report": 0 },
    "inquiryQueue":    { "waiting": 0, "oldestWaitingAt": null },
    "consents":        { "pushRate": 0.0, "marketingRate": 0.0, "adReachable": 0 },
    "todaySky":        null,
    "pushToday":       null
  }
  ```
  - **현황 v2**(결정 043 의 3): `users` 부터 아래 칸은 더해진 것이다 — 위의 칸(`accounts` ~ `inquiries`)은 그대로 있다(지금 관리 화면이 쓴다). "오늘" 은 **한국 날짜**(Asia/Seoul, 자정 = UTC 15:00)이고 `generatedAt` 과 같은 때로 센다.
    - `users`: 앱 사용자 계정 — `total` 전체(`accounts.total` 과 같다), `joinedToday` 오늘 가입, `joined7d` 오늘 포함 최근 7일(한국 날짜) 가입(`accounts.last7Days` 는 지금부터 168시간이라 다를 수 있다). 사용자 목록 위의 "이번 주 가입" 은 `joined7d` 다.
    - `nicknames.total`: 별명(요약 해석 v2, `readings` 의 `SUMMARY_V2`)이 있는 계정 수. 옛 `SUMMARY` 만 있는 계정은 세지 않는다.
    - `detailReports`: 자세한 풀이(`detail_reports`) — `me` 완성된 나 탭 풀이 · `timing` 완성된 시기 풀이(`DONE`) · `generating` 쓰는 중(`WRITING`, 두 종류 합). **결정 043 은 이 칸을 `reports` 라 불렀는데, 이미 있는 옛 리포트의 `reports` 칸과 겹쳐 이름을 `detailReports` 로 바꿨다.** 서버가 꺼져 멈춘 생성도 `WRITING` 으로 센다(위 리포트와 같다).
    - `failuresToday`: 오늘의 생성 실패 — `report` 는 오늘 `FAILED` 로 바뀐 자세한 풀이 수(지금도 `FAILED` 인 것, 다시 요청해 쓰는 중 · 완성이 되면 빠진다). `nickname` 은 별명 생성의 실패가 저장되지 않아 **셀 수 없으므로 언제나 `null`** 이다(0 이 아니다 — 감사 기록의 `READING_GENERATED` `FAILURE` 로 본다).
    - `inquiryQueue`: 답을 기다리는 문의 — 답이 없고(`answer` 없음) 상태가 `DONE` 이 아닌 것의 수 `waiting` 과 그 가운데 가장 오래된 것의 만든 때 `oldestWaitingAt`(UTC 글자, 없으면 `null`). **결정 043 은 이 칸을 `inquiries` 라 불렀는데, 이미 있는 `inquiries` 칸과 겹쳐 이름을 `inquiryQueue` 로 바꿨다.**
    - `consents`: `pushRate` · `marketingRate` 는 전체 계정 가운데 푸시 · 마케팅 수신에 동의한 계정의 비율(0~1, 소수 셋째 자리 반올림 — 예 `0.667`, 계정이 없으면 `0.0`, JSON 실수). `adReachable` 은 둘 다 동의했고 정지되지 않았고 기기를 등록한 계정 수(광고 푸시의 대상 수 · `GET /admin/push/audience` 와 같다 — 결정 043 의 6).
    - `todaySky` · `pushToday`: 아직 **언제나 `null`** 이다. 그날의 하늘(`day_skies`) · 아침 알림 줄(`today_sky_pushes`)은 생겼지만 푸시의 성공 · 실패를 날마다 세는 기록이 없어, 결정 043 의 모양(`{ today, onDemand, prebuilt }` · `{ sent, failed }`)은 발송 기록을 남기는 다음 묶음에서 채운다. 화면은 `null` 이면 "아직 없음" 으로 보인다.
- `GET /admin/stats/active?range=` → `ActiveChart` = `{ range, points: [{ label, value }], total, average, previousTotal }`(결정 043 의 4). 원본은 `daily_active`(그날 처음 접속한 계정마다 한 줄 — 아래 "데이터"). 부를 때마다 새로 센다. "오늘" 은 한국 날짜다.
  - `range`: `today` · `week` · `month` 중 하나(소문자). 없거나 빈 글이거나 그 밖의 값(`TODAY` 도)이면 400 `INVALID_RANGE`.
  - `today`: 점 24개, `label` `"00"` ~ `"23"`(한국 시각의 시), `value` 는 그 시에 그날 처음 접속한 사람 수(아직 오지 않은 시는 0). 앞 기간은 어제.
  - `week`: 오늘 포함 최근 7일의 일별 점 7개, `label` `"YYYY-MM-DD"`(한국 날짜), `value` 는 그날 접속한 사람 수. 앞 기간은 그 앞 7일.
  - `month`: 이번 달 1일부터 오늘까지의 일별 점(1일이면 1개). 앞 기간은 지난달 1일부터 지난달의 같은 날까지(지난달에 그날이 없으면 말일까지 — 3월 31일이면 2월 1일 ~ 28일).
  - 빈 시 · 빈 날도 점이 있고 `value` 는 0 이다. 점은 시 · 날짜 순서다.
  - `total`: 기간 안에 한 번이라도 접속한 고유 계정 수(점의 합이 아니다 — 여러 날 들어온 사람은 한 번). `average`: 점의 `value` 평균(소수 첫째 자리 반올림, JSON 실수 — `today` 는 24로 나눈다). `previousTotal`: 앞 기간의 고유 접속자 수.
  - 계정이 지워지면 그 사람의 줄도 지워져 지난 수가 줄 수 있다(누적이 아니다). 사용자 목록 위의 수치(오늘 · 이번 주 · 이번 달 접속자)는 각 `range` 의 `total` 이다.
  - 예:
    ```
    GET /admin/stats/active?range=week
    { "range": "week",
      "points": [ { "label": "2026-11-14", "value": 1 }, …, { "label": "2026-11-20", "value": 4 } ],
      "total": 5, "average": 1.0, "previousTotal": 2 }
    ```
  - `generatedAt`: 센 때. `accounts`: 앱 사용자 계정(관리자는 세지 않는다) — 전체, 지금부터 7일(168시간) 안에 만든 것, 30일 안에 만든 것(`last30Days` 는 `last7Days` 를 포함한다). 개발용 로그인 계정도 센다.
  - `profiles`: 출생 정보를 저장한 계정 수. `readings`: 요약 해석. `reports`: 심층 리포트. `synastries`: 궁합. `synastryReports`: 궁합 리포트. `directions`: 방향성 제시. `inquiries`: 문의.
  - `byStatus` 는 적힌 키가 언제나 모두 있고(없으면 0) 합이 `total` 이다. `byTopic` 은 DB 에 있는 주제만 온다(지금은 `LOVE` 하나. 글이 하나도 없으면 `{}`, 주제가 늘면 키가 늘어난다 — 화면은 키를 돌면서 그린다).
  - 리포트의 `WRITING` 은 행에 적힌 값 그대로다: 서버가 꺼져 멈춘 생성(앱에는 `FAILED` 로 보인다)도 여기서는 `WRITING` 이다.
  - **세지 못하는 것**: 저장되지 않는 실패 — 요약 해석·방향성 제시·궁합 요약의 생성 실패(502)는 DB 에 남지 않아 수치에 없다. 리포트의 `FAILED` 는 행으로 남은 것만이고, 다시 요청해 성공하면 그 행이 `DONE` 으로 바뀌므로 "지금까지 실패한 횟수" 가 아니라 "지금 실패 상태인 리포트 수" 다.
  - 계정이 지워지면 그 사람의 행도 지워지므로 모든 수는 "지금 남아 있는 것" 이다(누적이 아니다).

### FAQ
- `Faq` = `{ id, question, answer, sortOrder, published, updatedAt, updatedBy }`.
  - `question`: 앞뒤 공백을 뗀 1~200자. `answer`: 앞뒤 공백을 뗀 1~2000자(가운데 줄바꿈은 그대로 둔다). 둘 다 꾸밈 없는 글자다(HTML·마크다운으로 해석하지 않는다).
  - `sortOrder`: 정수. 작은 것부터 보인다는 뜻뿐이다(값이 1 부터 이어진다고 믿지 않는다 — 지우면 빈 번호가 생긴다). 목록은 이미 이 순서로 온다.
  - `published`: `true` 인 것만 앱(`GET /faqs`)에 보인다.
  - `updatedAt`·`updatedBy`(`{ id, name }`): 글이나 공개 여부를 마지막으로 바꾼(또는 만든) 때와 관리자. 순서만 바꾼 것은 적지 않는다.
- `POST /admin/faqs`: 목록의 끝에 붙는다. `published` 는 불리언이고 없으면 `false`(공개하지 않은 채로 만든다). 201 `Faq`.
  - 400 `INVALID_QUESTION`·`INVALID_ANSWER`(없거나 `null` 이거나 비었거나 너무 길다. 둘 다 틀리면 `INVALID_QUESTION`) · `INVALID_REQUEST`(타입이 틀리다 — `published: "true"` 도 틀리다).
- `PATCH /admin/faqs/{id}`: 보낸 키만 바꾼다(셋 중 하나는 있어야 한다). 공개는 `{ "published": true }`, 내리기는 `{ "published": false }`. 200 바뀐 `Faq`. 순서는 여기서 바꾸지 않는다(`sortOrder` 를 보내도 읽지 않는다).
  - 400 `INVALID_REQUEST`(세 키가 모두 없거나, 타입이 틀리거나, `published` 가 `null` 이다) · `INVALID_QUESTION`·`INVALID_ANSWER`(키가 있는데 `null` 이거나 비었거나 너무 길다) · 404 `NOT_FOUND`.
- `DELETE /admin/faqs/{id}`: 204. 되돌릴 수 없다. 404 `NOT_FOUND`.
- `PUT /admin/faqs/order`: `ids` 에 **지금 있는 FAQ 전부의 id 를(공개하지 않은 것까지) 보일 순서대로 한 번씩** 담아 보낸다. 200 새 순서의 `[Faq]`.
  - 400 `INVALID_ORDER`: 빠진 id 가 있거나, 없는 id·UUID 가 아닌 글자가 있거나, 같은 id 가 두 번 있다 — 그 사이 다른 관리자가 FAQ 를 만들거나 지웠을 때도 이 답이므로 화면은 목록을 다시 받아 온다. 400 `INVALID_REQUEST`: `ids` 가 없거나 배열이 아니거나 원소가 글자가 아니다. FAQ 가 하나도 없을 때의 `{ "ids": [] }` 는 200 `[]`.
- 앱 쪽 공개 조회는 `GET /faqs`(세션 없음, 위 "API 계약"): 공개한 것만 같은 순서로 `[{ id, question, answer }]`. 응답이 5분 캐시될 수 있어(`Cache-Control: public, max-age=300`) 공개·수정·내리기가 앱에 보이기까지 5분쯤 걸릴 수 있다. 관리 화면의 출처는 `GET /faqs` 를 부를 수 없다(CORS) — 미리 보기는 `GET /admin/faqs` 의 `published` 로 그린다.
- 처음 글은 서버에 없다(빈 목록). 관리 화면으로 넣는다.

### 광고 푸시 발송 (결정 043 의 6 · 보내는 길은 결정 042)
- 대상: 푸시 수신 동의(`push_consented_at`) · 마케팅 수신 동의(`marketing_consented_at`)가 둘 다 있고, 정지가 아니고(`suspended_at`), 기기(`push_devices`)가 하나 이상 등록된 계정. 기기 표(푸시 묶음 S⑩)나 정지 칸(S⑪)이 아직 없는 서버에서는 대상 수가 0 이다.
- `GET /admin/push/audience` → `{ count }`: 지금의 대상 수.
- `POST /admin/push/events { title, body, target }`: `title` 은 앞뒤 공백을 뗀 1~40자(아니면 400 `INVALID_TITLE`), `body` 는 1~120자(아니면 400 `INVALID_BODY`), `target` 은 앱의 이동 화면 키 `HOME` · `TODAY_SKY` · `ME` · `TIMING_MONTH` · `TIMING_YEAR` · `MY`(아니면 400 `INVALID_TARGET`). 글자 수는 코드 포인트.
  - 한국 시각 21:00 ~ 다음 날 08:00 은 409 `QUIET_HOURS`. 그날(한국 날짜) 관리자 누구든 이미 보냈으면 409 `DAILY_LIMIT`(두 관리자가 동시에 보내도 한 번만 — `push_events.kst_date` 유일 제약).
  - 202 `{ id, recipients }`(`recipients` 는 그때의 대상 수)로 답하고 뒤에서 보낸다: `admin` 이 이벤트 `admin.PushEventRequested(eventId, title, body, target)` 를 내고 `push`(S⑩)가 듣고 보낸다. 제목 앞의 "(광고) " 와 본문 끝의 줄바꿈 + "수신 거부: 마이 › 알림" 은 서버(`admin`)가 붙인다 — 화면은 붙이지 않는다. 푸시 data 는 `{ type: "EVENT", target }`(S⑩).
  - 다 보내면 `push` 가 `admin.PushEventSent(eventId, recipients, succeeded, failed)` 를 내고 `admin` 이 발송 내역을 채운다.
  - 감사 기록 `PUSH_EVENT_SENT`(대상 `PUSH_EVENT` id, `detail` 은 `target` · `recipients` — 제목 · 본문은 발송 내역에만).
- `GET /admin/push/events?page=&size=`: 보낸 때가 늦은 것부터. `page` 는 0 부터(기본 0), `size` 1~100(기본 20). 틀리면 400 `INVALID_PAGE`. `PushEvent` = `{ id, sentAt, title, recipients, succeeded, failed, sentBy }` — `title` 은 관리자가 쓴 그대로(앞의 "(광고) " 없이), `succeeded` · `failed` 는 다 보내기 전에는 `null`, `sentBy` 는 보낸 관리자의 이름.
- `GET /admin/push/status` → `PushStatus` = `{ canSend, reason, sentTodayAt, sentTodayBy, nextAvailableAt }`. 그날 이미 보냈으면 `reason: "DAILY_LIMIT"` · 그때 · 보낸 관리자 이름 · 다음 날 08:00, 야간이면 `reason: "QUIET_HOURS"` · 그다음 08:00(새벽이면 그날 08:00), 보낼 수 있으면 `canSend: true` 와 나머지 `null`. 시각은 UTC 글자.

### 결제 없이 여는 계정 (결정 056 의 9 · 앱 결정 101 의 5-2)
- 계정 이메일(가입 때 공급자가 준 `accounts.email`)이 이 목록에 있으면 결제 시작 뒤에도 자세한 풀이를 받는다(RevenueCat 확인보다 먼저 — 위 "API 계약" 의 "자세한 풀이 상품"). 운영자 · 팀원 · 테스트용. 애플 "이메일 가리기" 계정은 가려진 주소(`…@privaterelay.appleid.com`)를 넣는다. 받기 때마다 목록을 보므로 빼면 바로 닫힌다(이미 받은 풀이는 그대로 보인다).
- `AllowlistEntry` = `{ id, email, createdAt, createdBy }` — `email` 은 소문자, `createdBy` 는 넣은 관리자 `{ id, name }`.
- `POST /admin/entitlement-allowlist { email }`: 앞뒤 공백을 떼고 소문자로 둔다. `@` 가 하나이고 그 앞뒤에 공백 · `@` 가 없는 254자 이하 글자가 아니면(없거나 `null` 포함) 400 `INVALID_EMAIL`, 글자가 아니면 400 `INVALID_REQUEST`. 이미 있으면(대소문자 무관) 409 `ALREADY_LISTED`. 201 `AllowlistEntry`. 감사 기록 `ENTITLEMENT_ALLOWLIST_ADDED`.
- `DELETE /admin/entitlement-allowlist/{id}`: 204. 없으면 404 `NOT_FOUND`. 감사 기록 `ENTITLEMENT_ALLOWLIST_REMOVED`.
- `GET /admin/entitlement-allowlist`: 넣은 때가 늦은 것부터 전부(쪽 나누기 없음 — 몇 줄뿐이다).

### 감사 기록
- 관리자와 앱 사용자의 주요 행동을 "누가 무엇을 언제" 만 남긴 목록이다(결정 015). 읽기만 있다 — 고치거나 지우는 경로가 없다(1년이 지난 행은 서버가 날마다 지운다 — 아래 "보관"). 이 조회 자체와 다른 목록·수치 조회는 기록되지 않는다.
- `AuditLog` = `{ id, occurredAt, actorType, actor, action, targetType, targetId, outcome, detail }`.
  - `occurredAt`: 기록된 때(그 일이 끝난 직후).
  - `actorType`: `ADMIN`(관리자) · `USER`(앱 사용자) · `SYSTEM`(로그인하지 않은 채 일어난 일: 실패한 관리자 로그인, 서버가 만든 첫 관리자).
  - `actor`: `{ ref, label }` 또는 `null`(`SYSTEM` 이면 언제나 `null`). `label` 은 화면에 보여 줄 글, `ref` 는 `actorRef` 조건에 그대로 넣는 값이다.
    - `ADMIN`: 둘 다 **그때의** 로그인 아이디다(예 `"root-admin"`). 관리자 id 가 아니다.
    - `USER`: 둘 다 `ownerRef`(12자 16진수) — 문의(`Inquiry.ownerRef`)와 같은 값이라 같은 사람의 문의와 행동을 묶어 볼 수 있다. 계정 id·이름·이메일은 어떤 응답에도 없다.
    - 지금은 `ref` 와 `label` 이 같은 글자지만 화면은 둘을 구분해 쓴다(보여 줄 때는 `label`, 거를 때는 `ref`).
  - `action`: 아래 표의 코드. 서버가 모르는 코드가 늘 수 있으므로 화면은 모르는 코드를 글자 그대로 보여 준다.
  - `targetType`·`targetId`: 무엇에 한 일인지(`"ADMIN"`·`"INQUIRY"`·`"FAQ"`·`"SYNASTRY"`·`"APP_CONFIG"`·`"USER"`·`"PUSH_EVENT"`·`"ENTITLEMENT_ALLOWLIST"` 와 그 id) 또는 둘 다 `null`. `INQUIRY` 의 id 는 `GET /admin/inquiries/{id}` 의 id, `ADMIN` 의 id 는 `AdminAccount.id`, `FAQ` 의 id 는 `Faq.id`, `USER` 의 id 는 사용자 관리(`GET /admin/users/{id}`, 결정 043 의 1)의 계정 id, `PUSH_EVENT` 의 id 는 광고 푸시(`GET /admin/push/events` 의 `id`, 결정 043 의 6)다. 대상이 그 뒤 지워졌을 수 있다(그때 그 경로는 404).
  - `outcome`: `SUCCESS` · `FAILURE`. `FAILURE` 는 표에 "실패도 남는다" 고 적힌 행동에만 있다. 거절된 요청(400·404·409·429)은 기록되지 않는다.
  - `detail`: 코드 값만 담은 작은 객체 또는 `null`. 키는 행동마다 아래 표와 같다. 글(메모·문의·FAQ)·이름·이메일·출생 정보·비밀번호·토큰은 어디에도 없다.
- 행동(`action`) — 관리자:

  | `action` | 언제 | `actorType` | 대상 | `detail` |
  |---|---|---|---|---|
  | `ADMIN_LOGIN` | 관리자 로그인. **실패도 남는다**(401 `INVALID_CREDENTIALS`): 그때는 `actorType: SYSTEM`·`actor: null`·`outcome: FAILURE` 이고 시도한 아이디는 남지 않는다. 형식이 틀린 요청(400)과 제한에 걸린 요청(429)은 남지 않는다 | `ADMIN` / `SYSTEM` | 없음 | `null` |
  | `ADMIN_LOGOUT` | `POST /admin/auth/logout` | `ADMIN` | 없음 | `null` |
  | `ADMIN_PASSWORD_CHANGED` | `PUT /admin/me/password` 성공(임시 비밀번호를 바꾼 것도) | `ADMIN` | 없음 | `null` |
  | `ADMIN_PASSWORD_REISSUED` | `POST /admin/admins/{id}/password-reset` 성공(다른 관리자에게 임시 비밀번호를 다시 만들어 줬다) | `ADMIN`(재발급한 관리자) | `ADMIN` 그 관리자의 id | `null` |
  | `ADMIN_PASSWORD_RESET` | `PUT /admin/admins/{id}/password` 성공(다른 관리자의 비밀번호를 다시 정했다) | `ADMIN`(정해 준 관리자) | `ADMIN` 그 관리자의 id | `null` |
  | `ADMIN_CREATED` | `POST /admin/admins` | `ADMIN` | `ADMIN` 만든 관리자의 id | `null` |
  | `ADMIN_DEACTIVATED` · `ADMIN_REACTIVATED` | `PATCH /admin/admins/{id}` 로 끄거나 켰을 때. 이미 그 상태였으면 남지 않는다 | `ADMIN` | `ADMIN` 그 관리자의 id | `null` |
  | `ADMIN_BOOTSTRAPPED` | 서버가 뜨면서 첫 관리자를 만들었을 때 | `SYSTEM` | `ADMIN` 첫 관리자의 id | `null` |
  | `INQUIRY_VIEWED` | `GET /admin/inquiries/{id}`(한 건 보기). 목록은 남지 않는다 | `ADMIN` | `INQUIRY` id | `null` |
  | `INQUIRY_UPDATED` | `PATCH /admin/inquiries/{id}` 에 `status` · `memo` · `answerDraft` 가운데 하나라도 보냈을 때 | `ADMIN` | `INQUIRY` id | `{ status?, memoChanged, draftChanged? }` — `status` 는 보냈을 때만 그 값, `memoChanged` 는 `memo` 키를 보냈는지(불리언), `draftChanged` 는 `answerDraft` 키를 보냈을 때만 `true`. 메모 · 임시 저장의 글은 없다 |
  | `INQUIRY_ANSWERED` | `PATCH /admin/inquiries/{id}` 에 `answer` 를 보냈을 때(답을 저장할 때마다). 같은 요청에 `memo` 가 있으면 `INQUIRY_UPDATED` 와 두 줄이 된다 | `ADMIN` | `INQUIRY` id | `{ first }` — 처음 답이었는지(불리언, 앱 사용자에게 알린 것은 `true` 일 때뿐). 답의 글은 없다 |
  | `FAQ_CREATED` | `POST /admin/faqs` | `ADMIN` | `FAQ` id | `{ published }` |
  | `FAQ_UPDATED` | `PATCH /admin/faqs/{id}` 에 `question` 이나 `answer` 를 보냈을 때 | `ADMIN` | `FAQ` id | `null` |
  | `FAQ_PUBLISHED` · `FAQ_UNPUBLISHED` | `PATCH /admin/faqs/{id}` 에 `published: true` · `false` 를 보냈을 때(이미 그 상태였어도 남는다). 글과 함께 보내면 `FAQ_UPDATED` 와 두 줄이 된다 | `ADMIN` | `FAQ` id | `null` |
  | `FAQ_DELETED` | `DELETE /admin/faqs/{id}` | `ADMIN` | `FAQ` id(이제 없는 id) | `null` |
  | `FAQ_REORDERED` | `PUT /admin/faqs/order` | `ADMIN` | 없음 | `{ count }` — 순서를 정한 FAQ 의 수 |
  | `APP_CONFIG_UPDATED` | `PUT /admin/app-config/{platform}`(아래 "앱 설정") | `ADMIN` | `APP_CONFIG` `ios` 또는 `android` | `{ platform, minVersion?, latestVersion?, blockNow }` — `minVersion` · `latestVersion` 은 값이 있을 때만(`latestVersion` 은 키를 보내지 않았으면 그대로 둔 값). 안내 글과 스토어 주소는 없다 |
  | `MAINTENANCE_UPDATED` | `PUT /admin/app-config/maintenance`(아래 "앱 설정" 의 점검) | `ADMIN` | `APP_CONFIG` `maintenance` | `{ active }` — 켰는지 껐는지. 안내 글은 없다 |
  | `USER_SUSPENDED` · `USER_UNSUSPENDED` | 사용자를 정지했을 때 · 정지를 풀었을 때(`POST /admin/users/{id}/suspend` · `unsuspend`, 결정 043 의 1 · 044) | `ADMIN` | `USER` 그 계정의 id | 경로를 만드는 묶음이 정한다(사유 글은 넣지 않는다) |
  | `USER_DELETED_BY_ADMIN` | 사용자가 요청해 관리자가 탈퇴를 대행했을 때(`DELETE /admin/users/{id}`) | `ADMIN` | `USER` 그 계정의 id(이제 없는 id) | 경로를 만드는 묶음이 정한다(사유 · 닉네임은 넣지 않는다) |
  | `GENERATION_RETRIED` | 실패한 생성을 다시 하게 했을 때(`POST /admin/users/{id}/regenerate`) | `ADMIN` | `USER` 그 계정의 id | `{ "kind": "REPORT_ME" \| "REPORT_TIMING" }` |
  | `PUSH_EVENT_SENT` | 광고 푸시를 보냈을 때(`POST /admin/push/events`, 결정 043 의 6) | `ADMIN` | `PUSH_EVENT` 그 발송의 id | `{ "target": 이동 화면 키, "recipients": 그때의 대상 수 }`(제목 · 본문 글은 넣지 않는다) |
  | `ENTITLEMENT_ALLOWLIST_ADDED` · `ENTITLEMENT_ALLOWLIST_REMOVED` | 결제 없이 여는 계정을 넣거나 뺐을 때(`POST` · `DELETE /admin/entitlement-allowlist`, 결정 056 의 9) | `ADMIN` | `ENTITLEMENT_ALLOWLIST` 그 줄의 id | `null`(이메일은 넣지 않는다) |

  - 아래 다섯 행동(`USER_SUSPENDED` ~ `PUSH_EVENT_SENT`)은 코드(`action` 조건으로 거를 수 있다)만 먼저 더했다. 그 경로가 아직 없어 지금은 남지 않는다 — 경로를 만드는 묶음(계획 S⑪ · S⑩)이 남기고 `detail` 을 이 표에 적는다.

- 행동(`action`) — 앱 사용자(모두 `actorType: USER`):

  | `action` | 언제 | 대상 | `detail` |
  |---|---|---|---|
  | `USER_LOGIN` | 로그인해 세션을 받았을 때(지금은 개발용 로그인, 소셜 로그인도 이 코드를 쓴다) | 없음 | `null` |
  | `PROFILE_SAVED` | 출생 정보를 저장했을 때(`PUT /profile`) | 없음 | `{ change: "CREATED" \| "CHANGED" }` — 처음 저장인지 고친 것인지. 값은 없다 |
  | `READING_GENERATED` | 요약 해석을 **새로** 만들었을 때. **실패도 남는다**(502) | 없음 | `null` |
  | `REPORT_REQUESTED` | 심층 리포트(`POST /reports`) · 나 탭 풀이(`POST /me-report`) · 시기 풀이(`POST /timing-report`)를 만들기 시작했을 때(뒤에서 끝난 결과는 남지 않는다 — 옛 리포트는 수치의 `reports.byStatus` 로 본다) | 없음 | 옛 리포트 `null` · 나 탭 풀이 `{ kind: "ME" }` · 시기 풀이 `{ kind: "TIMING" }` |
  | `SYNASTRY_CREATED` | 궁합을 만들었을 때. **실패도 남는다**(502, 그때는 대상이 없다) | `SYNASTRY` id | `{ relation: "LOVE" \| "FRIEND" \| "WORK" \| "FAMILY" }` |
  | `SYNASTRY_REPORT_REQUESTED` | 궁합 리포트를 만들기 시작했을 때 | `SYNASTRY` id | `null` |
  | `SYNASTRY_DELETED` | 궁합을 지웠을 때(`DELETE /synastry/{id}`) | `SYNASTRY` id(이제 없는 id) | `null` |
  | `DIRECTION_GENERATED` | 방향성 제시를 **새로** 만들었을 때. **실패도 남는다**(502, 그때는 대상이 없다) | 궁합 주제만 `SYNASTRY` id | `{ topic: "LOVE" \| "FUTURE" \| "SYNASTRY" }` |
  | `SHARE_CREATED` | 공유 카드를 **새로** 만들었을 때(같은 내용을 다시 공유한 것은 남지 않는다) | 없음(공유 식별자는 남기지 않는다) | `{ kind: "READING" \| "SCORE" \| "RANKING" \| "NEXT_TURN" \| "NICKNAME" \| "ME_AREA" \| "DAY_SKY" \| "NEXT_BIG" }` |
  | `SHARE_DELETED` | 공유를 지웠을 때 | 없음 | `null` |
  | `INQUIRY_SUBMITTED` | 문의를 보냈을 때 | `INQUIRY` id | `{ category }` |
  | `ACCOUNT_DELETED` | 계정을 지웠을 때 | 없음 | `null` |
  | `TERMS_AGREED` | 가입으로 계정이 생겼을 때 — 받는 약관 · 처리방침 판에 동의했다(결정 026 · 045). 남은 가입권으로 다시 온 있는 계정은 남지 않는다 | 없음 | `{ termsVersion, privacyVersion }` — 사람이 보낸(동의한) 두 버전, 서버 설정 값이 아니다(결정 045). 이메일 · 닉네임은 없다 |
  | `PUSH_CONSENT_CHANGED` | `PATCH /account` 로 푸시 수신 동의를 켜거나 껐을 때(결정 027). 이미 그 값이었으면 남지 않는다. 가입 때의 동의는 남지 않는다(계정 칸에만) | 없음 | `{ consent: "true" \| "false" }` |

  - 저장된 글을 다시 받는 요청(같은 차트의 요약 해석·방향성 제시, 이미 쓰는 중이거나 완성된 리포트)은 남지 않는다. 그래서 `READING_GENERATED`·`DIRECTION_GENERATED`·`SYNASTRY_CREATED` 의 `SUCCESS`·`FAILURE` 수가 수치(`GET /admin/stats`)가 세지 못하던 "생성 실패" 를 보여 준다.
  - **계정을 지워도 그 사람의 행은 남는다**(문의·수치와 다르다). `ownerRef` 는 그대로이고, 그 계정의 문의는 지워져 `targetId` 의 문의는 404 가 된다.
  - 드물게 일은 됐는데 행이 없을 수 있다(기록을 넣다 실패하면 사용자의 요청을 막지 않고 건너뛴다).
- `GET /admin/audit-logs`: 기록된 때가 늦은 것부터(같으면 id 가 큰 것부터). 쿼리는 모두 없어도 되고, 빈 값(`action=`)은 없는 것으로 본다. 조건을 여러 개 주면 모두 맞는 것만 온다.
  - `actorType`: `ADMIN`·`USER`·`SYSTEM` 중 하나. 그 밖의 값은 400 `INVALID_ACTOR_TYPE`(소문자도 틀리다).
  - `action`: 위 두 표의 코드 중 하나. 그 밖의 값은 400 `INVALID_ACTION`. 맞는 코드인데 행이 없으면 빈 목록이다.
  - `from`·`to`: **한국 시간의 날짜 `YYYY-MM-DD`** 이고 두 끝을 포함한다 — `from=2026-11-10&to=2026-11-10` 은 한국 시간 11월 10일 00:00:00 부터 23:59:59 까지다. 하나만 줘도 된다. 모양이 다르거나(`2026-11-1`, 시각이 붙은 값) 없는 날(`2026-02-30`)이면 400 `INVALID_DATE`. `from` 이 `to` 보다 늦으면 오류가 아니라 빈 목록이다. 응답의 `occurredAt` 은 UTC 이므로 화면이 한국 시간으로 바꿔 보여 준다.
  - `actorRef`: 관리자의 로그인 아이디 또는 사용자의 `ownerRef`(`actor.ref` 의 값). 앞뒤 공백과 대소문자를 가리지 않고 **전체가 같은 것만** 준다(일부만 맞는 검색은 없다 — 일부 검색은 `q`). 맞는 것이 없으면 빈 목록이다. `SYSTEM` 의 행은 이 조건에 걸리지 않는다.
  - `q`(결정 043 의 8): 검색어. 앞뒤 공백을 떼고 대소문자를 가리지 않고 **일부가 맞는** 행을 준다 — `actorType: ADMIN` 의 행은 그때의 로그인 아이디(`actor.label`)에, `actorType: USER` 의 행은 그 사람의 **지금** 닉네임에 견준다. `%`·`_` 같은 글자도 글자 그대로다. 빈 값 · 공백뿐인 값은 없는 것으로 본다. 틀린 값은 없다(400 이 없다).
    - 닉네임은 감사 기록에 없다(결정 015) — 지금의 계정에서 닉네임으로 계정을 찾아 그 계정의 행을 준다. 그래서 **지워진 계정의 행은 닉네임으로 찾을 수 없고**(`actorRef` 의 `ownerRef` 로는 찾는다), 닉네임을 바꾼 사람은 새 닉네임으로 찾는다. 사용자의 `ownerRef` 나 계정 id 는 `q` 로 찾지 않는다. `SYSTEM` 의 행은 걸리지 않는다.
  - `page`: 0 부터 세는 쪽 번호(기본 0). `size`: 한 쪽의 개수 1~100(기본 20). 정수가 아니거나 범위를 벗어나면 400 `INVALID_PAGE`.
  - 여러 조건이 틀리면 `INVALID_ACTOR_TYPE` → `INVALID_ACTION` → `INVALID_DATE` → `INVALID_PAGE` 순서로 처음 걸린 것 하나다.
  - 응답 `{ items, page, size, total }`: 문의 목록과 같다 — `page`·`size` 는 서버가 쓴 값, `total` 은 조건에 맞는 전체 수, 마지막 쪽을 넘으면 `items` 가 빈 배열이다.
  - 예:
    ```
    GET /admin/audit-logs?actorType=ADMIN&from=2026-11-12&size=2
    {
      "items": [
        { "id": "0b0e…", "occurredAt": "2026-11-12T04:05:06Z", "actorType": "ADMIN", "actor": { "ref": "root-admin", "label": "root-admin" },
          "action": "INQUIRY_UPDATED", "targetType": "INQUIRY", "targetId": "7c1d…", "outcome": "SUCCESS", "detail": { "status": "DONE", "memoChanged": true } },
        { "id": "02aa…", "occurredAt": "2026-11-12T04:00:00Z", "actorType": "ADMIN", "actor": { "ref": "root-admin", "label": "root-admin" },
          "action": "ADMIN_LOGIN", "targetType": null, "targetId": null, "outcome": "SUCCESS", "detail": null }
      ],
      "page": 0, "size": 2, "total": 7
    }
    ```
- **보관은 1년**(결정 043 의 8 — 결정 015 의 "보관 기한 없음" 을 바꿨다): 서버가 날마다 한국 시각 04:30 에 일어난 지 365일이 지난 행을 지운다(문장 하나 — 서버가 여러 대여도 같다. 로그 `event=audit_retention_deleted count=…`). 그래서 `from` 을 1년보다 앞으로 줘도 그 앞의 행은 없다. 수치(`Stats`)에는 감사 기록의 수가 없다.

### 앱 설정 (최소 버전·최신 버전·강제 업데이트·점검)
- 앱(iOS·Android)이 써야 하는 가장 낮은 버전을 플랫폼마다 정하고, 급할 때는 그보다 낮은 버전의 요청을 서버가 막게 한다(결정 016). 업데이트를 권할 최신 버전도 둔다(결정 040). 플랫폼은 `ios`·`android` 둘뿐이고 더하거나 지울 수 없다.
- `AppConfig` = `{ ios: PlatformConfig, android: PlatformConfig, maintenance: Maintenance }`(`Maintenance` 는 아래 "점검"). `PlatformConfig` = `{ minVersion, latestVersion, storeUrl, message, blockNow, updatedAt, updatedBy }`. `GET` 의 응답은 `AppConfig`, `PUT` 의 응답은 바뀐 플랫폼의 `PlatformConfig` 하나다.
  - `minVersion`: 최소 버전의 글자 또는 `null`(정하지 않음). 버전 규칙: 점으로 나눈 1~3개의 0 이상 정수 — `"1"`·`"1.2"`·`"1.2.3"`, 자리마다 숫자 9개까지. 빠진 자리는 0 으로 보고(`"1.2"` = `"1.2.0"`) 숫자로 견준다(`1.10.0` > `1.9.9`). 보낸 글자 그대로 저장한다(`"1.2"` 를 `"1.2.0"` 으로 바꾸지 않는다).
  - `latestVersion`: 최신 버전의 글자 또는 `null`(권하지 않음). 버전 규칙 · 저장은 `minVersion` 과 같다. 이보다 낮고 `minVersion` 이상인 앱은 업데이트를 한 번 권받는다(버전마다 한 번 — 앱이 판정, 서버는 막지 않는다). `minVersion` 이 있으면 그보다 낮을 수 없다(같은 것은 된다).
  - `storeUrl`: 업데이트하러 갈 스토어 주소 또는 `null`. `https://` 로 시작하고 호스트가 있는 500자 이하의 주소다. 앞뒤 공백을 떼지 않는다(공백이 있으면 틀린 값이다).
  - `message`: 앱이 업데이트 안내에 보여 줄 글 또는 `null`(앱의 기본 글을 쓴다). 앞뒤 공백을 뗀 200자 이하(코드 포인트)의 꾸밈 없는 글자이고 가운데 줄바꿈은 그대로 둔다.
  - `blockNow`: `true` 면 그 플랫폼에서 `minVersion` 보다 낮은 버전의 앱이 보내는 **모든 요청이 426 `UPDATE_REQUIRED` 로 막힌다**(그 앱은 업데이트 화면만 보인다). `false` 면 서버는 막지 않고, 앱이 `minVersion` 을 보고 업데이트를 권하기만 한다. `minVersion` 이 `null` 이면 `true` 로 둘 수 없다.
  - `updatedAt`: 마지막으로 고친 때(한 번도 고치지 않았으면 서버에 이 기능이 들어온 때). `updatedBy`: `{ id, name }`(고친 관리자, `name` 은 표시 이름) 또는 `null`(아무도 고치지 않았다).
- `GET /admin/app-config`: 200 `AppConfig`. 두 플랫폼과 `maintenance` 가 언제나 다 있다. 처음에는 둘 다 `{ "minVersion": null, "latestVersion": null, "storeUrl": null, "message": null, "blockNow": false, "updatedAt": "…", "updatedBy": null }` 다.
- `PUT /admin/app-config/{platform}`: `platform` 은 `ios` 또는 `android`(소문자). 본문은 `{ "minVersion": string | null, "latestVersion": string | null, "storeUrl": string | null, "message": string | null, "blockNow": boolean }` 이고 **다섯 키를 모두 보낸다**(바꾸지 않는 값도 지금 값을 그대로 보낸다 — 화면은 `GET` 으로 받은 값을 폼에 채워 통째로 보낸다). 그 플랫폼의 값이 본문의 값으로 바뀐다. 다만 `latestVersion` 키가 없으면 지금 값을 그대로 둔다(네 키를 보내는 옛 관리 화면 호환 — 결정 040, 나머지 네 키는 없으면 400). 200 바뀐 `PlatformConfig`. 다른 플랫폼은 그대로다.
  - `message` 는 `null` 이거나 빈 글·공백뿐인 글이면 지워진다(`null`). `minVersion`·`latestVersion`·`storeUrl` 은 `null` 을 보내야 지워진다(빈 글은 틀린 값이다).
  - 404 `NOT_FOUND`: `platform` 이 `ios`·`android` 가 아니다(`IOS` 도 틀리다). `maintenance` 는 플랫폼이 아니라 아래 "점검" 의 경로다.
  - 400 `INVALID_REQUEST`: 본문이 객체가 아니거나, `latestVersion` 밖의 네 키 가운데 하나라도 없거나, 타입이 틀리다(`minVersion`·`latestVersion`·`storeUrl`·`message` 가 글자도 `null` 도 아니다 — 숫자 `1.2` 도 틀리다. `blockNow` 가 불리언이 아니다 — `null`·`"true"`·`1` 도 틀리다).
  - 400 `INVALID_VERSION`: `minVersion` 이 버전 규칙에 맞지 않는다(`""`·`"1.2.3.4"`·`"v1.2"`·`"1.2-beta"`·앞뒤 공백).
  - 400 `INVALID_LATEST_VERSION`: `latestVersion` 이 버전 규칙에 맞지 않는다(같은 예).
  - 400 `INVALID_STORE_URL`: `storeUrl` 이 `https://` 주소가 아니거나(`http://`·`itms-apps://`·`market://`·스킴 없음), 호스트가 없거나, 공백이 있거나, 500자를 넘는다.
  - 400 `INVALID_MESSAGE`: `message` 가 공백을 뗀 뒤 200자를 넘는다.
  - 400 `BLOCK_REQUIRES_MIN_VERSION`: `blockNow` 가 `true` 인데 `minVersion` 이 `null` 이다.
  - 400 `LATEST_BELOW_MIN`: `minVersion` 과 `latestVersion` 이 둘 다 있고 최신이 최소보다 낮다(숫자로 견준다). `latestVersion` 키가 없으면 저장된 최신 버전과 견준다 — 최소 버전만 올려 최신보다 높아져도 이 답이다.
  - 여러 개가 틀리면 이 순서(`INVALID_REQUEST` → `INVALID_VERSION` → `INVALID_LATEST_VERSION` → `INVALID_STORE_URL` → `INVALID_MESSAGE` → `BLOCK_REQUIRES_MIN_VERSION` → `LATEST_BELOW_MIN`)로 처음 걸린 것 하나다. 거절된 요청은 아무것도 바꾸지 않는다.
- 언제 적용되나: 막기(426)는 고친 서버에서 **바로**, 서버가 여러 대면 다른 서버는 길어야 5초 뒤다. 앱이 읽는 공개 조회 `GET /app-config`(위 "API 계약" 의 "앱 설정과 강제 업데이트" — 여기서 `updatedAt`·`updatedBy` 를 뺀 다섯 값)는 60초 캐시될 수 있어 안내 글·주소가 앱에 보이기까지 1분쯤 걸릴 수 있다.
- 막히는 것과 막히지 않는 것: 앱은 모든 요청에 `X-App-Platform`·`X-App-Version` 헤더를 보낸다. `blockNow` 가 켜진 플랫폼에서 버전이 `minVersion` 보다 낮은 요청만 막힌다. **관리자 API(`/admin/**`)는 어떤 경우에도 막히지 않는다** — 잘못 켰으면 같은 화면에서 끈다. 웹 빌드(헤더 없음)·모르는 플랫폼·읽을 수 없는 버전의 요청도 막히지 않는다. 버전이 `minVersion` 과 같으면 막히지 않는다.
  - 화면에 권하는 확인: `blockNow` 를 켜기 전에 "`minVersion` 미만의 앱은 업데이트 전까지 아무것도 할 수 없게 된다" 를 한 번 묻는다. `minVersion` 을 스토어에 아직 없는 버전으로 넣으면 그 플랫폼의 모든 사용자가 막힌다(서버는 스토어에 그 버전이 있는지 모른다).
- **점검**(결정 017): `Maintenance` = `{ active, message, updatedAt, updatedBy, startedAt, startedBy }`.
  - `active`: `true` 면 **앱과 웹의 모든 요청이 503 `MAINTENANCE` 로 막힌다**(사용자는 점검 화면만 본다). 플랫폼·버전과 상관없고 강제 업데이트보다 먼저다. `message`: 점검 화면에 보여 줄 글 또는 `null`(앱의 기본 글). `updatedAt`·`updatedBy`: 플랫폼 설정과 같은 뜻이다(아무도 고치지 않았으면 `updatedBy: null`). 처음에는 `{ "active": false, "message": null, "updatedAt": "…", "updatedBy": null, "startedAt": null, "startedBy": null }` 다.
  - `startedAt` · `startedBy`(결정 043 의 7 — 관리 화면 모든 화면 위의 띠): 점검을 **꺼진 상태에서 켠** 때(UTC 글자)와 그 관리자 `{ id, name }`. 켜진 채로 글만 바꾸면(다른 관리자가 해도) 그대로이고 `updatedAt` · `updatedBy` 만 바뀐다. 끄면 둘 다 `null` 이다(꺼진 채로 다시 꺼도 `null`). 이 둘은 관리자 쪽에만 있다 — 앱이 읽는 `GET /app-config` 의 `maintenance` 는 `{ active, message }` 그대로다.
  - `PUT /admin/app-config/maintenance`: 본문 `{ "active": boolean, "message": string | null }` — **두 키를 모두 보낸다**. 200 바뀐 `Maintenance`. `message` 는 앞뒤 공백을 뗀 200자 이하(코드 포인트)이고 `null`·빈 글·공백뿐인 글이면 지워진다(`null`). 끌 때 `message` 를 남겨 둬도 된다(다음에 켤 때 쓴다 — 꺼져 있는 동안 앱은 보여 주지 않는다).
    - 400 `INVALID_REQUEST`: 본문이 객체가 아니거나, 두 키 가운데 하나라도 없거나, 타입이 틀리다(`active` 가 불리언이 아니다 — `null`·`"true"`·`1` 도 틀리다. `message` 가 글자도 `null` 도 아니다). 400 `INVALID_MESSAGE`: 공백을 뗀 뒤 200자를 넘는다. 둘 다 틀리면 `INVALID_REQUEST`. 거절된 요청은 아무것도 바꾸지 않는다.
  - 켜고 끄는 것은 고친 서버에서 **바로**, 서버가 여러 대면 길어야 5초 뒤에 적용된다. **관리자 API(`/admin/**`)는 점검 중에도 열려 있다** — 같은 화면에서 끈다. 앱이 읽는 `GET /app-config` 의 `maintenance`(`active`·`message` 만)는 60초 캐시될 수 있다.
  - 화면에 권하는 확인: 켜기 전에 "모든 사용자가 앱과 웹을 쓸 수 없게 된다" 를 한 번 묻고, 켜져 있는 동안 눈에 띄게 보여 준다.
  - 고칠 때마다 감사 기록 `MAINTENANCE_UPDATED` 가 남는다(켰는지 껐는지만, 안내 글은 없다).
  - 예:
    ```
    PUT /admin/app-config/maintenance
    { "active": true, "message": "새벽 2시부터 4시까지 점검해요." }

    200
    { "active": true, "message": "새벽 2시부터 4시까지 점검해요.", "updatedAt": "2026-11-12T17:00:00Z", "updatedBy": { "id": "5f0c…", "name": "root-admin" },
      "startedAt": "2026-11-12T17:00:00Z", "startedBy": { "id": "5f0c…", "name": "root-admin" } }
    ```
- 고칠 때마다 감사 기록 `APP_CONFIG_UPDATED` 가 남는다(위 "감사 기록": 플랫폼·최소 버전·최신 버전·`blockNow` 만, 안내 글과 주소는 없다). 관리 화면의 출처는 `GET /app-config` 를 부를 수 없다(CORS) — 화면은 `GET /admin/app-config` 를 쓴다.
- 예:
  ```
  PUT /admin/app-config/ios
  { "minVersion": "1.2.0", "latestVersion": "1.4.0", "storeUrl": "https://apps.apple.com/kr/app/id0000000000", "message": "더 안정적인 새 버전이 나왔어요.", "blockNow": true }

  200
  { "minVersion": "1.2.0", "latestVersion": "1.4.0", "storeUrl": "https://apps.apple.com/kr/app/id0000000000", "message": "더 안정적인 새 버전이 나왔어요.", "blockNow": true,
    "updatedAt": "2026-11-12T04:05:06Z", "updatedBy": { "id": "5f0c…", "name": "root-admin" } }
  ```

### 브라우저 출처 (CORS)
- 관리 화면이 열리는 주소를 서버의 환경 변수 `ADMIN_ALLOWED_ORIGINS`(설정 `byeolmyeong.cors.admin-allowed-origins`, 쉼표로 구분, 예 `https://admin.example.com,http://localhost:5173`)에 적어야 브라우저가 부를 수 있다. 주소는 `스킴://호스트[:포트]` 까지이고 끝에 `/` 를 붙이지 않는다.
- `/admin/**` 는 이 주소만 허용한다. 허용 메서드 GET·POST·PUT·PATCH·DELETE, 허용 요청 헤더 `Authorization`·`Content-Type`. 쿠키·자격 증명 허용은 쓰지 않는다(`fetch` 에 `credentials` 를 주지 않는다).
- 앱의 주소(`ALLOWED_ORIGINS`)는 `/admin/**` 를 부를 수 없고, 관리 화면의 주소는 `/admin/**` 밖의 경로를 부를 수 없다. 허용하지 않은 출처의 브라우저 요청은 맞는 토큰이 있어도 403(본문 `Invalid CORS request`, JSON 이 아니다)이다. 비워 두면 `/admin/**` 는 어떤 브라우저 출처에도 열리지 않는다(`Origin` 헤더가 없는 호출 — curl·서버 사이 — 은 CORS 와 상관없이 토큰만 본다).
- 401 응답에도 허용 헤더가 붙으므로 화면이 401 을 읽을 수 있다.
- 관리 화면은 `X-App-Platform`·`X-App-Version` 헤더를 보내지 않는다(허용 요청 헤더에 없다). 보내더라도 `/admin/**` 는 강제 업데이트에 걸리지 않는다.

### 첫 관리자와 환경 변수
- 첫 관리자는 API 로 만들 수 없다. 서버가 뜰 때 **관리자가 한 명도 없고** 환경 변수 `ADMIN_BOOTSTRAP_ID`·`ADMIN_BOOTSTRAP_PASSWORD` 가 **둘 다** 있으면 그 값으로 만든다(표시 이름은 아이디와 같다). 아이디·비밀번호 규칙은 위와 같고, 규칙에 안 맞으면 만들지 않는다(로그 `event=admin_bootstrap_skipped reason=…`). 관리자가 한 명이라도 있으면 두 변수는 읽지 않는다(비밀번호를 덮어쓰지 않는다). 하나라도 없으면 관리자가 없는 채로 뜨고 `/admin/**` 는 전부 401 이다.
- 설정(`application.properties`): `byeolmyeong.admin.session-hours`(12) · `byeolmyeong.admin.bcrypt-strength`(12) · `byeolmyeong.rate-limit.admin-login-per-ip`(20) · `byeolmyeong.app-config.cache-seconds`(5) · `byeolmyeong.rate-limit.app-config-per-minute`(120).

## 데이터
- `accounts`(공급자, 공급자 사용자 식별자 unique, 이메일 — V19, 결정 024 · 닉네임(`lower(nickname)` 유일) · 마케팅 수신 동의 일시 — V20, 결정 025 · 이용약관 · 처리방침의 동의 버전과 시각 · 푸시 수신 동의 일시 · 프로필 번호 1~8 — V21, 결정 026 · 027 · 028. V19 뒤의 칸은 모두 null 허용이고 옛 계정은 비어 있다 · 오늘의 하늘 알림 `today_sky_push`(null = 푸시 수신 동의를 따름) · `today_sky_time`(한국 시각, 기본 08:00) — V34, 결정 042 · 이용 정지 `suspended_at` · `suspended_by`(관리자) · `suspension_reason`(관리자끼리만) — V27, 결정 044, 정지 · 해제 내역은 `account_suspensions`(V29: 계정 · SUSPEND/UNSUSPEND · 사유 · 관리자 · 때, 계정과 함께 지워진다) · 마지막 접속 `last_seen_at` — V25, 결정 043 의 2: 사용자 세션으로 온 요청이 10분이 지났거나 한국 날짜가 바뀌었을 때만 쓴다) · `daily_active`(V25: 한국 날짜 · 계정 기본 키, 그날 처음 접속한 한국 시각의 시 0~23 — 그날 처음 접속할 때 한 줄, 접속 차트가 센다. 감사 기록과 따로다) · `sessions`(토큰 해시, 마지막 사용 시각 — V16, 90일 지나면 끝난다 · 결정 021) · `signup_tickets`(V17: 가입권 해시, 공급자, 공급자 사용자 식별자, 끝나는 때 — 30분 · 1회용, 결정 021 · V18: 애플 클라이언트 · refresh token · V19: 이메일) · `apple_tokens`(V18: 계정, 클라이언트 ID, refresh token — 탈퇴 때 철회, 결정 021) · `profiles`(출생 정보. 태어난 곳은 시각을 알 때만 있다) · `readings`(소유자, 종류 — `SUMMARY_V2`(요약 해석 v2, 본문 `{ headline, sections[{ title, body, reason }], strengths, cautions }` — 결정 030 · 031) · 옛 `SUMMARY`(읽지 않는다 · 지우지 않는다), 차트 해시, 본문, 만든 때 `created_at`(응답의 `createdAt`), 더 쓰지 않는 공유 식별자 `share_id` — 결정 012) · `reports`(소유자·차트 해시 unique, 상태 `WRITING`·`DONE`·`FAILED`, 본문은 `DONE` 일 때만 — 네 영역의 글과 시기(`timing`: 마디·구간의 날짜와 글, "지금" 은 없음), 생성을 시작한 때(그 생성의 표식)·살아 있다는 표시를 마지막으로 적은 때(`active_at`, V9)·상태가 바뀐 때) · `detail_reports`(V22, 결정 029 — `reports` 와 같은 꼴: 소유자 · 종류(`ME` 나 탭 풀이 · `TIMING` 시기 풀이) · 차트 해시 unique, 상태 `WRITING`·`DONE`·`FAILED`, 본문은 `DONE` 일 때만 — `ME` 는 `MeReport`(별 · 일 방식 수치까지), `TIMING` 은 `TimingReport`(마디 · 구간의 날짜와 글 · 맡긴 구간의 칸, "지금" 과 대표 구간은 없음), 생성을 시작한 때 · `active_at` · 상태가 바뀐 때) · `day_skies`(V30, 결정 034 — 소유자 · 차트 해시 · 날짜(한국 날짜) unique, 본문 `{ title, category, flow, reason, practices }` — 검증을 통과한 글만, 만든 때 `created_at`(응답의 `createdAt`). 출생 정보를 고치면 옛 차트의 행은 지운다 — 결정 041) · `synastries`(두 참여자 또는 한 참여자 + 직접 입력, 관계 유형, 나 기준 점수, 부를 이름, 키워드, 본문, 공유 식별자) · `invites`(초대 식별자, 만료, 상태) · `directions`(소유자·주제·대상 unique, 본문. 대상의 뜻은 주제가 정한다: `LOVE` 는 차트 해시, `FUTURE` 는 `차트 해시|지금의 시기가 같은 동안의 첫날|마지막 날`, `SYNASTRY` 는 궁합 id — 결정 009·011. 궁합 주제의 행에도 상대의 출생 정보·행성 위치·연결은 없다) · `notifications`(V32, 결정 035: 소유자 · 종류 · 궁합 id · 문의 id(그 종류만, 궁합 · 문의를 지우면 함께 지워진다) · 만든 때 · 읽은 때 — 30일 지나면 날마다 지운다) · `used_id_tokens`(V35, 결정 047: 웹 구글 ID 토큰의 `jti` 기본 키 · 끝나는 때 — 끝난 줄은 날마다 지운다, 토큰 원문 · 계정은 없다) · `inquiries`(V11: 소유자, 종류, 글, 답장 받을 이메일 — 없으면 null(2026-11-20 뒤로는 언제나 null), 만든 때 · V23: 답 · 처음 답한 때와 관리자 · 답의 임시 저장 · 보낸 앱의 플랫폼과 버전 — 모두 null 허용, 결정 039. 기기 이름·주소는 없다 — 결정 013) · `push_devices`(V34, 결정 042: Expo 푸시 토큰 기본 키, 계정, 플랫폼 `IOS` · `ANDROID`, 앱 버전, 만든 때, 마지막 등록 때) · `today_sky_pushes`(V34, 결정 042 의 6: 계정 · 한국 날짜 기본 키, 미리 만들기를 맡은 때, 보낸 때 — 같은 날 한 번을 서버 여러 대에서도 지킨다).
- 마이그레이션은 배포 중 옛 jar 와 새 jar 가 같은 DB 를 함께 쓴다는 전제로 쓴다: 열 · 표 지우기 · 이름 바꾸기 · 기존 열에 NOT NULL 더하기는 두 번의 배포로 나누고, 두 번 돌면 안 되는 `@Scheduled` 는 `pg_try_advisory_lock` 으로 한 서버만 돌게 한다(결정 046, `.claude/rules/two-servers-overlap.md`).
- 소유자를 가리키는 테이블은 모두 `on delete cascade` 다: 계정을 지우면 문의도 함께 지워진다. 문의는 관리자 API(`/admin/inquiries`)로 읽고 처리한다(결정 014).
- 자세한 풀이 상품(V36, 결정 056 — `report` 가 쓴다): `entitlements`(계정 · 출처 `LEGACY`(1.0.0 수령자) · `STORE`(스토어에서 산 계정) · 적은 때 · 고지 판(`STORE` 일 때 확인 요청이 실어 보낸 `noticeVersion` — 처음 한 번), (계정, 출처)가 키 — 계정을 지우면 함께 지워지고 출생 정보를 바꿔도 남는다) · `entitlement_allowlist`(결제 없이 여는 계정 — 이메일(소문자, 유일) · 넣은 때 · 넣은 관리자, `admin` 이 쓰고 `report` 가 `accounts.email` 과 맞춰 읽는다). `LEGACY` 는 결제 시작 시각(`byeolmyeong.purchase.starts-at`) 전에 나 탭 풀이 · 시기 풀이를 새로 시작할 때(누른 때) 적고, 서버가 뜰 때 · 매시 10분에 `detail_reports` 의 `DONE`(완성한 때가 시작 전) ∪ 감사 기록 `REPORT_REQUESTED`(kind `ME` · `TIMING`, 누른 때가 시작 전)의 계정으로 채운다 — 시작 뒤에도 같은 조건으로 돈다(마이그레이션이 아니라 서버가 채운다 — 첫 배포의 블루그린 겹침 동안 옛 jar 가 받은 요청을 놓치지 않게).
- 광고 푸시 발송 내역 `push_events`(V33, 결정 043 의 6 — `admin` 이 쓴다): 보낸 한국 날짜(유일 — 하루 한 번) · 보낸 때 · 제목 · 본문 · 이동 화면 · 대상 수 · 성공 · 실패 수(다 보낸 뒤) · 보낸 관리자.
- 관리자 쪽 테이블(V12, 결정 014): `admin_accounts`(로그인 아이디 unique·소문자, bcrypt 해시, 표시 이름, 활성 여부, 만든 때 · V26, 결정 043 의 5: 임시 비밀번호라 바꿔야 하는지 `must_change_password` · 마지막 로그인 `last_login_at`) · `admin_sessions`(토큰 해시, 관리자, 끝나는 때) · `faqs`(질문, 답, 순서, 공개 여부, 고친 때, 고친 관리자). `inquiries` 에는 처리 칸 넷이 더해졌다: 상태(`NEW`·`IN_PROGRESS`·`DONE`, 기본 `NEW` — V23 이 `ANSWERED` 를 더했다)·관리자 메모·처리한 관리자·처리한 때. 관리자 테이블은 `accounts`·`sessions` 와 아무 관계가 없고, 관리자는 지우지 않는다(끄기만 한다).
- 감사 기록(V13, 결정 015): `audit_logs`(일어난 때, 행위자 종류 `ADMIN`·`USER`·`SYSTEM`, 행위자 id — 계정 id 의 글자, 행위자 표시 — 관리자는 그때의 로그인 아이디·사용자는 `ownerRef`, 행동 코드, 대상 종류·id, 결과 `SUCCESS`·`FAILURE`, 덧붙임 `detail` — 코드 값만 담는 작은 JSON). **계정을 가리키는 외래 키가 없다: 계정을 지워도 이 행들은 남는다**(위 줄의 "모두 `on delete cascade`" 의 유일한 예외). 그래서 출생 정보·이름·이메일·사용자가 쓴 글·비밀번호·토큰·주소는 어느 칸에도 없다. 넣기만 하고, 일어난 지 365일이 지난 행은 날마다 한 번 지운다(결정 043 의 8 — 계정을 지운 사람의 행도 1년 뒤에는 없다).
- 앱 설정(V14, 결정 016): `app_configs`(플랫폼 `ios`·`android` 가 기본 키 — 마이그레이션이 넣은 두 행뿐이다, 최소 버전, 최신 버전 — V24 · 결정 040 · null 허용, 스토어 주소, 안내 글, `block_now` — 최소 버전이 있어야 켤 수 있다(check), 고친 때, 고친 관리자). 점검(V15, 결정 017): `app_maintenance`(행 하나 — 켜짐 여부, 안내 글, 고친 때, 고친 관리자 · V28, 결정 043 의 7: 꺼진 상태에서 켠 때 `started_at` · 켠 관리자 `started_by` — 끄면 null, 켜진 채로 올라간 행은 마지막으로 고친 때 · 관리자로 채웠다).
- 직접 입력한 궁합 상대의 출생 정보는 저장하지 않는다. 계산한 뒤 버리고 글만 남긴다.
- 랭킹은 `synastries` 의 점수로 만든다. 따로 저장하지 않는다.
- `shares`(V10, 결정 012 · V31 이 카드 종류에 공유 v2 넷을 더했다 — 결정 036): 공유 식별자(기본 키, 무작위 128비트), 소유자(계정을 지우면 함께 지워진다), 카드 종류, 카드(공개 조회가 그대로 주는 JSON — 공개 필드뿐이다), 카드의 해시(같은 소유자의 같은 내용은 한 행), 만든 때. 랭킹 카드에는 앞의 3명만, 이름을 가렸으면 이니셜만 들어 있다. 다음 변곡점 카드에는 날짜가 아니라 해와 계절이 들어 있다.
- 지금의 궁합 테이블(직접 입력, V7. 위 줄의 `synastries` 설명보다 이쪽이 맞다): `synastries`(소유자, 부를 이름, 관계 유형, 나 기준 점수, 한 줄 요약, 키워드, 계산할 때의 내 차트 해시) · `synastry_reports`(궁합마다 한 행, 상태 `WRITING`·`DONE`·`FAILED`, 본문은 `DONE` 일 때만, 생성을 시작한 때, 살아 있다는 표시를 마지막으로 적은 때 `active_at` — V9). 초대로 연결된 두 참여자는 초대 때 더한다. 공유 식별자는 `synastries` 에 두지 않는다(`shares`).
- 직접 입력한 상대의 행성 황경·별자리와 두 차트 사이의 각도 목록도 저장하지 않는다. 이유: 내 차트와 겹쳐 보면 상대의 생일을 좁힐 수 있다(결정 010).

## 글 생성 규칙
- 프롬프트는 리소스 파일로 둔다. 본문에 점성술 용어를 쓰지 않고, 문단마다 근거(차트 요소)를 별도 필드로 받는다. 건강·투자·미래 사건을 단정하지 않는다.
- 근거 대조: AI 가 돌려준 `basis` 의 차트 요소가 서버가 계산한 차트에 없으면 그 응답을 버리고 다시 생성한다(상한 2회). 이유: 없는 요소를 근거로 든 글은 신뢰를 깬다. 요약 해석(결정 031)은 낱말 대신 "근거 후보" 의 id 를 고르게 한다: 구조 지정의 enum 이 그 차트의 후보 id 뿐이고, 서버가 다시 후보에 있는지(`BASIS_NOT_IN_CHART`)와 문단마다 1~2개 · 같은 id 가 둘이 아닌지(`MALFORMED`) 본다.
- 같은 소유자·차트·종류의 글은 한 번만 생성한다. 실패·빈 응답·구조가 틀린 응답은 저장하지 않는다.
- 생성 호출부는 인터페이스 하나(`generation.TextGenerator`) 뒤에 둔다. 이유: 공급자(지금은 Claude, 결정 006)와 모델을 바꿀 수 있어야 한다. 결정 006 이 말하는 `reading.ReadingGenerator`·`ClaudeReadingGenerator` 가 리포트도 쓰게 되면서 이 이름으로 옮겨졌다.
- 프롬프트에는 차트 요소(천체·별자리·하우스·각도 관계)만 넣는다. 출생 날짜·시각·장소와 도수는 넣지 않는다. 시각을 모르면 하우스·상승점·중천·행운점·버텍스를 넣지 않고, 글에서도 쓰지 않게 한다.
- 요약 해석의 모양(v2, 결정 030 · 031): 시각을 알면 문단 5개(중심 → 마음 → 첫인상과 지향 → 힘이 몰린 곳 → 과제), 모르면 "첫인상과 지향"이 빠져 4개. 강점 3개, 보완점 2개. 문단마다 근거 후보 id 1~2개(지시문이 문단마다 먼저 고를 후보를 적는다 — 중심 `P-SUN`, 마음 `P-MOON`, 첫인상 `P-ASC` · `P-MC`, 힘이 몰린 곳 `C-n` 또는 그곳의 `P-`, 과제 부딪히는 쌍의 `A-` 또는 `P-SATURN`). 개수가 다르거나 빈 글이 있으면 버린다. 저장할 때 id 를 `BasisItem` 으로 바꿔 `reason` 으로 둔다.
- 지시문은 공통 글쓰기 규칙(`prompts/writing-rules.md`: 말투·용어 금지·단정 금지·근거) 뒤에 글마다의 지시문을 붙여 만든다. 요약 해석: `reading-summary*.md`. 나 탭 풀이: `me-report.md` + 영역별 `me-report-<영역>.md` + 시각을 모르면 `report-time-unknown.md`. 시기 풀이: `timing-report.md` + 시각을 모르면 `report-time-unknown.md`. 그날의 하늘: `day-sky.md` + 시각을 모르면 `report-time-unknown.md`. 리포트: `report.md` + 영역별 `report-<영역>.md` + 시각을 모르면 `report-time-unknown.md`. 시기: `report-timing.md`.
- 리포트의 모양과 생성: 영역(성격·관계·일·감정)마다 모델을 따로 부른다(네 호출을 한꺼번에). 영역마다 제목 하나와 문단 4~6개, 문단마다 근거. 틀린 영역(빈 글·문단 수·근거 없는 문단·차트에 없는 근거)은 그 영역만 다시 생성한다(영역마다 다시 2회까지). 한 영역이라도 끝내 못 만들거나 호출이 실패하면 리포트 전체가 `FAILED` 이고 아무것도 저장하지 않는다. 관계 영역은 "나는 사람을 어떻게 대하는 사람인가"(친구·가족·연인 전반)이고 연애 조언이 아니다.
- 나 탭 풀이의 모양과 생성(결정 030 · 031 · 038): 영역(성격 · 연애 · 관계 · 일 · 감정)마다 모델을 따로 부른다(다섯 호출을 한꺼번에, 리포트의 일꾼을 같이 쓴다). 차트 글 끝에 "[근거 후보]"(요약 해석과 같다)를 주고, 영역마다 제목 · 키워드 3 · 문단 4(연애는 문단 대신 실천 3 + 영역의 근거) · 영역별 칸을 구조 지정으로 받는다. 근거는 그 차트의 후보 id enum 이다. 버리고 다시 생성하는 것: 빈 글 · 개수(키워드 3 겹침 없음, 문단 4, 한눈에 보기 2 · 2, 실천 3) · 근거가 1~2개가 아니거나 같은 id 가 둘(`MALFORMED`), 후보에 없는 id(`BASIS_NOT_IN_CHART`), 어느 칸에든 숫자 · 퍼센트(`HAS_NUMBER` — 일 방식의 수치는 서버만 말한다), 연애 실천 제목이 "기" 로 끝나지 않거나 어느 칸에든 단정 · 운명 · 앞일의 끝맺음(`NOT_ADVICE_FORM`, `generation.adviceProblem`). 틀린 영역만 다시 생성하고(영역마다 다시 2회까지), 한 영역이라도 끝내 못 만들거나 호출이 실패하면 풀이 전체가 `FAILED` 이고 아무것도 저장하지 않는다. 저장할 때 id 를 `Reason`(`BIRTH`)으로 바꾸고 별(`starsOf`)을 만들고 일 영역에 `workStyle` 을 넣는다 — 모델에게 일 방식의 수치는 주지 않는다. 일꾼 · 살아 있다는 표시 · 모델을 부르기 전마다 그 행이 아직 자기 생성의 것인지 보는 규칙은 아래 "리포트의 글" 과 같다(코드는 시기 풀이와 같이 쓰는 `DetailReportService` · `DetailReportRepository`). 다만 끝내기(본문 만들기 · 저장)에서 오류가 나면 2분 기다리지 않고 그 행을 바로 `FAILED` 로 돌린다. 로그는 `event=me_report_*` 이름 · 영역 · 사유 코드 · 시도 횟수만.
- 시기 풀이의 글(결정 033): 마디 · 구간을 계산하고(평생 약 0.3초, 자리를 잡기 전에) 모델에게 맡길 줄(마디 넷 + 그날에 걸쳐 있거나 10년 안에 시작하는 구간 30까지)을 순서대로 9줄씩 묶어 약 4번을 한꺼번에 부른다(리포트의 일꾼). 묶음마다 차트 글 아래 "[시기 목록]"(줄마다 id · 시작 해~끝 해 · 종류 · 무엇이 무엇에 닿는지 · 영역, 구간 줄 끝에는 고정된 `이름:` — 표의 제목)을 주고 `milestones[{ id, fromYear, toYear, title, body }]` · `intervals[{ id, fromYear, toYear, summary, body, detail{ changes, howTo, after }, practices[] }]` 를 받는다(구간에는 제목 칸이 없다). 버리고 그 묶음만 다시 생성하는 것(2회까지): 줄이 빠지거나 더해지거나 겹침 · 빈 글 · 실천이 3개가 아님(`MALFORMED`), 옮겨 적은 해가 계산값과 다르거나 글 안에 그 줄의 기간 밖의 해(`YEAR_MISMATCH`), 실천 제목이 "기" 로 끝나지 않거나 어느 글에든 단정 · 운명 · 앞일의 끝맺음(`NOT_ADVICE_FORM`, `generation.adviceProblem`). 한 묶음이라도 끝내 못 만들거나 호출이 실패하면 시기 풀이 전체가 `FAILED` 이고 저장하지 않는다. 저장할 때 구간의 제목은 표 그대로 두고 맡긴 구간에 칸과 `reason`(`NOW`, 계산한 구간에서)을 붙인다. 로그는 `event=timing_report_*` 이름 · 묶음(`BATCH1`…) · 사유 코드 · 시도 횟수만. 옛 리포트의 시기 글(`report-timing.md`)은 바꾸지 않는다(계획 S⑦ 까지).
- 그날의 하늘의 글(결정 034): 모델을 한 번 부른다. 차트 글 아래에 그날의 재료 "[그날의 통과]"(1° 안 다섯 별의 통과, 세기 순 다섯 줄까지 — "지나가는 수성 – 나의 태양 · 서로 도움" 꼴, 날짜 · 도수 · 오차 없음) · "[이유]"(서버가 정한 근거 한 줄) · "[칩]"(사람 · 일 · 마음) · "[제목]"(눈여겨볼 날이면 그 문구, 아니면 "정해지지 않음")을 주고 `{ title?, flow{ people, work, mind }, practices[] }` 를 받는다 — 제목이 정해진 날은 구조에 `title` 칸이 없다. 버리고 다시 생성하는 것(2회까지, 그래도 틀리면 502 이고 저장하지 않는다): 칸이 빠지거나 타입이 틀림(`UNUSABLE_RESPONSE`), 빈 글 · 실천이 3개가 아님(`MALFORMED`), 어느 글에든 숫자(`HAS_NUMBER`), 실천 제목이 "기" 로 끝나지 않거나 어느 글에든 단정 · 운명 · 앞일의 끝맺음(`NOT_ADVICE_FORM`, `generation.adviceProblem`), 어느 글에든 "건강" · "질병" · "병원" · "투자" · "주식" · "코인"(`FORBIDDEN_TOPIC`). 생성 호출 자체가 실패하면 다시 부르지 않는다. 저장할 때 근거는 서버가 정한 통과를 `Reason`(`DAY`)으로 둔다. 로그는 `event=day_sky_*` 이름 · 사유 코드 · 시도 횟수만(날짜 · 글 · 차트는 남기지 않는다).
- 방향성 제시의 모양과 검증: 지시문은 공통 규칙 + `direction.md` + 주제별 `direction-<주제>.md`(`direction-love.md`·`direction-future.md`·`direction-synastry.md`) + 시각을 모르면 `direction-time-unknown.md`. 한 줄 제목과 항목 3개, 항목마다 근거. 요약 해석과 같은 검증(빈 글·개수·근거 없는 항목·차트에 없는 근거)에 더해 조언형인지 글자로 본다: 항목 제목이 앞뒤 공백을 뗀 뒤 "기"로 끝나지 않거나(결정 037 — "보기" 도 통과한다), 어느 글에든 "반드시"·"틀림없이"·"운명", 앞일을 말하는 끝맺음(ㄹ 받침 뒤의 "거예요"·"거에요"·"겁니다"·"것입니다"·"것이에요"), "~게 됩니다"·"~게 돼요" 가 있으면 버리고 다시 생성한다(다시 2회까지, 그래도 틀리면 502). 규칙의 단일 출처는 `generation/Advice.kt` 의 `adviceProblem`(실천 한 줄을 쓰는 나 탭 풀이 · 시기 풀이 · 그날의 하늘도 같이 쓴다. `direction` 은 어느 글을 보는지만 정한다). 공통 글쓰기 규칙(`writing-rules.md`)이 "실천은 '~기' 로 끝나는 한 줄" 을 알린다. 이성 주제는 "연애에서 해 볼 만한 것"(실천)이고 리포트 관계 영역(성향 설명)과 다르다.
- 방향성 제시의 미래 주제(결정 011): 차트 글 아래에 "[지금의 시기]"(줄마다 `지금 지나는 중`·`다음에 오는 것` | 종류 | 무엇이 무엇에 닿는지 | 영역)를 준다. 시기는 요청 때 `chart` 로 계산하고(오늘을 덮는 저장본이 있으면 계산하지 않는다), 줄에 해·날짜·나이를 넣지 않는다. 모델이 받지 않은 해를 지어내지 못하게 글(제목·항목 제목·본문)에 숫자가 하나라도 있으면 버리고 다시 생성한다(`HAS_NUMBER`). 근거 말은 이성과 같다.
- 방향성 제시의 궁합 주제(결정 011): 지시문은 공통 규칙 + `direction.md` + `synastry.md`(두 사람 글의 규칙) + `direction-synastry.md`. 재료는 궁합 글과 같다(관계 유형·서버가 다시 계산한 점수·연결 목록 + "근거로 쓸 수 있는 말" = 내 쪽 요소). 부를 이름·출생 정보·도수는 주지 않는다. 근거가 내 쪽 요소의 말이 아니거나 글에 숫자가 있으면(`HAS_NUMBER` — 점수는 서버만 말한다) 버리고 다시 생성한다. 조언형·개수 검증은 다른 주제와 같다. 내 시각을 모르면 재료와 근거 말에서 상승점이 빠진다.
- 시기의 글(결정 008): 지시문은 공통 규칙 + `report-timing.md`(+ 시각을 모르면 `report-time-unknown.md`). 차트 글 아래에 "[시기 목록]"(줄마다 id · 시작 해~끝 해 · 종류 · 무엇이 무엇에 닿는지 · 영역)을 주고, 줄마다 `{ id, fromYear, toYear, title, body }` 를 받는다. 모델은 해를 계산하지 않고 옮겨 적기만 한다. 줄에는 해만 넣는다(달·날·나이·도수 없음). 옮겨 적은 해가 계산값과 다르거나 글 안에 그 줄의 기간 밖의 해가 나오면(`YEAR_MISMATCH`), 줄이 빠지거나 더해지거나 글이 비면(`MALFORMED`) 그 응답을 버리고 다시 생성한다(2회까지). 끝내 못 받으면 리포트 전체가 `FAILED` 다. `basis` 는 없다.
- 리포트의 글은 요청 스레드가 아니라 정해진 수의 일꾼(`byeolmyeong.report.worker-threads`·`queue-capacity`)이 쓴다. 일 하나는 한 영역의 글 또는 시기(타임라인 계산 + 글)다. 서버는 자기가 맡은 생성을 기억해 두고 `byeolmyeong.report.heartbeat-seconds`(15초)마다 그 행들의 `active_at` 을 다시 적는다(주기 작업, `@EnableScheduling`). 서버가 꺼지면 더 적히지 않으므로 2분 뒤 그 행은 멈춘 것으로 보인다. 서버를 닫을 때(배포에서 옛 색을 내릴 때, 결정 046)는 새 요청을 그만 받은 뒤 일꾼이 맡은 일을 줄에서 기다리는 것까지 끝까지 쓰고(최대 `byeolmyeong.report.shutdown-wait-seconds` 180초, `ReportExecutorDrain`), 그동안에도 표시를 적는다. 일은 모델을 부르기 전마다(줄에서 나온 직후, 답이 틀려 다시 부르기 전) 그 행이 아직 자기 생성의 것인지(`WRITING` 이고 `started_at` 이 같은지) 보고, 아니면 부르지 않고 그만둔다 — 다른 생성이 자리를 잡았거나, 줄이 차서 실패로 끝났거나, 계정이 지워진 경우다. 끝낼 때도 같은 조건으로만 저장한다. 로그에는 이벤트 이름·영역·사유 코드·시도 횟수·토큰 수만 남긴다.
- 궁합 글: 지시문은 `writing-rules.md` + `synastry.md`(두 사람 글의 규칙) + 글마다의 파일(`synastry-summary.md`). 모델에게 주는 것은 관계 유형, 서버가 계산한 점수(60 미만이면 "다른 결의 사이" 표시), 두 차트 사이의 연결("나의 금성(황소) – 상대의 화성(염소): 결", 강한 것부터)뿐이다. 부를 이름·출생 정보·도수는 주지 않는다. 키워드 3개(겹치지 않게)와 한 줄 요약을 받고, 개수·빈 글·길이가 틀리거나 글에 숫자가 있으면 버리고 다시 생성한다(다시 2회까지, 끝내 못 만들면 502 이고 저장하지 않는다). 점수는 응답 구조에 칸이 없다.
- 궁합 리포트의 글: 영역(끌림·대화·갈등·오래가기)마다 모델을 따로 부른다. 지시문은 `writing-rules.md` + `synastry.md` + `synastry-report.md` + `synastry-report-<영역>.md`, 재료는 요약과 같은 연결 목록에 "근거로 쓸 수 있는 말"(내 쪽 요소)을 붙인 것이다. 영역마다 제목 하나와 문단 3~4개. 틀린 영역(빈 글·문단 수·근거 없는 문단·내 쪽 요소가 아닌 근거·글에 적힌 점수나 퍼센트)은 그 영역만 다시 생성한다(다시 2회까지). 한 영역이라도 끝내 못 만들면 `FAILED` 이고 아무것도 저장하지 않는다. 뒤에서 일하는 스레드는 심층 리포트의 일꾼(`reportExecutor`, `byeolmyeong.report.*`)을 같이 쓴다 — 동시에 나가는 모델 호출 수의 상한이 하나다. 살아 있다는 표시를 적는 주기(`byeolmyeong.report.heartbeat-seconds`)와 멈춤의 기준(2분), 모델을 부르기 전마다 그 행이 아직 자기 생성의 것인지 보고 아니면 그만두는 것도 심층 리포트와 같고, 코드는 `synastry` 안에 따로 있다(기능 패키지는 서로 의존하지 않는다).
- 근거 후보(결정 031, `generation/Basis.kt` 의 `basisCandidates` 가 단일 출처, 순서 고정): 차트 글 끝 "[근거 후보]" 에 한 줄씩 `id: 설명`(설명의 점성술 용어는 모델만 읽는다)으로 준다. ① 놓인 자리 `P-SUN` … `P-PLUTO` + 시각을 알면 `P-ASC` · `P-MC` ② 하우스 모임 `C-5` — 시각을 알 때 천체 3개 이상인 하우스 ③ 각도 `A-SATURN-SUN-SQUARE` — 천체 10개 사이(`chart.aspects`) + 시각을 알면 해 · 달과 상승점 · 중천의 합 · 충 · 사분, 정확한 것부터 ④ 다스리는 별 `R-SUN` · `R-MOON` · 시각을 알면 `R-ASC` — 다스리는 별이 그 별 자신이면 없다. 지점은 후보가 아니다. 공통 글쓰기 규칙은 "[근거 후보]" 가 있으면 id 를, 없으면 "[근거로 쓸 수 있는 말]" 의 낱말을 고르게 한다(리포트 · 방향성 제시 · 궁합 글은 아직 낱말).
- 근거에 쓸 수 있는 말(`generation/Basis.kt` 가 단일 출처, 글자까지 같아야 한다 — 리포트 · 방향성 제시 · 궁합 글의 근거와 요약 해석의 옛 칸 `basis[]`): 천체 이름(태양·달·수성·금성·화성·목성·토성·천왕성·해왕성·명왕성), 지점 이름(북쪽 교점·릴리스, 시각을 알면 행운점·버텍스), 시각을 알면 `상승점`·`중천`, 그 차트에서 천체·지점·상승점·중천이 하나라도 놓인 별자리(양·황소·…·물고기, "자리"를 붙이지 않는다), 천체가 하나라도 놓인 하우스(`N하우스`, 시각을 알 때만).

## 요청 속도 제한 (남용 방지, 서버에서만 판정한다)
- 리포트·궁합·방향성 제시에 기능 횟수 제한은 없다(앱 결정 011). 같은 차트·같은 두 사람의 글은 한 번만 만든다(방향성 제시의 미래 주제는 지금의 시기가 같은 동안 한 번, 궁합 주제는 궁합마다 한 번).
- 계정·주소(IP)별 분당 요청 수를 제한한다. 한도는 `application.properties` 의 `byeolmyeong.rate-limit.*`(분당, 서버 메모리). 지금 거는 곳: `POST /charts`·`GET /places`·`POST /synastry/direct`(주소별, 요청마다 모델 호출이 나간다)·`GET /faqs`(주소별, `faqs-per-minute` 120)·`GET /app-config`(주소별, `app-config-per-minute` 120)·`GET /shared/{shareId}`(주소별 `shared-per-minute`, 식별자를 마구 넣어 보는 것을 늦춘다)·`POST /auth/login` + `POST /auth/signup` + `POST /auth/nickname-check`(합쳐 주소별 `auth-per-minute` 30 — 카카오 웹 코드는 확인 전에 카카오를 부른다, 결정 021). 주소는 연결의 상대 주소(`remoteAddr`)라 프록시 뒤에서는 전달 헤더 설정이 있어야 주소별이 된다 — 배포 VM 은 Caddy 뒤라 `SERVER_FORWARD_HEADERS_STRATEGY=native` 를 준다(`deploy/compose.yaml`, 결정 020).
- 관리자 로그인(`POST /admin/auth/login`)은 10분에 주소별 `byeolmyeong.rate-limit.admin-login-per-ip`(20)번까지다. 아이디별 제한(잠금)은 없다(결정 043 의 5). `admin.AdminAuthService` 가 같은 `web.RateLimiter` 를 쓴다(결정 014).
- 계정별 제한은 넷이다. ⓪ `GET /timing/month`: 소유자 id 별로 분당 `byeolmyeong.rate-limit.month-per-minute`(60)번, `sky.MonthSkyService` 가 `web.RateLimiter` 를 쓴다(결정 032 — 모델을 부르지 않아 생성 한도와 따로). ⓪-1 `POST /entitlements/confirm`: 소유자 id 별로 분당 `byeolmyeong.rate-limit.entitlement-confirm-per-minute`(6)번, 넘으면 RevenueCat 을 묻지 않고 429 `RATE_LIMITED` — `report.EntitlementService` 가 `web.RateLimiter` 를 쓴다(결정 056 의 7, 부를 때마다 RevenueCat 을 부르므로). ① `POST /inquiries`: 소유자 id 별로 한 시간에 `byeolmyeong.rate-limit.inquiries-per-hour`(5)건, 같은 `web.RateLimiter`(고정 창, 서버 메모리)를 `inquiry.InquiryService` 가 쓴다(결정 013). ② 생성 기능(결정 022): 모델을 부르는 `POST /readings`·`POST /me-report`·`POST /timing-report`·`POST /day-sky`·`POST /reports`·`POST /directions`·`POST /synastry/direct`·`POST /synastry/{id}/report` 를 한 계정이 합쳐서 분당 `generation-per-minute`(5)·시간당 `generation-per-hour`(30)번까지, 넘으면 429 `RATE_LIMITED`. 모델을 실제로 부르는 요청만 센다(결정 023): 각 서비스가 저장본이 없음을 확인하고 생성을 시작하기 직전에 `web.GenerationQuota` 로 소유자 id 를 한 번 센다(재시도·여러 영역도 한 번). 저장본을 돌려준 요청·404 는 세지 않는다. 나 탭 풀이·시기 풀이·리포트·궁합 리포트는 자리를 잡은 뒤 세고, 넘으면 그 자리를 실패로 돌린다. 기능 횟수 제한이 아니라 남용을 막는 속도 제한이다(결정 003).
- 광고 시청 판정은 1.1.0 에 더한다(앱 결정 015).

## 외부 시스템
| 시스템 | 용도 | 상태 |
|---|---|---|
| PostgreSQL | 저장 | 로컬은 컨테이너. 스테이징 · 운영은 오라클 VM 한 대의 Compose(결정 020) — 서버는 `api-blue` · `api-green` 중 하나가 받고 배포는 끊김 없이 색을 바꾼다(결정 046, docs/deploy.md) |
| Claude API | 글 생성 | 키는 환경 변수 `ANTHROPIC_API_KEY`. 모델은 설정 `byeolmyeong.claude.model`(결정 006) |
| 구글·애플·카카오 로그인 | ID 토큰 확인(공급자 JWKS, RS256 — `auth.ProviderTokenVerifier`) · 카카오 웹 코드 교환(`auth.KakaoOAuth`) · 애플 토큰 교환 · 철회 · 서버 알림(`auth.AppleSignIn`, `APPLE_TEAM_ID`·`APPLE_KEY_ID`·`APPLE_PRIVATE_KEY`) | 대상(aud)은 환경 변수 `GOOGLE_CLIENT_IDS`·`GOOGLE_WEB_CLIENT_ID`(결정 047)·`GOOGLE_IOS_CLIENT_IDS`·`GOOGLE_ANDROID_CLIENT_IDS`(로그 이름표만)·`APPLE_CLIENT_IDS`·`KAKAO_REST_API_KEY`·`KAKAO_NATIVE_APP_KEY`. 발급자·키 주소는 결정 021 "공급자 값". 만 14세는 출생일로 서버가 판정(결정 021). [확인 필요] 클라이언트 등록(소유자) |
| RevenueCat | 자세한 풀이를 산 계정 확인(결정 056 의 4 — 받기에서 표에 없을 때 · 결제 직후 확인) | `GET https://api.revenuecat.com/v1/subscribers/{계정 id}`, 비밀 키는 환경 변수 `REVENUECAT_SECRET_KEY`(비면 503). 앱의 RevenueCat 사용자 id = 계정 id. 상품 이름 `byeolmyeong.purchase.revenuecat-entitlement`(기본 `detail`). [확인 필요] RevenueCat 계정 · 스토어 상품 · 키(소유자), 환불 회수(웹훅)는 앱 결정 101 의 [확인 필요]가 닫힌 뒤 |
| Expo 푸시 서비스 | 별명 지음 · 풀이 완성 · 문의 답변 · 오늘의 하늘 · 광고 푸시(결정 042 — 모두 푸시 수신 동의가 있을 때만, 광고는 마케팅 동의도) | `POST https://exp.host/--/api/v2/push/send`(100건씩), 환경 변수 `EXPO_ACCESS_TOKEN`(비면 보내지 않고 로그만). 끄기 `byeolmyeong.push.enabled` · 오늘의 하늘 아침 일 `byeolmyeong.push.today-sky-enabled`. `DeviceNotRegistered` 면 토큰을 지운다 · 정지 계정에는 보내지 않는다 |

## 관련
- 결정: docs/decisions/
- 계획: docs/plans/2026-11-10-api-mvp.md
