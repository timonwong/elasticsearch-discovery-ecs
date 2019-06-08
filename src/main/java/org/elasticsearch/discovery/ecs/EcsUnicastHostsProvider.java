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

import com.aliyuncs.IAcsClient;
import com.aliyuncs.ecs.model.v20140526.DescribeInstancesRequest;
import com.aliyuncs.ecs.model.v20140526.DescribeInstancesResponse;
import com.aliyuncs.exceptions.ClientException;
import org.elasticsearch.Version;
import org.elasticsearch.cloud.alicloud.AlicloudEcsService;
import org.elasticsearch.cloud.alicloud.AlicloudEcsService.DISCOVERY_ECS;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.SingleObjectCache;
import org.elasticsearch.discovery.zen.ping.unicast.UnicastHostsProvider;
import org.elasticsearch.transport.TransportService;

import java.util.*;

/**
 *
 */
public class EcsUnicastHostsProvider extends AbstractComponent implements UnicastHostsProvider {

    private enum HostType {
        PRIVATE_IP,
        PUBLIC_IP,
    }

    private final TransportService transportService;

    private final IAcsClient client;

    private final Version version;

    private final boolean bindAnyGroup;

    private final Set<String> groups;

    private final Map<String, String> tags;

    private final Set<String> zoneIds;

    private final HostType hostType;

    private final DiscoNodesCache discoNodes;

    @Inject
    public EcsUnicastHostsProvider(Settings settings, TransportService transportService, AlicloudEcsService alicloudEcsService, Version version) {
        super(settings);
        this.transportService = transportService;
        this.client = alicloudEcsService.client();
        this.version = version;

        this.hostType = HostType.valueOf(settings.get(DISCOVERY_ECS.HOST_TYPE, "private_ip")
                .toUpperCase(Locale.ROOT));

        this.discoNodes = new DiscoNodesCache(this.settings.getAsTime(DISCOVERY_ECS.NODE_CACHE_TIME,
                TimeValue.timeValueMillis(10_000L)));

        this.bindAnyGroup = settings.getAsBoolean(DISCOVERY_ECS.ANY_GROUP, true);
        this.groups = new HashSet<>();
        groups.addAll(Arrays.asList(settings.getAsArray(DISCOVERY_ECS.GROUPS)));

        this.tags = settings.getByPrefix(DISCOVERY_ECS.TAG_PREFIX).getAsMap();

        Set<String> zonIds = new HashSet<>();
        zonIds.addAll(Arrays.asList(settings.getAsArray(DISCOVERY_ECS.ZONE_IDS)));
        if (settings.get(DISCOVERY_ECS.ZONE_IDS) != null) {
            zonIds.addAll(Strings.commaDelimitedListToSet(settings.get(DISCOVERY_ECS.ZONE_IDS)));
        }
        this.zoneIds = zonIds;

        if (logger.isDebugEnabled()) {
            logger.debug("using host_type [{}], tags [{}], groups [{}] with any_group [{}], availability_zones [{}]", hostType, tags, groups, bindAnyGroup, zonIds);
        }
    }

    @Override
    public List<DiscoveryNode> buildDynamicNodes() {
        return discoNodes.getOrRefresh();
    }

