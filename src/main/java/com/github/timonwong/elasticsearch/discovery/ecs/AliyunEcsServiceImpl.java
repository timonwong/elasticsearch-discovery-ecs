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

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.auth.AlibabaCloudCredentials;
import com.aliyuncs.auth.AlibabaCloudCredentialsProvider;
import com.aliyuncs.auth.StaticCredentialsProvider;
import com.aliyuncs.endpoint.DefaultEndpointResolver;
import com.aliyuncs.profile.DefaultProfile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.util.LazyInitializable;
import org.elasticsearch.common.util.concurrent.AbstractRefCounted;

import java.util.concurrent.atomic.AtomicReference;

public class AliyunEcsServiceImpl implements AliyunEcsService {

    private static final Logger logger = LogManager.getLogger(AliyunEcsServiceImpl.class);

    private final AtomicReference<LazyInitializable<AcsClientReference, ElasticsearchException>> lazyClientReference =
        new AtomicReference<>();

    private IAcsClient buildClient(EcsClientSettings clientSettings) {
        final AlibabaCloudCredentialsProvider credentials = buildCredentials(clientSettings);
        final DefaultProfile profile = DefaultProfile.getProfile(clientSettings.region);
        profile.setCredentialsProvider(credentials);

        // Customize endpoint
        String endpoint = null;
        if (Strings.hasText(clientSettings.endpoint)) {
            endpoint = clientSettings.endpoint;
            logger.debug("using explicit ecs endpoint [{}]", endpoint);
        }

        return buildClient(endpoint, profile, clientSettings);
    }

    // proxy for testing
    IAcsClient buildClient(String endpoint, DefaultProfile profile, EcsClientSettings clientSettings) {
        final DefaultAcsClient client = SocketAccess.doPrivileged(() -> new DefaultAcsClient(profile));

        if (endpoint != null) {
            final DefaultEndpointResolver endpointResolver = new DefaultEndpointResolver(client, profile);
            endpointResolver.putEndpointEntry(clientSettings.region, "ecs", endpoint);
            client.setEndpointResolver(endpointResolver);
        }

        return client;
    }

    // pkg private for tests
    static AlibabaCloudCredentialsProvider buildCredentials(EcsClientSettings clientSettings) {
        final AlibabaCloudCredentials credentials = clientSettings.credentials;
        if (credentials == null) {
            logger.debug("Using either environment variables, system properties or instance profile credentials");
            return new AliyunDefaultCredentialsProvider();
        } else {
            logger.debug("Using basic key/secret credentials");
            return new StaticCredentialsProvider(credentials);
        }
    }

    @Override
    public AcsClientReference client() {
        final LazyInitializable<AcsClientReference, ElasticsearchException> clientReference = this.lazyClientReference.get();
        if (clientReference == null) {
            throw new IllegalStateException("Missing ecs client configs");
        }
        return clientReference.getOrCompute();
    }

    /**
     * Refreshes the settings for the Aliyun ECS client. The new client will be build
     * using these new settings. The old client is usable until released. On release it
     * will be destroyed instead of being returned to the cache.
     */
    @Override
    public void refreshAndClearCache(EcsClientSettings clientSettings) {
        final LazyInitializable<AcsClientReference, ElasticsearchException> newClient = new LazyInitializable<>(
            () -> new AcsClientReference(buildClient(clientSettings)), AbstractRefCounted::incRef,
            AbstractRefCounted::decRef);
        final LazyInitializable<AcsClientReference, ElasticsearchException> oldClient = this.lazyClientReference.getAndSet(newClient);
        if (oldClient != null) {
            oldClient.reset();
        }
    }

    @Override
    public void close() {
        final LazyInitializable<AcsClientReference, ElasticsearchException> clientReference = this.lazyClientReference.getAndSet(null);
        if (clientReference != null) {
            clientReference.reset();
        }
    }
}
