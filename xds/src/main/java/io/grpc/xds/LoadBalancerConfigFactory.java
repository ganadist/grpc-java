/*
 * Copyright 2022 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import com.github.xds.type.v3.TypedStruct;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LeastRequestLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.LoadBalancingPolicy;
import io.envoyproxy.envoy.config.cluster.v3.LoadBalancingPolicy.Policy;
import io.envoyproxy.envoy.extensions.load_balancing_policies.ring_hash.v3.RingHash;
import io.envoyproxy.envoy.extensions.load_balancing_policies.round_robin.v3.RoundRobin;
import io.envoyproxy.envoy.extensions.load_balancing_policies.wrr_locality.v3.WrrLocality;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancerRegistry;
import io.grpc.internal.JsonParser;
import io.grpc.xds.ClientXdsClient.ResourceInvalidException;
import io.grpc.xds.LoadBalancerConfigFactory.LoadBalancingPolicyConverter.MaxRecursionReachedException;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.io.IOException;
import java.util.Map;

/**
 * Creates service config JSON  load balancer config objects for a given xDS Cluster message.
 * Supports both the "legacy" configuration style and the new, more advanced one that utilizes the
 * xDS "typed extension" mechanism.
 *
 * <p>Legacy configuration is done by setting the lb_policy enum field and any supporting
 * configuration fields needed by the particular policy.
 *
 * <p>The new approach is to set the load_balancing_policy field that contains both the policy
 * selection as well as any supporting configuration data. Providing a list of acceptable policies
 * is also supported. Note that if this field is used, it will override any configuration set using
 * the legacy approach. The new configuration approach is explained in detail in the <a href="
 * https://github.com/grpc/proposal/blob/master/A52-xds-custom-lb-policies.md">Custom LB Policies
 * gRFC</a>
 */
class LoadBalancerConfigFactory {

  private static final XdsLogger logger = XdsLogger.withLogId(
      InternalLogId.allocate("xds-client-lbconfig-factory", null));

  static final String ROUND_ROBIN_FIELD_NAME = "round_robin";

  static final String RING_HASH_FIELD_NAME = "ring_hash_experimental";
  static final String MIN_RING_SIZE_FIELD_NAME = "minRingSize";
  static final String MAX_RING_SIZE_FIELD_NAME = "maxRingSize";

  static final String LEAST_REQUEST_FIELD_NAME = "least_request_experimental";
  static final String CHOICE_COUNT_FIELD_NAME = "choiceCount";

  static final String WRR_LOCALITY_FIELD_NAME = "wrr_locality_experimental";
  static final String CHILD_POLICY_FIELD = "childPolicy";

  /**
   * Factory method for creating a new {link LoadBalancerConfigConverter} for a given xDS {@link
   * Cluster}.
   *
   * @throws ResourceInvalidException If the {@link Cluster} has an invalid LB configuration.
   */
  static ImmutableMap<String, ?> newConfig(Cluster cluster, boolean enableLeastRequest)
      throws ResourceInvalidException {
    // The new load_balancing_policy will always be used if it is set, but for backward
    // compatibility we will fall back to using the old lb_policy field if the new field is not set.
    if (cluster.hasLoadBalancingPolicy()) {
      try {
        return LoadBalancingPolicyConverter.convertToServiceConfig(cluster.getLoadBalancingPolicy(),
            0);
      } catch (MaxRecursionReachedException e) {
        throw new ResourceInvalidException("Maximum LB config recursion depth reached", e);
      }
    } else {
      return LegacyLoadBalancingPolicyConverter.convertToServiceConfig(cluster, enableLeastRequest);
    }
  }

  /**
   * Builds a service config JSON object for the ring_hash load balancer config based on the given
   * config values.
   */
  private static ImmutableMap<String, ?> buildRingHashConfig(Long minRingSize, Long maxRingSize) {
    ImmutableMap.Builder<String, Object> configBuilder = ImmutableMap.builder();
    if (minRingSize != null) {
      configBuilder.put(MIN_RING_SIZE_FIELD_NAME, minRingSize.doubleValue());
    }
    if (maxRingSize != null) {
      configBuilder.put(MAX_RING_SIZE_FIELD_NAME, maxRingSize.doubleValue());
    }
    return ImmutableMap.of(RING_HASH_FIELD_NAME, configBuilder.buildOrThrow());
  }

