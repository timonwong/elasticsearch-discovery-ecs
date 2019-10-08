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

import org.elasticsearch.cloud.alicloud.EcsMetadataUtils;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.network.NetworkService.CustomNameResolver;
import org.elasticsearch.common.settings.Settings;

import java.io.IOException;
import java.net.InetAddress;

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
     *
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
        try {
            logger.debug("obtaining ecs hostname from ecs meta-data {}", type.ecsName);
            String addr = EcsMetadataUtils.getMetadata(type.ecsName);
            if (addr == null || addr.length() == 0) {
                throw new IOException("no ecs metadata returned from [" + type.ecsName + "] for [" + type.configName + "]");
            }
            // only one address: because we explicitly ask for only one via the EcsHostnameType
            return new InetAddress[]{InetAddress.getByName(addr)};
        } catch (IOException e) {
            throw new IOException("IOException caught when fetching InetAddress from [" + type.ecsName + "]", e);
        }
    }

    @Override
    public InetAddress[] resolveDefault() {
        return null; // using this, one has to explicitly specify _ecs_ in network setting
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
