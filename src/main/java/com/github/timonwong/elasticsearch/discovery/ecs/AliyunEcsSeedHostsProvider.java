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

package com.github.timonwong.elasticsearch.discovery.ecs;

import com.aliyuncs.ecs.model.v20140526.DescribeInstancesRequest;
import com.aliyuncs.ecs.model.v20140526.DescribeInstancesResponse;
import com.aliyuncs.exceptions.ClientException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.SingleObjectCache;
import org.elasticsearch.discovery.SeedHostsProvider;
import org.elasticsearch.transport.TransportService;

import java.util.*;
import java.util.stream.Collectors;

import static com.github.timonwong.elasticsearch.discovery.ecs.AliyunEcsService.HostType.PRIVATE_IP;
import static com.github.timonwong.elasticsearch.discovery.ecs.AliyunEcsService.HostType.PUBLIC_IP;
import static com.github.timonwong.elasticsearch.discovery.ecs.AliyunEcsService.HostType.TAG_PREFIX;


public class AliyunEcsSeedHostsProvider implements SeedHostsProvider {

    private static final Logger logger = LogManager.getLogger(AliyunEcsSeedHostsProvider.class);

    private final TransportService transportService;

    private final AliyunEcsService ecsService;

    private final boolean bindAnyGroup;

    private final Set<String> groups;

    private final Map<String, List<String>> tags;

    private final Map<String, Set<String>> tagsWithMultipleValues;

    private final Set<String> zoneIds;

    private final String hostType;

    private final TransportAddressesCache dynamicHosts;


    public AliyunEcsSeedHostsProvider(Settings settings, TransportService transportService, AliyunEcsService ecsService) {
        this.transportService = transportService;
        this.ecsService = ecsService;

        this.hostType = AliyunEcsService.HOST_TYPE_SETTING.get(settings);
        this.dynamicHosts = new TransportAddressesCache(AliyunEcsService.NODE_CACHE_TIME_SETTING.get(settings));

        this.bindAnyGroup = AliyunEcsService.ANY_GROUP_SETTING.get(settings);
        this.groups = new HashSet<>();
        this.groups.addAll(AliyunEcsService.GROUPS_SETTING.get(settings));

        this.tags = AliyunEcsService.TAG_SETTING.getAsMap(settings);
        this.tagsWithMultipleValues = tags.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .collect(Collectors.toMap(Map.Entry::getKey, (v) -> new HashSet<>(v.getValue())));

        this.zoneIds = new HashSet<>();
        zoneIds.addAll(AliyunEcsService.ZONE_IDS_SETTING.get(settings));

        if (logger.isDebugEnabled()) {
            logger.debug("using host_type [{}], tags [{}], groups [{}] with any_group [{}], availability_zones [{}]", hostType, tags,
                groups, bindAnyGroup, zoneIds);
        }
    }

    @Override
    public List<TransportAddress> getSeedAddresses(HostsResolver hostsResolver) {
        return dynamicHosts.getOrRefresh();
    }

