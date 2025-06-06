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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.grpc.EquivalentAddressGroup;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.MetadataRegistry.MetadataValueParser;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.client.Locality;
import io.grpc.xds.client.XdsClient.ResourceUpdate;
import io.grpc.xds.client.XdsResourceType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

class XdsEndpointResource extends XdsResourceType<EdsUpdate> {
  static final String ADS_TYPE_URL_EDS =
      "type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment";

  public static final String GRPC_EXPERIMENTAL_XDS_DUALSTACK_ENDPOINTS =
      "GRPC_EXPERIMENTAL_XDS_DUALSTACK_ENDPOINTS";

  private static final XdsEndpointResource instance = new XdsEndpointResource();

  static XdsEndpointResource getInstance() {
    return instance;
  }

  @Override
  @Nullable
  protected String extractResourceName(Message unpackedResource) {
    if (!(unpackedResource instanceof ClusterLoadAssignment)) {
      return null;
    }
    return ((ClusterLoadAssignment) unpackedResource).getClusterName();
  }

  @Override
  public String typeName() {
    return "EDS";
  }

  @Override
  public String typeUrl() {
    return ADS_TYPE_URL_EDS;
  }

  @Override
  public boolean shouldRetrieveResourceKeysForArgs() {
    return true;
  }

  @Override
  protected boolean isFullStateOfTheWorld() {
    return false;
  }

  @Override
  protected Class<ClusterLoadAssignment> unpackedClassName() {
    return ClusterLoadAssignment.class;
  }

  @Override
  protected EdsUpdate doParse(Args args, Message unpackedMessage) throws ResourceInvalidException {
    if (!(unpackedMessage instanceof ClusterLoadAssignment)) {
      throw new ResourceInvalidException("Invalid message type: " + unpackedMessage.getClass());
    }
    return processClusterLoadAssignment((ClusterLoadAssignment) unpackedMessage);
  }

  private static boolean isEnabledXdsDualStack() {
    return GrpcUtil.getFlag(GRPC_EXPERIMENTAL_XDS_DUALSTACK_ENDPOINTS, false);
  }

  private static EdsUpdate processClusterLoadAssignment(ClusterLoadAssignment assignment)
      throws ResourceInvalidException {
    Map<Integer, Set<Locality>> priorities = new HashMap<>();
    Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap = new LinkedHashMap<>();
    List<Endpoints.DropOverload> dropOverloads = new ArrayList<>();
    int maxPriority = -1;
    for (io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints localityLbEndpointsProto
        : assignment.getEndpointsList()) {
      StructOrError<LocalityLbEndpoints> structOrError =
          parseLocalityLbEndpoints(localityLbEndpointsProto);
      if (structOrError == null) {
        continue;
      }
      if (structOrError.getErrorDetail() != null) {
        throw new ResourceInvalidException(structOrError.getErrorDetail());
      }

      LocalityLbEndpoints localityLbEndpoints = structOrError.getStruct();
      int priority = localityLbEndpoints.priority();
      maxPriority = Math.max(maxPriority, priority);
      // Note endpoints with health status other than HEALTHY and UNKNOWN are still
      // handed over to watching parties. It is watching parties' responsibility to
      // filter out unhealthy endpoints. See EnvoyProtoData.LbEndpoint#isHealthy().
      Locality locality =  parseLocality(localityLbEndpointsProto.getLocality());
      localityLbEndpointsMap.put(locality, localityLbEndpoints);
      if (!priorities.containsKey(priority)) {
        priorities.put(priority, new HashSet<>());
      }
      if (!priorities.get(priority).add(locality)) {
        throw new ResourceInvalidException("ClusterLoadAssignment has duplicate locality:"
            + locality + " for priority:" + priority);
      }
    }
    if (priorities.size() != maxPriority + 1) {
      throw new ResourceInvalidException("ClusterLoadAssignment has sparse priorities");
    }

    for (ClusterLoadAssignment.Policy.DropOverload dropOverloadProto
        : assignment.getPolicy().getDropOverloadsList()) {
      dropOverloads.add(parseDropOverload(dropOverloadProto));
    }
    return new EdsUpdate(assignment.getClusterName(), localityLbEndpointsMap, dropOverloads);
  }

  private static Locality parseLocality(io.envoyproxy.envoy.config.core.v3.Locality proto) {
    return Locality.create(proto.getRegion(), proto.getZone(), proto.getSubZone());
  }

  private static DropOverload parseDropOverload(
      io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy.DropOverload proto) {
    return DropOverload.create(proto.getCategory(), getRatePerMillion(proto.getDropPercentage()));
  }

  private static int getRatePerMillion(FractionalPercent percent) {
    int numerator = percent.getNumerator();
    FractionalPercent.DenominatorType type = percent.getDenominator();
    switch (type) {
      case TEN_THOUSAND:
        numerator *= 100;
        break;
      case HUNDRED:
        numerator *= 10_000;
        break;
      case MILLION:
        break;
      case UNRECOGNIZED:
      default:
        throw new IllegalArgumentException("Unknown denominator type of " + percent);
    }

    if (numerator > 1_000_000 || numerator < 0) {
      numerator = 1_000_000;
    }
    return numerator;
  }


