package org.forgerock.openidm.util;

import org.forgerock.i18n.LocalizableMessageDescriptor;

/**
 * This file contains localizable message descriptors having the resource
 * name {@code org.forgerock.openidm.util.resource}. This file was generated
 * automatically by the {@code i18n-maven-plugin} from the property file
 * {@code org/forgerock/openidm/util/resource.properties} and it should not be manually edited.
 */
public final class ResourceMessages {
    // The name of the resource bundle.
    private static final String RESOURCE = "org.forgerock.openidm.util.resource";

    // Prevent instantiation.
    private ResourceMessages() {
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
     * The %s operations are not supported
     */
    public static final LocalizableMessageDescriptor.Arg1<Object> ERR_OPERATION_NOT_SUPPORTED_EXPECTATION =
                    new LocalizableMessageDescriptor.Arg1<Object>(ResourceMessages.class, RESOURCE, "ERR_OPERATION_NOT_SUPPORTED_EXPECTATION", -1);

}
