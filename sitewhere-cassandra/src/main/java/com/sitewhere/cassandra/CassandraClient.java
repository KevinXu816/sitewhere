/*
 * Copyright (c) SiteWhere, LLC. All rights reserved. http://www.sitewhere.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package com.sitewhere.cassandra;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.UserType;
import com.datastax.driver.core.exceptions.QueryExecutionException;
import com.sitewhere.configuration.instance.cassandra.CassandraConfiguration;
import com.sitewhere.server.lifecycle.TenantEngineLifecycleComponent;
import com.sitewhere.server.lifecycle.parameters.StringComponentParameter;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.server.lifecycle.IDiscoverableTenantLifecycleComponent;
import com.sitewhere.spi.server.lifecycle.ILifecycleComponentParameter;
import com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor;

/**
 * Client used for connecting to and interacting with an Apache Cassandra
 * cluster.
 * 
 * @author Derek
 */
public class CassandraClient extends TenantEngineLifecycleComponent implements IDiscoverableTenantLifecycleComponent {

    /** Static logger instance */
    private static Log LOGGER = LogFactory.getLog(CassandraClient.class);

    /** Cassandra configuration */
    private CassandraConfiguration configuration;

    /** Cassandra cluster reference */
    private Cluster cluster;

    /** Cassandra session */
    private Session session;

    /** User type for location */
    private UserType locationType;

    /** Prepared statement for inserting a device location by id */
    private PreparedStatement insertDeviceLocationById;

    /** Prepared statement for inserting a device location by assignment */
    private PreparedStatement insertDeviceLocationByAssignment;

    /** Prepared statement for inserting a device location by area */
    private PreparedStatement insertDeviceLocationByArea;

    /** Contact points parameter */
    private ILifecycleComponentParameter<String> contactPoints;

    /** Keyspace parameter */
    private ILifecycleComponentParameter<String> keyspace;

    public CassandraClient(CassandraConfiguration configuration) {
	this.configuration = configuration;
    }

    /*
     * @see com.sitewhere.server.lifecycle.LifecycleComponent#initializeParameters()
     */
    @Override
    public void initializeParameters() throws SiteWhereException {
	// Add contact points.
	this.contactPoints = StringComponentParameter.newBuilder(this, "Contact Points")
		.value(getConfiguration().getContactPoints()).makeRequired().build();
	getParameters().add(contactPoints);

	// Add keyspace.
	this.keyspace = StringComponentParameter.newBuilder(this, "Keyspace").value(getConfiguration().getKeyspace())
		.makeRequired().build();
	getParameters().add(keyspace);
    }

    /*
     * @see
     * com.sitewhere.server.lifecycle.LifecycleComponent#initialize(com.sitewhere.
     * spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void initialize(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	super.start(monitor);

	// Verify that contact points were specified.
	String[] contactPoints = getContactPoints().getValue().split(",");
	if (contactPoints.length == 0) {
	    throw new SiteWhereException("No contact points specified for Cassandra cluster.");
	}

	Cluster.Builder builder = Cluster.builder();
	for (String contactPoint : contactPoints) {
	    builder.addContactPoint(contactPoint.trim());
	}
	this.cluster = builder.build();
	this.session = getCluster().connect();

	// Intitialize tenant data constructs.
	initializeTenant();
	initializePreparedStatements();
    }

    /**
     * Initializes tenant keyspace if not already created.
     */
    protected void initializeTenant() throws SiteWhereException {
	// Create keyspace.
	execute("CREATE KEYSPACE IF NOT EXISTS " + getKeyspace().getValue()
		+ " WITH replication =  {'class':'SimpleStrategy','replication_factor':'1'}");

	// Use keyspace.
	execute("USE " + getKeyspace().getValue() + ";");

	// Create location type.
	execute("CREATE TYPE IF NOT EXISTS " + getKeyspace().getValue()
		+ ".sw_location (latitude double, longitude double, elevation double);");
	this.locationType = session.getCluster().getMetadata().getKeyspace(getKeyspace().getValue())
		.getUserType("sw_location");

	// Create measurements type.
	execute("CREATE TYPE IF NOT EXISTS " + getKeyspace().getValue()
		+ ".sw_measurements (measurements map<text, double>);");

	// Create alerts type.
	execute("CREATE TYPE IF NOT EXISTS " + getKeyspace().getValue()
		+ ".sw_alert (source text, level text, type text, message text);");

	// Create events_by_id table.
	execute("CREATE TABLE IF NOT EXISTS " + getKeyspace().getValue()
		+ ".events_by_id (deviceId uuid, eventId uuid, alternateId text, eventType text, assignmentId uuid, areaId uuid, assetId uuid, eventDate timestamp, receivedDate timestamp, location frozen<sw_location>, measurements frozen<sw_measurements>, alert frozen<sw_alert>, PRIMARY KEY ((deviceId), eventId));");

	// Create events_by_assignment table.
	execute("CREATE TABLE IF NOT EXISTS " + getKeyspace().getValue()
		+ ".events_by_assignment (deviceId uuid, eventId uuid, alternateId text, eventType text, assignmentId uuid, areaId uuid, assetId uuid, eventDate timestamp, receivedDate timestamp, location frozen<sw_location>, measurements frozen<sw_measurements>, alert frozen<sw_alert>, PRIMARY KEY ((deviceId), assignmentId, eventDate, eventId)) WITH CLUSTERING ORDER BY (assignmentId asc, eventDate desc, eventId asc);");

	// Create events_by_area table.
	execute("CREATE TABLE IF NOT EXISTS " + getKeyspace().getValue()
		+ ".events_by_area (deviceId uuid, eventId uuid, alternateId text, eventType text, assignmentId uuid, areaId uuid, assetId uuid, eventDate timestamp, receivedDate timestamp, location frozen<sw_location>, measurements frozen<sw_measurements>, alert frozen<sw_alert>, PRIMARY KEY ((deviceId), areaId, eventDate, eventId)) WITH CLUSTERING ORDER BY (areaId asc, eventDate desc, eventId asc);");
    }

