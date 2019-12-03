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

import com.aliyuncs.auth.Credential;
import com.sun.net.httpserver.HttpServer;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.mocksocket.MockHttpServer;
import org.elasticsearch.node.Node;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.function.BiConsumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.elasticsearch.discovery.ecs.EcsMetadataUtils.ECS_METADATA_SERVICE_OVERRIDE_SYSTEM_PROPERTY;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@SuppressForbidden(reason = "use http server")
public class EcsDiscoveryPluginTests extends ESTestCase {

    private HttpServer startMetaServerForZoneId(String zoneId) throws Exception {
        final HttpServer httpServer = MockHttpServer.createHttp(
            new InetSocketAddress(InetAddress.getLoopbackAddress().getHostAddress(), 0), 0);

        BiConsumer<String, String> registerContext = (path, v) -> {
            final byte[] message = v.getBytes(UTF_8);
            httpServer.createContext(path, (s) -> {
                s.sendResponseHeaders(RestStatus.OK.getStatus(), message.length);
                OutputStream responseBody = s.getResponseBody();
                responseBody.write(message);
                responseBody.close();
            });
        };

        registerContext.accept("/latest/meta-data/zone-id", zoneId);
        httpServer.start();
        return httpServer;
    }

    private Settings getNodeAttributes(Settings settings, String zoneId) throws Exception {
        final HttpServer httpServer = startMetaServerForZoneId(zoneId);
        AccessController.doPrivileged((PrivilegedAction<String>) () ->
            System.setProperty(ECS_METADATA_SERVICE_OVERRIDE_SYSTEM_PROPERTY,
                "http://" + httpServer.getAddress().getHostName() + ":" + httpServer.getAddress().getPort())
        );

        try {
            final Settings realSettings = Settings.builder()
                .put(AliyunEcsService.AUTO_ATTRIBUTE_SETTING.getKey(), true)
                .put(settings).build();
            return EcsDiscoveryPlugin.getZoneIdAttributes(realSettings);
        } finally {
            httpServer.stop(0);
            AccessController.doPrivileged((PrivilegedAction<String>) () ->
                System.clearProperty(ECS_METADATA_SERVICE_OVERRIDE_SYSTEM_PROPERTY)
            );
        }
    }

    private void assertNodeAttributes(Settings settings, String zoneId, String expected) throws Exception {
        final Settings additional = getNodeAttributes(settings, zoneId);
        if (expected == null) {
            assertTrue(additional.isEmpty());
        } else {
            assertEquals(expected, additional.get(Node.NODE_ATTRIBUTES.getKey() + "alicloud_zone_id"));
        }
    }

    public void testNodeAttributesDisabled() throws Exception {
        final Settings settings = Settings.builder()
            .put(AliyunEcsService.AUTO_ATTRIBUTE_SETTING.getKey(), false).build();
        assertNodeAttributes(settings, "bogus", null);
    }

    public void testNodeAttributes() throws Exception {
        assertNodeAttributes(Settings.EMPTY, "cn-hangzhou-b", "cn-hangzhou-b");
    }

    public void testNodeAttributesEmpty() {
        final IllegalStateException e = expectThrows(IllegalStateException.class, () ->
            getNodeAttributes(Settings.EMPTY, "")
        );
        assertThat(e.getMessage(), is("no ecs [zone-id] metadata returned"));
    }

    public void testDefaultEndpoint() throws IOException {
        try (EcsDiscoveryPluginMock plugin = new EcsDiscoveryPluginMock(Settings.EMPTY)) {
            final String endpoint = ((AcsClientMock) plugin.ecsService.client().client()).endpoint;
            assertThat(endpoint, nullValue());
        }
    }

    public void testSpecificEndpoint() throws IOException {
        final Settings settings = Settings.builder().put(EcsClientSettings.ENDPOINT_SETTING.getKey(), "ecs.endpoint").build();
        try (EcsDiscoveryPluginMock plugin = new EcsDiscoveryPluginMock(settings)) {
            final String endpoint = ((AcsClientMock) plugin.ecsService.client().client()).endpoint;
            assertThat(endpoint, is("ecs.endpoint"));
        }
    }

