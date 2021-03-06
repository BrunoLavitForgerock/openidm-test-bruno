/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 20132-2014 ForgeRock AS. All Rights Reserved
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
 *
 */
package org.forgerock.openidm.cluster;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ConnectionFactory;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.NotFoundException;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResult;
import org.forgerock.json.resource.QueryResultHandler;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.RequestHandler;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.Resource;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourceName;
import org.forgerock.json.resource.ResultHandler;
import org.forgerock.json.resource.ServerContext;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openidm.config.enhanced.EnhancedConfig;
import org.forgerock.openidm.config.enhanced.JSONEnhancedConfig;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.repo.RepositoryService;
import org.forgerock.openidm.util.DateUtil;
import org.forgerock.openidm.util.ResourceUtil;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Cluster Management Service.
 *
 * @author Chad Kienle
 */
@Component(name = ClusterManager.PID, policy = ConfigurationPolicy.REQUIRE, metatype = true,
        description = "OpenIDM Policy Service", immediate = true)
@Service
@Properties({
    @Property(name = Constants.SERVICE_VENDOR, value = ServerConstants.SERVER_VENDOR_NAME),
    @Property(name = Constants.SERVICE_DESCRIPTION, value = "Cluster Management Service"),
    @Property(name = ServerConstants.ROUTER_PREFIX, value = "/cluster*") })
public class ClusterManager implements RequestHandler, ClusterManagementService {

    private static final Logger logger = LoggerFactory.getLogger(ClusterManager.class);

    private static final Object repoLock = new Object();
    private static final Object startupLock = new Object();

    public static final String PID = "org.forgerock.openidm.cluster";

    /**
     * Query ID for querying failed instances
     */
    private static final String QUERY_FAILED_INSTANCE = "query-cluster-instances";
    
    /**
     * Query ID for querying all instances
     */
    private static final String QUERY_INSTANCES = "query-all";

    /**
     * Resource name when issuing requests over the router
     */
    private static final ResourceName REPO_RESOURCE_CONTAINER = new ResourceName("repo", "cluster", "states");     
    
    /**
     * Resource name when issuing requests directly with the Repository Service
     */
    private static final ResourceName RESOURCE_CONTAINER = new ResourceName("cluster", "states"); 

    /**
     * The instance ID
     */
    private String instanceId;
    
    @Reference
    protected RepositoryService repoService;

    /**
     * The Connection Factory
     */
    @Reference(policy = ReferencePolicy.STATIC, target="(service.pid=org.forgerock.openidm.internal)")
    protected ConnectionFactory connectionFactory;

    /**
     * A list of listeners to notify when an instance fails
     */
    private Map<String, ClusterEventListener> listeners =
            new HashMap<String, ClusterEventListener>();

    /**
     * A thread to perform cluster management
     */
    private ClusterManagerThread clusterManagerThread = null;

    /**
     * Enhanced configuration utility
     */
    private EnhancedConfig enhancedConfig = JSONEnhancedConfig.newInstance();

    /**
     * The Cluster Manager Configuration
     */
    private ClusterConfig clusterConfig;

    /**
     * The current state of this instance
     */
    private InstanceState currentState = null;

    /**
     * A flag to indicate if the has checked-in yet
     */
    private boolean firstCheckin = true;

    /**
     * A flag to indicate if this instance has failed
     */
    private boolean failed = false;

    /**
     * A flag to indicate if the cluster management is enabled
     */
    private boolean enabled = false;

    @Activate
    void activate(ComponentContext compContext) throws ParseException {
        logger.debug("Activating Cluster Management Service with configuration {}", compContext
                .getProperties());
        JsonValue config = enhancedConfig.getConfigurationAsJson(compContext);
        ClusterConfig clstrCfg = new ClusterConfig(config);
        clusterConfig = clstrCfg;
        instanceId = clusterConfig.getInstanceId();

        if (clusterConfig.isEnabled()) {
            enabled = true;
            clusterManagerThread =
                    new ClusterManagerThread(clusterConfig.getInstanceCheckInInterval(),
                            clusterConfig.getInstanceCheckInOffset());
        }
    }

