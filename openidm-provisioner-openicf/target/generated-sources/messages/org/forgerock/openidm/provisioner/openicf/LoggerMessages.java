package org.forgerock.openidm.provisioner.openicf;

import org.forgerock.i18n.LocalizableMessageDescriptor;

/**
 * This file contains localizable message descriptors having the resource
 * name {@code org.forgerock.openidm.provisioner.openicf.logger}. This file was generated
 * automatically by the {@code i18n-maven-plugin} from the property file
 * {@code org/forgerock/openidm/provisioner/openicf/logger.properties} and it should not be manually edited.
 */
public final class LoggerMessages {
    // The name of the resource bundle.
    private static final String RESOURCE = "org.forgerock.openidm.provisioner.openicf.logger";

    // Prevent instantiation.
    private LoggerMessages() {
        // Do nothing.
    }

    /**
     * Returns the name of the resource associated with the messages contained
     * in this class. The resource name may be used for obtaining named loggers,
     * e.g. using SLF4J's {@code org.slf4j.LoggerFactory#getLogger(String name)}.
     *
     * @return The name of the resource associated with the messages contained
     *         in this class.
     */
    public static String resourceName() {
        return RESOURCE;
    }

    /**
     * Invalid configuration property %s, current value: %s
     */
    public static final LocalizableMessageDescriptor.Arg2<Object, Object> ERR_CONFIGURATION_EXPECTATION =
                    new LocalizableMessageDescriptor.Arg2<Object, Object>(LoggerMessages.class, RESOURCE, "ERR_CONFIGURATION_EXPECTATION", -1);

    /**
     * ConnectorEvent received. Topic: %s, Source: %s
     */
    public static final LocalizableMessageDescriptor.Arg2<Object, Object> TRACE_CONNECTOR_EVENT_RECEIVED =
                    new LocalizableMessageDescriptor.Arg2<Object, Object>(LoggerMessages.class, RESOURCE, "TRACE_CONNECTOR_EVENT_RECEIVED", -1);

}