    protected List<DiscoveryNode> fetchDynamicNodes() {
        List<DiscoveryNode> discoNodes = new ArrayList<>();

        int nextPageNumber = 1;
        final List<DescribeInstancesResponse.Instance> instances = new ArrayList<>();
        while (true) {
            final DescribeInstancesResponse descInstances;

            try {
                // Query ECS API based on region, instance state, and tag.

                // NOTE: we don't filter by security group during the describe instances request for two reasons:
                // 1. differences in VPCs require different parameters during query (ID vs Name)
                // 2. We want to use two different strategies: (all security groups vs. any security groups)

                descInstances = client.getAcsResponse(buildDescribeInstancesRequest(nextPageNumber));
            } catch (ClientException e) {
                logger.info("Exception while retrieving instance list from AWS API: {}", e.getMessage());
                logger.debug("Full exception:", e);
                return discoNodes;
            }

            final List<DescribeInstancesResponse.Instance> interInstances = descInstances.getInstances();
            if (interInstances.isEmpty()) {
                break;
            }

            instances.addAll(interInstances);
            nextPageNumber++;
        }

        logger.trace("building dynamic unicast discovery nodes...");
        for (DescribeInstancesResponse.Instance instance : instances) {
            // first, filter based on region ids
            if (!zoneIds.isEmpty()) {
                if (!zoneIds.contains(instance.getZoneId())) {
                    logger.trace("filtering out instance {} based on zone {}, not part of {}", instance.getInstanceId(), instance.getZoneId(), zoneIds);
                    // continue to the next instance
                    continue;
                }
            }

            // lets see if we can filter based on groups
            if (!groups.isEmpty()) {
                List<String> instanceSecurityGroups = instance.getSecurityGroupIds();
                if (bindAnyGroup) {
                    // We check if we can find at least one group name or one group id in groups.
                    if (Collections.disjoint(instanceSecurityGroups, groups)) {
                        logger.trace("filtering out instance {} based on groups {}, not part of {}", instance.getInstanceId(), instanceSecurityGroups, groups);
                        // continue to the next instance
                        continue;
                    }
                } else {
                    // We need tp match all group names or group ids, otherwise we ignore this instance
                    if (!(instanceSecurityGroups.containsAll(groups))) {
                        logger.trace("filtering out instance {} based on groups {}, does not include all of {}", instance.getInstanceId(), instanceSecurityGroups, groups);
                        // continue to the next instance
                        continue;
                    }
                }
            }

            String address = null;
            switch (hostType) {
                case PRIVATE_IP:
                    address = instance.getInnerIpAddress().get(0);
                    break;
                case PUBLIC_IP:
                    final List<String> addresses = instance.getPublicIpAddress();
                    if (addresses.size() > 0) {
                        address = addresses.get(0);
                    }
                    break;
            }
            if (address != null) {
                try {
                    // we only limit to 1 port per address, makes no sense to ping 100 ports
                    TransportAddress[] addresses = transportService.addressesFromString(address, 1);
                    for (int i = 0; i < addresses.length; i++) {
                        logger.trace("adding {}, address {}, transport_address {}", instance.getInstanceId(), address, addresses[i]);
                        discoNodes.add(new DiscoveryNode("#cloud-" + instance.getInstanceId() + "-" + i, addresses[i], version.minimumCompatibilityVersion()));
                    }
                } catch (Exception e) {
                    logger.warn("failed ot add {}, address {}", e, instance.getInstanceId(), address);
                }
            } else {
                logger.trace("not adding {}, address is null, host_type {}", instance.getInstanceId(), hostType);
            }
        }

        logger.debug("using dynamic discovery nodes {}", discoNodes);

        return discoNodes;
    }

    private DescribeInstancesRequest buildDescribeInstancesRequest(final int pageNumber) {
        DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest();
        describeInstancesRequest.setStatus("Running");
        if (pageNumber > 1) {
            describeInstancesRequest.setPageNumber(pageNumber);
        }
        describeInstancesRequest.setPageSize(100);

        final List<DescribeInstancesRequest.Tag> filterTags = new ArrayList<>();
        for (Map.Entry<String, String> tagFilter : tags.entrySet()) {
            // for a given tag key, OR relationship for multiple different values
            final DescribeInstancesRequest.Tag tag = new DescribeInstancesRequest.Tag();
            tag.setKey(tagFilter.getKey());
            tag.setValue(tagFilter.getValue());
            filterTags.add(tag);
        }
        describeInstancesRequest.setTags(filterTags);
        return describeInstancesRequest;
    }

    private final class DiscoNodesCache extends SingleObjectCache<List<DiscoveryNode>> {

        private boolean empty = true;

        protected DiscoNodesCache(TimeValue refreshInterval) {
            super(refreshInterval, new ArrayList<DiscoveryNode>());
        }

        @Override
        protected boolean needsRefresh() {
            return (empty || super.needsRefresh());
        }

        @Override
        protected List<DiscoveryNode> refresh() {
            List<DiscoveryNode> nodes = fetchDynamicNodes();
            empty = nodes.isEmpty();
            return nodes;
        }
    }
}
