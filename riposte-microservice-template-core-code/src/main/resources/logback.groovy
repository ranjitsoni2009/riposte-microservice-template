import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.encoder.PatternLayoutEncoder

private String getEnvironmentString() {
    def environment = System.getProperty("@environment")
    if (environment != null) {
        return environment
    }

    return System.getProperty("archaius.deployment.environment")
}

private boolean isLocalEnvironment() {
    def serviceName = getEnvironmentString()
    return (serviceName == null) || ("local".equals(serviceName))
}

private boolean booleanSystemPropertyExtractionHelper(String systemPropertyToSearchFor, boolean defaultIfSystemPropertyNotFound) {
    // If the property is defined then return true unless the value is explicitly false.
    def outputSystemProp = System.getProperty(systemPropertyToSearchFor)
    if (outputSystemProp != null) {
        if (outputSystemProp.equalsIgnoreCase("false")) {
            return false
        }

        return true
    }

    // Not explicitly defined. Return the default argument passed in.
    return defaultIfSystemPropertyNotFound
}

private boolean shouldOutputToConsole() {
    // If not explicitly requested then we only want to output to console if you're running locally
    //      (local environments default console *on*, non-local environments default console *off*).
    return booleanSystemPropertyExtractionHelper("logToConsole", isLocalEnvironment())
}

private boolean shouldOutputToLogFile() {
    // If not explicitly requested then we only want to output to log file if you're *NOT* running locally
    //      (local environments default log files *off*, non-local environments default log files *on*)
    return booleanSystemPropertyExtractionHelper("logToLocalFile", !isLocalEnvironment())
}

private boolean shouldOutputAccessLogsToConsole() {
    // If not explicitly requested then we only want to output to console if you're running locally
    //      (local environments default console *on*, non-local environments default console *off*).
    return booleanSystemPropertyExtractionHelper("logAccessLogToConsole", isLocalEnvironment())
}

private boolean shouldOutputAccessLogsToLogFile() {
    // If not explicitly requested then we only want to output to log file if you're *NOT* running locally
    //      (local environments default log files *off*, non-local environments default log files *on*)
    return booleanSystemPropertyExtractionHelper("logAccessLogToLocalFile", !isLocalEnvironment())
}

// Define encoder patters, async queue size, etc.
addInfo("Processing logback.groovy, environment: " + getEnvironmentString() + "...")
println("Processing logback.groovy, environment: " + getEnvironmentString() + "...")

def SERVICE_ENV_NAME = getEnvironmentString() == null? "NA" : getEnvironmentString()

def encoderPattern = "traceId=%X{traceId:-NO_TRACE_RUNNING} %date{\"yyyy-MM-dd'T'HH:mm:ss,SSSXXX\"} [%thread] appname=@@APPNAME@@ environment=${SERVICE_ENV_NAME} version=@@RELEASE@@ |-%-5level %logger{36} - %msg%n"
def accessLogEncoderPattern = "%msg%n"
def defaultAsyncQueueSize = 16000

def allAsyncAppendersArray = []
def allAsyncAccessLogAppendersArray = []

// Setup the console appender for app logs.
addInfo("******Outputting app logs to console: " + shouldOutputToConsole())
println("******Outputting app logs to console: " + shouldOutputToConsole())

def setupConsoleAppender(String appenderName, String encoderPatternToUse, List allAsyncAppendersListToUse, int defaultAsyncQueueSize) {
    def Appender coreAppender = null
    def asyncAppenderName = "Async" + appenderName

    appender(appenderName, ConsoleAppender) {
        encoder(PatternLayoutEncoder) {
            pattern = encoderPatternToUse
        }

        coreAppender = component
    }

    appender(asyncAppenderName, AsyncAppender) {
        queueSize = defaultAsyncQueueSize
        component.addAppender(coreAppender)
    }

    allAsyncAppendersListToUse.add(asyncAppenderName)
}

if (shouldOutputToConsole()) {
    setupConsoleAppender("ConsoleAppender", encoderPattern, allAsyncAppendersArray, defaultAsyncQueueSize)
}

// Setup the console appender for access logs.
addInfo("******Outputting access logs to console: " + shouldOutputAccessLogsToConsole())
println("******Outputting access logs to console: " + shouldOutputAccessLogsToConsole())

if (shouldOutputAccessLogsToConsole()) {
    setupConsoleAppender("AccessLogConsoleAppender", accessLogEncoderPattern, allAsyncAccessLogAppendersArray, defaultAsyncQueueSize)
}

// Setup the log file appender for app logs.
def LOG_FILE_DIRECTORY_PATH = isLocalEnvironment() ? "logs" : "/var/log/@@APPNAME@@"
addInfo("******Outputting app logs to log file in directory ${LOG_FILE_DIRECTORY_PATH}: " + shouldOutputToLogFile())
println("******Outputting app logs to log file in directory ${LOG_FILE_DIRECTORY_PATH}: " + shouldOutputToLogFile())

