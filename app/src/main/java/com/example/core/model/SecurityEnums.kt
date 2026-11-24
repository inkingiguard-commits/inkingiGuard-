package com.example.core.model

enum class SecurityState {
    NORMAL,
    WARNING,
    DANGER,
    SUSPECTED_LOST,
    OWNER_CONFIRMED_STOLEN,
    STOLEN_MODE,
    RECOVERY_MODE,
    RECOVERED
}

enum class RiskLevel {
    LOW,      // 0 - 29
    MEDIUM,   // 30 - 69
    HIGH      // 70 - 100
}

enum class CorrelationThreatState {
    NORMAL,
    LOW_RISK,
    SUSPICIOUS,
    HIGH_RISK,
    THEFT_CONFIRMATION_REQUIRED
}

enum class EventSeverity {
    INFO,
    WARNING,
    CRITICAL
}

enum class MovementType {
    STATIONARY,
    NORMAL_HANDHELD,
    SUDDEN_ACCELERATION,
    UNUSUAL_ROTATION,
    PROLONGED_MOVEMENT,
    TRANSITION_TO_MOVING,
    TRANSITION_TO_STATIONARY
}

enum class ConfidenceLevel {
    LOW_CONFIDENCE,
    MEDIUM_CONFIDENCE,
    HIGH_CONFIDENCE
}

enum class IncidentStatus {
    ACTIVE,
    INVESTIGATING,
    CONFIRMED_THEFT,
    RECOVERING,
    RESOLVED,
    CLOSED
}

enum class IncidentSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

enum class CommandType {
    LOCK_DEVICE,
    ACTIVATE_STOLEN_MODE,
    ACTIVATE_RECOVERY_MODE,
    REQUEST_LOCATION,
    TRIGGER_ALARM,
    STOP_ALARM,
    MARK_RECOVERED
}

enum class CommandStatus {
    PENDING,
    AUTHORIZED,
    EXECUTED,
    PENDING_SERVICE_ACTIVATION,
    FAILED,
    REJECTED,
    EXPIRED,
    UNSUPPORTED_ON_DEVICE
}

enum class SyncStatus {
    LOCAL_QUEUED,
    PENDING,
    SYNCING,
    SENT_TO_BACKEND,
    DISPATCH_ACCEPTED,
    DELIVERY_CONFIRMED,
    SYNCED,
    FAILED,
    NOT_CONFIGURED
}

enum class QueuePriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

enum class TamperType {
    ADMIN_DISABLE_ATTEMPT,
    PERMISSION_REVOKED,
    UNEXPECTED_REBOOT,
    SYSTEM_CONFIG_ALTERED,
    BATTERY_CRITICAL_DISCONNECT
}

enum class MonitoringFrequency {
    LOW,       // NORMAL: relaxed battery conservation
    MODERATE,  // WARNING: elevated sensor vigilance
    HIGH,      // DANGER: fast polling
    CRITICAL   // STOLEN_MODE: maximum authorized recovery telemetry
}

enum class UserRole {
    OWNER,
    GUEST,
    TRUSTED_PERSON
}