    @Deactivate
    void deactivate(ComponentContext compContext) {
        logger.debug("Deactivating Cluster Management Service {}", compContext);
        if (clusterConfig.isEnabled()) {
            clusterManagerThread.shutdown();
            synchronized (repoLock) {
                try {
                    InstanceState state = getInstanceState(instanceId);
                    state.updateShutdown();
                    state.setState(InstanceState.STATE_DOWN);
                    updateInstanceState(instanceId, state);
                } catch (ResourceException e) {
                    logger.warn("Failed to update instance shutdown timestamp", e);
                }
            }
        }
    }

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void startClusterManagement() {
        synchronized (startupLock) {
            if (clusterConfig.isEnabled() && !clusterManagerThread.isRunning()) {
                // Start thread
                logger.info("Starting Cluster Management");
                clusterManagerThread.startup();
            }
        }
    }

    @Override
    public void stopClusterManagement() {
        synchronized (startupLock) {
            if (clusterConfig.isEnabled() && clusterManagerThread.isRunning()) {
                logger.info("Stopping Cluster Management");
                // Start thread
                clusterManagerThread.shutdown();
                checkOut();
            }
        }
    }

    @Override
    public boolean isStarted() {
        return clusterManagerThread.isRunning();
    }

    @Override
    public void handleRead(ServerContext context, ReadRequest request,
            ResultHandler<Resource> handler) {
        try {
            try {
                Map<String, Object> resultMap = new HashMap<String, Object>();
                logger.debug("Resource Name: " + request.getResourceName());
                if (request.getResourceName().isEmpty()) {
                    // Return a list of all nodes in the cluster
                    QueryRequest queryRequest = Requests.newQueryRequest(REPO_RESOURCE_CONTAINER.toString());
                    queryRequest.setQueryId(QUERY_INSTANCES);
                    queryRequest.setAdditionalParameter("fields", "*");
                    logger.debug("Attempt query {}", QUERY_INSTANCES);
                    final List<Object> list = new ArrayList<Object>();
                    connectionFactory.getConnection().query(context, queryRequest, new QueryResultHandler() {
                        @Override
                        public void handleError(ResourceException error) {
                            // ignore
                        }

                        @Override
                        public boolean handleResource(Resource resource) {
                            list.add(getInstanceMap(resource.getContent()));
                            return true;
                        }

                        @Override
                        public void handleResult(QueryResult result) {
                            // Ignore
                        }
                    });
                    resultMap.put("results", list);
                    handler.handleResult(new Resource(request.getResourceName(), null, new JsonValue(resultMap)));
                } else {
                    String id = request.getResourceName();
                    logger.debug("Attempting to read instance {} from the database", id);
                    ReadRequest readRequest = Requests.newReadRequest(REPO_RESOURCE_CONTAINER.child(id).toString());
                    Resource instanceValue = connectionFactory.getConnection().read(context, readRequest);
                    JsonValue result = new JsonValue(getInstanceMap(instanceValue.getContent()));
                    handler.handleResult(new Resource(request.getResourceName(), null, result));
                }
            } catch (ResourceException e) {
                handler.handleError(e);
            }
        } catch (Throwable t) {
            handler.handleError(ResourceUtil.adapt(t));
        }
    }

    @Override
    public void register(String listenerId, ClusterEventListener listener) {
        logger.debug("Registering listener {}", listenerId);
        listeners.put(listenerId, listener);
    }

    @Override
    public void unregister(String listenerId) {
        logger.debug("Unregistering listener {}", listenerId);
        listeners.remove(listenerId);
    }

