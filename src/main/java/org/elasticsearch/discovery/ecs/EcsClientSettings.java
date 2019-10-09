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

import com.aliyuncs.auth.AlibabaCloudCredentials;
import com.aliyuncs.auth.BasicCredentials;
import com.aliyuncs.auth.BasicSessionCredentials;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.elasticsearch.common.settings.*;
import org.elasticsearch.common.settings.Setting.Property;

import java.util.Locale;

/**
 * A container for settings used to create an ECS client.
 */
final class EcsClientSettings {
    /**
     * The region for connecting to ecs.
     */
    static final Setting<String> REGION_SETTING = new Setting<>("discovery.ecs.region", "", s -> s.toLowerCase(Locale.ROOT),
        Property.NodeScope);

    /**
     * The access key (ie login id) for connecting to ecs.
     */
    static final Setting<SecureString> ACCESS_KEY_SETTING = SecureSetting.secureString("discovery.ecs.access_key", null);

    /**
     * The secret key (ie password) for connecting to ecs.
     */
    static final Setting<SecureString> SECRET_KEY_SETTING = SecureSetting.secureString("discovery.ecs.secret_key", null);

    /**
     * The session token for connecting to ecs.
     */
    static final Setting<SecureString> SESSION_TOKEN_SETTING = SecureSetting.secureString("discovery.ecs.session_token", null);

    /**
     * An override for the ecs endpoint to connect to.
     */
    static final Setting<String> ENDPOINT_SETTING = new Setting<>("discovery.ecs.endpoint", "", s -> s.toLowerCase(Locale.ROOT),
        Property.NodeScope);

    private static final Logger logger = LogManager.getLogger(EcsClientSettings.class);
    /**
     * Credentials to authenticate with ecs.
     */
    final AlibabaCloudCredentials credentials;

    /**
     * The region to connect to.
     */
    final String region;

    /**
     * The ecs endpoint the client should talk to, or empty string to use the
     * default.
     */
    final String endpoint;

    private EcsClientSettings(AlibabaCloudCredentials credentials, String region, String endpoint) {
        this.credentials = credentials;
        this.region = region;
        this.endpoint = endpoint;
    }

    static AlibabaCloudCredentials loadCredentials(Settings settings) {
        try (final SecureString key = ACCESS_KEY_SETTING.get(settings);
             final SecureString secret = SECRET_KEY_SETTING.get(settings);
             final SecureString sessionToken = SESSION_TOKEN_SETTING.get(settings)) {
            if (key.length() == 0 && secret.length() == 0) {
                if (sessionToken.length() > 0) {
                    throw new SettingsException("Setting [{}] is set but [{}] and [{}] are not",
                        SESSION_TOKEN_SETTING.getKey(), ACCESS_KEY_SETTING.getKey(), SECRET_KEY_SETTING.getKey());
                }

                logger.debug("Using either environment variables, system properties or instance profile credentials");
                return null;
            } else {
                final AlibabaCloudCredentials credentials;
                if (sessionToken.length() == 0) {
                    logger.debug("Using basic key/secret credentials");
                    credentials = new BasicCredentials(key.toString(), secret.toString());
                } else {
                    logger.debug("Using basic session credentials");
                    credentials = new BasicSessionCredentials(key.toString(), secret.toString(), sessionToken.toString());
                }
                return credentials;
            }
        }
    }

    /**
     * Parse settings for a single client.
     */
    static EcsClientSettings getClientSettings(Settings settings) {
        final AlibabaCloudCredentials credentials = loadCredentials(settings);
        return new EcsClientSettings(
            credentials,
            REGION_SETTING.get(settings),
            ENDPOINT_SETTING.get(settings)
        );
    }
}

