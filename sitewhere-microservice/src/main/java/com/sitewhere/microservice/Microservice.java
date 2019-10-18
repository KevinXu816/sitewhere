/*
 * Copyright (c) SiteWhere, LLC. All rights reserved. http://www.sitewhere.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package com.sitewhere.microservice;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;

import com.sitewhere.Version;
import com.sitewhere.grpc.client.tenant.CachedTenantManagement;
import com.sitewhere.microservice.management.MicroserviceManagementGrpcServer;
import com.sitewhere.microservice.scripting.ScriptTemplateManager;
import com.sitewhere.microservice.tenant.persistence.KubernetesTenantManagement;
import com.sitewhere.rest.model.configuration.ConfigurationModel;
import com.sitewhere.rest.model.microservice.state.MicroserviceDetails;
import com.sitewhere.rest.model.microservice.state.MicroserviceState;
import com.sitewhere.server.lifecycle.CompositeLifecycleStep;
import com.sitewhere.server.lifecycle.LifecycleComponent;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.microservice.IFunctionIdentifier;
import com.sitewhere.spi.microservice.IMicroservice;
import com.sitewhere.spi.microservice.IMicroserviceAnalytics;
import com.sitewhere.spi.microservice.configuration.model.IConfigurationModel;
import com.sitewhere.spi.microservice.configuration.model.IElementNode;
import com.sitewhere.spi.microservice.configuration.model.IElementRole;
import com.sitewhere.spi.microservice.grpc.IMicroserviceManagementGrpcServer;
import com.sitewhere.spi.microservice.instance.IInstanceSettings;
import com.sitewhere.spi.microservice.kafka.IKafkaTopicNaming;
import com.sitewhere.spi.microservice.metrics.IMetricsServer;
import com.sitewhere.spi.microservice.scripting.IScriptTemplateManager;
import com.sitewhere.spi.microservice.security.ISystemUser;
import com.sitewhere.spi.microservice.security.ITokenManagement;
import com.sitewhere.spi.microservice.state.IMicroserviceDetails;
import com.sitewhere.spi.microservice.state.IMicroserviceState;
import com.sitewhere.spi.microservice.state.ITenantEngineState;
import com.sitewhere.spi.server.lifecycle.ICompositeLifecycleStep;
import com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor;
import com.sitewhere.spi.system.IVersion;
import com.sitewhere.spi.tenant.ITenantManagement;

/**
 * Common base class for all SiteWhere microservices.
 */