    /**
     * Creates a map representing an instance's state and recovery statistics
     * that can be used for responses to read requests.
     *
     * @param instanceValue
     *            an instances state object
     * @return a map representing an instance's state and recovery statistics
     */
    private Map<String, Object> getInstanceMap(JsonValue instanceValue) {
        DateUtil dateUtil = DateUtil.getDateUtil();
        Map<String, Object> instanceInfo = new HashMap<String, Object>();
        String instanceId = instanceValue.get("instanceId").asString();
        InstanceState state = new InstanceState(instanceId, instanceValue.asMap());
        instanceInfo.put("instanceId", instanceId);
        instanceInfo.put("startup", dateUtil.formatDateTime(new Date(state.getStartup())));
        instanceInfo.put("shutdown", "");
        Map<String, Object> recoveryMap = new HashMap<String, Object>();
        switch (state.getState()) {
        case InstanceState.STATE_RUNNING:
            instanceInfo.put("state", "running");
            break;
        case InstanceState.STATE_DOWN:
            instanceInfo.put("state", "down");
            if (!state.hasShutdown()) {
                if (state.getRecoveryAttempts() > 0) {
                    recoveryMap.put("recoveredBy", state.getRecoveringInstanceId());
                    recoveryMap.put("recoveryAttempts", state.getRecoveryAttempts());
                    recoveryMap.put("recoveryStarted", dateUtil.formatDateTime(new Date(state
                            .getRecoveryStarted())));
                    recoveryMap.put("recoveryFinished", dateUtil.formatDateTime(new Date(state
                            .getRecoveryFinished())));
                    recoveryMap.put("detectedDown", dateUtil.formatDateTime(new Date(state
                            .getDetectedDown())));
                    instanceInfo.put("recovery", recoveryMap);
                } else {
                    // Should never reach this state
                    logger.error("Instance {} is in 'down' but has not been shutdown or recovered",
                            instanceId);
                }
            } else {
                instanceInfo
                        .put("shutdown", dateUtil.formatDateTime(new Date(state.getShutdown())));
            }
            break;
        case InstanceState.STATE_PROCESSING_DOWN:
            recoveryMap.put("state", "processing-down");
            recoveryMap.put("recoveryAttempts", state.getRecoveryAttempts());
            recoveryMap.put("recoveringBy", state.getRecoveringInstanceId());
            recoveryMap.put("recoveryStarted", dateUtil.formatDateTime(new Date(state.getRecoveryStarted())));
            recoveryMap.put("detectedDown", dateUtil.formatDateTime(new Date(state.getDetectedDown())));
            instanceInfo.put("recovery", recoveryMap);
        }
        return instanceInfo;
    }

    public void renewRecoveryLease(String instanceId) {
        synchronized (repoLock) {
            try {
                InstanceState state = getInstanceState(instanceId);
                // Update the recovery timestamp
                state.updateRecoveringTimestamp();
                updateInstanceState(instanceId, state);
                logger.debug("Updated recovery timestamp of instance {}", instanceId);
            } catch (ResourceException e) {
                if (e.getCode() != ResourceException.CONFLICT) {
                    logger.warn("Failed to update recovery timestamp of instance {}: {}",
                            instanceId, e.getMessage());
                }
            }
        }
    }

    /**
     * Updates an instance's state.
     *
     * @param instanceId
     *            the id of the instance to update
     * @param instanceState
     *            the updated InstanceState object
     * @throws ResourceException
     */
    private void updateInstanceState(String instanceId, InstanceState instanceState)
            throws ResourceException {
        synchronized (repoLock) {
            ResourceName resourceName = RESOURCE_CONTAINER.child(instanceId);
            UpdateRequest updateRequest = Requests.newUpdateRequest(resourceName.toString(), new JsonValue(instanceState.toMap()));
            updateRequest.setRevision(instanceState.getRevision());
            repoService.update(updateRequest);
        }
    }