    @SuppressWarnings("deprecation")
    public void testClientSettingsReInit() throws IOException {
        final MockSecureSettings mockSecure1 = new MockSecureSettings();
        mockSecure1.setString(EcsClientSettings.ACCESS_KEY_SETTING.getKey(), "ecs_access_1");
        mockSecure1.setString(EcsClientSettings.SECRET_KEY_SETTING.getKey(), "ecs_secret_1");
        final boolean mockSecure1HasSessionToken = randomBoolean();
        if (mockSecure1HasSessionToken) {
            mockSecure1.setString(EcsClientSettings.SESSION_TOKEN_SETTING.getKey(), "ecs_session_token_1");
        }
        final Settings settings1 = Settings.builder()
            .put(EcsClientSettings.ENDPOINT_SETTING.getKey(), "ecs_endpoint_1")
            .setSecureSettings(mockSecure1)
            .build();
        final MockSecureSettings mockSecure2 = new MockSecureSettings();
        mockSecure2.setString(EcsClientSettings.ACCESS_KEY_SETTING.getKey(), "ecs_access_2");
        mockSecure2.setString(EcsClientSettings.SECRET_KEY_SETTING.getKey(), "ecs_secret_2");
        final boolean mockSecure2HasSessionToken = randomBoolean();
        if (mockSecure2HasSessionToken) {
            mockSecure2.setString(EcsClientSettings.SESSION_TOKEN_SETTING.getKey(), "ecs_session_token_2");
        }
        final Settings settings2 = Settings.builder()
            .put(EcsClientSettings.ENDPOINT_SETTING.getKey(), "ecs_endpoint_2")
            .setSecureSettings(mockSecure2)
            .build();
        try (EcsDiscoveryPluginMock plugin = new EcsDiscoveryPluginMock(settings1)) {
            try (AcsClientReference clientReference = plugin.ecsService.client()) {
                {
                    final Credential credential = ((AcsClientMock) clientReference.client()).profile.getCredential();
                    assertThat(credential.getAccessKeyId(), is("ecs_access_1"));
                    assertThat(credential.getAccessSecret(), is("ecs_secret_1"));
                    if (mockSecure1HasSessionToken) {
                        assertThat(credential.getSecurityToken(), is("ecs_session_token_1"));
                    } else {
                        assertThat(credential.getSecurityToken(), is(nullValue()));
                    }
                    assertThat(((AcsClientMock) clientReference.client()).endpoint, is("ecs_endpoint_1"));
                }
                // reload secure settings2
                plugin.reload(settings2);
                // client is not released, it is still using the old settings
                {
                    final Credential credential = ((AcsClientMock) clientReference.client()).profile.getCredential();
                    if (mockSecure1HasSessionToken) {
                        assertThat(credential.getSecurityToken(), is("ecs_session_token_1"));
                    } else {
                        assertThat(credential.getSecurityToken(), is(nullValue()));
                    }
                    assertThat(((AcsClientMock) clientReference.client()).endpoint, is("ecs_endpoint_1"));
                }
            }
            try (AcsClientReference clientReference = plugin.ecsService.client()) {
                final Credential credential = ((AcsClientMock) clientReference.client()).profile.getCredential();
                assertThat(credential.getAccessKeyId(), is("ecs_access_2"));
                assertThat(credential.getAccessSecret(), is("ecs_secret_2"));
                if (mockSecure2HasSessionToken) {
                    assertThat(credential.getSecurityToken(), is("ecs_session_token_2"));
                } else {
                    assertThat(credential.getSecurityToken(), is(nullValue()));
                }
                assertThat(((AcsClientMock) clientReference.client()).endpoint, is("ecs_endpoint_2"));
            }
        }
    }

}
