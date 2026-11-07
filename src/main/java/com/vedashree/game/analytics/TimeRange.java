package com.vedashree.game.analytics;

/** Chart ranges the dashboard may request. Bucket sizes are fixed constants (never user input). */
public enum TimeRange {
    LAST_24_HOURS("24h", 3_600_000L, 24, "hour"),
    LAST_7_DAYS("7d", 86_400_000L, 7, "day"),
    LAST_30_DAYS("30d", 86_400_000L, 30, "day");

    private final String code;
    private final long bucketMs;
    private final int buckets;
    private final String unit;

    TimeRange(String code, long bucketMs, int buckets, String unit) {
        this.code = code;
        this.bucketMs = bucketMs;
        this.buckets = buckets;
        this.unit = unit;
    }

    public String code() { return code; }
    public long bucketMs() { return bucketMs; }
    public int buckets() { return buckets; }
    public String unit() { return unit; }
}
