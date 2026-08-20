package com.e2eechat.core.util;

/**
 * Factory for obtaining Logger instances.
 * Thread-safe.
 */
public class LoggerFactory {
    private static LoggerProvider provider;

    public interface LoggerProvider {
        Logger getLogger(String name);
    }

    public static void setProvider(LoggerProvider p) {
        provider = p;
    }

    public static Logger getLogger(String name) {
        if (provider != null) {
            return provider.getLogger(name);
        }
        // Fallback no-op logger if uninitialized
        return new Logger() {
            public void info(String message) {}
            public void warn(String message) {}
            public void error(String message, Throwable t) {}
            public void debug(String message) {}
        };
    }

    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }
}
