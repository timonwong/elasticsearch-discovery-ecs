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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.SeedHostsProvider;
import org.elasticsearch.node.Node;
import org.elasticsearch.plugins.DiscoveryPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ReloadablePlugin;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;


public class EcsDiscoveryPlugin extends Plugin implements DiscoveryPlugin, ReloadablePlugin {

    private static final Logger logger = LogManager.getLogger(EcsDiscoveryPlugin.class);
    private static final String ECS = "ecs";

    static {
        SpecialPermission.check();
    }

    private final Settings settings;
    // protected for testing
    protected final AliyunEcsService ecsService;

    public EcsDiscoveryPlugin(Settings settings) {
        this(settings, new AliyunEcsServiceImpl());
    }

    protected EcsDiscoveryPlugin(Settings settings, AliyunEcsServiceImpl ecsService) {
        this.settings = settings;
        this.ecsService = ecsService;
        // eagerly load client settings when secure settings are accessible
        reload(settings);
    }

    @Override
    public NetworkService.CustomNameResolver getCustomNameResolver(Settings settings) {
        logger.debug("Register _ecs_, _ecs:xxx_ network names");
        return new EcsNameResolver();
    }


    @Override
    public Map<String, Supplier<SeedHostsProvider>> getSeedHostProviders(TransportService transportService, NetworkService networkService) {
        return Collections.singletonMap(ECS, () -> new AliyunEcsSeedHostsProvider(settings, transportService, ecsService));
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(
            // Register ECS discovery settings: discovery.ecs
            EcsClientSettings.ACCESS_KEY_SETTING,
            EcsClientSettings.SECRET_KEY_SETTING,
            EcsClientSettings.SESSION_TOKEN_SETTING,
            EcsClientSettings.REGION_SETTING,
            EcsClientSettings.ENDPOINT_SETTING,
            AliyunEcsService.HOST_TYPE_SETTING,
            AliyunEcsService.ANY_GROUP_SETTING,
            AliyunEcsService.GROUPS_SETTING,
            AliyunEcsService.ZONE_IDS_SETTING,
            AliyunEcsService.NODE_CACHE_TIME_SETTING,
            AliyunEcsService.TAG_SETTING,
            // Register cloud node settings: cloud.node
            AliyunEcsService.AUTO_ATTRIBUTE_SETTING);
    }

    @Override
    public Settings additionalSettings() {
        final Settings.Builder builder = Settings.builder();

        // Adds a node attribute for the ecs availability zone
        builder.put(getZoneIdAttributes(settings));
        return builder.build();
    }

    // pkg private for testing
    @SuppressForbidden(reason = "We call getInputStream in doPrivileged and provide SocketPermission")
    static Settings getZoneIdAttributes(Settings settings) {
        if (!AliyunEcsService.AUTO_ATTRIBUTE_SETTING.get(settings)) {
            return Settings.EMPTY;
        }

        final Settings.Builder attrs = Settings.builder();
        try {
            logger.debug("obtaining ecs [zone-id] from ecs meta-data url");
            final String zoneId = SocketAccess.doPrivilegedIOException(EcsMetadataUtils::getZoneId);
            if (!Strings.hasText(zoneId)) {
                throw new IllegalStateException("no ecs [zone-id] metadata returned");
            } else {
                attrs.put(Node.NODE_ATTRIBUTES.getKey() + "alicloud_zone_id", zoneId);
            }
        } catch (final IOException e) {
            logger.error("failed to get ecs metadata for [zone-id]", e);
        }
        return attrs.build();
    }

    @Override
    public BiConsumer<DiscoveryNode, ClusterState> getJoinValidator() {
        return null;
    }

    @Override
    public void close() throws IOException {
        ecsService.close();
    }

    @Override
    public void reload(Settings settings) {
        // secure settings should be readable
        final EcsClientSettings clientSettings = EcsClientSettings.getClientSettings(settings);
        ecsService.refreshAndClearCache(clientSettings);
    }
}
