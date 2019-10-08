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

package org.elasticsearch.cloud.alicloud;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.auth.InstanceProfileCredentialsProvider;
import com.aliyuncs.endpoint.DefaultEndpointResolver;
import com.aliyuncs.profile.DefaultProfile;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.cloud.alicloud.network.EcsNameResolver;
import org.elasticsearch.cloud.alicloud.node.EcsCustomNodeAttributes;
import org.elasticsearch.cluster.node.DiscoveryNodeService;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;

/**
 *
 */
public class AlicloudEcsServiceImpl extends AbstractLifecycleComponent<AlicloudEcsService> implements AlicloudEcsService {

    public static final String ECS_METADATA_URL = "http://100.100.100.200/latest/meta-data/";

    private DefaultAcsClient client;

    @Inject
    public AlicloudEcsServiceImpl(Settings settings, SettingsFilter settingsFilter, NetworkService networkService, DiscoveryNodeService discoveryNodeService) {
        super(settings);
        settingsFilter.addFilter(CLOUD_ECS.KEY);
        settingsFilter.addFilter(CLOUD_ECS.SECRET);
        // add specific ecs name resolver
        networkService.addCustomNameResolver(new EcsNameResolver(settings));
        discoveryNodeService.addCustomAttributeProvider(new EcsCustomNodeAttributes(settings));
    }

    public synchronized IAcsClient client() {
        if (client != null) {
            return client;
        }

        String region = settings.get(CLOUD_ECS.REGION);
        if (region != null) {
            region = region.toLowerCase();
        }

        DefaultProfile profile;
        if (settings.get(CLOUD_ECS.INSTANCE_ROLE) != null) {
            String role = settings.get(CLOUD_ECS.INSTANCE_ROLE);
            logger.debug("using instance role [{}]", role);

            profile = DefaultProfile.getProfile(region);
            profile.setCredentialsProvider(new InstanceProfileCredentialsProvider(role));
        } else {
            String account = settings.get(CLOUD_ECS.KEY);
            String key = settings.get(CLOUD_ECS.SECRET);

            profile = DefaultProfile.getProfile(region, account, key);
        }

        if (settings.getAsBoolean(CLOUD_ECS.USE_VPC_ENDPOINT, true)) {
            logger.debug("using vpc endpoint");
            profile.enableUsingVpcEndpoint();
        }

        client = new DefaultAcsClient(profile);

        // Customize endpoint
        DefaultEndpointResolver endpointResolver = new DefaultEndpointResolver(client);
        String endpoint = settings.get(CLOUD_ECS.ENDPOINT);
        if (endpoint != null) {
            logger.debug("using explicit ecs endpoint [{}]", endpoint);
            endpointResolver.putEndpointEntry(region, "ecs", endpoint);
        }

        client.setEndpointResolver(endpointResolver);
        return this.client;
    }

    @Override
    protected void doStart() throws ElasticsearchException {
    }

    @Override
    protected void doStop() throws ElasticsearchException {
    }

    @Override
    protected void doClose() throws ElasticsearchException {
        if (client != null) {
            client.shutdown();
        }
    }
}