    protected List<TransportAddress> fetchDynamicNodes() {

        final List<TransportAddress> dynamicHosts = new ArrayList<>();

        final List<DescribeInstancesResponse.Instance> instances = new ArrayList<>();
        for (int pageNumber = 1; ; pageNumber++) {
            final DescribeInstancesResponse descInstances;

            try (AcsClientReference clientReference = ecsService.client()) {
                // Query ECS API based on region, instance state, and tag.

                // NOTE: we don't filter by security group during the describe instances request for two reasons:
                // 1. differences in VPCs require different parameters during query (ID vs Name)
                // 2. We want to use two different strategies: (all security groups vs. any security groups)
                final int currentPageNumber = pageNumber;
                descInstances = SocketAccess.doPrivilegedClient(() -> clientReference.client().getAcsResponse(
                    buildDescribeInstancesRequest(currentPageNumber)));
            } catch (final ClientException e) {
                logger.info("Exception while retrieving instance list from ECS API: {}", e.getMessage());
                logger.debug("Full exception:", e);
                return dynamicHosts;
            }

            final List<DescribeInstancesResponse.Instance> respInstances = descInstances.getInstances();
            if (respInstances.isEmpty()) {
                break;
            }
            instances.addAll(respInstances);
        }

        logger.trace("finding seed nodes...");
        for (DescribeInstancesResponse.Instance instance : instances) {
            // first, filter based on region ids
            if (!zoneIds.isEmpty()) {
                if (!zoneIds.contains(instance.getZoneId())) {
                    logger.trace("filtering out instance {} based on zone {}, not part of {}", instance.getInstanceId(), instance.getZoneId(), zoneIds);
                    // continue to the next instance
                    continue;
                }
            }

            // then by tags, OR relationship
            boolean tagFound = true;
            for (final DescribeInstancesResponse.Instance.Tag tag : instance.getTags()) {
                if (!tagsWithMultipleValues.containsKey(tag.getTagKey())) {
                    continue;
                }

                if (!tagsWithMultipleValues.get(tag.getTagKey()).contains(tag.getTagValue())) {
                    tagFound = false;
                    break;
                }
            }
            if (!tagFound) {
                continue;
            }

            // lets see if we can filter based on groups
            if (!groups.isEmpty()) {
                final List<String> instanceSecurityGroups = instance.getSecurityGroupIds();
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
            if (hostType.equals(PRIVATE_IP)) {
                final DescribeInstancesResponse.Instance.VpcAttributes attrs = instance.getVpcAttributes();
                if (attrs == null) {
                    logger.debug("filtering out instance {}, classic network is not supported", instance.getInstanceId());
                    continue;
                }

                final List<String> addrList = attrs.getPrivateIpAddress();
                if (!addrList.isEmpty()) {
                    address = addrList.get(0);
                }
            } else if (hostType.equals(PUBLIC_IP)) {
                final DescribeInstancesResponse.Instance.EipAddress eipAddr = instance.getEipAddress();
                address = eipAddr.getIpAddress();
            } else if (hostType.startsWith(TAG_PREFIX)) {// Reading the node host from its metadata
                final String tagName = hostType.substring(TAG_PREFIX.length());
                logger.debug("reading hostname from [{}] instance tag", tagName);
                final List<DescribeInstancesResponse.Instance.Tag> tags = instance.getTags();
                for (final DescribeInstancesResponse.Instance.Tag tag : tags) {
                    if (tag.getTagKey().equals(tagName)) {
                        address = tag.getTagValue();
                        logger.debug("using [{}] as the instance address", address);
                        break;
                    }
                }
            } else {
                throw new IllegalArgumentException(hostType + " is unknown for discovery.ecs.host_type");
            }

            if (address != null) {
                try {
                    // we only limit to 1 port per address, makes no sense to ping 100 ports
                    TransportAddress[] addresses = transportService.addressesFromString(address);
                    for (TransportAddress transportAddr : addresses) {
                        logger.trace("adding {}, address {}, transport_address {}", instance.getInstanceId(), address, transportAddr);
                        dynamicHosts.add(transportAddr);
                    }
                } catch (final Exception e) {
                    logger.warn("failed to add {}, address {}: {}", instance.getInstanceId(), address, e);
                }
            } else {
                logger.trace("not adding {}, address is null, host_type {}", instance.getInstanceId(), hostType);
            }
        }

        logger.debug("using dynamic transport addresses {}", dynamicHosts);
        return dynamicHosts;
    }

    private DescribeInstancesRequest buildDescribeInstancesRequest(int pageNumber) {
        final List<DescribeInstancesRequest.Tag> filterTags = new ArrayList<>();
        for (final Map.Entry<String, List<String>> tagFilter : tags.entrySet()) {
            // for a given tag key, AND relationship is applied. So in order to implement OR
            // relationship for tag filter, only tag key is given.
            final DescribeInstancesRequest.Tag tag = new DescribeInstancesRequest.Tag();
            tag.setKey(tagFilter.getKey());
            if (tagFilter.getValue().size() == 1) {
                tag.setValue(tagFilter.getValue().get(0));
            }
            filterTags.add(tag);
        }

        final DescribeInstancesRequest request = new DescribeInstancesRequest();
        request.setPageNumber(pageNumber);
        request.setPageSize(100);
        request.setStatus("Running");
        request.setTags(filterTags);
        return request;
    }

    private final class TransportAddressesCache extends SingleObjectCache<List<TransportAddress>> {

        private boolean empty = true;

        protected TransportAddressesCache(TimeValue refreshInterval) {
            super(refreshInterval, Collections.emptyList());
        }

        @Override
        protected boolean needsRefresh() {
            return (empty || super.needsRefresh());
        }

        @Override
        protected List<TransportAddress> refresh() {
            final List<TransportAddress> nodes = fetchDynamicNodes();
            empty = nodes.isEmpty();
            return nodes;
        }
    }
}
