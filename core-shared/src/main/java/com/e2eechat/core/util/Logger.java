package com.e2eechat.core.util;

/**
 * Thin Logger interface.
 * Implementations should be thread-safe.
 */
public interface Logger {
    void info(String message);
    void warn(String message);
    void error(String message, Throwable t);
    void debug(String message);
}
