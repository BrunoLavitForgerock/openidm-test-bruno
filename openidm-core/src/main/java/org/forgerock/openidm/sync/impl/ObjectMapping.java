/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2014 ForgeRock AS. All rights reserved.
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
package org.forgerock.openidm.sync.impl;

// Java SE
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.script.ScriptException;

import org.forgerock.json.fluent.JsonPointer;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.fluent.JsonValueException;
import org.forgerock.json.patch.JsonPatch;
import org.forgerock.json.resource.Context;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.NotFoundException;
import org.forgerock.json.resource.QueryFilter;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResult;
import org.forgerock.json.resource.QueryResultHandler;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.Resource;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.RootContext;
import org.forgerock.json.resource.SecurityContext;
import org.forgerock.json.resource.ServerContext;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openidm.config.enhanced.InternalErrorException;
import org.forgerock.openidm.smartevent.EventEntry;
import org.forgerock.openidm.smartevent.Name;
import org.forgerock.openidm.smartevent.Publisher;
import org.forgerock.openidm.sync.TriggerContext;
import org.forgerock.openidm.sync.impl.Scripts.Script;
import org.forgerock.openidm.util.DateUtil;
import org.forgerock.script.exception.ScriptThrownException;
import org.forgerock.script.source.SourceUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.forgerock.json.resource.servlet.HttpUtils.PARAM_QUERY_EXPRESSION;
import static org.forgerock.json.resource.servlet.HttpUtils.PARAM_QUERY_FILTER;
import static org.forgerock.json.resource.servlet.HttpUtils.PARAM_QUERY_ID;

/**
 * An ObjectMapping defines policies between source and target objects and their attributes
 * during synchronization and reconciliation.  Mappings can also define triggers for validation,
 * customization, filtering, and transformation of source and target objects.
 *
 * @author Paul C. Bryan
 * @author aegloff
 * @author brmiller
 */
class ObjectMapping {

    /**
     * Event names for monitoring ObjectMapping behavior
     */
    public static final Name EVENT_CREATE_OBJ = Name.get("openidm/internal/discovery-engine/sync/create-object");
    public static final Name EVENT_SOURCE_ASSESS_SITUATION = Name.get("openidm/internal/discovery-engine/sync/source/assess-situation");
    public static final Name EVENT_SOURCE_DETERMINE_ACTION = Name.get("openidm/internal/discovery-engine/sync/source/determine-action");
    public static final Name EVENT_SOURCE_PERFORM_ACTION = Name.get("openidm/internal/discovery-engine/sync/source/perform-action");
    public static final Name EVENT_CORRELATE_TARGET = Name.get("openidm/internal/discovery-engine/sync/source/correlate-target");
    public static final Name EVENT_UPDATE_TARGET = Name.get("openidm/internal/discovery-engine/sync/update-target");
    public static final Name EVENT_DELETE_TARGET = Name.get("openidm/internal/discovery-engine/sync/delete-target");
    public static final Name EVENT_TARGET_ASSESS_SITUATION = Name.get("openidm/internal/discovery-engine/sync/target/assess-situation");
    public static final Name EVENT_TARGET_DETERMINE_ACTION = Name.get("openidm/internal/discovery-engine/sync/target/determine-action");
    public static final Name EVENT_TARGET_PERFORM_ACTION = Name.get("openidm/internal/discovery-engine/sync/target/perform-action");
    public static final String EVENT_OBJECT_MAPPING_PREFIX = "openidm/internal/discovery-engine/sync/objectmapping/";

    /**
     * Event names for monitoring Reconciliation behavior
     */
    public static final Name EVENT_RECON = Name.get("openidm/internal/discovery-engine/reconciliation");
    public static final Name EVENT_RECON_ID_QUERIES = Name.get("openidm/internal/discovery-engine/reconciliation/id-queries-phase");
    public static final Name EVENT_RECON_SOURCE = Name.get("openidm/internal/discovery-engine/reconciliation/source-phase");
    public static final Name EVENT_RECON_TARGET = Name.get("openidm/internal/discovery-engine/reconciliation/target-phase");

    /** Logger */
    private static final Logger LOGGER = LoggerFactory.getLogger(ObjectMapping.class);

    /**
     * Date util used when creating ReconObject timestamps
     */
    private static final DateUtil dateUtil = DateUtil.getDateUtil("UTC");

    /** Status of a recon operation */
    private enum Status { SUCCESS, FAILURE }

    /** The mapping name */
    private final String name;

    /** The raw mapping configuration */
    private final JsonValue config;

    /** The name of the links set to use. Defaults to mapping name. */
    private final String linkTypeName;

    /** The link type to use */
    LinkType linkType;

    /**
     * Whether to link source IDs in a case sensitive fashion.
     * Only effective if this mapping defines links, is ignored if this
     * mapping re-uses another mapping's links
     * Default to {@code TRUE}
     */
    private final Boolean sourceIdsCaseSensitive;

    /**
     * Whether to link target IDs in a case sensitive fashion.
     * Only effective if this mapping defines links, is ignored if this
     * mapping re-uses another mapping's links
     * Default to {@code TRUE}
     */
    private final Boolean targetIdsCaseSensitive;

    /** the source system */
    private final String sourceObjectSet;

    /** the target system */
    private String targetObjectSet;

    /** a script that determines if a source object is valid to be mapped */
    private Script validSource;

    /** a script that determines if a target object is valid to be mapped */
    private Script validTarget;

    /** a script the yields a query object to query the target object set when a source object has no linked target */
    private Script correlationQuery;

    /** a script that applies the effective assignments as part of the mapping */
    private Script defaultMapping;

    /** an array of property-mapping objects */
    private ArrayList<PropertyMapping> properties = new ArrayList<PropertyMapping>();

    /** an array of {@link Policy} objects */
    private ArrayList<Policy> policies = new ArrayList<Policy>();

    /** a script to execute when a target object is to be created */
    private Script onCreateScript;

    /** a script to execute when a target object is to be updated */
    private Script onUpdateScript;

    /** a script to execute when a target object is to be deleted */
    private Script onDeleteScript;

    /** a script to execute when a source object is to be linked to a target object */
    private Script onLinkScript;

    /** a script to execute when a source object and a target object are to be unlinked */
    private Script onUnlinkScript;

    /** a script to execute on each mapping event regardless of the operation */
    private Script resultScript;

    /** an additional set of key-value conditions to be met for a source object to be valid to be mapped */
    private JsonValue sourceCondition;

    /**
     * Whether existing links should be fetched in one go along with the source and target id lists.
     * false indicates links should be retrieved individually as they are needed.
     */
    private final Boolean prefetchLinks;

    /**
     * Whether when at the outset of correlation the target set is empty (query all ids returns empty),
     * it should try to correlate source entries to target when necessary.
     * Default to {@code FALSE}
     */
    private final Boolean correlateEmptyTargetSet;

    /**
     * Whether to maintain links for sync-d targets
     * Default to {@code TRUE}
     */
    private final Boolean linkingEnabled;

    /** The number of processing threads to use in reconciliation */
    int taskThreads = 10; // TODO: make configurable

    /** a reference to the {@link SynchronizationService} */
    private final SynchronizationService service;

    /** Whether synchronization (automatic propagation of changes as they are detected) is enabled on that mapping */
    private final Boolean syncEnabled;

    /**
     * Create an instance of a mapping between source and target
     *
     * @param service The associated sychronization service
     * @param config The configuration for this mapping
     * @throws JsonValueException if there is an issue initializing based on the configuration.
     */
    public ObjectMapping(SynchronizationService service, JsonValue config) throws JsonValueException {
        this.service = service;
        this.config = config;
        name = config.get("name").required().asString();
        linkTypeName = config.get("links").defaultTo(name).asString();
        sourceObjectSet = config.get("source").required().asString();
        targetObjectSet = config.get("target").required().asString();
        sourceIdsCaseSensitive = config.get("sourceIdsCaseSensitive").defaultTo(Boolean.TRUE).asBoolean();
        targetIdsCaseSensitive = config.get("targetIdsCaseSensitive").defaultTo(Boolean.TRUE).asBoolean();
        validSource = Scripts.newInstance("ObjectMapping", config.get("validSource"));
        validTarget = Scripts.newInstance("ObjectMapping", config.get("validTarget"));
        sourceCondition = config.get("sourceCondition").expect(Map.class);
        correlationQuery = Scripts.newInstance("ObjectMapping", config.get("correlationQuery"));
        for (JsonValue jv : config.get("properties").expect(List.class)) {
            properties.add(new PropertyMapping(service, jv));
        }
        for (JsonValue jv : config.get("policies").expect(List.class)) {
            policies.add(new Policy(service, jv));
        }
        defaultMapping = Scripts.newInstance("ObjectMapping", config.get("defaultMapping").defaultTo(
                new JsonValue(new HashMap<String, Object>())
                    .put(SourceUnit.ATTR_TYPE, "text/javascript")
                    .put(SourceUnit.ATTR_NAME, "roles/defaultMapping.js")));
        onCreateScript = Scripts.newInstance("ObjectMapping", config.get("onCreate"));
        onUpdateScript = Scripts.newInstance("ObjectMapping", config.get("onUpdate"));
        onDeleteScript = Scripts.newInstance("ObjectMapping", config.get("onDelete"));
        onLinkScript = Scripts.newInstance("ObjectMapping", config.get("onLink"));
        onUnlinkScript = Scripts.newInstance("ObjectMapping", config.get("onUnlink"));
        resultScript = Scripts.newInstance("ObjectMapping", config.get("result"));
        prefetchLinks = config.get("prefetchLinks").defaultTo(Boolean.TRUE).asBoolean();
        Integer confTaskThreads = config.get("taskThreads").asInteger();
        if (confTaskThreads != null) {
            taskThreads = confTaskThreads.intValue();
        }
        correlateEmptyTargetSet = config.get("correlateEmptyTargetSet").defaultTo(Boolean.FALSE).asBoolean();
        syncEnabled = config.get("enableSync").defaultTo(Boolean.TRUE).asBoolean();
        linkingEnabled = config.get("enableLinking").defaultTo(Boolean.TRUE).asBoolean();

        LOGGER.debug("Instantiated {}", name);
    }

    /**
     * Return whether synchronization is enabled for this mapping.
     *
     * @return whether synchronization is enabled for this mapping
     */
    public boolean isSyncEnabled() {
        return syncEnabled.booleanValue();
    }

    /**
     * Return whether linking is enabled for this mapping.
     *
     * @return whether linking is enabled for this mapping
     */
    public boolean isLinkingEnabled() {
        return linkingEnabled.booleanValue();
    }

