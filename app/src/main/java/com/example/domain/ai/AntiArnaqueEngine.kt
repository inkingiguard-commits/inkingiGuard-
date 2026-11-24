package com.example.domain.ai

/**
 * Moteur Anti-Arnaque IA & Apprentissage adaptatif.
 * Détection de phishing, liens malveillants, messages frauduleux et ingénierie sociale.
 */
object AntiArnaqueEngine {

    data class AnalysisResult(
        val isSuspicious: Boolean,
        val riskScore: Int, // 0 - 100
        val verdict: String,
        val detectedIndicators: List<String>,
        val advice: String
    )

    // General social engineering, phishing and credential harvesting patterns (FR + EN)
    private val SCAM_KEYWORDS_FR = listOf(
        "chronopost" to "Usurpation livraison / colis",
        "colissimo" to "Faux avis de passage de livraison",
        "amende" to "Fausse contravention",
        "antai" to "Imitation de service officiel",
        "bloque" to "Menace de suspension ou blocage",
        "compte suspendu" to "Hameçonnage d'identifiants ou compte",
        "cliquez ici" to "Incitation urgente au clic",
        "urgent" to "Pression psychologique d'urgence",
        "gagne" to "Fausse loterie / gain frauduleux",
        "felicitations" to "Félicitations suspectes / fausse récompense",
        "remboursement" to "Faux remboursement officiel",
        "ameli" to "Usurpation Assurance Maladie",
        "carte vitale" to "Hameçonnage Carte Vitale",
        "virement" to "Suspicion d'ingénierie financière",
        "code reçu" to "Tentative de détournement d'OTP / 2FA",
        "mot de passe" to "Demande illégitime d'identifiant secret",
        "code secret" to "Demande illégitime de code PIN",
        "pin secret" to "Tentative d'extorsion de code de sécurité",
        "validez votre solde" to "Hameçonnage bancaire",
        "maman" to "Arnaque à l'urgence familiale",
        "papa" to "Arnaque à l'urgence familiale"
    )

    private val SCAM_KEYWORDS_EN = listOf(
        "delivery" to "Package delivery impersonation",
        "package" to "Fake delivery notification",
        "fine" to "Fake penalty or citation fine",
        "account suspended" to "Credential harvesting or account phishing",
        "account locked" to "Urgent account lock threat",
        "click here" to "Urgent click incentive",
        "urgent" to "High-urgency psychological pressure",
        "winner" to "Fake lottery or prize scam",
        "congratulations" to "Suspicious reward congratulations",
        "refund" to "Fake official refund phishing",
        "wire transfer" to "Financial engineering suspicion",
        "otp" to "2FA / OTP token theft attempt",
        "password" to "Illegitimate password request",
        "pin" to "Illegitimate security PIN request",
        "verify your account" to "Banking credential phishing",
        "family emergency" to "Family emergency impersonation scam"
    )

    private val SUSPICIOUS_URL_PATTERNS = listOf(
        "bit.ly", "tinyurl", "cutt.ly", "is.gd", ".xyz", ".top", ".buzz", ".club", "http://", "t.co", "ow.ly"
    )

    // Mémoire d'apprentissage adaptatif des signalements confirmés par l'utilisateur
    private val learnedScamSignatures = mutableSetOf<String>()

    fun recordConfirmedScam(sampleText: String) {
        val snippet = sampleText.trim().lowercase().take(50)
        if (snippet.isNotBlank()) {
            learnedScamSignatures.add(snippet)
        }
    }

