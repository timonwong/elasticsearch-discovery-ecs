# Aliyun ECS Discovery Plugin for Elasticsearch

## Versions

Plugin version | ES version
---------------|------------
2.3.4 | 2.3.4

## Install

Option 1: Download or compile:

Option 2: Use elasticsearch plugin program:

Once plugin installed, elasticsearch restart is required.

## Configuration

### Authentication

These can be overridden by, in increasing order of precedence, system properties aws.accessKeyId and aws.secretKey, environment variables AWS_ACCESS_KEY_ID and AWS_SECRET_KEY, or the elasticsearch config using cloud.aws.access_key and cloud.aws.secret_key:

RAM instance profile:

```yaml
cloud.alicloud.ecs.instance_role: instance-role # REQUIRED: RAM instance role
```

Access key & secret access key:

```yaml
cloud.alicloud.ecs.access_key: YOUR_ACCESS_KEY
cloud.alicloud.ecs.secret_key: YOUR_SECRET_KEY
```

#### Recommended ECS permissions

ECS discovery requires making a call to the ECS service. You’ll want to setup an RAM policy to allow this. You can create a custom policy via the RAM Management Console. It should look similar to this.

```json
{
    "Version": "1",
    "Statement": [
        {
            "Action": "ecs:DescribeInstances",
            "Resource": "*",
            "Effect": "Allow"
        }
    ]
}
```

### Region

`cloud.alicloud.ecs.region` should be set to the region which elasticsearch runs on.

Here is a short list of available regions:

- Regions in Mainland China
    - cn-qingdao
    - cn-beijing
    - cn-zhangjiakou
    - cn-huhehaote
    - cn-hangzhou
    - cn-shanghai
    - cn-shenzhen
- International regions
    - cn-hongkong
    - ap-southeast-1
    - ap-southeast-2
    - ap-southeast-3
    - ap-southeast-5
    - ap-south-1
    - ap-northeast-1
    - us-west-1
    - us-east-1
    - eu-central-1
    - eu-west-1
    - me-east-1

**NOTE**: For a complete list of regions, please visit: https://www.alibabacloud.com/help/doc-detail/40654.htm

### VPC Endpoint

In order to use internal VPC endpoint for ECS API. The `cloud.alicloud.ecs.use_vpc_endpoint` defaults to `true`.

### ECS Discovery

ecs discovery allows to use the ECS APIs to perform automatic discovery (similar to multicast in non hostile multicast environments). Here is a simple sample configuration:

```yaml
discovery.type: ecs
```

The following are a list of settings (prefixed with `discovery.ecs`) that can further control the discovery:

- groups: Either a comma separated list or array based list of (security) groups. Only instances with the provided security groups will be used in the cluster discovery. (NOTE: You should only provide group ID.)
- host_type: The type of host type to use to communicate with other instances. Can be one of `private_ip`, `public_ip`. Defaults to `private_ip`.
- zone_ids: Either a comma separated list or array based list of zones. Only instances within the provided availability zones will be used in the cluster discovery.
- any_group: If set to `false`, will require all security groups to be present for the instance to be used for the discovery. Defaults to `true`.
- node_cache_time: How long the list of hosts is cached to prevent further requests to the AWS API. Defaults to `10s`.

### ECS Network host

The following are also allowed as valid network host settings:

ECS Host Value | Description
---------------|-------------
`_ecs:privateIpv4_` | The private IP address (ipv4) of the machine.
`_ecs:publicIpv4_` | The public IP address (ipv4) of the machine.
`_ecs:privateIp_` | equivalent to `_ecs:privateIpv4_`.
`_ecs:publicIp_` | equivalent to `_ecs:publicIpv4_`.
`_ecs_` | equivalent to `_ecs:privateIpv4_`.

### Filtering by Tags

The ecs discovery can also filter machines to include in the cluster based on tags (and not just groups). The settings to use include the `discovery.ecs.tag.` prefix. For example, setting `discovery.ecs.tag.stage` to dev will only filter instances with a tag key set to stage, and a value of dev. Several tags set will require all of those tags to be set for the instance to be included.

One practical use for tag filtering is when an ecs cluster contains many nodes that are not running elasticsearch. In this case (particularly with high `discovery.zen.ping_timeout` values) there is a risk that a new node’s discovery phase will end before it has found the cluster (which will result in it declaring itself master of a new cluster with the same name - highly undesirable). Tagging elasticsearch ecs nodes and then filtering by that tag will resolve this issue.

### Automatic Node Attributes

Though not dependent on actually using ecs as discovery (but still requires the cloud aws plugin installed), the plugin can automatically add node attributes relating to ecs (for example, availability zone, that can be used with the awareness allocation feature). In order to enable it, set `cloud.node.auto_attributes` to true in the settings.