    /**
     * Mappings can share the same link tables.
     * Establish the relationship between the mappings and determine the proper
     * link type to use
     * @param syncSvc the associated synchronization service
     * @param allMappings The list of all existing mappings
     */
    public void initRelationships(SynchronizationService syncSvc, List<ObjectMapping> allMappings) {
        linkType = LinkType.getLinkType(this, allMappings);
    }

    /**
     * @return the associated synchronization service
     */
    SynchronizationService getService() {
        return service;
    }

    /**
     * @return The name of the object mapping
     */
    public String getName() {
        return name;
    }

    /**
     * @return The raw config associated with the object mapping
     */
    public JsonValue getConfig() {
        return config;
    }

    /**
     * @return The configured name of the link set to use for this object mapping
     */
    public String getLinkTypeName() {
        return linkTypeName;
    }

    /**
     * @return The resolved name of the link set to use for this object mapping
     */
    public LinkType getLinkType() {
        return linkType;
    }

    /**
     * @return The mapping source object set
     */
    public String getSourceObjectSet() {
        return sourceObjectSet;
    }

    /**
     * @return The mapping target object set
     */
    public String getTargetObjectSet() {
        return targetObjectSet;
    }

    /**
     * @return The setting for whether to link
     * source IDs in a case sensitive fashion.
     * Only effective if the mapping defines the links,
     * not if the mapping re-uses another mapping's links
     */
    public boolean getSourceIdsCaseSensitive() {
        return sourceIdsCaseSensitive.booleanValue();
    }

    /**
     * @return The setting for whether to link
     * target IDs in a case sensitive fashion.
     * Only effective if the mapping defines the links,
     * not if the mapping re-uses another mapping's links
     */
    public boolean getTargetIdsCaseSensitive() {
        return targetIdsCaseSensitive.booleanValue();
    }

    /**
     * Convenience function with deleted defaulted to false and oldValue defaulted to null
     * @see ObjectMapping#doSourceSync(String, JsonValue, boolean, JsonValue)
     */
    private JsonValue doSourceSync(String resourceId, JsonValue value) throws SynchronizationException {
        return doSourceSync(resourceId, value, false, null);
    }

    /**
     * Source synchronization
     *
     * @param resourceId object identifier.
     * @param value null to have it query the source state if applicable,
     *        or JsonValue to tell it the value of the existing source to sync
     * @param sourceDeleted Whether the source object has been deleted
     * @return sync results of the {@link SyncOperation}
     * @throws SynchronizationException if sync-ing fails.
     */
    private JsonValue doSourceSync(String resourceId, JsonValue value, boolean sourceDeleted, JsonValue oldValue)
            throws SynchronizationException {
        LOGGER.trace("Start source synchronization of {} {}", resourceId,
                (value == null) ? "without a value" : "with a value");

        // TODO: one day bifurcate this for synchronous and asynchronous source operation
        SourceSyncOperation op = new SourceSyncOperation();
        op.oldValue = oldValue;
        if (sourceDeleted) {
            op.sourceObjectAccessor = new LazyObjectAccessor(service, sourceObjectSet, resourceId, null);
        } else if (value != null) {
            value.put("_id", resourceId); // unqualified
            op.sourceObjectAccessor = new LazyObjectAccessor(service, sourceObjectSet, resourceId, value);
        } else {
            op.sourceObjectAccessor = new LazyObjectAccessor(service, sourceObjectSet, resourceId);
        }
        return op.sync();
    }