    private InstanceState getInstanceState(String instanceId) throws ResourceException {
        synchronized (repoLock) {
            ResourceName resourceName = RESOURCE_CONTAINER.child(instanceId);
            return new InstanceState(instanceId, getOrCreateRepo(resourceName.toString()));
        }
    }

    private Map<String, Object> getOrCreateRepo(String resourceName) throws ResourceException {
        synchronized (repoLock) {
            String container, id;
            Map<String, Object> map;

            map = readFromRepo(resourceName).asMap();
            if (map == null) {
                map = new HashMap<String, Object>();
                // create resource
                logger.debug("Creating resource {}", resourceName);
                container = resourceName.substring(0, resourceName.lastIndexOf("/"));
                id = resourceName.substring(resourceName.lastIndexOf("/") + 1);
                Resource resource = null;
                CreateRequest createRequest = Requests.newCreateRequest(container, id, new JsonValue(map));
                resource = repoService.create(createRequest);
                map = resource.getContent().asMap();
            }
            return map;
        }
    }

    private JsonValue readFromRepo(String resourceName) throws ResourceException {
        try {
            logger.debug("Reading resource {}", resourceName);
            Resource resource = null;
            ReadRequest readRequest = Requests.newReadRequest(resourceName);
            resource = repoService.read(readRequest);
            resource.getContent().put("_id", resource.getId());
            resource.getContent().put("_rev", resource.getRevision());
            return resource.getContent();
        } catch (NotFoundException e) {
            return new JsonValue(null);
        }
    }

    /**
     * Updates the timestamp for this instance in the instance check-in map.
     *
     * @ return the InstanceState object, or null if an expected failure (MVCC)
     * was encountered
     */
    private InstanceState checkIn() {
        InstanceState state = null;
        try {
            logger.debug("Getting instance state for {}", instanceId);
            state = getInstanceState(instanceId);
            if (firstCheckin) {
                state.updateStartup();
                state.clearShutdown();
                firstCheckin = false;
            }
            switch (state.getState()) {
            case InstanceState.STATE_RUNNING:
                // just update the timestamp
                state.updateTimestamp();
                break;
            case InstanceState.STATE_DOWN:
                // instance has been recovered, so switch to "normal" state and
                // update timestamp
                state.setState(InstanceState.STATE_RUNNING);
                logger.debug("Instance {} state changing from {} to {}", new Object[] { instanceId,
                    InstanceState.STATE_DOWN, InstanceState.STATE_RUNNING });
                state.updateTimestamp();
                break;
            case InstanceState.STATE_PROCESSING_DOWN:
                // rare case, do not update state or timestamp
                // system may attempt to recover itself if recovery timeout has
                // elapsed
                logger.debug("Instance {} is in state {}, waiting for recovery attempt to finish",
                        new Object[] { instanceId, state.getState() });
                return state;
            }
            updateInstanceState(instanceId, state);
            logger.debug("Instance {} state updated successfully", instanceId);
        } catch (ResourceException e) {
            if (e.getCode() != ResourceException.CONFLICT) {
                logger.warn("Error updating instance timestamp", e);
            } else {
                // MVCC failure, return null
                logger.info("Failed to set this instance state to {}", state.getState());
                return null;
            }
        }
        return state;
    }

    /**
     * Performs an instance check-out, setting the state to down if it is currently running.
     */
    private void checkOut() {
        logger.debug("checkOut()");
        InstanceState state = null;
        try {
            logger.debug("Getting instance state for {}", instanceId);
            state = getInstanceState(instanceId);
            switch (state.getState()) {
            case InstanceState.STATE_RUNNING:
                // just update the timestamp
                state.setState(InstanceState.STATE_DOWN);
                updateInstanceState(instanceId, state);
                logger.debug("Instance {} state updated successfully");
                break;
            case InstanceState.STATE_DOWN:
                // Already down
                break;
            case InstanceState.STATE_PROCESSING_DOWN:
                // Some other instance is processing this down state
                // Leave in this state
                break;
            }
        } catch (ResourceException e) {
            if (e.getCode() != ResourceException.CONFLICT) {
                logger.warn("Error checking out instance", e);
            } else {
                // MVCC failure, return null
                logger.info("Failed to set this instance state to {}", state.getState());
            }
        }
    }