public abstract class Microservice<T extends IFunctionIdentifier> extends LifecycleComponent
	implements IMicroservice<T> {

    /** Instance settings */
    @Autowired
    private IInstanceSettings instanceSettings;

    /** Metrics server */
    @Autowired
    private IMetricsServer metricsServer;

    /** JWT token management */
    @Autowired
    private ITokenManagement tokenManagement;

    /** System superuser */
    @Autowired
    private ISystemUser systemUser;

    /** Kafka topic naming */
    @Autowired
    private IKafkaTopicNaming kafkaTopicNaming;

    /** Tenant management implementation */
    private ITenantManagement tenantManagement;

    /** Cached version of tenant management API */
    private ITenantManagement cachedTenantManagement;

    /** Version information */
    private IVersion version = new Version();

    /** Configuration model */
    private IConfigurationModel configurationModel;

    /** Microservice management GRPC server */
    private IMicroserviceManagementGrpcServer microserviceManagementGrpcServer;

    /** Script template manager instance */
    private IScriptTemplateManager scriptTemplateManager;

    /** Microservice runtime analytics interface */
    private IMicroserviceAnalytics microserviceAnalytics = new MicroserviceAnalytics();

    /** Lifecycle operations thread pool */
    private ExecutorService microserviceOperationsService;

    /** Unique id for microservice */
    private UUID id = UUID.randomUUID();

    /** Timestamp in milliseconds when service started */
    private long startTime;

    public Microservice() {
	this.microserviceOperationsService = Executors
		.newSingleThreadExecutor(new MicroserviceOperationsThreadFactory());

	// Create script template manager.
	this.scriptTemplateManager = new ScriptTemplateManager();
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getId()
     */
    @Override
    public UUID getId() {
	return id;
    }

    /*
     * @see com.sitewhere.server.lifecycle.LifecycleComponent#getMicroservice()
     */
    @Override
    public IMicroservice<T> getMicroservice() {
	return this;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.server.lifecycle.LifecycleComponent#initialize(com.
     * sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void initialize(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	// Initialize configuration model.
	initializeConfigurationModel();

	// Initialize GRPC components.
	initializeGrpcComponents();

	// Initialize management APIs.
	initializeManagementApis();

	// Organizes steps for initializing microservice.
	ICompositeLifecycleStep initialize = new CompositeLifecycleStep("Initialize " + getName());

	// Initialize microservice management GRPC server.
	initialize.addInitializeStep(this, getMicroserviceManagementGrpcServer(), true);

	// Start microservice management GRPC server.
	initialize.addStartStep(this, getMicroserviceManagementGrpcServer(), true);

	// Initialize script template manager.
	initialize.addInitializeStep(this, getScriptTemplateManager(), true);

	// Start script template manager.
	initialize.addStartStep(this, getScriptTemplateManager(), true);

	// Initialize tenant management API.
	initialize.addInitializeStep(this, getCachedTenantManagement(), true);

	// Start HTTP tenant management API.
	initialize.addStartStep(this, getCachedTenantManagement(), true);

	// Initialize HTTP metrics server.
	initialize.addInitializeStep(this, getMetricsServer(), true);

	// Start HTTP metrics server.
	initialize.addStartStep(this, getMetricsServer(), true);

	// Execute initialization steps.
	initialize.execute(monitor);

	// Record start time.
	this.startTime = System.currentTimeMillis();
	getMicroserviceAnalytics().sendMicroserviceStarted(this);
    }

    /**
     * Initialize configuration model.
     */
    protected void initializeConfigurationModel() {
	this.configurationModel = buildConfigurationModel();
	((ConfigurationModel) configurationModel).setMicroserviceDetails(getMicroserviceDetails());
    }

    /**
     * Initialize GRPC components.
     */
    protected void initializeGrpcComponents() {
	this.microserviceManagementGrpcServer = new MicroserviceManagementGrpcServer(this);
    }

    /**
     * Initialize management APIs.
     */
    protected void initializeManagementApis() {
	this.tenantManagement = new KubernetesTenantManagement();
	this.cachedTenantManagement = new CachedTenantManagement(this.tenantManagement,
		new CachedTenantManagement.CacheSettings());
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.IMicroservice#afterMicroserviceStarted()
     */
    @Override
    public void afterMicroserviceStarted() {
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.server.lifecycle.LifecycleComponent#terminate(com.sitewhere
     * .spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void terminate(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	getMicroserviceAnalytics().sendMicroserviceStopped(this);

	// Create step that will stop components.
	ICompositeLifecycleStep stop = new CompositeLifecycleStep("Stop " + getComponentName());

	// HTTP metrics server.
	stop.addStopStep(this, getMetricsServer());

	// Tenant management API.
	stop.addStopStep(this, getCachedTenantManagement());

	// Terminate script template manager.
	stop.addStopStep(this, getScriptTemplateManager());

	// Stop microservice management GRPC server.
	stop.addStopStep(this, getMicroserviceManagementGrpcServer());

	// Execute shutdown steps.
	stop.execute(monitor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.IMicroservice#
     * waitForInstanceInitialization()
     */
    @Override
    public void waitForInstanceInitialization() throws SiteWhereException {
	// getLogger().info("Verifying that instance has been bootstrapped...");
	// Callable<Boolean> bootstrapCheck = () -> {
	// return getZookeeperManager().getCurator().checkExists()
	// .forPath(getInstanceConfigBootstrappedMarker()) == null ? false : true;
	// };
	// RetryConfig config = new
	// RetryConfigBuilder().retryOnReturnValue(Boolean.FALSE).retryIndefinitely()
	// .withDelayBetweenTries(Duration.ofSeconds(2)).withRandomBackoff().build();
	// RetryListener listener = new RetryListener<Boolean>() {
	//
	// @Override
	// public void onEvent(Status<Boolean> status) {
	// getLogger().info(String.format(
	// "Unable to locate bootstrap marker on attempt %d (total wait so far %dms).
	// Retrying after fallback...",
	// status.getTotalTries(), status.getTotalElapsedDuration().toMillis()));
	// }
	// };
	// new
	// CallExecutorBuilder().config(config).afterFailedTryListener(listener).build().execute(bootstrapCheck);
	// getLogger().info("Confirmed that instance was bootstrapped.");
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getHostname()
     */
    @Override
    public String getHostname() {
	if (getInstanceSettings().getKubernetesPodAddress() != null) {
	    return getInstanceSettings().getKubernetesPodAddress();
	}
	try {
	    InetAddress local = InetAddress.getLocalHost();
	    return local.getHostName();
	} catch (UnknownHostException e) {
	    throw new RuntimeException("Unable to find hostname.", e);
	}
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getMicroserviceDetails()
     */
    @Override
    public IMicroserviceDetails getMicroserviceDetails() {
	MicroserviceDetails details = new MicroserviceDetails();
	details.setIdentifier(getIdentifier().getPath());
	details.setHostname(getHostname());

	IElementNode root = getRootElementNode();
	details.setName(root.getName());
	details.setIcon(root.getIcon());
	details.setDescription(root.getDescription());
	details.setGlobal(isGlobal());
	return details;
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getCurrentState()
     */
    @Override
    public IMicroserviceState getCurrentState() throws SiteWhereException {
	MicroserviceState state = new MicroserviceState();
	state.setMicroservice(getMicroserviceDetails());
	state.setLifecycleStatus(getLifecycleStatus());
	state.setUptime(System.currentTimeMillis() - startTime);
	return state;
    }

    /**
     * Get element node for root configuration role.
     * 
     * @return
     */
    protected IElementNode getRootElementNode() {
	IElementRole role = getConfigurationModel().getRolesById().get(getConfigurationModel().getRootRoleId());
	if (role == null) {
	    throw new RuntimeException("Root role was not found for configuration model.");
	}
	List<IElementNode> matches = getConfigurationModel().getElementsByRole()
		.get(getConfigurationModel().getRootRoleId());
	if (matches == null) {
	    throw new RuntimeException("Configuration had no elements for root role.");
	}
	if (matches.size() != 1) {
	    throw new RuntimeException("Configuration model had " + matches.size() + " elements for root role.");
	}
	return matches.get(0);
    }

    /*
     * @see
     * com.sitewhere.spi.microservice.IMicroservice#onTenantEngineStateChanged(com.
     * sitewhere.spi.microservice.state.ITenantEngineState)
     */
    @Override
    public void onTenantEngineStateChanged(ITenantEngineState state) {
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.IMicroservice#getInstanceZkPath()
     */
    @Override
    public String getInstanceZkPath() {
	return "/" + getInstanceSettings().getInstanceId();
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getConfigurationModel()
     */
    @Override
    public IConfigurationModel getConfigurationModel() {
	return configurationModel;
    }

    public void setConfigurationModel(IConfigurationModel configurationModel) {
	this.configurationModel = configurationModel;
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getMetricsServer()
     */
    @Override
    public IMetricsServer getMetricsServer() {
	return metricsServer;
    }

    public void setMetricsServer(IMetricsServer metricsServer) {
	this.metricsServer = metricsServer;
    }

    /*
     * @see com.sitewhere.microservice.spi.IMicroservice#getTokenManagement()
     */
    @Override
    public ITokenManagement getTokenManagement() {
	return tokenManagement;
    }

    public void setTokenManagement(ITokenManagement tokenManagement) {
	this.tokenManagement = tokenManagement;
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getTenantManagement()
     */
    @Override
    public ITenantManagement getTenantManagement() {
	return tenantManagement;
    }

    public void setTenantManagement(ITenantManagement tenantManagement) {
	this.tenantManagement = tenantManagement;
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getCachedTenantManagement()
     */
    @Override
    public ITenantManagement getCachedTenantManagement() {
	return cachedTenantManagement;
    }

    public void setCachedTenantManagement(ITenantManagement cachedTenantManagement) {
	this.cachedTenantManagement = cachedTenantManagement;
    }

    /*
     * @see com.sitewhere.microservice.spi.IMicroservice#getSystemUser()
     */
    @Override
    public ISystemUser getSystemUser() {
	return systemUser;
    }

    public void setSystemUser(ISystemUser systemUser) {
	this.systemUser = systemUser;
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getKafkaTopicNaming()
     */
    @Override
    public IKafkaTopicNaming getKafkaTopicNaming() {
	return kafkaTopicNaming;
    }

    public void setKafkaTopicNaming(IKafkaTopicNaming kafkaTopicNaming) {
	this.kafkaTopicNaming = kafkaTopicNaming;
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#
     * getMicroserviceManagementGrpcServer()
     */
    @Override
    public IMicroserviceManagementGrpcServer getMicroserviceManagementGrpcServer() {
	return microserviceManagementGrpcServer;
    }

    public void setMicroserviceManagementGrpcServer(
	    IMicroserviceManagementGrpcServer microserviceManagementGrpcServer) {
	this.microserviceManagementGrpcServer = microserviceManagementGrpcServer;
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getScriptTemplateManager()
     */
    @Override
    public IScriptTemplateManager getScriptTemplateManager() {
	return scriptTemplateManager;
    }

    public void setScriptTemplateManager(IScriptTemplateManager scriptTemplateManager) {
	this.scriptTemplateManager = scriptTemplateManager;
    }

    /*
     * @see com.sitewhere.spi.microservice.IMicroservice#getMicroserviceAnalytics()
     */
    @Override
    public IMicroserviceAnalytics getMicroserviceAnalytics() {
	return microserviceAnalytics;
    }

    public void setMicroserviceAnalytics(IMicroserviceAnalytics microserviceAnalytics) {
	this.microserviceAnalytics = microserviceAnalytics;
    }

    /*
     * @see com.sitewhere.microservice.spi.IMicroservice#getInstanceSettings()
     */
    @Override
    public IInstanceSettings getInstanceSettings() {
	return instanceSettings;
    }

    public void setInstanceSettings(IInstanceSettings instanceSettings) {
	this.instanceSettings = instanceSettings;
    }

    /*
     * @see com.sitewhere.microservice.spi.IMicroservice#getVersion()
     */
    @Override
    public IVersion getVersion() {
	return version;
    }

    public void setVersion(IVersion version) {
	this.version = version;
    }

    /*
     * @see
     * com.sitewhere.spi.microservice.IMicroservice#getMicroserviceOperationsService
     * ()
     */
    @Override
    public ExecutorService getMicroserviceOperationsService() {
	return microserviceOperationsService;
    }

    public void setMicroserviceOperationsService(ExecutorService microserviceOperationsService) {
	this.microserviceOperationsService = microserviceOperationsService;
    }

    /** Used for naming microservice operation threads */
    private class MicroserviceOperationsThreadFactory implements ThreadFactory {

	/** Counts threads */
	private AtomicInteger counter = new AtomicInteger();

	public Thread newThread(Runnable r) {
	    return new Thread(r, "Service Ops " + counter.incrementAndGet());
	}
    }
}