    /**
     * TODO: Description.
     *
     * @param query TODO.
     * @return TODO.
     * @throws SynchronizationException TODO.
     */
    private Map<String, Object> queryTargetObjectSet(Map<String, Object> query)
            throws SynchronizationException {
        try {
            Map<String, Object> result = new HashMap<String, Object>(1);
            final Collection<Object> list = new ArrayList<Object>();
            result.put(QueryResult.FIELD_RESULT, list);

            QueryRequest r = Requests.newQueryRequest(targetObjectSet);
            for (Map.Entry<String, Object> e: query.entrySet()) {
                if (PARAM_QUERY_ID.equals(e.getKey())) {
                    r.setQueryId(String.valueOf(e.getValue()));
                } else if (PARAM_QUERY_EXPRESSION.equals(e.getKey())) {
                    r.setQueryExpression(String.valueOf(e.getValue()));
                } else if (PARAM_QUERY_FILTER.equals(e.getKey())) {
                    r.setQueryFilter(QueryFilter.valueOf(String.valueOf(e.getValue())));
                } else {
                    r.setAdditionalParameter(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            service.getConnectionFactory().getConnection().query(service.getServerContext(), r, new QueryResultHandler() {
                @Override
                public void handleError(ResourceException error) {
                    // ignore
                }

                @Override
                public boolean handleResource(Resource resource) {
                    list.add(resource.getContent().asMap());
                    return true;
                }

                @Override
                public void handleResult(QueryResult result) {
                    //ignore
                }
            });
            return result;
        } catch (ResourceException ose) {
            throw new SynchronizationException(ose);
        }
    }

// TODO: maybe move all this target stuff into a target object wrapper to keep this class clean
    /**
     * TODO: Description.
     *
     * @param target TODO.
     * @throws SynchronizationException TODO.
     */
    private LazyObjectAccessor createTargetObject(JsonValue target) throws SynchronizationException {
        EventEntry measure = Publisher.start(EVENT_CREATE_OBJ, target, null);
        LazyObjectAccessor targetObject = null;
        LOGGER.trace("Create target object {}/{}", targetObjectSet, target.get("_id").asString());
        try {
            CreateRequest cr = Requests.newCreateRequest(targetObjectSet, target.get("_id").asString(), target);
            Resource r =  service.getConnectionFactory().getConnection().create(service.getServerContext(), cr);
            targetObject = new LazyObjectAccessor(service, targetObjectSet, r.getId(), target);
            measure.setResult(target);
        } catch (JsonValueException jve) {
            throw new SynchronizationException(jve);
        } catch (ResourceException ose) {
            LOGGER.warn("Failed to create target object", ose);
            throw new SynchronizationException(ose);
        } finally {
            measure.end();
        }
        return targetObject;
    }

// TODO: maybe move all this target stuff into a target object wrapper to keep this class clean
    /**
     * TODO: Description.
     *
     * @param target TODO.
     * @throws SynchronizationException TODO.
     */
    private void updateTargetObject(JsonValue target) throws SynchronizationException {
        EventEntry measure = Publisher.start(EVENT_UPDATE_TARGET, target, null);
        try {
            String id = LazyObjectAccessor.qualifiedId(targetObjectSet,
                    target.get("_id").required().asString());
            LOGGER.trace("Update target object {}", id);
            UpdateRequest ur = Requests.newUpdateRequest(id, target);
            ur.setRevision(target.get("_rev").asString());
            service.getConnectionFactory().getConnection().update(service.getServerContext(), ur);
            measure.setResult(target);
        } catch (JsonValueException jve) {
            throw new SynchronizationException(jve);
        } catch (ResourceException ose) {
            LOGGER.warn("Failed to update target object", ose);
            throw new SynchronizationException(ose);
        } finally {
            measure.end();
        }
    }

// TODO: maybe move all this target stuff into a target object wrapper to keep this class clean
    /**
     * TODO: Description.
     *
     * @param target TODO.
     * @throws SynchronizationException TODO.
     */
    private void deleteTargetObject(JsonValue target) throws SynchronizationException {
        if (target != null && target.get("_id").isString()) { // forgiving delete
            EventEntry measure = Publisher.start(EVENT_DELETE_TARGET, target, null);
            try {
                DeleteRequest ur = Requests.newDeleteRequest(targetObjectSet, target.get("_id").required().asString());
                ur.setRevision(target.get("_rev").asString());
                LOGGER.trace("Delete target object {}", ur.getResourceName());
                service.getConnectionFactory().getConnection().delete(service.getServerContext(), ur);
            } catch (JsonValueException jve) {
                throw new SynchronizationException(jve);
            } catch (NotFoundException nfe) {
                // forgiving delete
            } catch (ResourceException ose) {
                LOGGER.warn("Failed to delete target object", ose);
                throw new SynchronizationException(ose);
            } finally {
                measure.end();
            }
        }
    }

    /**
     * Apply the configured sync mappings
     *
     * @param source The current source object 
     * @param oldSource an optional previous source object before the change(s) that triggered the sync, 
     * null if not provided
     * @param target the current target object to modify
     * @param existingTarget the full existing target object
     * @throws SynchronizationException if applying the mappings fails.
     */
    private void applyMappings(JsonValue source, JsonValue oldSource, JsonValue target, JsonValue existingTarget) throws SynchronizationException {
        EventEntry measure = Publisher.start(getObjectMappingEventName(), source, null);
        try {
            for (PropertyMapping property : properties) {
                property.apply(source, oldSource, target);
            }
            // Apply default mapping, if configured
            applyDefaultMappings(source, oldSource, target, existingTarget);
            
            measure.setResult(target);
        } finally {
            measure.end();
        }
    }

    private JsonValue applyDefaultMappings(JsonValue source, JsonValue oldSource, JsonValue target, JsonValue existingTarget) throws SynchronizationException {
        JsonValue result = null;
        if (defaultMapping != null) {
            Map<String, Object> queryScope = new HashMap<String, Object>();
            queryScope.put("source", source.asMap());
            if (oldSource != null) {
            	queryScope.put("oldSource", oldSource.asMap());
            }
            queryScope.put("target", target.asMap());
            queryScope.put("config", config.asMap());
            queryScope.put("existingTarget", existingTarget.asMap());
            try {
                result = new JsonValue(defaultMapping.exec(queryScope));
            } catch (ScriptException se) {
                LOGGER.debug("{} defaultMapping script encountered exception", name, se);
                throw new SynchronizationException(se);
            }
        }
        return result;
    }

    /**
     * @return an event name for monitoring this object mapping
     */
    private Name getObjectMappingEventName() {
        return Name.get(EVENT_OBJECT_MAPPING_PREFIX + name);
    }

    /**
     * Returns {@code true} if the specified object identifer is in this mapping's source
     * object set.
     */
    public boolean isSourceObject(String resourceContainer, String resourceId) {
        return sourceObjectSet.equals(resourceContainer) && !resourceId.isEmpty();
    }

    /**
     * Notify the target system that an object has been created in a source system.
     *
     * @param resourceContainer source system
     * @param resourceId source object id
     * @param value object to synchronize
     * @return the SynchronizationOperatioin result details
     * @throws SynchronizationException on failure to synchronize
     */
    public JsonValue notifyCreate(String resourceContainer, String resourceId, JsonValue value)
            throws SynchronizationException {
        if (isSourceObject(resourceContainer, resourceId)) {
            if (value == null || value.getObject() == null) {
                // notification without the actual value
                value = LazyObjectAccessor.rawReadObject(
                        service.getServerContext(), service.getConnectionFactory(), resourceContainer, resourceId);
            }
            return doSourceSync(resourceId, value); // synchronous for now
        }
        return new JsonValue(null);
    }

    /**
     * Notify the target system that an object has been updated in a source system.
     *
     * @param resourceContainer source system
     * @param resourceId source object id
     * @param oldValue previous object value
     * @param newValue updated object to synchronize
     * @return
     * @throws SynchronizationException on failure to synchronize
     */
    public JsonValue notifyUpdate(String resourceContainer, String resourceId, JsonValue oldValue, JsonValue newValue)
            throws SynchronizationException {
        if (isSourceObject(resourceContainer, resourceId)) {
        	if (newValue == null || newValue.getObject() == null) { // notification without the actual value
                newValue = LazyObjectAccessor.rawReadObject(
                        service.getServerContext(), service.getConnectionFactory(), resourceContainer, resourceId);
            }

            if (oldValue == null || oldValue.getObject() == null || JsonPatch.diff(oldValue, newValue).size() > 0) {
                return doSourceSync(resourceId, newValue, false, oldValue); // synchronous for now
            } else {
                LOGGER.trace("There is nothing to update on {}", resourceContainer + "/" + resourceId);
            }
        }
        return new JsonValue(null);
    }

    /**
     * Notify the target system that an object has been deleted in a source system.
     *
     * @param resourceContainer source system
     * @param resourceId source object id
     * @param oldValue deleted object to synchronize
     * @return
     * @throws SynchronizationException on failure to synchronize
     */
    public JsonValue notifyDelete(String resourceContainer, String resourceId, JsonValue oldValue)
            throws SynchronizationException {
        if (isSourceObject(resourceContainer, resourceId)) {
            return doSourceSync(resourceId, null, true, oldValue); // synchronous for now
        }
        return new JsonValue(null);
    }

    /**
     * Perform the reconciliation action on a pre-assessed job.
     * <p/>
     * For the input parameters see {@link org.forgerock.openidm.sync.impl.ObjectMapping.SourceSyncOperation#toJsonValue()} or
     * {@link org.forgerock.openidm.sync.impl.ObjectMapping.TargetSyncOperation#toJsonValue()}.
     * <p/>
     * Script example:
     * <pre>
     *     try {
     *          openidm.action('sync',recon.actionParam)
     *     } catch(e) {
     *
     *     };
     * </pre>
     * @param params the input parameters to proceed with the pre-assessed job
     * includes state from previous pre-assessment (source or target sync operation),
     * plus instructions of what to execute. Specifically beyond the pre-asessed state
     * it expects changes to params
     * - action: the desired action to execute
     * - situation (optional): the situation to expect before executing the action.
     * To enforce that the action is only executed if the situation didn't change,
     * supply the situation from the pre-assessment.
     * To attempt execution of the action without enforcing the situation check,
     * supply no situation param
     * @throws SynchronizationException
     */
    public void performAction(JsonValue params) throws SynchronizationException {
        // If reconId is set this action is part of a reconciliation run
        String reconId = params.get("reconId").asString();
        ServerContext context = ObjectSetContext.get();
        if (reconId != null) {
            context = new TriggerContext(context, "recon");
            ObjectSetContext.push(context);
        }

        try {
            Context rootContext = context.asContext(RootContext.class);
            Action action = params.get("action").required().asEnum(Action.class);
            SyncOperation op = null;
            ReconEntry entry = null;
            SynchronizationException exception = null;
            try {
                if (params.get("target").isNull()) {
                    SourceSyncOperation sop = new SourceSyncOperation();
                    op = sop;
                    sop.fromJsonValue(params);

                    entry = new ReconEntry(sop, rootContext, dateUtil);
                    entry.sourceId = LazyObjectAccessor.qualifiedId(sourceObjectSet, sop.getSourceObjectId());
                    if (null == sop.getSourceObject()) {
                        exception = new SynchronizationException(
                                "Source object " + entry.sourceId + " does not exists");
                        throw exception;
                    }
                    //TODO blank check
                    String targetId = params.get("targetId").asString();
                    if (null != targetId){
                        op.targetObjectAccessor = new LazyObjectAccessor(service, targetObjectSet, targetId);
                        if (null == sop.getTargetObject()) {
                            exception = new SynchronizationException(
                                    "Target object " + targetId + " does not exists");
                            throw exception;
                        }
                    }
                    sop.assessSituation();
                } else {
                    TargetSyncOperation top = new TargetSyncOperation();
                    op = top;
                    top.fromJsonValue(params);
                    String targetId = params.get("targetId").required().asString();

                    entry = new ReconEntry(top, rootContext, dateUtil);
                    entry.targetId = LazyObjectAccessor.qualifiedId(targetObjectSet, targetId);
                    top.targetObjectAccessor = new LazyObjectAccessor(service, targetObjectSet, targetId);
                    if (null == top.getTargetObject()) {
                        exception = new SynchronizationException(
                                "Target object " + entry.targetId + " does not exists");
                        throw exception;
                    }
                    top.assessSituation();
                }
                // IF an expected situation is supplied, compare and reject if current situation changed
                if (params.isDefined("situation")) {
                    Situation situation = params.get("situation").required().asEnum(Situation.class);
                    if (!situation.equals(op.situation)) {
                        exception = new SynchronizationException(
                                "Expected situation does not match. Expect: " + situation.name()
                                + " Found: " + op.situation.name());
                        throw exception;
                    }
                }
                op.action = action;
                op.performAction();
            } catch (SynchronizationException se) {
                if (op.action != Action.EXCEPTION) {
                    entry.status = Status.FAILURE; // exception was not intentional
                    if (reconId != null) {
                        LOGGER.warn("Unexpected failure during source reconciliation {}", reconId, se);
                    } else {
                        LOGGER.warn("Unexpected failure in performing action {}", params, se);
                    }
                }
                setReconEntryMessage(entry, se);
            }
            if (reconId != null && !Action.NOREPORT.equals(action) && (entry.status == Status.FAILURE || op.action != null)) {
                entry.timestamp = new Date();
                if (op instanceof SourceSyncOperation) {
                    entry.reconciling = "source";
                    if (op.getTargetObject() != null) {
                        entry.targetId = LazyObjectAccessor.qualifiedId(targetObjectSet,
                                op.getTargetObject().get("_id").asString());
                    }
                    entry.setAmbiguousTargetIds(((SourceSyncOperation) op).getAmbiguousTargetIds());
                } else {
                    entry.reconciling = "target";
                    if (op.getSourceObject() != null) {
                        entry.sourceId = LazyObjectAccessor.qualifiedId(sourceObjectSet,
                                op.getSourceObject().get("_id").asString());
                    }
                }
                entry.actionId = op.actionId;
                logReconEntry(entry);
            }
            if (exception != null) {
                throw exception;
            }
        } finally {
            if (reconId != null) {
                ObjectSetContext.pop(); // pop the TriggerContext
            }
        }
    }

    private void doResults(ReconciliationContext reconContext) throws SynchronizationException {
        if (resultScript != null) {
            Map<String, Object> scope = new HashMap<String, Object>();
            scope.put("source", reconContext.getStatistics().getSourceStat().asMap());
            scope.put("target", reconContext.getStatistics().getSourceStat().asMap());
            scope.put("global", reconContext.getStatistics().asMap());
            try {
                resultScript.exec(scope);
            } catch (ScriptException se) {
                LOGGER.debug("{} result script encountered exception", name, se);
                throw new SynchronizationException(se);
            }
        }
    }

    /**
     * Execute a full reconciliation
     *
     * @param reconContext the context specific to the reconciliation run
     * @throws SynchronizationException if any unforseen failure occurs during the reconciliation
     */
    public void recon(ReconciliationContext reconContext) throws SynchronizationException {
        EventEntry measure = Publisher.start(EVENT_RECON, reconContext.getReconId(), null);
        doRecon(reconContext);
        measure.end();
    }

    /**
     * TEMPORARY. Future version will have this break-down into discrete units of work.
     * @param reconContext
     * @throws SynchronizationException
     */
    private void doRecon(ReconciliationContext reconContext) throws SynchronizationException {
        reconContext.getStatistics().reconStart();
        String reconId = reconContext.getReconId();
        EventEntry measureIdQueries = Publisher.start(EVENT_RECON_ID_QUERIES, reconId, null);
        reconContext.setStage(ReconStage.ACTIVE_QUERY_ENTRIES);
        ServerContext context = ObjectSetContext.get();
        try {
            context = new TriggerContext(context, "recon");
            ObjectSetContext.push(context);
            Context rootContext = context.asContext(RootContext.class);
            logReconStart(reconId, rootContext, context);

            // Get the relevant source (and optionally target) identifiers before we assess the situations
            reconContext.getStatistics().sourceQueryStart();
            Iterator<String> sourceIdsIter = reconContext.querySourceIdsIter();
            reconContext.getStatistics().sourceQueryEnd();
            if (!sourceIdsIter.hasNext()) {
                if (!reconContext.getReconHandler().allowEmptySourceSet()) {
                    LOGGER.warn("Cannot perform reconciliation with an empty source object set, unless explicitly configured to allow it.");
                    reconContext.getStatistics().reconEnd();
                    logReconEnd(reconContext, rootContext, context);
                    return;
                }
            }

            // If we will handle a target phase, pre-load all relevant target identifiers
            Collection<String> remainingTargetIds = null;
            if (reconContext.getReconHandler().isRunTargetPhase()) {
                reconContext.getStatistics().targetQueryStart();
                remainingTargetIds = reconContext.getReconHandler().queryTargetIds();
                reconContext.setTargetIds(new ArrayList(remainingTargetIds));
                reconContext.getStatistics().targetQueryEnd();
            } else {
                remainingTargetIds = new ArrayList<String>();
            }

            // Optionally get all links up front as well
            Map<String, Link> allLinks = null;
            if (prefetchLinks) {
                reconContext.getStatistics().linkQueryStart();
                allLinks = Link.getLinksForMapping(ObjectMapping.this);
                reconContext.setAllLinks(allLinks);
                reconContext.getStatistics().linkQueryEnd();
            }

            measureIdQueries.end();

            EventEntry measureSource = Publisher.start(EVENT_RECON_SOURCE, reconId, null);
            reconContext.setStage(ReconStage.ACTIVE_RECONCILING_SOURCE);

            ReconPhase sourcePhase = new ReconPhase(sourceIdsIter, reconContext, context,
                    rootContext, allLinks, remainingTargetIds, sourceRecon);
            sourcePhase.execute();
            measureSource.end();

            LOGGER.debug("Remaining targets after source phase : {}", remainingTargetIds);

            if (reconContext.getReconHandler().isRunTargetPhase()) {
                EventEntry measureTarget = Publisher.start(EVENT_RECON_TARGET, reconId, null);
                reconContext.setStage(ReconStage.ACTIVE_RECONCILING_TARGET);              

                ReconPhase targetPhase = new ReconPhase(remainingTargetIds.iterator(), reconContext, context, 
                        rootContext, allLinks, null, targetRecon);
                targetPhase.execute();
                measureTarget.end();
            }

            reconContext.getStatistics().reconEnd();
            reconContext.setStage(ReconStage.ACTIVE_PROCESSING_RESULTS);
            doResults(reconContext);
            reconContext.setStage(ReconStage.COMPLETED_SUCCESS);
            logReconEnd(reconContext, rootContext, context);
        } catch (InterruptedException ex) {
            reconContext.checkCanceled();
            throw new SynchronizationException("Interrupted execution of reconciliation", ex);
        } finally {
            ObjectSetContext.pop(); // pop the TriggerContext
            if (!reconContext.getStatistics().hasEnded()) {
                reconContext.getStatistics().reconEnd();
            }
        }

// TODO: cleanup orphan link objects (no matching source or target) here
    }

    /**
     * Reconcile a given source ID
     * @param sourceId the id to reconcile
     * @param reconContext reconciliation context
     * @param rootContext json resource root ctx
     * @param allLinks all links if pre-queried, or null for on-demand link querying
     * @param remainingTargetIds The set to update/remove any targets that were matched
     * @throws SynchronizationException if there is a failure reported in reconciling this id
     */
    void reconSourceById(String sourceId, ReconciliationContext reconContext, Context rootContext,
            Map<String, Link> allLinks, Collection<String> remainingTargetIds) throws SynchronizationException {
        SourceSyncOperation op = new SourceSyncOperation();
        op.reconContext = reconContext;
        ReconEntry entry = new ReconEntry(op, rootContext, dateUtil);
        op.sourceObjectAccessor = new LazyObjectAccessor(service, sourceObjectSet, sourceId);
        if (allLinks != null) {
            String normalizedSourceId = linkType.normalizeSourceId(sourceId);
            op.initializeLink(allLinks.get(normalizedSourceId));
        }
        entry.sourceId = LazyObjectAccessor.qualifiedId(sourceObjectSet, sourceId);
        op.reconId = reconContext.getReconId();
        try {
            op.sync();
        } catch (SynchronizationException se) {
            if (op.action != Action.EXCEPTION) {
                entry.status = Status.FAILURE; // exception was not intentional
                LOGGER.warn("Unexpected failure during source reconciliation {}", op.reconId, se);
            }
            setReconEntryMessage(entry, se);
        }
        String[] targetIds = op.getTargetIds();
        for (String handledId : targetIds) {
            // If target system has case insensitive IDs, remove without regard to case
            String normalizedHandledId = linkType.normalizeTargetId(handledId);
            remainingTargetIds.remove(normalizedHandledId);
            LOGGER.trace("Removed target from remaining targets: {}", normalizedHandledId);
        }
        if (!Action.NOREPORT.equals(op.action) && (entry.status == Status.FAILURE || op.action != null)) {
            entry.timestamp = new Date();
            entry.reconciling = "source";
            try {
                if (op.hasTargetObject()) {
                    entry.targetId = LazyObjectAccessor.qualifiedId(targetObjectSet, op.getTargetObjectId());
                }
            } catch (SynchronizationException ex) {
                entry.message = "Failure in preparing recon entry " + ex.getMessage()
                        + " for target: " + op.getTargetObjectId() + " original status: " + entry.status
                        + " message: "  + entry.message;
                entry.status = Status.FAILURE;
            }
            entry.setAmbiguousTargetIds(op.getAmbiguousTargetIds());
            entry.actionId = op.actionId;
            logReconEntry(entry);
        }
    }

    public void setReconEntryMessage(ReconEntry entry, Exception syncException) {
        JsonValue messageDetail = null;  // top level ResourceException
        Throwable cause = syncException; // Root cause
        entry.exception = syncException;
        
        // Loop to find original cause and top level ResourceException (if any)
        while (cause.getCause() != null) {
            cause = cause.getCause();
            if (messageDetail == null && cause instanceof ResourceException) {
                messageDetail = ((ResourceException) cause).toJsonValue();
                // Check if there is a detail field to use
                if (!messageDetail.get("detail").isNull()) {
                	messageDetail = messageDetail.get("detail");
                }
            }
        }
        
        // Set message and messageDetail
        entry.messageDetail = messageDetail;
        entry.message = syncException != cause 
                ? syncException.getMessage() + ". Root cause: " + cause.getMessage()
                : cause.getMessage();
    }
    
    private final ReconAction sourceRecon = new ReconAction() {
        /**
         * Reconcile a given source ID
         * @param id the id to reconcile
         * @param reconContext reconciliation context
         * @param rootContext json resource root ctx
         * @param allLinks all links if pre-queried, or null for on-demand link querying
         * @param remainingIds The set to update/remove any targets that were matched
         * @throws SynchronizationException if there is a failure reported in reconciling this id
         */
        @Override
        public void recon(String id, ReconciliationContext reconContext, Context rootContext, Map<String, Link> allLinks, Collection<String> remainingIds)
                throws SynchronizationException {
            SourceSyncOperation op = new SourceSyncOperation();
            op.reconContext = reconContext;
            ReconEntry entry = new ReconEntry(op, rootContext, dateUtil);
            op.sourceObjectAccessor = new LazyObjectAccessor(service, sourceObjectSet, id);
            if (allLinks != null) {
                String normalizedSourceId = linkType.normalizeSourceId(id);
                op.initializeLink(allLinks.get(normalizedSourceId));
            }
            entry.sourceId = LazyObjectAccessor.qualifiedId(sourceObjectSet, id);
            op.reconId = reconContext.getReconId();
            try {
                op.sync();
            } catch (SynchronizationException se) {
                if (op.action != Action.EXCEPTION) {
                    entry.status = Status.FAILURE; // exception was not intentional
                    LOGGER.warn("Unexpected failure during source reconciliation {}", op.reconId, se);
                }
                setReconEntryMessage(entry, se);
            }
            String[] targetIds = op.getTargetIds();
            for (String handledId : targetIds) {
                // If target system has case insensitive IDs, remove without regard to case
                String normalizedHandledId = linkType.normalizeTargetId(handledId);
                remainingIds.remove(normalizedHandledId);
                LOGGER.trace("Removed target from remaining targets: {}", normalizedHandledId);
            }
            if (!Action.NOREPORT.equals(op.action) && (entry.status == Status.FAILURE || op.action != null)) {
                entry.timestamp = new Date();
                entry.reconciling = "source";
                try {
                    if (op.hasTargetObject()) {
                        entry.targetId = LazyObjectAccessor.qualifiedId(targetObjectSet, op.getTargetObjectId());
                    }
                } catch (SynchronizationException ex) {
                    entry.message = "Failure in preparing recon entry " + ex.getMessage()
                            + " for target: " + op.getTargetObjectId() + " original status: " + entry.status
                            + " message: "  + entry.message;
                    entry.status = Status.FAILURE;
                }
                entry.setAmbiguousTargetIds(op.getAmbiguousTargetIds());
                entry.actionId = op.actionId;
                logReconEntry(entry);
            }
        }
    };

    private final ReconAction targetRecon = new ReconAction() {
        /**
        * Reconcile a given target ID
        * @param id the id to reconcile
        * @param reconContext reconciliation context
        * @param rootContext json resource root ctx
        * @param allLinks all links if pre-queried, or null for on-demand link querying
        * @throws SynchronizationException if there is a failure reported in reconciling this id
        */
        @Override
        public void recon(String id, ReconciliationContext reconContext, Context rootContext, Map<String, Link> allLinks, Collection<String> remainingIds)  throws SynchronizationException {
            reconContext.checkCanceled();
            TargetSyncOperation op = new TargetSyncOperation();
            op.reconContext = reconContext;
            ReconEntry entry = new ReconEntry(op, rootContext, dateUtil);
            entry.targetId = LazyObjectAccessor.qualifiedId(targetObjectSet, id);
            op.reconId = reconContext.getReconId();
            try {
                op.targetObjectAccessor = new LazyObjectAccessor(service, targetObjectSet, id);
                op.sync();
            } catch (SynchronizationException se) {
                if (op.action != Action.EXCEPTION) {
                    entry.status = Status.FAILURE; // exception was not intentional
                    LOGGER.warn("Unexpected failure during target reconciliation {}", reconContext.getReconId(), se);
                }
                setReconEntryMessage(entry, se);
            }
            if (!Action.NOREPORT.equals(op.action) && (entry.status == Status.FAILURE || op.action != null)) {
                entry.timestamp = new Date();
                entry.reconciling = "target";
                if (op.getSourceObjectId() != null) {
                    entry.sourceId = LazyObjectAccessor.qualifiedId(sourceObjectSet, op.getSourceObjectId());
                }
                entry.actionId = op.actionId;
                logReconEntry(entry);
            }
        }
    };
      
    /**
     * Wrapper to submit source/target recon for a given id for concurrent processing
     * @author aegloff
     */
    class ReconTask implements Callable<Void> {
        String id;
        ReconciliationContext reconContext;
        ServerContext parentContext;
        Context rootContext;
        Map<String, Link> allLinks;
        Collection<String> remainingIds;
        ReconAction reconById;

        public ReconTask(String id, ReconciliationContext reconContext,
                               ServerContext parentContext, Context rootContext,
                Map<String, Link> allLinks, Collection<String> remainingIds, ReconAction reconById) {
            this.id = id;
            this.reconContext = reconContext;
            this.parentContext = parentContext;
            this.rootContext = rootContext;
            this.allLinks = allLinks;
            this.remainingIds = remainingIds;
            this.reconById = reconById;
        }

        public Void call() throws SynchronizationException {
            //TODO I miss the Request Context
            ObjectSetContext.push(new ServerContext(parentContext));
            try {
                reconById.recon(id, reconContext, rootContext, allLinks, remainingIds);
            } finally {
                ObjectSetContext.pop();
            }
            return null;
        }
    }

    /**
     * Reconcile the source/target phase, multi threaded or single threaded.
     * @author aegloff
     */
    class ReconPhase extends ReconFeeder {
        ServerContext parentContext;
        Context rootContext;
        Map<String, Link> allLinks;
        Collection<String> remainingIds;
        ReconAction reconById;

        public ReconPhase(Iterator<String> idsIter, ReconciliationContext reconContext,
                ServerContext parentContext, Context rootContext,
                Map<String, Link> allLinks, Collection<String> remainingIds, ReconAction reconById) {
            super(idsIter, reconContext, reconById);
            this.parentContext = parentContext;
            this.rootContext = rootContext;
            this.allLinks = allLinks;
            this.remainingIds = remainingIds;
            this.reconById = reconById;
        }
        @Override
        Callable createTask(String id) throws SynchronizationException {
            return new ReconTask(id, reconContext, parentContext, rootContext,
                    allLinks, remainingIds, reconById);
        }
    }

    /**
     * @return the configured number of threads to use for processing tasks.
     * 0 to process in a single thread.
     */
    int getTaskThreads() {
        return taskThreads;
    }

    /**
     * TODO: Description.
     *
     * @param entry TODO.
     * @throws SynchronizationException TODO.
     */
    private void logReconEntry(ReconEntry entry) throws SynchronizationException {
        try {
            CreateRequest cr = Requests.newCreateRequest("audit/recon",entry.toJsonValue());
            service.getConnectionFactory().getConnection().create(service.getServerContext(), cr);
        } catch (ResourceException ose) {
            throw new SynchronizationException(ose);
        }
    }

    private void logReconStart(String reconId, Context rootContext, ServerContext context) throws SynchronizationException {
        ReconEntry reconStartEntry = new ReconEntry(null, rootContext, ReconEntry.RECON_START, dateUtil);
        reconStartEntry.timestamp = new Date();
        reconStartEntry.reconId = reconId;
        reconStartEntry.message = "Reconciliation initiated by " + context.asContext(SecurityContext.class).getAuthenticationId();
        logReconEntry(reconStartEntry);
    }

    private void logReconEnd(ReconciliationContext reconContext, Context rootContext, ServerContext context) throws SynchronizationException {
        ReconEntry reconEndEntry = new ReconEntry(null, rootContext, ReconEntry.RECON_END, dateUtil);
        reconEndEntry.timestamp = new Date();
        reconEndEntry.reconId = reconContext.getReconId();
        String simpleSummary = reconContext.getStatistics().simpleSummary();
        reconEndEntry.message = simpleSummary;
        reconEndEntry.messageDetail = new JsonValue(reconContext.getSummary());
        logReconEntry(reconEndEntry);
        LOGGER.info("Reconciliation completed. " + simpleSummary);
    }

    /**
     * Execute a sync engine action explicitly, without going through situation assessment.
     * @param sourceObject the source object if applicable to the action
     * @param targetObject the target object if applicable to the action
     * @param situation an optional situation that was originally assessed. Null if not the result of an earlier situation assessment.
     * @param action the explicit action to invoke
     * @param reconId an optional identifier for the recon context if this is done in the context of reconciliation
     */
    public void explicitOp(JsonValue sourceObject, JsonValue targetObject, Situation situation, Action action, String reconId)
            throws SynchronizationException {
        ExplicitSyncOperation linkOp = new ExplicitSyncOperation();
        linkOp.init(sourceObject, targetObject, situation, action, reconId);
        linkOp.sync();
    }

    /**
     * TODO: Description.
     */
    abstract class SyncOperation {

        /** TODO: Description. */
        public String reconId;
        /** An optional reconciliation context */
        public ReconciliationContext reconContext;
        /** Access to the source object */
        public LazyObjectAccessor sourceObjectAccessor;
        /** Access to the target object */
        public LazyObjectAccessor targetObjectAccessor;
        /** Optional value of the source object before the change that triggered this sync, or null if not supplied */
        public JsonValue oldValue;

        /**
         * Holds the link representation
         * An initialized link can be interpreted as representing state retrieved from the repository,
         * i.e. a linkObject with id of null represents a link that does not exist (yet)
         */
        public Link linkObject = new Link(ObjectMapping.this);
        // This operation  newly created the link.
        // linkObject above may not be set for newly created links
        boolean linkCreated;

        /** TODO: Description. */
        public Situation situation;
        /** TODO: Description. */
        public Action action;
        public String actionId;
        public boolean ignorePostAction = false;
        public Policy activePolicy = null;

        /**
         * TODO: Description.
         *
         * @return sync results of the {@link SyncOperation}
         * @throws SynchronizationException TODO.
         */
        public abstract JsonValue sync() throws SynchronizationException;

        protected abstract boolean isSourceToTarget();


        /**
         * @return the source object, potentially loaded on demand and/or cached, or null if does not exist
         * @throws SynchronizationException if on-demand load of the object failed
         */
        protected JsonValue getSourceObject() throws SynchronizationException {
            if (sourceObjectAccessor == null || sourceObjectAccessor.getLocalId() == null) {
                return null;
            } else {
                return sourceObjectAccessor.getObject();
            }
        }

        /**
         * @return the target object, potentially loaded on demand and/or cached, or null if does not exist
         * @throws SynchronizationException if on-demand load of the object failed
         */
        protected JsonValue getTargetObject() throws SynchronizationException {
            if (targetObjectAccessor == null || (!targetObjectAccessor.isLoaded() && targetObjectAccessor.getLocalId() == null)) {
                return null;
            } else {
                return targetObjectAccessor.getObject();
            }
        }

        /**
         * The set unqualified (local) source object ID
         * That a source identifier is set does not automatically imply that the source object exists.
         * @return local identifier of the source object, or null if none
         */
        protected String getSourceObjectId() {
            return sourceObjectAccessor == null ? null : sourceObjectAccessor.getLocalId();
        }

        /**
         * The set unqualified (local) targt object ID
         * That a target identifier is set does not automatically imply that the target object exists.
         * @return local identifier of the target object, or null if none
         */
        protected String getTargetObjectId() {
            return targetObjectAccessor == null ? null : targetObjectAccessor.getLocalId();
        }

        /**
          * @return Whether the target representation is loaded, i.e. the getObject represents what it found.
          * IF a target was not found, the state is loaded with a payload / object of null.
         */
        protected boolean isTargetLoaded() {
            return targetObjectAccessor == null ? false : targetObjectAccessor.isLoaded();
        }

        /**
         * @return Whether the source object exists. May cause the loading of the (lazy) source object,
         * or in the context of reconciliation may check against the bulk existing source/target IDs if
         * the object existed at that point.
         * @throws SynchronizationException if on-demand load of the object failed
         */
        protected boolean hasSourceObject() throws SynchronizationException {
            boolean defined = false;
            if (sourceObjectAccessor == null || sourceObjectAccessor.getLocalId() == null) {
                defined = false;
            } else {
                if (sourceObjectAccessor.isLoaded() && sourceObjectAccessor.getObject() != null) {
                    // Check against already laoded/defined object first, without causing new load
                    defined = true;
                } else if (reconContext != null && reconContext.getSourceIds() != null) {
                    // If available, check against all queried existing IDs
                    defined = reconContext.getSourceIds().contains(sourceObjectAccessor.getLocalId());
                } else {
                    // If no lists of existing ids is available, do a load of the object to check
                    defined = (sourceObjectAccessor.getObject() != null);
                }
            }
            return defined;
        }

        /**
         * @return Whether the target object exists. May cause the loading of the (lazy) source object,
         * or in the context of reconciliation may check against the bulk existing source/target IDs if
         * the object existed at that point.
         * @throws SynchronizationException if on-demand load of the object failed
         */
        protected boolean hasTargetObject() throws SynchronizationException {
            boolean defined = false;

            if (isTargetLoaded()) {
                // Check against already laoded/defined object first, without causing new load
                defined = (targetObjectAccessor.getObject() != null);
            } else if (targetObjectAccessor == null || targetObjectAccessor.getLocalId() == null) {
                // If it's not loaded, but no id to load is available it has no target
                defined = false;
            } else {
                // Either check against a list of all targets, or load to check for existence
                if (reconContext != null && reconContext.getTargetIds() != null) {
                    // If available, check against all queried existing IDs
                    // If target system has case insensitive IDs, compare without regard to case
                    String normalizedTargetId = linkType.normalizeTargetId(targetObjectAccessor.getLocalId());
                    defined = reconContext.getTargetIds().contains(normalizedTargetId);
                } else {
                    // If no lists of existing ids is available, do a load of the object to check
                    defined = (targetObjectAccessor.getObject() != null);
                }
            }

            return defined;
        }

        /**
         * @return true if it knows there were no objects in the target set during a bulk query
         * at the outset of reconciliation.
         * false if there were objects, or it does not know.
         * Does not take into account objects getting added during reconciliation, or data getting added
         * by another process concurrently
         */
        protected boolean hadEmptyTargetObjectSet() {
            if (reconContext != null && reconContext.getTargetIds() != null) {
                // If available, check against all queried existing IDs
                return (reconContext.getTargetIds().size() == 0);
            } else {
                return false;
            }
        }

        /**
         * @return the found unqualified (local) link ID, null if none
         */
        protected String getLinkId() {
            if (linkObject != null && linkObject.initialized) {
                return linkObject._id;
            } else {
                return null;
            }
        }

        /**
         * Initializes the link representation
         * @param link the link object for links that were found/exist in the repository, null to represent no existing link
         */
        protected void initializeLink(Link link) {
            if (link != null) {
                this.linkObject = link;
            } else {
                // Keep track of the fact that we did not find a link
                this.linkObject.clear();
                this.linkObject.initialized = true;
            }
        }

        protected Action getAction() {
            return (this.action == null ? Action.IGNORE : this.action);
        }

        /**
         * TODO: Description.
         * @param sourceAction sourceAction true if the {@link Action} is determined for the {@link SourceSyncOperation}
         * and false if the action is determined for the {@link TargetSyncOperation}.
         * @throws SynchronizationException TODO.
         */
        protected void determineAction(boolean sourceAction) throws SynchronizationException {
            if (situation != null) {
                action = situation.getDefaultAction(); // start with a reasonable default
                for (Policy policy : policies) {
                    if (situation == policy.getSituation()) {
                        activePolicy = policy;
                        action = activePolicy.getAction(sourceObjectAccessor, targetObjectAccessor, this);
                        // TODO: Consider limiting what actions can be returned for the given situation.
                        break;
                    }
                }
            }
            LOGGER.debug("Determined action to be {}", action);
        }

        /**
         * TODO: Description.
         *
         * @throws SynchronizationException TODO.
         */
        @SuppressWarnings("fallthrough")
        protected void performAction() throws SynchronizationException {
            switch (getAction()) {
                case CREATE:
                case UPDATE:
                case LINK:
                case DELETE:
                case UNLINK:
                case EXCEPTION:
                    try {
                        actionId = ObjectSetContext.get().getId();
                        switch (getAction()) {
                            case CREATE:
                                if (getSourceObject() == null) {
                                    throw new SynchronizationException("no source object to create target from");
                                }
                                if (getTargetObject() != null) {
                                    throw new SynchronizationException("target object already exists");
                                }
                                JsonValue createTargetObject = new JsonValue(new HashMap<String, Object>());
                                applyMappings(getSourceObject(), oldValue, createTargetObject, new JsonValue(null)); // apply property mappings to target
                                targetObjectAccessor = new LazyObjectAccessor(service, targetObjectSet,
                                        createTargetObject.get("_id").asString(), createTargetObject);
                                execScript("onCreate", onCreateScript);

                                // Allow the early link creation as soon as the target identifier is known
                                String sourceId = getSourceObjectId();
                                if (isLinkingEnabled()) {
                                    // Create and populate the PendingLinkContext, replacing the top of the ObjectSetContext stack
                                    Context oldContext = ObjectSetContext.pop();
                                    ServerContext newContext = PendingLink.populate(oldContext, ObjectMapping.this.name, sourceId, getSourceObject(), reconId, situation);
                                    ObjectSetContext.push(newContext);
                                    
                                }
                                Context context = ObjectSetContext.get();

                                targetObjectAccessor = createTargetObject(createTargetObject);

                                if (!isLinkingEnabled()) {
                                    LOGGER.debug("Linking disabled for {} during {}, skipping additional link processing", sourceId, reconId);
                                    break;
                                }

                                boolean wasLinked = PendingLink.wasLinked(context);
                                if (wasLinked) {
                                    linkCreated = true;
                                    LOGGER.debug("Pending link for {} during {} has already been created, skipping additional link processing", sourceId, reconId);
                                    break;
                                } else {
                                    LOGGER.debug("Pending link for {} during {} not yet resolved, proceed to link processing", sourceId, reconId);
                                    PendingLink.clear(context); // We'll now handle link creation ourselves
                                }
                                // falls through to link the newly created target
                            case UPDATE:
                            case LINK:
                                String targetId = getTargetObjectId();
                                if (getTargetObjectId() == null) {
                                    throw new SynchronizationException("no target object to link");
                                }

                                // get a copy of the target before the onLink trigger,
                                // the onUpdate trigger or the mappings are applied
                                JsonValue oldTarget = getTargetObject().copy();

                                if (isLinkingEnabled() && linkObject._id == null) {
                                    try {
                                        createLink(getSourceObjectId(), targetId, reconId);
                                        linkCreated = true;
                                    } catch (SynchronizationException ex) {
                                        // Allow for link to have been created in the meantime, e.g. programmatically
                                        // create would fail with a failed precondition for link already existing
                                        // Try to read again to see if that is the issue
                                        linkObject.getLinkForSource(getSourceObjectId());
                                        if (linkObject._id == null) {
                                            LOGGER.warn("Failed to create link between {}-{}",
                                                    new Object[] {LazyObjectAccessor.qualifiedId(sourceObjectSet, getSourceObjectId()),
                                                    LazyObjectAccessor.qualifiedId(targetObjectSet, targetId), ex});
                                            throw ex; // it was a different issue
                                        }
                                    }
                                }
                                if (isLinkingEnabled() && linkObject._id != null && !linkObject.targetEquals(targetId)) {
                                    linkObject.targetId = targetId;
                                    linkObject.update();
                                }
                                // TODO: Detect change of source id, and update link accordingly.
                                if (action == Action.CREATE || action == Action.LINK) {
                                    break; // do not update target
                                }
                                if (getSourceObject() != null && getTargetObject() != null) {
                                    applyMappings(getSourceObject(), oldValue, getTargetObject(), oldTarget);
                                    execScript("onUpdate", onUpdateScript, oldTarget);
                                    if (JsonPatch.diff(oldTarget, getTargetObject())
                                            .size() > 0) { // only update if target changes
                                        updateTargetObject(getTargetObject());
                                    }
                                }
                                break; // terminate UPDATE
                            case DELETE:
                                if (getTargetObjectId() != null && getTargetObject() != null) { // forgiving; does nothing if no target
                                    execScript("onDelete", onDeleteScript);
                                    deleteTargetObject(getTargetObject());
                                    // Represent as not existing anymore so it gets removed from processed targets
                                    targetObjectAccessor = new LazyObjectAccessor(service,
                                            targetObjectSet, getTargetObjectId(), null);
                                }
                                // falls through to unlink the deleted target
                            case UNLINK:
                                if (linkObject._id != null) { // forgiving; does nothing if no link exists
                                    execScript("onUnlink", onUnlinkScript);
                                    linkObject.delete();
                                }
                                break; // terminate DELETE and UNLINK
                            case EXCEPTION:
                                throw new SynchronizationException("Situation " + situation + " marked as EXCEPTION"); // aborts change; recon reports
                        }
                    } catch (JsonValueException jve) {
                        throw new SynchronizationException(jve);
                    }
                case REPORT:
                case NOREPORT:
                    if (!ignorePostAction) {
                        if (null == activePolicy) {
                            for (Policy policy : policies) {
                                if (situation == policy.getSituation()) {
                                    activePolicy = policy;
                                    break;
                                }
                            }
                        }
                        postAction(isSourceToTarget());
                    }
                    break;
                case ASYNC:
                case IGNORE:
            }
        }

        /**
         * TODO: Description.
         * @param sourceAction sourceAction true if the {@link Action} is determined for the {@link SourceSyncOperation}
         * and false if the action is determined for the {@link TargetSyncOperation}.
         * @throws SynchronizationException TODO.
         */
        protected void postAction(boolean sourceAction) throws SynchronizationException {
            if (null != activePolicy) {
                activePolicy.evaluatePostAction(sourceObjectAccessor, targetObjectAccessor, action, sourceAction);
            }
        }

        protected void createLink(String sourceId, String targetId, String reconId) throws SynchronizationException {
            Link linkObject = new Link(ObjectMapping.this);
            execScript("onLink", onLinkScript);
            linkObject.sourceId = sourceId;
            linkObject.targetId = targetId;
            linkObject.create();
            initializeLink(linkObject);
            LOGGER.debug("Established link sourceId: {} targetId: {} in reconId: {}", new Object[] {sourceId, targetId, reconId});
        }
        
        /**
         * Checks any declared source conditions on the source object
         * 
         * @return true if the conditions pass, false otherwise
         * @throws SynchronizationException
         */
        protected boolean checkSourceConditions() throws SynchronizationException {
            if (!sourceCondition.isNull()) {
                for (String key : sourceCondition.keys()) {
                    JsonPointer pointer = new JsonPointer(key);
                    JsonValue source = sourceObjectAccessor.getObject();
                    if (!source.isNull() && source.get(pointer) != null) {
                        JsonValue value = source.get(pointer);
                        if (value.isList()) {
                            if (!value.asList().contains(sourceCondition.get(key).getObject())) {
                                return false;
                            }
                        } else if (!value.getObject().equals(sourceCondition.get(key).getObject())) {
                            return false;
                        }
                    } else {
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * Evaluated source valid on source object
         * @see SyncOperation#isSourceValid(JsonValue)
         */
        protected boolean isSourceValid() throws SynchronizationException {
            return isSourceValid(null);
        }

        /**
         * Evaluates source valid for the supplied sourceObjectOverride, or the source object
         * associated with the sync operation if null
         *
         * @return whether valid for this mapping or not.
         * @throws SynchronizationException if evaluation failed.
         */
        protected boolean isSourceValid(JsonValue sourceObjectOverride) throws SynchronizationException {
            boolean result = false;
            if (hasSourceObject() || sourceObjectOverride != null) { // must have a source object to be valid
                if (validSource != null) {
                    Map<String, Object> scope = new HashMap<String, Object>();
                    if (sourceObjectOverride != null) {
                        scope.put("source", sourceObjectOverride.asMap());
                    } else {
                        // TODO: This forced load into memory is necessary until we can do on demand get in script engine
                        JsonValue sourceValue = getSourceObject();
                        scope.put("source", null != sourceValue ? sourceValue.getObject() : null);
                    }
                    try {
                        Object o = validSource.exec(scope);
                        if (o == null || !(o instanceof Boolean)) {
                            throw new SynchronizationException("Expecting boolean value from validSource");
                        }
                        result = (Boolean) o;
                    } catch (ScriptException se) {
                        LOGGER.debug("{} validSource script encountered exception", name, se);
                        throw new SynchronizationException(se);
                    }
                } else { // no script means true
                    result = true;
                }
            }
            if (sourceObjectOverride == null) {
                LOGGER.trace("isSourceValid of {} evaluated: {}", getSourceObjectId(), result);
            }
            return result;
        }

        /**
         * TODO: Description.
         *
         * @return TODO.
         * @throws SynchronizationException TODO.
         */
        protected boolean isTargetValid() throws SynchronizationException {
            boolean result = false;
            if (hasTargetObject()) { // must have a target object to qualify
                if (validTarget != null && getTargetObject() != null) { // forces pulling object into memory
                    Map<String, Object> scope = new HashMap<String, Object>();
                    scope.put("target", getTargetObject().asMap());
                    try {
                        Object o = validTarget.exec(scope);
                        if (o == null || !(o instanceof Boolean)) {
                            throw new SynchronizationException("Expecting boolean value from validTarget");
                        }
                        result = (Boolean) o;
                    } catch (ScriptException se) {
                        LOGGER.debug("{} validTarget script encountered exception", name, se);
                        throw new SynchronizationException(se);
                    }
                } else { // no script means true
                    result = true;
                }
            }
            LOGGER.trace("isTargetValid of {} evaluated: {}", getTargetObjectId(), result);
            return result;
        }

        /**
         * @see #execScript with oldTarget null
         */
        private void execScript(String type, Script script) throws SynchronizationException {
            execScript(type, script, null);
        }

        /**
         * Executes the given script with the appropriate context information
         *
         * @param type The script hook name
         * @param script The script to execute
         * @param oldTarget optional old target object before any mappings were applied,
         * such as before an update
         * null if not applicable to this script hook
         * @throws SynchronizationException TODO.
         */
        private void execScript(String type, Script script, JsonValue oldTarget) throws SynchronizationException {
            if (script != null) {
                Map<String, Object> scope = new HashMap<String, Object>();
                // TODO: Once script engine can do on-demand get replace these forced loads
                if (getSourceObjectId() != null) {
                    JsonValue source = getSourceObject();
                    scope.put("source", null != source ? source.getObject() : null);
                }
                // Target may not have ID yet, e.g. an onCreate with the target object defined,
                // but not stored/id assigned.
                if (isTargetLoaded() || getTargetObjectId() != null) {
                    if (getTargetObject() != null) {
                        scope.put("target", getTargetObject().asMap());
                        if (oldTarget != null) {
                            scope.put("oldTarget", oldTarget.asMap());
                        }
                    }
                }
                if (situation != null) {
                    scope.put("situation", situation.toString());
                }
                try {
                    script.exec(scope);
                } catch (ScriptThrownException se) {
                    LOGGER.debug("{} script encountered exception", name + " " + type, se);
                    throw new SynchronizationException(
                            se.toResourceException(ResourceException.INTERNAL_ERROR,
                                    name + " " + type + " script encountered exception"));
                } catch (ScriptException se) {
                    LOGGER.debug("{} script encountered exception", name + " " + type, se);
                    throw new SynchronizationException(
                            new InternalErrorException(name + " " + type + " script encountered exception", se));
                }
            }
        }

        public JsonValue toJsonValue() {
            JsonValue actionParam = new JsonValue(new HashMap<String,Object>());
            actionParam.put("reconId", reconId);
            actionParam.put("mapping", ObjectMapping.this.getName());
            actionParam.put("situation", situation != null ? situation.name() : null);
            actionParam.put("action", situation != null ? situation.getDefaultAction().name() : null);
            actionParam.put("sourceId", getSourceObjectId());
            if (targetObjectAccessor != null && targetObjectAccessor.getLocalId() != null) {
                actionParam.put("targetId", targetObjectAccessor.getLocalId());
            }
            return actionParam;
        }
    }
   
    /**
     * Explicit execution of a sync operation where the appropriate
     * action is known without having to assess the situation and apply
     * policy to decide the action
     */
    private class ExplicitSyncOperation extends SyncOperation {

        protected boolean isSourceToTarget() {
            //TODO: detect by the source id match
            return true;
        }

        public void init(JsonValue sourceObject, JsonValue targetObject, Situation situation, Action action, String reconId) {
            this.reconId = reconId;
            this.sourceObjectAccessor = new LazyObjectAccessor(service, sourceObjectSet, sourceObject.get("_id").required().asString(), sourceObject);
            this.targetObjectAccessor = new LazyObjectAccessor(service, targetObjectSet, targetObject.get("_id").required().asString(), targetObject);
            this.situation = situation;
            this.action = action;
            this.ignorePostAction = true;
        }

        @Override
        public JsonValue sync() throws SynchronizationException {
            LOGGER.debug("Initiate explicit operation call for situation: {}, action: {}", situation, action);
            performAction();
            LOGGER.debug("Complected explicit operation call for situation: {}, action: {}", situation, action);
            return toJsonValue();
        }
    }

    /**
     * TODO: Description.
     */
    class SourceSyncOperation extends SyncOperation {

        // If it can not uniquely identify a target, the list of ambiguous target ids
        public List<String> ambiguousTargetIds;

        @Override
        @SuppressWarnings("fallthrough")
        public JsonValue sync() throws SynchronizationException {
            JsonValue oldTargetValue = new JsonValue(null);
            try {
                EventEntry measureSituation = Publisher.start(EVENT_SOURCE_ASSESS_SITUATION, getSourceObjectId(), null);
                try {
                    assessSituation();
                } finally {
                    measureSituation.end();
                    if (targetObjectAccessor != null) {
                        oldTargetValue = targetObjectAccessor.getObject();
                    }
                }
                EventEntry measureDetermine = Publisher.start(EVENT_SOURCE_DETERMINE_ACTION, getSourceObjectId(), null);
                boolean linkExisted = (getLinkId() != null);

                try {
                    determineAction(true);
                } finally {
                    measureDetermine.end();
                }
                EventEntry measurePerform = Publisher.start(EVENT_SOURCE_PERFORM_ACTION, getSourceObjectId(), null);
                try {
                    performAction();
                } finally {
                    measurePerform.end();
                    if (reconContext != null){
                        // The link ID presence after the action can not be interpreted as an indication if the link has been created
                        reconContext.getStatistics().getSourceStat().processed(getSourceObjectId(), getTargetObjectId(),
                                linkExisted, getLinkId(), linkCreated, situation, action);
                    }
                }

                JsonValue syncResult = toJsonValue();
                syncResult.put("oldTargetValue", oldTargetValue != null ? oldTargetValue.getObject() : null);
                return syncResult;
            } catch (SynchronizationException e) {
                JsonValue syncResult = toJsonValue();
                syncResult.put("oldTargetValue", oldTargetValue != null ? oldTargetValue.getObject() : null);
                e.setDetail(syncResult);
                throw e;
            }
        }

        protected boolean isSourceToTarget() {
            return true;
        }

        /**
         * @return all found matching target identifier(s), or a 0 length array if none.
         * More than one target identifier is possible for ambiguous matches
         */
        public String[] getTargetIds() {
            String[] targetIds = null;
            if (ambiguousTargetIds != null) {
                targetIds = ambiguousTargetIds.toArray(new String[ambiguousTargetIds.size()]);
            } else if (getTargetObjectId() != null) {
                targetIds = new String[] { getTargetObjectId() };
            } else {
                targetIds = new String[0];
            }
            return targetIds;
        }

        /**
         * @return the ambiguous target identifier(s), or an empty list if no ambiguous entries are present
         */
        public List getAmbiguousTargetIds() {
            return ambiguousTargetIds;
        }

        private void setAmbiguousTargetIds(JsonValue results) {
            ambiguousTargetIds = new ArrayList<String>(results == null ? 0 : results.size());
            for (JsonValue resultValue : results) {
                String anId = resultValue.get("_id").required().asString();
                ambiguousTargetIds.add(anId);
            }
        }

        public void fromJsonValue(JsonValue params) {
            reconId = params.get("reconId").asString();
            sourceObjectAccessor = new LazyObjectAccessor(service, sourceObjectSet, params.get("sourceId").required().asString());
            ignorePostAction = params.get("ignorePostAction").defaultTo(false).asBoolean();
        }

        /**
         * TODO: Description.
         *
         * @throws SynchronizationException TODO.
         */
        private void assessSituation() throws SynchronizationException {
            situation = null;
            if (!isLinkingEnabled()) {
                initializeLink(null); // If we're not linking, set link to none
            }

            if (getSourceObjectId() != null && linkObject.initialized == false) { // In case the link was not pre-read get it here
                linkObject.getLinkForSource(getSourceObjectId());
            }
            if (linkObject._id != null) {
                targetObjectAccessor = new LazyObjectAccessor(service, targetObjectSet, linkObject.targetId);
            }

            if (!hasSourceObject()) {
                /*
                For sync of delete. For recon these are assessed instead in target phase

                no source, link, target & valid target     -  source missing
                no source, link, target & not valid target - target ignored
                no source, link, no target                 - link only
                no source, no link - can't correlate (no previous object available) - all gone
                no source, no link - (correlate)
                                     no target                       - all gone
                                     1 target & valid (source)       - unassigned
                                     1 target & not valid (source)   - target ignored
                                     > 1 target & valid (source)     - ambiguous
                                     > 1 target & not valid (source) - unqualified
                 */

                if (linkObject._id != null) {
                    if (hasTargetObject()) {
                        if (isTargetValid()) {
                            situation = Situation.SOURCE_MISSING;
                        } else {
                            // target is not valid for this mapping; ignore it
                            situation = Situation.TARGET_IGNORED;
                        }
                    } else {
                        situation = Situation.LINK_ONLY;
                    }
                } else {
                    if (oldValue == null) {
                        // If there is no previous value known we can not correlate
                        situation = Situation.ALL_GONE;
                    } else {
                        // Correlate the old value to potential target(s)
                        JsonValue results = correlateTarget(oldValue);
                        boolean valid = isSourceValid(oldValue);
                        if (results == null || results.size() == 0) {
                            // Results null means no correlation query defined, size 0 we know there is no target
                            situation = Situation.ALL_GONE;
                        } else if (results.size() == 1) {
                            JsonValue resultValue = results.get((Integer) 0).required();
                            targetObjectAccessor = getCorrelatedTarget(resultValue);
                            if (valid) {
                                situation = Situation.UNASSIGNED;
                            } else {
                                // target is not valid for this mapping; ignore it
                                situation = Situation.TARGET_IGNORED;
                            }
                        } else if (results.size() > 1) {
                            if (valid) {
                                // Note this situation is used both when there is a source and a deleted source
                                // with multiple matching targets
                                situation = Situation.AMBIGUOUS;
                            } else {
                                situation = Situation.UNQUALIFIED;
                            }
                        }
                    }
                }
            } else if (isSourceValid() && checkSourceConditions()) { // source is valid for mapping
                if (linkObject._id != null) { // source object linked to target
                    if (hasTargetObject()) {
                        situation = Situation.CONFIRMED;
                    } else {
                        situation = Situation.MISSING;
                    }
                } else { // source object not linked to target
                    JsonValue results = correlateTarget();
                    if (results == null) { // no correlationQuery defined
                        situation = Situation.ABSENT;
                    } else if (results.size() == 1) {
                        JsonValue resultValue = results.get((Integer) 0).required();
                        targetObjectAccessor = getCorrelatedTarget(resultValue);

                        Link checkExistingLink = new Link(ObjectMapping.this);
                        checkExistingLink.getLinkForTarget(targetObjectAccessor.getLocalId());
                        if (checkExistingLink._id == null || checkExistingLink.sourceId == null) {
                            situation = Situation.FOUND;
                        } else {
                            situation = Situation.FOUND_ALREADY_LINKED;
                            // TODO: consider enhancements:
                            // For reporting, should it log existing link and source
                            // What actions should be available for a found, already linked
                        }
                    } else if (results.size() == 0) {
                        situation = Situation.ABSENT;
                    } else {
                        situation = Situation.AMBIGUOUS;
                        setAmbiguousTargetIds(results);
                    }
                }
            } else { // mapping does not qualify for target
                if (linkObject._id != null) {
                    situation = Situation.UNQUALIFIED;
                } else {
                    JsonValue results = correlateTarget();
                    if (results == null || results.size() == 0) {
                        situation = Situation.SOURCE_IGNORED; // source not valid for mapping, and no link or target exist
                    } else if (results.size() == 1) {
                        // TODO: Consider if we can optimize out the read for unqualified conditions
                        JsonValue resultValue = results.get((Integer) 0).required();
                        targetObjectAccessor = getCorrelatedTarget(resultValue);
                        situation = Situation.UNQUALIFIED;
                    } else if (results.size() > 1) {
                        situation = Situation.UNQUALIFIED;
                        setAmbiguousTargetIds(results);
                    }
                }
                if (reconContext != null) {
                    reconContext.getStatistics().getSourceStat().addNotValid(getSourceObjectId());
                }
            }
            LOGGER.debug("Mapping '{}' assessed situation of {} to be {}", new Object[]{name, getSourceObjectId(), situation});
        }

        /**
         * Correlates (finds an associated) target for the source object
         * @see #correlateTarget(JsonValue)
         *
         * @return JsonValue if found, null if none
         * @throws SynchronizationException if the correlation failed.
         */
        private JsonValue correlateTarget() throws SynchronizationException {
            return correlateTarget(null);
        }
        /**
         * Correlates (finds an associated) target for the given source
         * @param sourceObjectOverride optional explicitly supplied source object to correlate,
         * or null to use the source object associated with the sync operation
         *
         * @return JsonValue if found, null if none
         * @throws SynchronizationException if the correlation failed.
         */
        @SuppressWarnings("unchecked")
        private JsonValue correlateTarget(JsonValue sourceObjectOverride) throws SynchronizationException {
            JsonValue result = null;
            // TODO: consider if there are cases where this would better be lazy and not get the full target
            if (hasTargetObject()) {
                result = new JsonValue(new ArrayList<Map<String, Object>>(1));
                result.add(0, getTargetObject());
            } else if (correlationQuery != null && (correlateEmptyTargetSet || !hadEmptyTargetObjectSet())) {
                EventEntry measure = Publisher.start(EVENT_CORRELATE_TARGET, getSourceObject(), null);

                Map<String, Object> queryScope = new HashMap<String, Object>();
                if (sourceObjectOverride != null) {
                    queryScope.put("source", sourceObjectOverride.asMap());
                } else {
                    queryScope.put("source", getSourceObject().asMap());
                }
                try {
                    Object query = correlationQuery.exec(queryScope);
                    if (query == null || !(query instanceof Map)) {
                        throw new SynchronizationException("Expected correlationQuery script to yield a Map");
                    }
                    result = new JsonValue(queryTargetObjectSet((Map)query)).get(QueryResult.FIELD_RESULT).required();
                } catch (ScriptException se) {
                    LOGGER.debug("{} correlationQuery script encountered exception", name, se);
                    throw new SynchronizationException(se);
                } finally {
                    measure.end();
                }
            }
            return result;
        }

        /**
         * Given a result entry from a correlation query get the full correlated target object
         * @param resultValue an entry from the correlation query result list.
         * May already be the full target object, or just contain the id.
         * @return the target object
         * @throws SynchronizationException
         */
        private LazyObjectAccessor getCorrelatedTarget(JsonValue resultValue) throws SynchronizationException {
            // TODO: Optimize to get the entire object with one query if it's sufficient
            LazyObjectAccessor fullObj = null;
            if (hasNonSpecialAttribute(resultValue.keys())) { //Assume this is a full object
                fullObj = new LazyObjectAccessor(service, targetObjectSet, resultValue.get("_id").required().asString(), resultValue);
            } else {
                fullObj = new LazyObjectAccessor(service, targetObjectSet, resultValue.get("_id").required().asString());
                //fullObj.getObject();
            }
            return fullObj;
        }

        /**
         * Primitive implementation to decide if the object is a "full" or a partial.
         *
         * @param keys attribute names of object
         * @return true if the {@code keys} has value not starting with "_" char
         */
        private boolean hasNonSpecialAttribute(Collection<String> keys) {
            for (String attr : keys) {
                if (!attr.startsWith("_")) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * TODO: Description.
     */
    class TargetSyncOperation extends SyncOperation {

        @Override
        public JsonValue sync() throws SynchronizationException {
            try {
                EventEntry measureSituation = Publisher.start(EVENT_TARGET_ASSESS_SITUATION, targetObjectAccessor, null);
                try {
                    assessSituation();
                } finally {
                    measureSituation.end();
                }
                boolean linkExisted = (getLinkId() != null);

                EventEntry measureDetermine = Publisher.start(EVENT_TARGET_DETERMINE_ACTION, targetObjectAccessor, null);
                try {
                    determineAction(false);
                } finally {
                    measureDetermine.end();
                }
                EventEntry measurePerform = Publisher.start(EVENT_TARGET_PERFORM_ACTION, targetObjectAccessor, null);
                try {
                    // TODO: Option here to just report what action would be performed?
                    performAction();
                } finally {
                    measurePerform.end();
                    if (reconContext != null) {
                        reconContext.getStatistics().getTargetStat().processed(getSourceObjectId(), getTargetObjectId(), linkExisted, getLinkId(), linkCreated,
                                situation, action);
                    }
                }
                return toJsonValue();
            } catch (SynchronizationException e) {
                e.setDetail(toJsonValue());
                throw e;
            }
        }

        protected boolean isSourceToTarget() {
            return false;
        }

        public void fromJsonValue(JsonValue params) {
            reconId = params.get("reconId").asString();
            ignorePostAction = params.get("ignorePostAction").defaultTo(false).asBoolean();
        }

        public JsonValue toJsonValue() {
            JsonValue actionParam = new JsonValue(new HashMap<String,Object>());
            actionParam.put("reconId",reconId);
            actionParam.put("mapping",ObjectMapping.this.getName());
            actionParam.put("situation",situation.name());
            actionParam.put("action",situation.getDefaultAction().name());
            actionParam.put("target","true");
            if (targetObjectAccessor != null && targetObjectAccessor.getLocalId() != null) {
                actionParam.put("targetId", targetObjectAccessor.getLocalId());
            }
            return actionParam;
        }

        /**
         * TODO: Description.
         *
         * @throws SynchronizationException TODO.
         */
        private void assessSituation() throws SynchronizationException {
            situation = null;
            String targetId = getTargetObjectId();

            // May want to consider an optimization to not query
            // if we don't need the link for the TARGET_IGNORED action
            if (targetId != null) {
                linkObject.getLinkForTarget(targetId);
            }

            if (!isTargetValid()) { // target is not valid for this mapping; ignore it
                situation = Situation.TARGET_IGNORED;
                if (reconContext != null && targetId != null) {
                    reconContext.getStatistics().getTargetStat().addNotValid(targetId);
                }
                return;
            }
            if (linkObject._id == null || linkObject.sourceId == null) {
                situation = Situation.UNASSIGNED;
            } else {
                sourceObjectAccessor = new LazyObjectAccessor(service, sourceObjectSet, linkObject.sourceId);
                if (getSourceObject() == null) { // force load to double check
                    situation = Situation.SOURCE_MISSING;
                } else if (!isSourceValid()) {
                    situation = Situation.UNQUALIFIED; // Should happen rarely done in source phase
                    LOGGER.info("Situation in target reconciliation that indicates source may have changed {} {} {} {}",
                            new Object[] {situation, getSourceObject(), targetId, linkObject});
                } else { // proper link
                    situation = Situation.CONFIRMED; // Should happen rarely as done in source phase
                    LOGGER.info("Situation in target reconciliation that indicates source may have changed {} {} {} {}",
                            new Object[] {situation, getSourceObject(), targetId, linkObject});
                }
            }
        }
    }

    /**
     * TEMPORARY.
     */
    private class ReconEntry {

        public final static String RECON_START = "start";
        public final static String RECON_END = "summary";
        public final static String RECON_ENTRY = ""; // regular reconciliation entry has an empty entry type

        /** Type of the audit log entry. Allows for marking recon start / summary records */
        public String entryType = RECON_ENTRY;
        /** TODO: Description. */
        public final SyncOperation op;
        /** The id identifying the reconciliation run */
        public String reconId;
        /** The root invocation context */
        public final Context rootContext;
        /** TODO: Description. */
        public Date timestamp;
        /** TODO: Description. */
        public Status status = ObjectMapping.Status.SUCCESS;
        /** TODO: Description. */
        public String sourceId;
        /** TODO: Description. */
        public String targetId;
        /** TODO: Description. */
        public String reconciling;
        /** TODO: Description. */
        public String message;
        /** TODO: Description. */
        public JsonValue messageDetail;
        /** TODO: Description. */
        public Exception exception;
        /** TODO: Description. */
        public String actionId;
        // Name of the mapping
        public String mappingName;

        private DateUtil dateUtil;

        // A comma delimited formatted representation of any ambiguous identifiers
        protected String ambigiousTargetIds;
        public void setAmbiguousTargetIds(List<String> ambiguousIds) {
            if (ambiguousIds != null) {
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (String id : ambiguousIds) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    sb.append(id);
                }
                ambigiousTargetIds = sb.toString();
            } else {
                ambigiousTargetIds = "";
            }
        }

        private String getReconId() {
            return (reconId == null && op != null) ? op.reconId : reconId;
        }

        /**
         * Constructor that allows specifying the type of reconciliation log entry
         */
        public ReconEntry(SyncOperation op, Context rootContext, String entryType, DateUtil dateUtil) {
            this.op = op;
            this.rootContext = rootContext;
            this.entryType = entryType;
            this.dateUtil = dateUtil;
            if (!entryType.equals(RECON_ENTRY)) {
                this.mappingName = name;
            }
        }

        /**
         * Constructor for regular reconciliation log entries
         */
        public ReconEntry(SyncOperation op, Context rootContext, DateUtil dateUtil) {
            this(op, rootContext, RECON_ENTRY, dateUtil);
        }

        /**
         * TODO: Description.
         *
         * @return TODO.
         */
        private JsonValue toJsonValue() {
            JsonValue jv = new JsonValue(new HashMap<String, Object>());
            jv.put("entryType", entryType);
            jv.put("rootActionId", rootContext.getId());
            jv.put("reconId", getReconId());
            jv.put("reconciling", reconciling);
            jv.put("sourceObjectId", sourceId);
            jv.put("targetObjectId", targetId);
            jv.put("ambiguousTargetObjectIds", ambigiousTargetIds);
            jv.put("timestamp", dateUtil.formatDateTime(timestamp));
            jv.put("situation", ((op == null || op.situation == null) ? null : op.situation.toString()));
            jv.put("action", ((op == null || op.action == null) ? null : op.action.toString()));
            jv.put("status", (status == null ? null : status.toString()));
            jv.put("message", message);
            jv.put("messageDetail", messageDetail);
            jv.put("actionId", actionId);
            jv.put("exception", exception);
            jv.put("mapping", mappingName);
            return jv;
        }
    }
}
