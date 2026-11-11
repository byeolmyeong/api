package com.byeolmyeong.api.generation

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
 * SDK 의 자동 재시도는 끈다: 다시 생성할지는 부르는 서비스가 정하고, 사용자는 실패 화면에서 다시 시도할 수 있다.
 */
@Component
class ClaudeTextGenerator(
	@Value("\${byeolmyeong.claude.api-key}") apiKey: String,
	@Value("\${byeolmyeong.claude.base-url}") baseUrl: String,
	@Value("\${byeolmyeong.claude.model}") private val model: String,
	@Value("\${byeolmyeong.claude.effort}") private val effort: String,
	@Value("\${byeolmyeong.claude.timeout-seconds}") timeoutSeconds: Long,
	@Value("\${byeolmyeong.claude.refusal-fallback}") private val refusalFallback: Boolean,
) : TextGenerator {

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

	override fun generate(request: GenerationRequest): JsonNode? {
		val client = client ?: throw GenerationException("NO_API_KEY")
		val message = try {
			client.messages().create(params(request))
		} catch (e: AnthropicServiceException) {
			throw GenerationException("HTTP_${e.statusCode()}")
		} catch (e: AnthropicIoException) {
			throw GenerationException("IO")
		} catch (e: AnthropicException) {
			throw GenerationException("SDK")
		}
		val stopReason = message.stopReason().orElse(null)
		val usage = message.usage()
		log.info(
			"event=generation_model_call purpose={} model={} stop_reason={} input_tokens={} output_tokens={} thinking_tokens={}",
			request.purpose, model, stopReason, usage.inputTokens(), usage.outputTokens(),
			usage.outputTokensDetails().map { it.thinkingTokens() }.orElse(null),
		)
		// 거절(refusal)·잘림(max_tokens)이면 내용이 구조에 맞지 않을 수 있다. 내용을 읽기 전에 본다.
		if (stopReason != StopReason.END_TURN) return null
		val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
		return parse(text)
	}

	// 생각(thinking)은 이 모델에서 늘 켜져 있어 따로 보내지 않고, 깊이는 effort 로 정한다. 표본 추출 값(temperature 등)은 보내지 않는다.
	private fun params(request: GenerationRequest): MessageCreateParams {
		val schema = JsonOutputFormat.Schema.builder()
			.putAllAdditionalProperties(request.schema.mapValues { JsonValue.from(it.value) })
			.build()
		val builder = MessageCreateParams.builder()
			.model(model)
			.maxTokens(MAX_TOKENS)
			.system(request.instructions)
			.addUserMessage(request.userText)
			.outputConfig(
				OutputConfig.builder()
					.effort(OutputConfig.Effort.of(effort))
					.format(JsonOutputFormat.builder().schema(schema).build())
					.build(),
			)
		if (refusalFallback) {
			builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
		}
		return builder.build()
	}

	// 외부 응답이다. JSON 객체일 때만 돌려준다. 필드가 맞는지는 부르는 쪽이 본다.
	private fun parse(text: String): JsonNode? = try {
		mapper.readTree(text)?.takeIf { it.isObject }
	} catch (e: JacksonException) {
		null
	}

	private companion object {
		// 생각에 쓰는 토큰도 이 한도에 든다. 한 번에 받는 글은 2~3천 토큰 안팎이다.
		const val MAX_TOKENS = 16_000L
		const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
	}
}