    private fun stripAccents(str: String): String {
        val nfd = java.text.Normalizer.normalize(str, java.text.Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    fun analyzeTextOrUrl(input: String, lang: String = "fr"): AnalysisResult {
        val isEn = lang.equals("en", ignoreCase = true)
        val normalized = stripAccents(input.trim().lowercase())
        if (normalized.isBlank()) {
            return AnalysisResult(
                isSuspicious = false,
                riskScore = 0,
                verdict = if (isEn) "No text provided" else "Aucun texte fourni",
                detectedIndicators = emptyList(),
                advice = if (isEn) "Paste a suspicious message or web link to inspect." else "Collez un message SMS ou un lien web pour analyse."
            )
        }

        val indicators = mutableListOf<String>()
        var score = 5

        // 1. Détection apprentissage local (mémoire Anti-Arnaque adaptative)
        learnedScamSignatures.forEach { sig ->
            if (normalized.contains(sig)) {
                indicators.add(
                    if (isEn) "Matches a previously confirmed scam signature (Adaptive Learning)"
                    else "Correspondance avec une signature d'arnaque confirmée précédemment (Apprentissage)"
                )
                score += 45
            }
        }

        // 2. Détection mots-clés d'ingénierie sociale (FR)
        SCAM_KEYWORDS_FR.forEach { (keyword, desc) ->
            if (normalized.contains(keyword)) {
                indicators.add(if (isEn) "Phishing indicator ('$keyword')" else "$desc (terme détecté : '$keyword')")
                score += 25
            }
        }

        // 3. Détection mots-clés d'ingénierie sociale (EN)
        SCAM_KEYWORDS_EN.forEach { (keyword, desc) ->
            if (normalized.contains(keyword)) {
                indicators.add(if (isEn) "$desc (keyword: '$keyword')" else "$desc (terme détecté : '$keyword')")
                score += 25
            }
        }

        // 4. Détection liens suspects / réducteurs
        SUSPICIOUS_URL_PATTERNS.forEach { pattern ->
            if (normalized.contains(pattern)) {
                indicators.add(
                    if (isEn) "Shortened URL or high-risk domain ('$pattern')"
                    else "Lien court ou domaine à risque élevé ('$pattern')"
                )
                score += 30
            }
        }

        // 5. Détection demandes de transferts d'argent ou coupons prépayés
        if (Regex("(089[0-9]|\\+22|western union|pcs|transcash|cryptomonnaie|crypto|wire transfer|prepaid card)").containsMatchIn(normalized)) {
            indicators.add(
                if (isEn) "Payment requested via prepaid voucher, crypto or premium-rate number"
                else "Demande de paiement par coupons, cryptomonnaie ou numéro surtaxé"
            )
            score += 40
        }

        val finalScore = score.coerceIn(0, 100)
        val isSuspicious = finalScore >= 40

        val verdict = if (isEn) {
            when {
                finalScore >= 75 -> "HIGH-CONFIDENCE SCAM (CRITICAL DANGER)"
                finalScore >= 40 -> "SUSPICIOUS MESSAGE — CAUTION REQUIRED"
                else -> "PROBABLY LEGITIMATE MESSAGE"
            }
        } else {
            when {
                finalScore >= 75 -> "ARNAQUE TRÈS PROBABLE (DANGER CRITIQUE)"
                finalScore >= 40 -> "MESSAGE SUSPECT — VIGILANCE REQUISE"
                else -> "MESSAGE PROBABLEMENT LÉGITIME"
            }
        }

        val advice = if (isEn) {
            when {
                finalScore >= 75 -> "Do not click any suspicious link. Never disclose your security PIN, passwords, or one-time codes (OTP)."
                finalScore >= 40 -> "Verify directly on the official application or website of the service without using this link or number."
                else -> "No major scam indicators detected. Remain alert if the sender is unfamiliar."
            }
        } else {
            when {
                finalScore >= 75 -> "Ne cliquez sur aucun lien suspect. Ne transmettez jamais votre code PIN, vos mots de passe ni vos codes OTP."
                finalScore >= 40 -> "Vérifiez directement sur le site ou l'application officielle du service sans passer par ce lien ou ce numéro."
                else -> "Aucun indicateur d'arnaque majeur détecté. Restez vigilant si l'expéditeur vous est inconnu."
            }
        }

        return AnalysisResult(
            isSuspicious = isSuspicious,
            riskScore = finalScore,
            verdict = verdict,
            detectedIndicators = indicators.distinct(),
            advice = advice
        )
    }
}
