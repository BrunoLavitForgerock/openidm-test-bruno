/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2013-2014 ForgeRock AS.
 */

package org.forgerock.openidm.jaspi.auth;

import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.resource.ConnectionFactory;
import org.forgerock.openidm.crypto.CryptoService;
import org.forgerock.util.promise.Function;
import org.forgerock.util.promise.NeverThrowsException;

import static org.forgerock.openidm.jaspi.modules.IDMJaspiModuleWrapper.AUTHENTICATION_ID;
import static org.forgerock.openidm.jaspi.modules.IDMJaspiModuleWrapper.PROPERTY_MAPPING;
import static org.forgerock.openidm.jaspi.modules.IDMJaspiModuleWrapper.QUERY_ID;
import static org.forgerock.openidm.jaspi.modules.IDMJaspiModuleWrapper.QUERY_ON_RESOURCE;
import static org.forgerock.openidm.jaspi.modules.IDMJaspiModuleWrapper.USER_CREDENTIAL;

/**
 * A factory Function to build an Authenticator from an auth module config.
 *
 * @author brmiller
 */
public class AuthenticatorFactory implements Function<JsonValue, Authenticator, NeverThrowsException> {

    private final ConnectionFactory connectionFactory;
    private final CryptoService cryptoService;

    public AuthenticatorFactory(final ConnectionFactory connectionFactory, final CryptoService cryptoService) {
        this.connectionFactory = connectionFactory;
        this.cryptoService = cryptoService;
    }

    /**
     * Provided the auth module properties {@link JsonValue}, instantiate an appropriate {@link Authenticator}.
     *
     * @param jsonValue the auth module properties
     * @return an Authenticator
     * @throws NeverThrowsException
     */
    @Override
    public Authenticator apply(JsonValue jsonValue) throws NeverThrowsException {
        if (!jsonValue.get(QUERY_ID).isNull()) {
            return new ResourceQueryAuthenticator(cryptoService, connectionFactory,
                    jsonValue.get(QUERY_ON_RESOURCE).required().asString(),
                    jsonValue.get(QUERY_ID).required().asString(),
                    jsonValue.get(PROPERTY_MAPPING).get(AUTHENTICATION_ID).required().asString(),
                    jsonValue.get(PROPERTY_MAPPING).get(USER_CREDENTIAL).required().asString());
        } else {
            return new PassthroughAuthenticator(connectionFactory,
                    jsonValue.get(QUERY_ON_RESOURCE).required().asString());
        }
    }
}

