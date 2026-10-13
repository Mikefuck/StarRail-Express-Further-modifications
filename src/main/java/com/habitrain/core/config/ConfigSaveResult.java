package com.habitrain.core.config;

/** Outcome of a durable save or a matched server acknowledgement. */
public record ConfigSaveResult(Status status, String message) {
    public enum Status { NO_CHANGE, SAVED, FAILED, SUBMITTING, CONFLICT }
    public boolean successful() { return status == Status.SAVED || status == Status.NO_CHANGE; }
    public static ConfigSaveResult saved() { return new ConfigSaveResult(Status.SAVED, "配置已保存"); }
    public static ConfigSaveResult unchanged() { return new ConfigSaveResult(Status.NO_CHANGE, "配置没有变化"); }
    public static ConfigSaveResult failed(String reason) { return new ConfigSaveResult(Status.FAILED, reason); }
}
