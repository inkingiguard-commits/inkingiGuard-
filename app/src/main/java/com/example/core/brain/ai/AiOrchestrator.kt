package com.example.core.brain.ai

enum class AiProviderType {
    GEMINI,
    CLAUDE,
    LLAMA
}

data class AiAnalysisRequest(
    val incidentId: String?,
    val deviceId: String,
    val riskScore: Int,
    val anomalousFactors: List<String>,
    val systemTelemetrySummary: String,
    val requestedProvider: AiProviderType = AiProviderType.GEMINI
)

data class AiAnalysisResponse(
    val providerType: AiProviderType,
    val executiveSummary: String,
    val threatHypothesis: String,
    val recommendedRemediation: String,
    val isExternalModelConnected: Boolean = false,
    val advisoryDisclaimer: String = "AI advisory output. Authoritative security governance maintained by deterministic policy engine."
)

/**
 * Contract for individual AI model provider adapters.
 * Future phases will connect live remote SDKs/APIs behind these adapters.
 */
interface AiProviderAdapter {
    val providerType: AiProviderType
    val isExternalConnectionConfigured: Boolean
    suspend fun analyze(request: AiAnalysisRequest): AiAnalysisResponse
}

class GeminiProviderAdapter(
    override val isExternalConnectionConfigured: Boolean = false
) : AiProviderAdapter {
    override val providerType: AiProviderType = AiProviderType.GEMINI

    override suspend fun analyze(request: AiAnalysisRequest): AiAnalysisResponse {
        val summary = "Gemini Security Engine Adapter: Evaluated ${request.anomalousFactors.size} threat vectors."
        val hypothesis = if (request.riskScore >= 70) {
            "High confidence telemetry divergence suggests device relocation without subscriber presence."
        } else {
            "Telemetry consistent with handling variation or transient radio degradation."
        }
        val remediation = if (request.riskScore >= 70) {
            "Prompt owner verification via biometric or PIN. Prepare recovery beacon."
        } else {
            "Maintain current vigilance profile. No immediate escalation indicated."
        }

        return AiAnalysisResponse(
            providerType = providerType,
            executiveSummary = summary,
            threatHypothesis = hypothesis,
            recommendedRemediation = remediation,
            isExternalModelConnected = isExternalConnectionConfigured
        )
    }
}

class ClaudeProviderAdapter(
    override val isExternalConnectionConfigured: Boolean = false
) : AiProviderAdapter {
    override val providerType: AiProviderType = AiProviderType.CLAUDE

    override suspend fun analyze(request: AiAnalysisRequest): AiAnalysisResponse {
        return AiAnalysisResponse(
            providerType = providerType,
            executiveSummary = "Claude Analytical Adapter: Structured correlation of device ${request.deviceId} signals.",
            threatHypothesis = "Anomalies: ${request.anomalousFactors.joinToString("; ")}.",
            recommendedRemediation = "Verify physical custody. Audit SIM presence.",
            isExternalModelConnected = isExternalConnectionConfigured
        )
    }
}

class LlamaProviderAdapter(
    override val isExternalConnectionConfigured: Boolean = false
) : AiProviderAdapter {
    override val providerType: AiProviderType = AiProviderType.LLAMA

    override suspend fun analyze(request: AiAnalysisRequest): AiAnalysisResponse {
        return AiAnalysisResponse(
            providerType = providerType,
            executiveSummary = "Llama On-Device Intelligence Adapter: Lightweight local reasoning completed.",
            threatHypothesis = "Telemetry evaluation score: ${request.riskScore}/100.",
            recommendedRemediation = "Maintain standard defensive policy.",
            isExternalModelConnected = isExternalConnectionConfigured
        )
    }
}

/**
 * AI Orchestrator interface.
 * Routes advisory requests to the selected provider adapter.
 */
interface AiOrchestrator {
    fun getRegisteredProviders(): List<AiProviderType>
    suspend fun orchestrateAnalysis(request: AiAnalysisRequest): AiAnalysisResponse
}

class DefaultAiOrchestrator(
    private val adapters: Map<AiProviderType, AiProviderAdapter> = mapOf(
        AiProviderType.GEMINI to GeminiProviderAdapter(),
        AiProviderType.CLAUDE to ClaudeProviderAdapter(),
        AiProviderType.LLAMA to LlamaProviderAdapter()
    )
) : AiOrchestrator {

    override fun getRegisteredProviders(): List<AiProviderType> = adapters.keys.toList()

    override suspend fun orchestrateAnalysis(request: AiAnalysisRequest): AiAnalysisResponse {
        val adapter = adapters[request.requestedProvider] ?: adapters.values.first()
        return adapter.analyze(request)
    }
}