  /**
   * Builds a service config JSON object for the least_request load balancer config based on the
   * given config values..
   */
  private static ImmutableMap<String, ?> buildLeastRequestConfig(Integer choiceCount) {
    ImmutableMap.Builder<String, Object> configBuilder = ImmutableMap.builder();
    if (choiceCount != null) {
      configBuilder.put(CHOICE_COUNT_FIELD_NAME, choiceCount.doubleValue());
    }
    return ImmutableMap.of(LEAST_REQUEST_FIELD_NAME, configBuilder.buildOrThrow());
  }

  /**
   * Builds a service config JSON wrr_locality by wrapping another policy config.
   */
  private static ImmutableMap<String, ?> buildWrrLocalityConfig(
      ImmutableMap<String, ?> childConfig) {
    return ImmutableMap.<String, Object>builder().put(WRR_LOCALITY_FIELD_NAME,
        ImmutableMap.of(CHILD_POLICY_FIELD, ImmutableList.of(childConfig))).buildOrThrow();
  }

  /**
   * Builds an empty service config JSON config object for round robin (it is not configurable).
   */
  private static ImmutableMap<String, ?> buildRoundRobinConfig() {
    return ImmutableMap.of(ROUND_ROBIN_FIELD_NAME, ImmutableMap.of());
  }

  /**
   * Responsible for converting from a {@code envoy.config.cluster.v3.LoadBalancingPolicy} proto
   * message to a gRPC service config format.
   */
  static class LoadBalancingPolicyConverter {

    private static final int MAX_RECURSION = 16;

    /**
     * Converts a {@link LoadBalancingPolicy} object to a service config JSON object.
     */
    private static ImmutableMap<String, ?> convertToServiceConfig(
        LoadBalancingPolicy loadBalancingPolicy, int recursionDepth)
        throws ResourceInvalidException, MaxRecursionReachedException {
      if (recursionDepth > MAX_RECURSION) {
        throw new MaxRecursionReachedException();
      }
      ImmutableMap<String, ?> serviceConfig = null;

      for (Policy policy : loadBalancingPolicy.getPoliciesList()) {
        Any typedConfig = policy.getTypedExtensionConfig().getTypedConfig();
        try {
          if (typedConfig.is(RingHash.class)) {
            serviceConfig = convertRingHashConfig(typedConfig.unpack(RingHash.class));
          } else if (typedConfig.is(WrrLocality.class)) {
            serviceConfig = convertWrrLocalityConfig(typedConfig.unpack(WrrLocality.class),
                recursionDepth);
          } else if (typedConfig.is(RoundRobin.class)) {
            serviceConfig = convertRoundRobinConfig();
          } else if (typedConfig.is(TypedStruct.class)) {
            serviceConfig = convertCustomConfig(typedConfig.unpack(TypedStruct.class));
          }
          // TODO: support least_request once it is added to the envoy protos.
        } catch (InvalidProtocolBufferException e) {
          throw new ResourceInvalidException(
              "Unable to unpack typedConfig for: " + typedConfig.getTypeUrl(), e);
        }
        // The service config is expected to have a single root entry, where the name of that entry
        // is the name of the policy. A Load balancer with this name must exist in the registry.
        if (serviceConfig == null || LoadBalancerRegistry.getDefaultRegistry()
            .getProvider(Iterables.getOnlyElement(serviceConfig.keySet())) == null) {
          logger.log(XdsLogLevel.WARNING, "Policy {0} not found in the LB registry, skipping",
              typedConfig.getTypeUrl());
          continue;
        } else {
          return serviceConfig;
        }
      }

      // If we could not find a Policy that we could both convert as well as find a provider for
      // then we have an invalid LB policy configuration.
      throw new ResourceInvalidException("Invalid LoadBalancingPolicy: " + loadBalancingPolicy);
    }

    /**
     * Converts a ring_hash {@link Any} configuration to service config format.
     */
    private static ImmutableMap<String, ?> convertRingHashConfig(RingHash ringHash)
        throws ResourceInvalidException {
      // The hash function needs to be validated here as it is not exposed in the returned
      // configuration for later validation.
      if (RingHash.HashFunction.XX_HASH != ringHash.getHashFunction()) {
        throw new ResourceInvalidException(
            "Invalid ring hash function: " + ringHash.getHashFunction());
      }

      return buildRingHashConfig(
          ringHash.hasMinimumRingSize() ? ringHash.getMinimumRingSize().getValue() : null,
          ringHash.hasMaximumRingSize() ? ringHash.getMaximumRingSize().getValue() : null);
    }