    /**
     * Initialize prepared statements.
     * 
     * @throws SiteWhereException
     */
    protected void initializePreparedStatements() throws SiteWhereException {
	this.insertDeviceLocationById = getSession().prepare("insert into " + getKeyspace().getValue()
		+ ".events_by_id (deviceId, eventId, alternateId, eventType, assignmentId, areaId, assetId, eventDate, receivedDate, location) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
	this.insertDeviceLocationByAssignment = getSession().prepare("insert into " + getKeyspace().getValue()
		+ ".events_by_assignment (deviceId, eventId, alternateId, eventType, assignmentId, areaId, assetId, eventDate, receivedDate, location) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
	this.insertDeviceLocationByArea = getSession().prepare("insert into " + getKeyspace().getValue()
		+ ".events_by_area (deviceId, eventId, alternateId, eventType, assignmentId, areaId, assetId, eventDate, receivedDate, location) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
    }

    /**
     * Execute a query in the current Cassandra session.
     * 
     * @param query
     * @throws SiteWhereException
     */
    protected void execute(String query) throws SiteWhereException {
	try {
	    getSession().execute(query);
	} catch (QueryExecutionException e) {
	    throw new SiteWhereException("Query execution failed.", e);
	}
    }

    /*
     * @see com.sitewhere.spi.server.lifecycle.ILifecycleComponent#getLogger()
     */
    @Override
    public Log getLogger() {
	return LOGGER;
    }

    /*
     * @see
     * com.sitewhere.spi.server.lifecycle.IDiscoverableTenantLifecycleComponent#
     * isRequired()
     */
    @Override
    public boolean isRequired() {
	return true;
    }

    public CassandraConfiguration getConfiguration() {
	return configuration;
    }

    public void setConfiguration(CassandraConfiguration configuration) {
	this.configuration = configuration;
    }

    public ILifecycleComponentParameter<String> getContactPoints() {
	return contactPoints;
    }

    public void setContactPoints(ILifecycleComponentParameter<String> contactPoints) {
	this.contactPoints = contactPoints;
    }

    public ILifecycleComponentParameter<String> getKeyspace() {
	return keyspace;
    }

    public void setKeyspace(ILifecycleComponentParameter<String> keyspace) {
	this.keyspace = keyspace;
    }

    public Cluster getCluster() {
	return cluster;
    }

    public void setCluster(Cluster cluster) {
	this.cluster = cluster;
    }

    public Session getSession() {
	return session;
    }

    public void setSession(Session session) {
	this.session = session;
    }

    public UserType getLocationType() {
	return locationType;
    }

    public void setLocationType(UserType locationType) {
	this.locationType = locationType;
    }

    public PreparedStatement getInsertDeviceLocationById() {
	return insertDeviceLocationById;
    }

    public void setInsertDeviceLocationById(PreparedStatement insertDeviceLocationById) {
	this.insertDeviceLocationById = insertDeviceLocationById;
    }

    public PreparedStatement getInsertDeviceLocationByAssignment() {
	return insertDeviceLocationByAssignment;
    }

    public void setInsertDeviceLocationByAssignment(PreparedStatement insertDeviceLocationByAssignment) {
	this.insertDeviceLocationByAssignment = insertDeviceLocationByAssignment;
    }

    public PreparedStatement getInsertDeviceLocationByArea() {
	return insertDeviceLocationByArea;
    }

    public void setInsertDeviceLocationByArea(PreparedStatement insertDeviceLocationByArea) {
	this.insertDeviceLocationByArea = insertDeviceLocationByArea;
    }
}