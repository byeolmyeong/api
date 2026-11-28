package com.byeolmyeong.api

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.CompositeArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * docs/architecture.md "의존 규칙" 표를 한 줄씩 테스트로 옮긴다. 표와 이 파일은 같이 고친다.
 * 아직 없는 패키지의 규칙은 비어도 통과한다(allowEmptyShould). 위반이 나면 규칙이 아니라 코드를 고친다.
 */
@AnalyzeClasses(packages = [ArchitectureTest.ROOT], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {

    companion object {
        const val ROOT = "com.byeolmyeong.api"

        private val FEATURES = listOf("profile", "reading", "report", "synastry", "direction", "inquiry", "admin", "faq", "audit", "appconfig", "share", "push", "sky", "notification")
        private val SHARED = listOf("chart", "birth", "auth", "web", "generation")

        /** 기능 사이에 허용되는 유일한 연결: Spring 이벤트 클래스. 이벤트를 늘리면 docs/architecture.md 와 여기를 같이 고친다. */
        private val EVENTS = setOf(
            "$ROOT.report.ReportDone",
            // 나 탭 풀이가 완성됐다(결정 029 의 7) — 알림함(계획 S⑤)이 듣는다
            "$ROOT.report.MeReportDone",
            // 시기 풀이가 완성됐다(결정 029 의 7) — 알림함(계획 S⑤)이 듣는다
            "$ROOT.report.TimingReportDone",
            "$ROOT.synastry.InviteAccepted",
            // 궁합이 지워지면 direction 이 그 궁합의 글을 지운다(결정 019)
            "$ROOT.synastry.SynastryDeleted",
            // 공유 카드에 실을 글을 그 글을 가진 기능에게 묻는다(결정 012)
            "$ROOT.share.ReadingCardRequested",
            "$ROOT.share.ScoreCardRequested",
            "$ROOT.share.RankingCardRequested",
            "$ROOT.share.NextTurnCardRequested",
            // 공유 v2 카드 넷(결정 036) — reading · report · sky 가 답한다
            "$ROOT.share.NicknameCardRequested",
            "$ROOT.share.MeAreaCardRequested",
            "$ROOT.share.DaySkyCardRequested",
            "$ROOT.share.NextBigCardRequested",
            // 관리자가 앱 설정을 고치면 appconfig 가 캐시를 비운다(결정 016)
            "$ROOT.admin.AppConfigChanged",
            // 관리자가 문의에 처음 답했다(결정 039 의 5) — 알림함(계획 S⑤) · 푸시(S⑩)가 듣는다
            "$ROOT.admin.InquiryAnswered",
            // 출생 정보가 바뀌면 reading · report · sky 가 옛 차트의 저장분을 지운다(결정 041) · synastry 가 궁합 점수를 다시 계산한다(결정 053)
            "$ROOT.profile.BirthProfileChanged",
            // 관리자가 실패한 자세한 풀이를 다시 만들게 했다(결정 043 의 1) — report 가 다시 시작한다
            "$ROOT.admin.GenerationRetryRequested",
            // 별명을 처음 지었다 · 궁합 리포트가 완성됐다 — 알림함(결정 035)과 푸시(결정 042)가 듣는다
            "$ROOT.reading.ReadingCreated",
            "$ROOT.synastry.SynastryReportDone",
            // 관리자가 광고 푸시를 보냈다 · push 가 다 보냈다(결정 043 의 6) — push(S⑩)가 보내고 admin 이 내역을 채운다
            "$ROOT.admin.PushEventRequested",
            "$ROOT.admin.PushEventSent",
            // 오늘의 하늘 아침 알림을 보낼 때가 됐다 — push 가 보낸다(결정 042 의 6)
            "$ROOT.sky.TodaySkyDue",
            "$ROOT.sky.TodaySkyDue\$Item",
        )

        /** 외부 공급자 SDK·HTTP 클라이언트. 이것을 쓰는 클래스는 우리 인터페이스 하나 뒤에 있어야 한다. */
        private val EXTERNAL_CLIENTS = arrayOf("com.anthropic..", "com.openai..", "java.net.http..", "org.springframework.web.client..", "org.springframework.web.reactive.function.client..")

        private fun pkg(name: String) = "$ROOT.$name.."

        private fun topLevel(c: JavaClass): JavaClass = generateSequence(c) { it.enclosingClass.orElse(null) }.last()

        @ArchTest
        @JvmField
        val chartDependsOnlyOnStdlibTimeAndAstronomy: ArchRule =
            classes().that().resideInAPackage(pkg("chart"))
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                    pkg("chart"),
                    "kotlin..",
                    "org.jetbrains.annotations..", // Kotlin 컴파일러가 붙이는 널 표시
                    "java.lang..",
                    "java.util..", // Kotlin 표준 컬렉션의 JVM 표현
                    "java.time..",
                    "io.github.cosinekitty.astronomy..",
                )
                .because("계산은 Spring·DB·HTTP·AI 없이 홀로 검증된다")

        @ArchTest
        @JvmField
        val birthDependsOnlyOnChart: ArchRule =
            classes().that().resideInAPackage(pkg("birth"))
                .should().onlyDependOnClassesThat(
                    resideOutsideOfPackage("$ROOT..").or(resideInAnyPackage(pkg("birth"), pkg("chart"))),
                )
                .because("시각 변환도 순수하게 둔다")

        @ArchTest
        @JvmField
        val featuresDoNotDependOnEachOther: ArchRule = CompositeArchRule.of(
            FEATURES.map { feature ->
                noClasses().that().resideInAPackage(pkg(feature))
                    .should().dependOnClassesThat(
                        resideInAnyPackage(*FEATURES.filter { it != feature }.map(::pkg).toTypedArray())
                            .and(DescribedPredicate.describe("is not an event") { it.name !in EVENTS }),
                    )
                    .allowEmptyShould(true)
            },
        ).because("기능 사이 연결은 Spring 이벤트로만 한다")

        @ArchTest
        @JvmField
        val featuresDependOnlyOnSharedPackages: ArchRule =
            classes().that().resideInAnyPackage(*FEATURES.map(::pkg).toTypedArray())
                .should().onlyDependOnClassesThat(
                    resideOutsideOfPackage("$ROOT..")
                        .or(resideInAnyPackage(*(SHARED + FEATURES).map(::pkg).toTypedArray())),
                )
                .allowEmptyShould(true)
                .because("공통으로 쓰는 것(chart·birth·auth·web·generation)만 아래에 둔다")

        @ArchTest
        @JvmField
        val generationDependsOnlyOnChart: ArchRule =
            classes().that().resideInAPackage(pkg("generation"))
                .should().onlyDependOnClassesThat(
                    resideOutsideOfPackage("$ROOT..").or(resideInAnyPackage(pkg("generation"), pkg("chart"))),
                )
                .because("글 생성 공통은 차트만 안다. 누구의 글인지·어디에 저장하는지는 기능이 맡는다")

        @ArchTest
        @JvmField
        val sharedPackagesDoNotDependOnFeatures: ArchRule =
            noClasses().that().resideInAnyPackage(pkg("auth"), pkg("web"), pkg("generation"))
                .should().dependOnClassesThat().resideInAnyPackage(*FEATURES.map(::pkg).toTypedArray())
                .allowEmptyShould(true)
                .because("공통이 기능을 알면 순환이 생긴다")

        @ArchTest
        @JvmField
        val repositoriesAreCalledOnlyByServiceOfSamePackage: ArchRule =
            classes().that().haveSimpleNameEndingWith("Repository")
                .should(beAccessedOnlyBySameRepositoryOrServiceOfSamePackage())
                .allowEmptyShould(true)
                .because("소유자 검사를 서비스 한 곳에서 한다")

        @ArchTest
        @JvmField
        val noPackageCycles: ArchRule =
            slices().matching("$ROOT.(*)..").should().beFreeOfCycles()

        @ArchTest
        @JvmField
        val externalClientsSitBehindOneInterface: ArchRule =
            classes().that().resideInAPackage("$ROOT..")
                .and().areNotInterfaces()
                .and(dependOnAnyPackage(*EXTERNAL_CLIENTS))
                .should(implementProjectInterface())
                .allowEmptyShould(true)
                .because("공급자를 바꿀 수 있게 외부 호출은 인터페이스 하나 뒤에만 둔다")

        @ArchTest
        @JvmField
        val externalClientImplementationsAreUsedOnlyThroughTheirInterface: ArchRule =
            noClasses().that().resideInAPackage("$ROOT..")
                .should().dependOnClassesThat(
                    DescribedPredicate.describe("call external clients directly (use the interface)") { c: JavaClass ->
                        c.packageName.startsWith(ROOT) && !c.isInterface && dependsOnAny(c, *EXTERNAL_CLIENTS)
                    },
                )
                .allowEmptyShould(true)
                .because("공급자를 바꿀 수 있게 구현 클래스가 아니라 인터페이스에 의존한다")

        private fun dependsOnAny(c: JavaClass, vararg packages: String): Boolean {
            val target = resideInAnyPackage(*packages)
            return c.directDependenciesFromSelf.any { target.test(it.targetClass) }
        }

        private fun dependOnAnyPackage(vararg packages: String) =
            DescribedPredicate.describe("depend on ${packages.joinToString()}") { c: JavaClass -> dependsOnAny(c, *packages) }

        private fun implementProjectInterface() =
            object : ArchCondition<JavaClass>("implement an interface of $ROOT") {
                override fun check(item: JavaClass, events: ConditionEvents) {
                    val ok = topLevel(item).allRawInterfaces.any { resideInAPackage("$ROOT..").test(it) }
                    if (!ok) events.add(SimpleConditionEvent.violated(item, "${item.name} 이 외부를 부르지만 $ROOT 의 인터페이스를 구현하지 않는다"))
                }
            }

        private fun beAccessedOnlyBySameRepositoryOrServiceOfSamePackage() =
            object : ArchCondition<JavaClass>("be used only by a *Service of the same package") {
                override fun check(item: JavaClass, events: ConditionEvents) {
                    item.directDependenciesToSelf.forEach { dep ->
                        val origin = topLevel(dep.originClass)
                        val allowed = origin == item ||
                            (origin.packageName == item.packageName && origin.simpleName.endsWith("Service"))
                        if (!allowed) events.add(SimpleConditionEvent.violated(dep, dep.description))
                    }
                }
            }
    }
}
