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

import org.elasticsearch.Version;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.transport.nio.MockNioTransport;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

public class EcsDiscoveryTests extends ESTestCase {

    protected static ThreadPool threadPool;
    protected MockTransportService transportService;
    private final Map<String, TransportAddress> poorMansDNS = new ConcurrentHashMap<>();

    @BeforeClass
    public static void createThreadPool() {
        threadPool = new TestThreadPool(EcsDiscoveryTests.class.getName());
    }

    @AfterClass
    public static void stopThreadPool() {
        if (threadPool != null) {
            terminate(threadPool);
            threadPool = null;
        }
    }

    @Before
    public void createTransportService() {
        NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(Collections.emptyList());
        final Transport transport = new MockNioTransport(Settings.EMPTY, Version.CURRENT, threadPool,
            new NetworkService(Collections.emptyList()), PageCacheRecycler.NON_RECYCLING_INSTANCE, namedWriteableRegistry,
            new NoneCircuitBreakerService()) {
            @Override
            public TransportAddress[] addressesFromString(String address) {
                // we just need to ensure we don't resolve DNS here
                return new TransportAddress[]{poorMansDNS.getOrDefault(address, buildNewFakeTransportAddress())};
            }
        };
        transportService = new MockTransportService(Settings.EMPTY, transport, threadPool, TransportService.NOOP_TRANSPORT_INTERCEPTOR,
            null);
    }

    protected List<TransportAddress> buildDynamicHosts(Settings nodeSettings, int nodes) {
        return buildDynamicHosts(nodeSettings, nodes, null);
    }

    protected List<TransportAddress> buildDynamicHosts(Settings nodeSettings, int nodes, List<AcsClientMock.NodeOption> nodeOptions) {
        try (EcsDiscoveryPluginMock plugin = new EcsDiscoveryPluginMock(Settings.EMPTY, nodes, nodeOptions)) {
            AliyunEcsSeedHostsProvider provider = new AliyunEcsSeedHostsProvider(nodeSettings, transportService, plugin.ecsService);
            List<TransportAddress> dynamicHosts = provider.getSeedAddresses(null);
            logger.debug("--> addresses found: {}", dynamicHosts);
            return dynamicHosts;
        } catch (IOException e) {
            fail("Unexpected IOException");
            return null;
        }
    }

    public void testDefaultSettings() {
        int nodes = randomInt(10);
        Settings nodeSettings = Settings.builder()
            .build();
        List<TransportAddress> discoveryNodes = buildDynamicHosts(nodeSettings, nodes);
        assertThat(discoveryNodes, hasSize(nodes));
    }

    public void testPrivateIp() {
        int nodes = randomInt(10);
        for (int i = 0; i < nodes; i++) {
            poorMansDNS.put(AcsClientMock.PREFIX_PRIVATE_IP + (i + 1), buildNewFakeTransportAddress());
        }
        Settings nodeSettings = Settings.builder()
            .put(AliyunEcsService.HOST_TYPE_SETTING.getKey(), "private_ip")
            .build();
        List<TransportAddress> transportAddresses = buildDynamicHosts(nodeSettings, nodes);
        assertThat(transportAddresses, hasSize(nodes));
        // We check that we are using here expected address
        int node = 1;
        for (TransportAddress address : transportAddresses) {
            TransportAddress expected = poorMansDNS.get(AcsClientMock.PREFIX_PRIVATE_IP + node++);
            assertEquals(address, expected);
        }
    }

    public void testPublicIp() {
        int nodes = randomInt(10);
        for (int i = 0; i < nodes; i++) {
            poorMansDNS.put(AcsClientMock.PREFIX_PUBLIC_IP + (i + 1), buildNewFakeTransportAddress());
        }
        Settings nodeSettings = Settings.builder()
            .put(AliyunEcsService.HOST_TYPE_SETTING.getKey(), "public_ip")
            .build();
        List<TransportAddress> dynamicHosts = buildDynamicHosts(nodeSettings, nodes);
        assertThat(dynamicHosts, hasSize(nodes));
        // We check that we are using here expected address
        int node = 1;
        for (TransportAddress address : dynamicHosts) {
            TransportAddress expected = poorMansDNS.get(AcsClientMock.PREFIX_PUBLIC_IP + node++);
            assertEquals(address, expected);
        }
    }