    /**
     * Returns a list of all instances who have timed out.
     *
     * @return a map of all instances who have timed out (failed).
     */
    private Map<String, InstanceState> findFailedInstances() {
        Map<String, InstanceState> failedInstances = new HashMap<String, InstanceState>();
        try {
            QueryRequest queryRequest = Requests.newQueryRequest(RESOURCE_CONTAINER.toString());
            queryRequest.setQueryId(QUERY_FAILED_INSTANCE);
            String time = InstanceState.pad(System.currentTimeMillis() - clusterConfig.getInstanceTimeout());
            queryRequest.setAdditionalParameter(InstanceState.PROP_TIMESTAMP_LEASE, time);
            logger.debug("Attempt query {} for failed instances", QUERY_FAILED_INSTANCE);
            List<Resource> resultList = repoService.query(queryRequest);
            for (Resource resource : resultList) {
                Map<String, Object> valueMap = resource.getContent().asMap();
                String id = (String) valueMap.get("instanceId");
                InstanceState state = new InstanceState(id, valueMap);
                switch (state.getState()) {
                case InstanceState.STATE_RUNNING:
                    // Found failed instance
                    failedInstances.put(id, state);
                    break;
                case InstanceState.STATE_PROCESSING_DOWN:
                    // Check if recovering has failed
                    if (state.hasRecoveringFailed(clusterConfig.getInstanceRecoveryTimeout())) {
                        failedInstances.put(id, state);
                    }
                    break;
                case InstanceState.STATE_DOWN:
                    // Already recovered instance, do nothing
                    break;
                }
            }

        } catch (ResourceException e) {
            logger.error("Error reading instance check in map", e);
        }

        return failedInstances;
    }

    /**
     * Recovers a failed instance by looping through all listeners and calling
     * their instanceFailed method.
     *
     * @param instanceId
     *            the id of the instance to recover
     * @return true if any triggers were "freed", false otherwise
     */
    private boolean recoverFailedInstance(String instanceId, InstanceState state) {
        // First attempt to "claim" the failed instance
        try {
            if (state.getState() == InstanceState.STATE_RUNNING) {
                state.updateDetectedDown();
                state.clearRecoveryAttempts();
            }
            // Update the instance state to recovered
            state.setState(InstanceState.STATE_PROCESSING_DOWN);
            state.setRecoveringInstanceId(this.instanceId);
            state.updateRecoveringTimestamp();
            state.startRecovery();
            updateInstanceState(instanceId, state);
        } catch (ResourceException e) {
            if (e.getCode() != ResourceException.CONFLICT) {
                logger.warn("Failed to update instance state", e);
            }
            return false;
        }

        // Then, attempt recovery
        boolean success =
                sendEventToListeners(new ClusterEvent(ClusterEventType.RECOVERY_INITIATED,
                        instanceId));

        if (success) {
            logger.info("Instance {} recovered successfully", instanceId);
            try {
                // Update the instance state to recovered
                InstanceState newState = getInstanceState(instanceId);
                newState.setState(InstanceState.STATE_DOWN);
                newState.finishRecovery();
                updateInstanceState(instanceId, newState);
            } catch (ResourceException e) {
                if (e.getCode() != ResourceException.CONFLICT) {
                    logger.warn("Failed to update instance state", e);
                }
                return false;
            }
        } else {
            logger.warn("Instance {} was not successfully recovered", instanceId);
            return false;
        }

        return true;

    }