def setupLogFileAppender(String appenderName, String encoderPatternToUse, List allAsyncAppendersListToUse, String logFileDirectoryPath, String baseFilename, int numRolloverFilesToKeep, String maxFileSizeAsString, int defaultAsyncQueueSize) {
    def Appender coreAppender = null
    def asyncAppenderName = "Async" + appenderName

    appender(appenderName, RollingFileAppender) {
        file = "${logFileDirectoryPath}/${baseFilename}.log"

        rollingPolicy(FixedWindowRollingPolicy) {
            // NOTE: To have it archive into a zip or gzip file end the fileNamePattern with .zip or .gz
            fileNamePattern = "${logFileDirectoryPath}/${baseFilename}.%i.log.zip"
            minIndex = 1
            maxIndex = numRolloverFilesToKeep
        }

        triggeringPolicy(SizeBasedTriggeringPolicy) {
            maxFileSize = maxFileSizeAsString
        }

        encoder(PatternLayoutEncoder) {
            pattern = encoderPatternToUse
        }

        coreAppender = component
    }

    appender(asyncAppenderName, AsyncAppender) {
        queueSize = defaultAsyncQueueSize
        component.addAppender(coreAppender)
    }

    allAsyncAppendersListToUse.add(asyncAppenderName)
}

if (shouldOutputToLogFile()) {
    // Make sure we stay under 10GB for the amount of app log history we keep (in reality size on disk will be lower due to zipping the archived files, but we don't have a lot of space to play with so better safe than sorry)
    setupLogFileAppender("FileAppender", encoderPattern, allAsyncAppendersArray, LOG_FILE_DIRECTORY_PATH, "@@APPNAME@@", 20, "500MB", defaultAsyncQueueSize)
}

// Setup the log file appender for access logs.
addInfo("******Outputting access logs to log file in directory ${LOG_FILE_DIRECTORY_PATH}: " + shouldOutputAccessLogsToLogFile())
println("******Outputting access logs to log file in directory ${LOG_FILE_DIRECTORY_PATH}: " + shouldOutputAccessLogsToLogFile())

if (shouldOutputAccessLogsToLogFile()) {
    // Make sure we stay under 500MB for the amount of access log history we keep (in reality size on disk will be lower due to zipping the archived files, but we don't have a lot of space to play with so better safe than sorry)
    setupLogFileAppender("AccessLogFileAppender", accessLogEncoderPattern, allAsyncAccessLogAppendersArray, LOG_FILE_DIRECTORY_PATH, "@@APPNAME@@-access", 10, "50MB", defaultAsyncQueueSize)
}

// CUSTOM LOGGER SETTINGS (setting output levels for various classes)
logger("com.nike.riposte.server.handler.RequestContentDeserializerHandler", INFO, allAsyncAppendersArray, false)
logger("com.nike.riposte.server.handler.DTraceStartHandler", INFO, allAsyncAppendersArray, false)
logger("com.nike.riposte.metrics.codahale.CodahaleMetricsListener", INFO, allAsyncAppendersArray, false)
logger("com.nike.riposte.server.http.ResponseSender", INFO, allAsyncAppendersArray, false)
logger("com.nike.riposte.server.handler.ProxyRouterEndpointExecutionHandler\$StreamingCallbackForCtx", INFO, allAsyncAppendersArray, false)
logger("com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient", INFO, allAsyncAppendersArray, false)
logger("com.nike.riposte.server.handler.SecurityValidationHandler", INFO, allAsyncAppendersArray, false)

logger("com.nike.wingtips.Tracer", INFO, allAsyncAppendersArray, false)
logger("com.nike.wingtips.http.HttpRequestTracingUtils", INFO, allAsyncAppendersArray, false)
logger("VALID_WINGTIPS_SPANS", INFO, allAsyncAppendersArray, false)
logger("INVALID_WINGTIPS_SPANS", INFO, allAsyncAppendersArray, false)

logger("org.hibernate.validator", INFO, allAsyncAppendersArray, false)
logger("com.ning.http", INFO, allAsyncAppendersArray, false)

logger("com.netflix.config.util.OverridingPropertiesConfiguration", INFO, allAsyncAppendersArray, false) // Part of Archaius - set this to debug if you want a little more info into what archaius is doing

logger("org.apache.http", INFO, allAsyncAppendersArray, false)
logger("org.apache.cassandra", INFO, allAsyncAppendersArray, false)
logger("com.datastax.driver.core.Connection", INFO, allAsyncAppendersArray, false)
logger("com.netflix", INFO, allAsyncAppendersArray, false)
logger("com.newrelic", INFO, allAsyncAppendersArray, false)

// ACCESS LOG SETTINGS
def disableAccessLog = booleanSystemPropertyExtractionHelper("disableAccessLog", false)
def accessLogLevel = (disableAccessLog) ? OFF : INFO
addInfo("******Access logs disabled: " + disableAccessLog)
println("******Access logs disabled: " + disableAccessLog)
logger("ACCESS_LOG", accessLogLevel, allAsyncAccessLogAppendersArray, false)

// Root logger.
root(INFO, allAsyncAppendersArray)

// Auto-scan this config file for changes and reload the logging config if it changes.
// NOTE: Due to performance concerns, the scanner is not only time based (which we set here), it is also sampled
//       so the time check only happens once every x logging attempts (x is dynamically determined by logback based
//       on how often the app logs). Both the sample check and the time check must pass before the log file will be
//       reprocessed. See http://logback.qos.ch/manual/configuration.html#autoScan for more details.
scan("60 seconds")

addInfo("...logback.groovy processing finished.")
println("...logback.groovy processing finished.")