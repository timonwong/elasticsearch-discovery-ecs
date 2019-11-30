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

package org.elasticsearch.discovery.ecs;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.unit.TimeValue;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

interface AliyunEcsService extends Closeable {
    Setting<Boolean> AUTO_ATTRIBUTE_SETTING = Setting.boolSetting("cloud.node.auto_attributes", false, Property.NodeScope);

    class HostType {
        public static final String PRIVATE_IP = "private_ip";
        public static final String PUBLIC_IP = "public_ip";
        public static final String TAG_PREFIX = "tag:";
    }

    /**
     * discovery.ecs.host_type: The type of host type to use to communicate with other instances.
     * Can be one of private_ip, public_ip or tag:XXXX where
     * XXXX refers to a name of a tag configured for all ECS instances. Instances which don't
     * have this tag set will be ignored by the discovery process. Defaults to private_ip.
     */
    Setting<String> HOST_TYPE_SETTING =
        new Setting<>("discovery.ecs.host_type", HostType.PRIVATE_IP, Function.identity(), Property.NodeScope);

    /**
     * discovery.ecs.any_group: If set to false, will require all security groups to be present for the instance to be used for the
     * discovery. Defaults to true.
     */
    Setting<Boolean> ANY_GROUP_SETTING = Setting.boolSetting("discovery.ecs.any_group", true, Property.NodeScope);

    /**
     * discovery.ecs.groups: Either a comma separated list or array based list of (security) groups. Only instances with the provided
     * security groups will be used in the cluster discovery. (NOTE: You can only provide group ID.)
     */
    Setting<List<String>> GROUPS_SETTING = Setting.listSetting("discovery.ecs.groups", new ArrayList<>(), s -> s, Property.NodeScope);

    /**
     * discovery.ecs.zone_ids: Either a comma separated list or array based list of zone ids. Only instances within
     * the provided zones will be used in the cluster discovery.
     */
    Setting<List<String>> ZONE_IDS_SETTING = Setting.listSetting("discovery.ecs.zone_ids", Collections.emptyList(),
        s -> s, Property.NodeScope);
    /**
     * discovery.ecs.node_cache_time: How long the list of hosts is cached to prevent further requests to the ECS API. Defaults to 10s.
     */
    Setting<TimeValue> NODE_CACHE_TIME_SETTING = Setting.timeSetting("discovery.ecs.node_cache_time", TimeValue.timeValueSeconds(10),
        Property.NodeScope);

    /**
     * discovery.ecs.tag.*: The ecs discovery can filter machines to include in the cluster based on tags (and not just groups).
     * The settings to use include the discovery.ecs.tag. prefix. For example, setting discovery.ecs.tag.stage to dev will only filter
     * instances with a tag key set to stage, and a value of dev. Several tags set will require all of those tags to be set for the
     * instance to be included.
     */
    Setting.AffixSetting<String> TAG_SETTING = Setting.prefixKeySetting("discovery.ecs.tag.",
        key -> Setting.simpleString(key, Property.NodeScope));


    AliyunEcsReference client();

    /**
     * Updates the settings for building the client and releases the cached one.
     * Future client requests will use the new settings to lazily built the new
     * client.
     *
     * @param clientSettings the new refreshed settings
     */
    void refreshAndClearCache(EcsClientSettings clientSettings);

}