  @VisibleForTesting
  @Nullable
  static StructOrError<LocalityLbEndpoints> parseLocalityLbEndpoints(
      io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto)
      throws ResourceInvalidException {
    // Filter out localities without or with 0 weight.
    if (!proto.hasLoadBalancingWeight() || proto.getLoadBalancingWeight().getValue() < 1) {
      return null;
    }
    if (proto.getPriority() < 0) {
      return StructOrError.fromError("negative priority");
    }

    ImmutableMap<String, Object> localityMetadata;
    MetadataRegistry registry = MetadataRegistry.getInstance();
    try {
      localityMetadata = registry.parseMetadata(proto.getMetadata());
    } catch (ResourceInvalidException e) {
      throw new ResourceInvalidException("Failed to parse Locality Endpoint metadata: "
          + e.getMessage(), e);
    }
    List<Endpoints.LbEndpoint> endpoints = new ArrayList<>(proto.getLbEndpointsCount());
    for (io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint endpoint : proto.getLbEndpointsList()) {
      // The endpoint field of each lb_endpoints must be set.
      // Inside of it: the address field must be set.
      if (!endpoint.hasEndpoint() || !endpoint.getEndpoint().hasAddress()) {
        return StructOrError.fromError("LbEndpoint with no endpoint/address");
      }
      ImmutableMap<String, Object> endpointMetadata;
      try {
        endpointMetadata = registry.parseMetadata(endpoint.getMetadata());
      } catch (ResourceInvalidException e) {
        throw new ResourceInvalidException("Failed to parse Endpoint metadata: "
            + e.getMessage(), e);
      }
      List<java.net.SocketAddress> addresses = new ArrayList<>();
      addresses.add(getInetSocketAddress(endpoint.getEndpoint().getAddress()));

      if (isEnabledXdsDualStack()) {
        for (Endpoint.AdditionalAddress additionalAddress
            : endpoint.getEndpoint().getAdditionalAddressesList()) {
          addresses.add(getInetSocketAddress(additionalAddress.getAddress()));
        }
      }
      boolean isHealthy = (endpoint.getHealthStatus() == HealthStatus.HEALTHY)
              || (endpoint.getHealthStatus() == HealthStatus.UNKNOWN);
      endpoints.add(Endpoints.LbEndpoint.create(
          new EquivalentAddressGroup(addresses),
          endpoint.getLoadBalancingWeight().getValue(), isHealthy,
          endpoint.getEndpoint().getHostname(),
          endpointMetadata));
    }
    return StructOrError.fromStruct(Endpoints.LocalityLbEndpoints.create(
        endpoints, proto.getLoadBalancingWeight().getValue(),
        proto.getPriority(), localityMetadata));
  }

  private static InetSocketAddress getInetSocketAddress(Address address)
      throws ResourceInvalidException {
    io.envoyproxy.envoy.config.core.v3.SocketAddress socketAddress = address.getSocketAddress();
    InetAddress parsedAddress;
    try {
      parsedAddress = InetAddresses.forString(socketAddress.getAddress());
    } catch (IllegalArgumentException ex) {
      throw new ResourceInvalidException("Address is not an IP", ex);
    }
    return new InetSocketAddress(parsedAddress, socketAddress.getPortValue());
  }

  static final class EdsUpdate implements ResourceUpdate {
    final String clusterName;
    final Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap;
    final List<DropOverload> dropPolicies;

    EdsUpdate(String clusterName, Map<Locality, LocalityLbEndpoints> localityLbEndpoints,
              List<DropOverload> dropPolicies) {
      this.clusterName = checkNotNull(clusterName, "clusterName");
      this.localityLbEndpointsMap = Collections.unmodifiableMap(
          new LinkedHashMap<>(checkNotNull(localityLbEndpoints, "localityLbEndpoints")));
      this.dropPolicies = Collections.unmodifiableList(
          new ArrayList<>(checkNotNull(dropPolicies, "dropPolicies")));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      EdsUpdate that = (EdsUpdate) o;
      return Objects.equals(clusterName, that.clusterName)
          && Objects.equals(localityLbEndpointsMap, that.localityLbEndpointsMap)
          && Objects.equals(dropPolicies, that.dropPolicies);
    }

    @Override
    public int hashCode() {
      return Objects.hash(clusterName, localityLbEndpointsMap, dropPolicies);
    }

    @Override
    public String toString() {
      return
          MoreObjects
              .toStringHelper(this)
              .add("clusterName", clusterName)
              .add("localityLbEndpointsMap", localityLbEndpointsMap)
              .add("dropPolicies", dropPolicies)
              .toString();
    }
  }

  public static class AddressMetadataParser implements MetadataValueParser {

    @Override
    public String getTypeUrl() {
      return "type.googleapis.com/envoy.config.core.v3.Address";
    }

    @Override
    public java.net.SocketAddress parse(Any any) throws ResourceInvalidException {
      SocketAddress socketAddress;
      try {
        socketAddress = any.unpack(Address.class).getSocketAddress();
      } catch (InvalidProtocolBufferException ex) {
        throw new ResourceInvalidException("Invalid Resource in address proto", ex);
      }
      validateAddress(socketAddress);

      String ip = socketAddress.getAddress();
      int port = socketAddress.getPortValue();

      try {
        return new InetSocketAddress(InetAddresses.forString(ip), port);
      } catch (IllegalArgumentException e) {
        throw createException("Invalid IP address or port: " + ip + ":" + port);
      }
    }

    private void validateAddress(SocketAddress socketAddress) throws ResourceInvalidException {
      if (socketAddress.getAddress().isEmpty()) {
        throw createException("Address field is empty or invalid.");
      }
      long port = Integer.toUnsignedLong(socketAddress.getPortValue());
      if (port > 65535) {
        throw createException(String.format("Port value %d out of range 1-65535.", port));
      }
    }

    private ResourceInvalidException createException(String message) {
      return new ResourceInvalidException(
          "Failed to parse envoy.config.core.v3.Address: " + message);
    }
  }
}
