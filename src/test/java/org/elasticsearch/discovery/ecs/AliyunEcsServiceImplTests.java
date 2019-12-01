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
import com.aliyuncs.auth.AlibabaCloudCredentialsProvider;
import com.aliyuncs.auth.BasicSessionCredentials;
import com.aliyuncs.exceptions.ClientException;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;


public class AliyunEcsServiceImplTests extends ESTestCase {

    public void testAliyunCredentialsWithDefaultProviders() {
        final AlibabaCloudCredentialsProvider credentialsProvider = AliyunEcsServiceImpl.buildCredentials(
            EcsClientSettings.getClientSettings(Settings.EMPTY));
        assertThat(credentialsProvider, instanceOf(AliyunDefaultCredentialsProvider.class));
    }

    public void testAliyunCredentialsWithElasticsearchEcsSettings() throws ClientException {
        final MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("discovery.ecs.access_key", "aliyun_access_key");
        secureSettings.setString("discovery.ecs.secret_key", "aliyun_secret");
        final AlibabaCloudCredentials credentials = AliyunEcsServiceImpl.buildCredentials(
            EcsClientSettings.getClientSettings(Settings.builder().setSecureSettings(secureSettings).build())).getCredentials();
        assertThat(credentials.getAccessKeyId(), is("aliyun_access_key"));
        assertThat(credentials.getAccessKeySecret(), is("aliyun_secret"));
    }

    public void testAliyunSessionCredentialWithElasticsearchEcsSettings() throws ClientException {
        final MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("discovery.ecs.access_key", "aliyun_access_key");
        secureSettings.setString("discovery.ecs.secret_key", "aliyun_secret");
        secureSettings.setString("discovery.ecs.session_token", "aliyun_session_token");
        final AlibabaCloudCredentials credentials = AliyunEcsServiceImpl.buildCredentials(
            EcsClientSettings.getClientSettings(Settings.builder().setSecureSettings(secureSettings).build())).getCredentials();
        assertThat(credentials, instanceOf(BasicSessionCredentials.class));
        assertThat(credentials.getAccessKeyId(), is("aliyun_access_key"));
        assertThat(credentials.getAccessKeySecret(), is("aliyun_secret"));
        assertThat(((BasicSessionCredentials)credentials).getSessionToken(), is("aliyun_session_token"));
    }

    public void testRejectionOfLoneSessionToken() {
        final MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("discovery.ecs.session_token", "aws_session_token");
        SettingsException e = expectThrows(SettingsException.class, () -> AliyunEcsServiceImpl.buildCredentials(
            EcsClientSettings.getClientSettings(Settings.builder().setSecureSettings(secureSettings).build())));
        assertThat(e.getMessage(), is(
            "Setting [discovery.ecs.session_token] is set but [discovery.ecs.access_key] and [discovery.ecs.secret_key] are not"));
    }

}
