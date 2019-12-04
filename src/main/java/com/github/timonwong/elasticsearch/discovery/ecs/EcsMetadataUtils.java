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

import org.apache.commons.io.IOUtils;
import org.elasticsearch.common.SuppressForbidden;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class EcsMetadataUtils {
    public static class EcsMetadataException extends IOException {
        EcsMetadataException() {
            super();
        }

        EcsMetadataException(String message) {
            super(message);
        }
    }

    public static final String ECS_METADATA_SERVICE_OVERRIDE_SYSTEM_PROPERTY = "com.github.timonwong.elasticsearch.discovery.ecs.ecsMetadataServiceEndpointOverride";
    private static final String ECS_METADATA_SERVICE_URL = "http://100.100.100.200";
    private static final String ECS_METADATA_ROOT = "/latest/meta-data/";

    @SuppressForbidden(reason = "We call getInputStream in doPrivileged and provide SocketPermission")
    private static String readResult(String url, int retries) throws IOException {
        final URL endpoint = new URL(url);
        int attempts = 0;

        while (true) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) endpoint.openConnection(Proxy.NO_PROXY);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.connect();

                final int statusCode = connection.getResponseCode();
                if (statusCode == HttpURLConnection.HTTP_OK) {
                    final InputStream in = connection.getInputStream();
                    return IOUtils.toString(in, StandardCharsets.UTF_8);
                } else if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    throw new EcsMetadataException("Invalid metadata url " + url);
                }
            } catch (final EcsMetadataException e) {
                throw e;
            } catch (final IOException e) {
                attempts++;
                if (attempts > retries) {
                    throw e;
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    public static String getMetadata(String component) throws IOException {
        return getMetadata(component, 0);
    }

    public static String getMetadata(String component, int retries) throws IOException {
        return readResult(getEcsMetadataServiceUrl() + ECS_METADATA_ROOT + component, retries);
    }

    private static String getEcsMetadataServiceUrl() {
        final String endpoint = System.getProperty(ECS_METADATA_SERVICE_OVERRIDE_SYSTEM_PROPERTY);
        if (endpoint != null) {
            return endpoint;
        }
        return ECS_METADATA_SERVICE_URL;
    }

    public static String getZoneId() throws IOException {
        return getMetadata("zone-id");
    }

    public static String findInstanceRoleName() {
        try {
            final String result = getMetadata("ram/security-credentials/");
            final String[] lines = result.split("\n");
            if (lines.length == 0) {
                return null;
            }

            return lines[0];
        } catch (final IOException e) {
            return null;
        }
    }
}