    /**
     * Sends a ClusterEvent to all registered listeners
     *
     * @param event
     *            the ClusterEvent to handle
     * @return true if the event was handled appropriately, false otherwise
     */
    private boolean sendEventToListeners(ClusterEvent event) {
        boolean success = true;
        for (String listenerId : listeners.keySet()) {
            logger.debug("Notifying listener {} of event {} for instance {}", new Object[] {
                listenerId, event.getType(), instanceId });
            ClusterEventListener listener = listeners.get(listenerId);
            if (listener != null && !listener.handleEvent(event)) {
                success = false;
            }
        }
        return success;
    }

    /**
     * A thread for managing this instance's lease and detecting cluster events.
     */
    class ClusterManagerThread {

        private long checkinInterval;
        private long checkinOffset;
        private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        private ScheduledFuture handler;
        private boolean running = false;

        public ClusterManagerThread(long checkinInterval, long checkinOffset) {
            this.checkinInterval = checkinInterval;
        }

        public void startup() {
            running = true;
            logger.info("Starting the cluster manager thread");
            handler = scheduler.scheduleAtFixedRate(new Runnable() {
                public void run() {
                    try {
                        // Check in this instance
                        logger.debug("Instance check-in");
                        InstanceState state = checkIn();
                        if (state == null) {
                            if (!failed) {
                                logger.debug("This instance has failed");
                                failed = true;
                                // Notify listeners that this instance has
                                // failed
                                sendEventToListeners(new ClusterEvent(
                                        ClusterEventType.INSTANCE_FAILED, instanceId));
                                // Set current state to null
                                currentState = null;
                            }
                            return;
                        } else if (failed) {
                            logger.debug("This instance is no longer failed");
                            failed = false;
                        }

                        // If transitioning to a "running" state, send events
                        if (state.getState() == InstanceState.STATE_RUNNING) {
                            if (currentState == null
                                    || currentState.getState() != InstanceState.STATE_RUNNING) {
                                sendEventToListeners(new ClusterEvent(
                                        ClusterEventType.INSTANCE_RUNNING, instanceId));
                            }
                        }

                        // set current state
                        currentState = state;

                        // Find failed instances
                        logger.debug("Finding failed instances");
                        Map<String, InstanceState> failedInstances = findFailedInstances();
                        logger.debug("{} failed instances found", failedInstances.size());
                        if (failedInstances.size() > 0) {
                            logger.info("Attempting recovery");
                            // Recover failed instance's triggers
                            for (String id : failedInstances.keySet()) {
                                recoverFailedInstance(id, failedInstances.get(id));
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error performing cluster manager thread logic");
                        e.printStackTrace();
                    }
                }
            }, checkinOffset, checkinInterval + checkinOffset, TimeUnit.MILLISECONDS);
        }

        public void shutdown() {
            logger.info("Shutting down the cluster manager thread");
            if (handler != null) {
                handler.cancel(true);
            }
            running = false;
        }

        public boolean isRunning() {
            return running;
        }
    }

    @Override
    public void handleAction(ServerContext context, ActionRequest request,
            ResultHandler<JsonValue> handler) {
        handler.handleError(ResourceUtil.notSupported(request));
    }

    @Override
    public void handleCreate(ServerContext context, CreateRequest request,
            ResultHandler<Resource> handler) {
        handler.handleError(ResourceUtil.notSupported(request));
    }

    @Override
    public void handleDelete(ServerContext context, DeleteRequest request,
            ResultHandler<Resource> handler) {
        handler.handleError(ResourceUtil.notSupported(request));
    }

    @Override
    public void handlePatch(ServerContext context, PatchRequest request,
            ResultHandler<Resource> handler) {
        handler.handleError(ResourceUtil.notSupported(request));
    }

    @Override
    public void handleQuery(ServerContext context, QueryRequest request, QueryResultHandler handler) {
        handler.handleError(ResourceUtil.notSupported(request));
    }

    @Override
    public void handleUpdate(ServerContext context, UpdateRequest request,
            ResultHandler<Resource> handler) {
        handler.handleError(ResourceUtil.notSupported(request));
    }
}
