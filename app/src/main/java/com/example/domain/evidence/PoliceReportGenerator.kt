package com.example.domain.evidence

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats genuine recorded telemetry into a descriptive, app-generated technical incident report.
 * Explicitly distinguishes available data from unavailable data and states clear legal disclaimers.
 */
object PoliceReportGenerator {

    fun generateReportText(
        evidence: Map<String, Any?>,
        ownerEmail: String = "Non configuré"
    ): String {
        val deviceId = evidence["deviceId"] ?: "ID_INCONNU"
        val lat = evidence["lastKnownLat"] as? Double
        val lng = evidence["lastKnownLng"] as? Double
        val timestamp = evidence["timestamp"] as? Long ?: System.currentTimeMillis()
        val simStatus = evidence["simStatus"] ?: "Non disponible"
        val battery = evidence["battery"] ?: "Non disponible"
        val events = evidence["events"] ?: "[]"
        val syncStatus = evidence["syncStatus"] ?: "LOCAL_SEULEMENT"

        val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm:ss z", Locale.FRENCH).format(Date(timestamp))

        val locationText = if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
            "Coordonnées: Lat $lat, Lng $lng (Lien: https://maps.google.com/?q=$lat,$lng)"
        } else {
            "Localisation: NON DISPONIBLE (GPS désactivé ou absence de signal au moment de la capture)"
        }

        return """
            ===================================================================
            RAPPORT TECHNIQUE D'INCIDENT — INKINGIGUARD AI (GÉNÉRÉ PAR L'APPLICATION)
            AVERTISSEMENT LÉGAL: Ce document constitue un extrait technique d'observations
            enregistrées sur l'appareil. Il ne constitue pas un procès-verbal officiel certifié.
            ===================================================================
            Identifiant du terminal (Node): $deviceId
            Compte propriétaire associé: $ownerEmail
            Horodatage de l'événement: $formattedDate (Epoch: $timestamp)
            État de synchronisation: $syncStatus
            
            [DONNÉES DE LOCALISATION]
            $locationText
            
            [SITUATION MATÉRIELLE]
            État carte SIM: $simStatus
            Niveau de batterie: $battery
            
            [JOURNAL DES ÉVÉNEMENTS ANOMALIES]
            $events
            
            Ce rapport technique est mis à disposition des autorités judiciaires et de police
            sur demande exclusive du propriétaire authentifié du terminal.
            ===================================================================
        """.trimIndent()
    }
}
