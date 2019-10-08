/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cloud.alicloud.node;

import org.elasticsearch.cloud.alicloud.EcsMetadataUtils;
import org.elasticsearch.cluster.node.DiscoveryNodeService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 *
 */
public class EcsCustomNodeAttributes extends AbstractComponent implements DiscoveryNodeService.CustomAttributesProvider {

    public EcsCustomNodeAttributes(Settings settings) {
        super(settings);
    }

    @Override
    public Map<String, String> buildAttributes() {
        if (!settings.getAsBoolean("cloud.node.auto_attributes", false)) {
            return null;
        }
        Map<String, String> ecsAttributes = new HashMap<>();

        try {
            logger.debug("obtaining ecs [zone-id] from ecs meta-data url");
            String zoneId = EcsMetadataUtils.getZoneId();
            if (zoneId == null || zoneId.length() == 0) {
                logger.error("no ecs metadata returned");
                return null;
            }
            ecsAttributes.put("alicloud_zone_id", zoneId);
        } catch (IOException e) {
            logger.debug("failed to get ecs metadata for [zone-id]", e);
        }

        return ecsAttributes;
    }
}