    public void testInvalidHostType() {
        Settings nodeSettings = Settings.builder()
            .put(AliyunEcsService.HOST_TYPE_SETTING.getKey(), "does_not_exist")
            .build();

        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
            () -> buildDynamicHosts(nodeSettings, 1));
        assertThat(exception.getMessage(), containsString("does_not_exist is unknown for discovery.ecs.host_type"));
    }

    public void testFilterByZoneId() {
        int nodes = randomIntBetween(5, 10);
        Settings nodeSettings = Settings.builder()
            .put(AliyunEcsService.ZONE_IDS_SETTING.getKey(), "valid-zone")
            .build();

        int prodInstances = 0;
        List<AcsClientMock.NodeOption> nodeOptions = new ArrayList<>();
        for (int node = 0; node < nodes; node++) {
            final String zoneID;

            if (randomBoolean()) {
                zoneID = "valid-zone";
                prodInstances++;
            } else {
                zoneID = "invalid-zone";
            }
            final AcsClientMock.NodeOption nodeOption = AcsClientMock.NodeOption.builder()
                .setZondId(zoneID)
                .build();
            nodeOptions.add(nodeOption);
        }

        logger.info("started [{}] instances with [{}] zone=valid-zone", nodes, prodInstances);
        List<TransportAddress> dynamicHosts = buildDynamicHosts(nodeSettings, nodes, nodeOptions);
        assertThat(dynamicHosts, hasSize(prodInstances));
    }

    public void testFilterByMultipleZoneIds() {
        int nodes = randomIntBetween(5, 10);
        Settings nodeSettings = Settings.builder()
            .putList(AliyunEcsService.ZONE_IDS_SETTING.getKey(), "valid-zone-a", "valid-zone-b")
            .build();

        int prodInstances = 0;
        List<AcsClientMock.NodeOption> nodeOptions = new ArrayList<>();
        for (int node = 0; node < nodes; node++) {
            final String zoneID;

            if (randomBoolean()) {
                zoneID = randomBoolean() ? "valid-zone-a" : "valid-zone-b";
                prodInstances++;
            } else {
                zoneID = "invalid-zone";
            }
            final AcsClientMock.NodeOption nodeOption = AcsClientMock.NodeOption.builder()
                .setZondId(zoneID)
                .build();
            nodeOptions.add(nodeOption);
        }


        logger.info("started [{}] instances with [{}] zone=valid-zone", nodes, prodInstances);
        List<TransportAddress> dynamicHosts = buildDynamicHosts(nodeSettings, nodes, nodeOptions);
        assertThat(dynamicHosts, hasSize(prodInstances));
    }

    public void testFilterByTags() {
        int nodes = randomIntBetween(5, 10);
        Settings nodeSettings = Settings.builder()
            .put(AliyunEcsService.TAG_SETTING.getKey() + "stage", "prod")
            .build();

        int prodInstances = 0;
        List<AcsClientMock.NodeOption> nodeOptions = new ArrayList<>();
        for (int node = 0; node < nodes; node++) {
            final AcsClientMock.InstanceTag tag;
            if (randomBoolean()) {
                tag = new AcsClientMock.InstanceTag("stage", "prod");
                prodInstances++;
            } else {
                tag = new AcsClientMock.InstanceTag("stage", "dev");
            }
            final AcsClientMock.NodeOption nodeOption = AcsClientMock.NodeOption.builder()
                .setTags(Collections.singletonList(tag))
                .build();
            nodeOptions.add(nodeOption);
        }

        logger.info("started [{}] instances with [{}] stage=prod tag", nodes, prodInstances);
        List<TransportAddress> dynamicHosts = buildDynamicHosts(nodeSettings, nodes, nodeOptions);
        assertThat(dynamicHosts, hasSize(prodInstances));
    }

    public void testFilterByMultipleTags() {
        int nodes = randomIntBetween(5, 10);
        Settings nodeSettings = Settings.builder()
            .putList(AliyunEcsService.TAG_SETTING.getKey() + "stage", "prod", "preprod")
            .build();

        int prodInstances = 0;
        List<AcsClientMock.NodeOption> nodeOptions = new ArrayList<>();
        for (int node = 0; node < nodes; node++) {
            final AcsClientMock.InstanceTag tag;
            if (randomBoolean()) {
                tag = new AcsClientMock.InstanceTag("stage", randomBoolean() ? "preprod" : "prod");
                prodInstances++;
            } else {
                tag = new AcsClientMock.InstanceTag("stage", "test");
            }
            final AcsClientMock.NodeOption nodeOption = AcsClientMock.NodeOption.builder()
                .setTags(Collections.singletonList(tag))
                .build();
            nodeOptions.add(nodeOption);
        }

        logger.info("started [{}] instances with [{}] stage=prod tag", nodes, prodInstances);
        List<TransportAddress> dynamicHosts = buildDynamicHosts(nodeSettings, nodes, nodeOptions);
        assertThat(dynamicHosts, hasSize(prodInstances));
    }

    public void testReadHostFromTag() throws UnknownHostException {
        int nodes = randomIntBetween(5, 10);

        String[] addresses = new String[nodes];

        for (int node = 0; node < nodes; node++) {
            addresses[node] = "192.168.0." + (node + 1);
            poorMansDNS.put("node" + (node + 1), new TransportAddress(InetAddress.getByName(addresses[node]), 9300));
        }

        Settings nodeSettings = Settings.builder()
            .put(AliyunEcsService.HOST_TYPE_SETTING.getKey(), "tag:foo")
            .build();

        List<AcsClientMock.NodeOption> nodeOptions = new ArrayList<>();

        for (int node = 0; node < nodes; node++) {
            final AcsClientMock.NodeOption nodeOption = AcsClientMock.NodeOption.builder()
                .setTags(Collections.singletonList(new AcsClientMock.InstanceTag("foo", "node" + (node + 1))))
                .build();
            nodeOptions.add(nodeOption);
        }

        logger.info("started [{}] instances", nodes);
        List<TransportAddress> dynamicHosts = buildDynamicHosts(nodeSettings, nodes, nodeOptions);
        assertThat(dynamicHosts, hasSize(nodes));
        int node = 1;
        for (TransportAddress address : dynamicHosts) {
            TransportAddress expected = poorMansDNS.get("node" + node++);
            assertEquals(address, expected);
        }
    }

    abstract static class DummyEcsSeedHostsProvider extends AliyunEcsSeedHostsProvider {
        public int fetchCount = 0;

        DummyEcsSeedHostsProvider(Settings settings, TransportService transportService, AliyunEcsService service) {
            super(settings, transportService, service);
        }
    }

    public void testGetNodeListEmptyCache() {
        AliyunEcsService aliyunEcsService = new AliyunEcsServiceMock(1, null);
        DummyEcsSeedHostsProvider provider = new DummyEcsSeedHostsProvider(Settings.EMPTY, transportService, aliyunEcsService) {
            @Override
            protected List<TransportAddress> fetchDynamicNodes() {
                fetchCount++;
                return new ArrayList<>();
            }
        };
        for (int i = 0; i < 3; i++) {
            provider.getSeedAddresses(null);
        }
        assertThat(provider.fetchCount, is(3));
    }

    public void testGetNodeListCached() throws Exception {
        Settings.Builder builder = Settings.builder()
            .put(AliyunEcsService.NODE_CACHE_TIME_SETTING.getKey(), "500ms");
        try (EcsDiscoveryPluginMock plugin = new EcsDiscoveryPluginMock(Settings.EMPTY)) {
            DummyEcsSeedHostsProvider provider = new DummyEcsSeedHostsProvider(builder.build(), transportService, plugin.ecsService) {
                @Override
                protected List<TransportAddress> fetchDynamicNodes() {
                    fetchCount++;
                    return EcsDiscoveryTests.this.buildDynamicHosts(Settings.EMPTY, 1);
                }
            };
            for (int i = 0; i < 3; i++) {
                provider.getSeedAddresses(null);
            }
            assertThat(provider.fetchCount, is(1));
            Thread.sleep(1_000L); // wait for cache to expire
            for (int i = 0; i < 3; i++) {
                provider.getSeedAddresses(null);
            }
            assertThat(provider.fetchCount, is(2));
        }
    }

}
