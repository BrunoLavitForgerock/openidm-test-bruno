/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014 ForgeRock AS. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */
package org.forgerock.openidm.audit.impl;

import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.resource.Context;
import org.forgerock.json.resource.PersistenceConfig;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ServerContext;

/**
 * A ServerContext used when auditing over the router.
 */
class AuditContext extends ServerContext {
    public AuditContext(Context parent) {
        super(parent);
    }

    public AuditContext(String id, Context parent) {
        super(id, parent);
    }

    public AuditContext(JsonValue savedContext, PersistenceConfig config) throws ResourceException {
        super(savedContext, config);
    }
}
