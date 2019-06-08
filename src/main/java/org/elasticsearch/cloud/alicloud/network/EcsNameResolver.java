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

package org.elasticsearch.cloud.alicloud.network;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.cloud.alicloud.AlicloudEcsServiceImpl;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.network.NetworkService.CustomNameResolver;
import org.elasticsearch.common.settings.Settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/**
 * <p>
 * Resolves certain ecs related 'meta' hostnames into an actual hostname
 * obtained from ecs meta-data.
 * </p>
 * Valid config values for {@link EcsHostnameType}s are -
 * <ul>
 * <li>_ecs_ - maps to privateIpv4</li>
 * <li>_ecs:privateIp_ - maps to privateIpv4</li>
 * <li>_ecs:privateIpv4_</li>
 * <li>_ecs:publicIp_ - maps to publicIpv4</li>
 * <li>_ecs:publicIpv4_</li>
 * </ul>
 *
 * @author Paul_Loy (keteracel)
 */
public class EcsNameResolver extends AbstractComponent implements CustomNameResolver {

    /**
     * enum that can be added to over time with more meta-data types (such as ipv6 when this is available)
     *
     * @author Paul_Loy
     */
    private enum EcsHostnameType {

        PRIVATE_IPv4("ecs:privateIpv4", "private-ipv4"),
        PUBLIC_IPv4("ecs:publicIpv4", "public-ipv4"),

        // some less verbose defaults
        PUBLIC_IP("ecs:publicIp", PUBLIC_IPv4.ecsName),
        PRIVATE_IP("ecs:privateIp", PRIVATE_IPv4.ecsName),
        ECS("ecs", PRIVATE_IPv4.ecsName);

        final String configName;
        final String ecsName;

        EcsHostnameType(String configName, String ecsName) {
            this.configName = configName;
            this.ecsName = ecsName;
        }
    }

    /**
     * Construct a {@link CustomNameResolver}.
     * @param settings The global settings
     */
    public EcsNameResolver(Settings settings) {
        super(settings);
    }

    /**
     * @param type the ecs hostname type to discover.
     * @return the appropriate host resolved from ecs meta-data.
     * @throws IOException if ecs meta-data cannot be obtained.
     * @see CustomNameResolver#resolveIfPossible(String)
     */
    public InetAddress[] resolve(EcsHostnameType type) throws IOException {
        InputStream in = null;
        String metadataUrl = AlicloudEcsServiceImpl.ECS_METADATA_URL + type.ecsName;
        try {
            URL url = new URL(metadataUrl);
            logger.debug("obtaining ecs hostname from ecs meta-data url {}", url);
            URLConnection urlConnection = url.openConnection();
            urlConnection.setConnectTimeout(2000);
            in = urlConnection.getInputStream();
            BufferedReader urlReader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            String metadataResult = urlReader.readLine();
            if (metadataResult == null || metadataResult.length() == 0) {
                throw new IOException("no gce metadata returned from [" + url + "] for [" + type.configName + "]");
            }
            // only one address: because we explicitly ask for only one via the EcsHostnameType
            return new InetAddress[] { InetAddress.getByName(metadataResult) };
        } catch (IOException e) {
            throw new IOException("IOException caught when fetching InetAddress from [" + metadataUrl + "]", e);
        } finally {
            IOUtils.closeWhileHandlingException(in);
        }
    }

    @Override
    public InetAddress[] resolveDefault() {
        return null; // using this, one has to explicitly specify _ecs_ in network setting
//        return resolve(EcsHostnameType.DEFAULT, false);
    }

    @Override
    public InetAddress[] resolveIfPossible(String value) throws IOException {
        for (EcsHostnameType type : EcsHostnameType.values()) {
            if (type.configName.equals(value)) {
                return resolve(type);
            }
        }
        return null;
    }

}
