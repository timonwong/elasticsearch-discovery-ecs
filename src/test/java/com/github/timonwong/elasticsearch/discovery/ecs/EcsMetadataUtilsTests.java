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

import com.sun.net.httpserver.HttpServer;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.mocksocket.MockHttpServer;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.function.BiConsumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.is;

@SuppressForbidden(reason = "use http server")
public class EcsMetadataUtilsTests extends ESTestCase {

    private static HttpServer httpServer;

    @BeforeClass
    public static void startHttp() throws Exception {
        httpServer = MockHttpServer.createHttp(new InetSocketAddress(InetAddress.getLoopbackAddress().getHostAddress(), 0), 0);
        httpServer.start();
    }

    @AfterClass
    public static void stopHttp() {
        httpServer.stop(0);
        httpServer = null;
    }

    @Before
    public void setup() {
        // redirect ECS metadata service to httpServer
        AccessController.doPrivileged((PrivilegedAction<String>) () ->
            System.setProperty(EcsMetadataUtils.ECS_METADATA_SERVICE_OVERRIDE_SYSTEM_PROPERTY,
                String.format("http://%s:%d", httpServer.getAddress().getHostName(), httpServer.getAddress().getPort()))
        );
    }

    @After
    public void teardown() {
        AccessController.doPrivileged((PrivilegedAction<String>) () ->
            System.clearProperty(EcsMetadataUtils.ECS_METADATA_SERVICE_OVERRIDE_SYSTEM_PROPERTY)
        );
    }

    public void testFindInstanceRoleName() {
        BiConsumer<String, String> registerContext = (path, v) -> {
            final byte[] message = v.getBytes(UTF_8);
            httpServer.createContext(path, (s) -> {
                s.sendResponseHeaders(RestStatus.OK.getStatus(), message.length);
                OutputStream responseBody = s.getResponseBody();
                responseBody.write(message);
                responseBody.close();
            });
        };

        registerContext.accept("/latest/meta-data/ram/security-credentials/", "ram-profile");
        assertThat(SocketAccess.doPrivileged(EcsMetadataUtils::findInstanceRoleName), is("ram-profile"));
    }

}
