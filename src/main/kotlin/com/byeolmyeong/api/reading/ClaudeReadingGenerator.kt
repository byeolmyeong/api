package com.byeolmyeong.api.reading

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * Claude API(Messages)로 글을 만든다. Anthropic SDK 를 아는 유일한 클래스다(결정 006).
 * 응답은 구조를 정한 JSON(`output_config.format`)으로 받는다. 키·프롬프트·글을 로그에 남기지 않는다.
 * SDK 의 자동 재시도는 끈다: 다시 생성할지는 `ReadingService` 가 정하고, 사용자는 실패 화면에서 다시 시도할 수 있다.
 */
@Component
class ClaudeReadingGenerator(
	@Value("\${byeolmyeong.claude.api-key}") apiKey: String,
	@Value("\${byeolmyeong.claude.base-url}") baseUrl: String,
	@Value("\${byeolmyeong.claude.model}") private val model: String,
	@Value("\${byeolmyeong.claude.effort}") private val effort: String,
	@Value("\${byeolmyeong.claude.timeout-seconds}") timeoutSeconds: Long,
	@Value("\${byeolmyeong.claude.refusal-fallback}") private val refusalFallback: Boolean,
) : ReadingGenerator {

	private val log = LoggerFactory.getLogger(javaClass)
	private val mapper = JsonMapper()

	// 키가 없으면 클라이언트를 만들지 않는다. 서버는 뜨고, 생성 요청만 실패한다.
	private val client: AnthropicClient? = apiKey.takeIf { it.isNotBlank() }?.let {
		AnthropicOkHttpClient.builder()
			.apiKey(it)
			.baseUrl(baseUrl)
			.maxRetries(0)
			.timeout(Duration.ofSeconds(timeoutSeconds))
			.build()
	}

	override fun generate(prompt: ReadingPrompt): ReadingDraft? {
		val client = client ?: throw ReadingGenerationException("NO_API_KEY")
		val message = try {
			client.messages().create(params(prompt))
		} catch (e: AnthropicServiceException) {
			throw ReadingGenerationException("HTTP_${e.statusCode()}")
		} catch (e: AnthropicIoException) {
			throw ReadingGenerationException("IO")
		} catch (e: AnthropicException) {
			throw ReadingGenerationException("SDK")
		}
		val stopReason = message.stopReason().orElse(null)
		val usage = message.usage()
		log.info(
			"event=reading_model_call model={} stop_reason={} input_tokens={} output_tokens={} thinking_tokens={}",
			model, stopReason, usage.inputTokens(), usage.outputTokens(),
			usage.outputTokensDetails().map { it.thinkingTokens() }.orElse(null),
		)
		// 거절(refusal)·잘림(max_tokens)이면 내용이 구조에 맞지 않을 수 있다. 내용을 읽기 전에 본다.
		if (stopReason != StopReason.END_TURN) return null
		val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
		return parse(text)
	}

	// 생각(thinking)은 이 모델에서 늘 켜져 있어 따로 보내지 않고, 깊이는 effort 로 정한다. 표본 추출 값(temperature 등)은 보내지 않는다.
	private fun params(prompt: ReadingPrompt): MessageCreateParams {
		val builder = MessageCreateParams.builder()
			.model(model)
			.maxTokens(MAX_TOKENS)
			.system(prompt.instructions)
			.addUserMessage(prompt.facts)
			.outputConfig(
				OutputConfig.builder()
					.effort(OutputConfig.Effort.of(effort))
					.format(
						JsonOutputFormat.builder()
							.schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(schema(prompt.basisVocabulary)).build())
							.build(),
					)
					.build(),
			)
		if (refusalFallback) {
			builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
		}
		return builder.build()
	}

	// 근거는 차트에 있는 말 중에서만 고르게 한다(enum). 개수 제약은 구조 지정이 받지 않아 지시문과 서버 검증이 맡는다.
	private fun schema(basisVocabulary: List<String>): Map<String, JsonValue> {
		val string = mapOf("type" to "string")
		val strings = mapOf("type" to "array", "items" to string)
		val section = mapOf(
			"type" to "object",
			"properties" to mapOf(
				"title" to string,
				"body" to string,
				"basis" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to basisVocabulary)),
			),
			"required" to listOf("title", "body", "basis"),
			"additionalProperties" to false,
		)
		return mapOf(
			"type" to "object",
			"properties" to mapOf(
				"headline" to string,
				"sections" to mapOf("type" to "array", "items" to section),
				"strengths" to strings,
				"cautions" to strings,
			),
			"required" to listOf("headline", "sections", "strengths", "cautions"),
			"additionalProperties" to false,
		).mapValues { JsonValue.from(it.value) }
	}

	// 외부 응답이다. 필드가 있고 타입이 맞는지 본 뒤에만 글로 만든다. 하나라도 틀리면 null.
	private fun parse(text: String): ReadingDraft? {
		val root = try {
			mapper.readTree(text)
		} catch (e: JacksonException) {
			return null
		}
		val sections = root["sections"]?.takeIf { it.isArray }?.values()?.map { section ->
			ReadingSection(
				title = section["title"].stringOrNull() ?: return null,
				body = section["body"].stringOrNull() ?: return null,
				basis = section["basis"].stringsOrNull() ?: return null,
			)
		}
		return ReadingDraft(
			headline = root["headline"].stringOrNull() ?: return null,
			sections = sections ?: return null,
			strengths = root["strengths"].stringsOrNull() ?: return null,
			cautions = root["cautions"].stringsOrNull() ?: return null,
		)
	}

	private fun JsonNode?.stringOrNull(): String? = this?.takeIf { it.isString }?.asString()

	private fun JsonNode?.stringsOrNull(): List<String>? =
		this?.takeIf { it.isArray }?.values()?.map { it.stringOrNull() ?: return null }

	private companion object {
		// 생각에 쓰는 토큰도 이 한도에 든다. 글은 2천 토큰 안팎이다.
		const val MAX_TOKENS = 16_000L
		const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
	}
}
