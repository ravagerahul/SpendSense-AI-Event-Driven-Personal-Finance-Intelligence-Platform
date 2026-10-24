package com.spendsense.anomaly;

/** One rule hit against one transaction. Severity is one of HIGH, MEDIUM, LOW. */
public record Anomaly(String type, String reason, String severity) {

    public static final String UNUSUAL_AMOUNT = "UNUSUAL_AMOUNT";
    public static final String UNKNOWN_MERCHANT = "UNKNOWN_MERCHANT";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String FREQUENCY_SPIKE = "FREQUENCY_SPIKE";

    public static final String HIGH = "HIGH";
    public static final String MEDIUM = "MEDIUM";
    public static final String LOW = "LOW";
}
