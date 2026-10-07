package com.veritrade.contracts.model;

/** Overall risk level of a filing, declared from lowest to highest. NONE means no findings. */
public enum RiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
