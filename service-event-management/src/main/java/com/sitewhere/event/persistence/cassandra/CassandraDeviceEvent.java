/*
 * Copyright (c) SiteWhere, LLC. All rights reserved. http://www.sitewhere.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package com.sitewhere.event.persistence.cassandra;

import com.datastax.driver.core.BoundStatement;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.device.event.IDeviceEvent;

public class CassandraDeviceEvent {

    /**
     * Bind fields from a device event to an existing {@link BoundStatement}.
     * 
     * @param bound
     * @param event
     * @throws SiteWhereException
     */
    public static void bindEventFields(BoundStatement bound, IDeviceEvent event) throws SiteWhereException {
	bound.setUUID("deviceId", event.getDeviceId());
	bound.setUUID("eventId", event.getId());
	if (event.getAlternateId() != null) {
	    bound.setString("alternateId", event.getAlternateId());
	}
	bound.setString("eventType", event.getEventType().name());
	bound.setUUID("assignmentId", event.getDeviceAssignmentId());
	if (event.getAreaId() != null) {
	    bound.setUUID("areaId", event.getAreaId());
	}
	if (event.getAssetId() != null) {
	    bound.setUUID("assetId", event.getAssetId());
	}
	bound.setTimestamp("eventDate", event.getEventDate());
	bound.setTimestamp("receivedDate", event.getReceivedDate());
    }
}