    /**
     * Converts a wrr_locality {@link Any} configuration to service config format.
     */
    private static ImmutableMap<String, ?> convertWrrLocalityConfig(WrrLocality wrrLocality,
        int recursionDepth) throws ResourceInvalidException,
        MaxRecursionReachedException {
      return buildWrrLocalityConfig(
          convertToServiceConfig(wrrLocality.getEndpointPickingPolicy(), recursionDepth + 1));
    }

    /**
     * "Converts" a round_robin configuration to service config format.
     */
    private static ImmutableMap<String, ?> convertRoundRobinConfig() {
      return buildRoundRobinConfig();
    }

    /**
     * Converts a custom LB config {@link Any} configuration to service config format.
     */
    @SuppressWarnings("unchecked")
    private static ImmutableMap<String, ?> convertCustomConfig(TypedStruct configTypedStruct)
        throws ResourceInvalidException {
      Object rawJsonConfig = null;
      try {
        rawJsonConfig = JsonParser.parse(JsonFormat.printer().print(configTypedStruct.getValue()));
      } catch (IOException e) {
        throw new ResourceInvalidException("Unable to parse custom LB config JSON", e);
      }

      if (!(rawJsonConfig instanceof Map)) {
        throw new ResourceInvalidException("Custom LB config does not contain a JSON object");
      }

      String customConfigTypeName = configTypedStruct.getTypeUrl();
      if (customConfigTypeName.contains("/")) {
        customConfigTypeName = customConfigTypeName.substring(
            customConfigTypeName.lastIndexOf("/") + 1);
      }

      return ImmutableMap.of(customConfigTypeName, (Map<String, ?>) rawJsonConfig);
    }

    // Used to signal that the LB config goes too deep.
    static class MaxRecursionReachedException extends Exception {
      static final long serialVersionUID = 1L;
    }
  }

  /**
   * Builds a JSON LB configuration based on the old style of using the xDS Cluster proto message.
   * The lb_policy field is used to select the policy and configuration is extracted from various
   * policy specific fields in Cluster.
   */
  static class LegacyLoadBalancingPolicyConverter {

    /**
     * Factory method for creating a new {link LoadBalancerConfigConverter} for a given xDS {@link
     * Cluster}.
     *
     * @throws ResourceInvalidException If the {@link Cluster} has an invalid LB configuration.
     */
    static ImmutableMap<String, ?> convertToServiceConfig(Cluster cluster,
        boolean enableLeastRequest) throws ResourceInvalidException {
      switch (cluster.getLbPolicy()) {
        case RING_HASH:
          return convertRingHashConfig(cluster);
        case ROUND_ROBIN:
          return buildWrrLocalityConfig(buildRoundRobinConfig());
        case LEAST_REQUEST:
          if (enableLeastRequest) {
            return buildWrrLocalityConfig(convertLeastRequestConfig(cluster));
          }
          break;
        default:
      }
      throw new ResourceInvalidException(
          "Cluster " + cluster.getName() + ": unsupported lb policy: " + cluster.getLbPolicy());
    }

    /**
     * Creates a new ring_hash service config JSON object based on the old {@link RingHashLbConfig}
     * config message.
     */
    private static ImmutableMap<String, ?> convertRingHashConfig(Cluster cluster)
        throws ResourceInvalidException {
      RingHashLbConfig lbConfig = cluster.getRingHashLbConfig();

      // The hash function needs to be validated here as it is not exposed in the returned
      // configuration for later validation.
      if (lbConfig.getHashFunction() != RingHashLbConfig.HashFunction.XX_HASH) {
        throw new ResourceInvalidException(
            "Cluster " + cluster.getName() + ": invalid ring hash function: " + lbConfig);
      }

      return buildRingHashConfig(
          lbConfig.hasMinimumRingSize() ? (Long) lbConfig.getMinimumRingSize().getValue() : null,
          lbConfig.hasMaximumRingSize() ? (Long) lbConfig.getMaximumRingSize().getValue() : null);
    }

    /**
     * Creates a new least_request service config JSON object based on the old {@link
     * LeastRequestLbConfig} config message.
     */
    private static ImmutableMap<String, ?> convertLeastRequestConfig(Cluster cluster) {
      LeastRequestLbConfig lbConfig = cluster.getLeastRequestLbConfig();
      return buildLeastRequestConfig(
          lbConfig.hasChoiceCount() ? (Integer) lbConfig.getChoiceCount().getValue() : null);
    }
  }
}
