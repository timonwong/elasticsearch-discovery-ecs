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

package org.elasticsearch.cloud.alicloud;

import com.aliyuncs.IAcsClient;
import org.elasticsearch.common.component.LifecycleComponent;

public interface AlicloudEcsService extends LifecycleComponent<AlicloudEcsService> {
    final class CLOUD_ECS {
        public static final String INSTANCE_ROLE = "cloud.alicloud.ecs.instance_role";
        public static final String KEY = "cloud.alicloud.ecs.access_key";
        public static final String SECRET = "cloud.alicloud.ecs.secret_key";
        public static final String REGION = "cloud.alicloud.ecs.region";
        public static final String ENDPOINT = "cloud.alicloud.ecs.endpoint";
        public static final String USE_VPC_ENDPOINT = "cloud.alicloud.ecs.use_vpc_endpoint";
    }

    final class DISCOVERY_ECS {
        public static final String HOST_TYPE = "discovery.ecs.host_type";
        public static final String ANY_GROUP = "discovery.ecs.any_group";
        public static final String GROUPS = "discovery.ecs.groups";
        public static final String TAG_PREFIX = "discovery.ecs.tag.";
        public static final String ZONE_IDS = "discovery.ecs.zone-ids";
        public static final String NODE_CACHE_TIME = "discovery.ecs.node_cache_time";
    }

    IAcsClient client();
}
