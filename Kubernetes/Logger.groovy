import static Logger.*

/**
 * Log levels supported by the plugin procedures
 */
class Logger {
    static final Integer DEBUG = 1
    static final Integer INFO = 2
    static final Integer WARNING = 3
    static final Integer ERROR = 4

    // Default log level used till the configured log level
    // is read from the plugin configuration.
    static Integer logLevel = INFO

    def static getLogLevelStr(Integer level) {
        switch (level) {
            case DEBUG:
                return '[DEBUG] '
            case INFO:
                return '[INFO] '
            case WARNING:
                return '[WARNING] '
            default://ERROR
                return '[ERROR] '

        }
    }
}
