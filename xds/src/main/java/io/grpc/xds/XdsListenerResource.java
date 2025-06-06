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
import static io.grpc.xds.XdsClusterResource.validateCommonTlsContext;
import static io.grpc.xds.XdsRouteConfigureResource.extractVirtualHosts;
import static io.grpc.xds.client.XdsClient.ResourceUpdate;

import com.github.udpa.udpa.type.v1.TypedStruct;
import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InetAddresses;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TrafficDirection;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.CidrRange;
import io.grpc.xds.EnvoyServerProtoData.ConnectionSourceType;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.EnvoyServerProtoData.FilterChainMatch;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.client.XdsResourceType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

class XdsListenerResource extends XdsResourceType<LdsUpdate> {
  static final String ADS_TYPE_URL_LDS =
      "type.googleapis.com/envoy.config.listener.v3.Listener";
  static final String TYPE_URL_HTTP_CONNECTION_MANAGER =
      "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3"
          + ".HttpConnectionManager";
  private static final String TRANSPORT_SOCKET_NAME_TLS = "envoy.transport_sockets.tls";
  private static final XdsListenerResource instance = new XdsListenerResource();
  private static final FilterRegistry filterRegistry = FilterRegistry.getDefaultRegistry();

  static XdsListenerResource getInstance() {
    return instance;
  }

  @Override
  @Nullable
  protected String extractResourceName(Message unpackedResource) {
    if (!(unpackedResource instanceof Listener)) {
      return null;
    }
    return ((Listener) unpackedResource).getName();
  }

  @Override
  public String typeName() {
    return "LDS";
  }

  @Override
  protected Class<Listener> unpackedClassName() {
    return Listener.class;
  }

  @Override
  public String typeUrl() {
    return ADS_TYPE_URL_LDS;
  }

  @Override
  public boolean shouldRetrieveResourceKeysForArgs() {
    return false;
  }

  @Override
  protected boolean isFullStateOfTheWorld() {
    return true;
  }

  @Override
  protected LdsUpdate doParse(Args args, Message unpackedMessage)
      throws ResourceInvalidException {
    if (!(unpackedMessage instanceof Listener)) {
      throw new ResourceInvalidException("Invalid message type: " + unpackedMessage.getClass());
    }
    Listener listener = (Listener) unpackedMessage;

    if (listener.hasApiListener()) {
      return processClientSideListener(listener, args);
    } else {
      return processServerSideListener(listener, args);
    }
  }

  private LdsUpdate processClientSideListener(Listener listener, XdsResourceType.Args args)
      throws ResourceInvalidException {
    // Unpack HttpConnectionManager from the Listener.
    HttpConnectionManager hcm;
    try {
      hcm = unpackCompatibleType(
          listener.getApiListener().getApiListener(), HttpConnectionManager.class,
          TYPE_URL_HTTP_CONNECTION_MANAGER, null);
    } catch (InvalidProtocolBufferException e) {
      throw new ResourceInvalidException(
          "Could not parse HttpConnectionManager config from ApiListener", e);
    }
    return LdsUpdate.forApiListener(
        parseHttpConnectionManager(hcm, filterRegistry, true /* isForClient */, args));
  }

  private LdsUpdate processServerSideListener(Listener proto, XdsResourceType.Args args)
      throws ResourceInvalidException {
    Set<String> certProviderInstances = null;
    if (args.getBootstrapInfo() != null && args.getBootstrapInfo().certProviders() != null) {
      certProviderInstances = args.getBootstrapInfo().certProviders().keySet();
    }
    return LdsUpdate.forTcpListener(parseServerSideListener(proto,
        (TlsContextManager) args.getSecurityConfig(),
        filterRegistry, certProviderInstances, args));
  }

  @VisibleForTesting
  static EnvoyServerProtoData.Listener parseServerSideListener(
      Listener proto, TlsContextManager tlsContextManager,
      FilterRegistry filterRegistry, Set<String> certProviderInstances, XdsResourceType.Args args)
      throws ResourceInvalidException {
    TrafficDirection trafficDirection = proto.getTrafficDirection();
    if (!trafficDirection.equals(TrafficDirection.INBOUND)
        && !trafficDirection.equals(TrafficDirection.UNSPECIFIED)) {
      throw new ResourceInvalidException(
          "Listener " + proto.getName() + " with invalid traffic direction: " + trafficDirection);
    }
    if (!proto.getListenerFiltersList().isEmpty()) {
      throw new ResourceInvalidException(
          "Listener " + proto.getName() + " cannot have listener_filters");
    }
    if (proto.hasUseOriginalDst()) {
      throw new ResourceInvalidException(
          "Listener " + proto.getName() + " cannot have use_original_dst set to true");
    }

    String address = null;
    SocketAddress socketAddress = null;
    if (proto.getAddress().hasSocketAddress()) {
      socketAddress = proto.getAddress().getSocketAddress();
      address = socketAddress.getAddress();
      if (address.isEmpty()) {
        throw new ResourceInvalidException("Invalid address: Empty address is not allowed.");
      }
      switch (socketAddress.getPortSpecifierCase()) {
        case NAMED_PORT:
          throw new ResourceInvalidException("NAMED_PORT is not supported in gRPC.");
        case PORT_VALUE:
          address = address + ":" + socketAddress.getPortValue();
          break;
        default:
          // noop
      }
    }

    ImmutableList.Builder<FilterChain> filterChains = ImmutableList.builder();
    Set<String> filterChainNames = new HashSet<>();
    Set<FilterChainMatch> filterChainMatchSet = new HashSet<>();
    int i = 0;
    for (io.envoyproxy.envoy.config.listener.v3.FilterChain fc : proto.getFilterChainsList()) {
      // May be empty. If it's not empty, required to be unique.
      String filterChainName = fc.getName();
      if (filterChainName.isEmpty()) {
        // Generate a name, so we can identify it in the logs.
        filterChainName = "chain_" + i;
      }
      if (!filterChainNames.add(filterChainName)) {
        throw new ResourceInvalidException("Filter chain names must be unique. "
            + "Found duplicate: " + filterChainName);
      }
      filterChains.add(
          parseFilterChain(fc, filterChainName, tlsContextManager, filterRegistry,
              filterChainMatchSet, certProviderInstances, args));
      i++;
    }

    FilterChain defaultFilterChain = null;
    if (proto.hasDefaultFilterChain()) {
      String defaultFilterChainName = proto.getDefaultFilterChain().getName();
      if (defaultFilterChainName.isEmpty()) {
        defaultFilterChainName = "chain_default";
      }
      defaultFilterChain = parseFilterChain(
          proto.getDefaultFilterChain(), defaultFilterChainName, tlsContextManager, filterRegistry,
          null, certProviderInstances, args);
    }

    return EnvoyServerProtoData.Listener.create(proto.getName(), address, filterChains.build(),
        defaultFilterChain, socketAddress == null ? null : socketAddress.getProtocol());
  }

  @VisibleForTesting
  static FilterChain parseFilterChain(
      io.envoyproxy.envoy.config.listener.v3.FilterChain proto,
      String filterChainName,
      TlsContextManager tlsContextManager,
      FilterRegistry filterRegistry,
      // null disables FilterChainMatch uniqueness check, used for defaultFilterChain
      @Nullable Set<FilterChainMatch> filterChainMatchSet,
      Set<String> certProviderInstances,
      XdsResourceType.Args args)
      throws ResourceInvalidException {
    // FilterChain contains L4 filters, so we ensure it contains only HCM.
    if (proto.getFiltersCount() != 1) {
      throw new ResourceInvalidException("FilterChain " + filterChainName
          + " should contain exact one HttpConnectionManager filter");
    }
    io.envoyproxy.envoy.config.listener.v3.Filter l4Filter = proto.getFiltersList().get(0);
    if (!l4Filter.hasTypedConfig()) {
      throw new ResourceInvalidException(
          "FilterChain " + filterChainName + " contains filter " + l4Filter.getName()
              + " without typed_config");
    }
    Any any = l4Filter.getTypedConfig();
    if (!any.getTypeUrl().equals(TYPE_URL_HTTP_CONNECTION_MANAGER)) {
      throw new ResourceInvalidException(
          "FilterChain " + filterChainName + " contains filter " + l4Filter.getName()
              + " with unsupported typed_config type " + any.getTypeUrl());
    }

    // Parse HCM.
    HttpConnectionManager hcmProto;
    try {
      hcmProto = any.unpack(HttpConnectionManager.class);
    } catch (InvalidProtocolBufferException e) {
      throw new ResourceInvalidException("FilterChain " + filterChainName + " with filter "
          + l4Filter.getName() + " failed to unpack message", e);
    }
    io.grpc.xds.HttpConnectionManager httpConnectionManager = parseHttpConnectionManager(
        hcmProto, filterRegistry, false /* isForClient */, args);

    // Parse Transport Socket.
    EnvoyServerProtoData.DownstreamTlsContext downstreamTlsContext = null;
    if (proto.hasTransportSocket()) {
      if (!TRANSPORT_SOCKET_NAME_TLS.equals(proto.getTransportSocket().getName())) {
        throw new ResourceInvalidException("transport-socket with name "
            + proto.getTransportSocket().getName() + " not supported.");
      }
      DownstreamTlsContext downstreamTlsContextProto;
      try {
        downstreamTlsContextProto =
            proto.getTransportSocket().getTypedConfig().unpack(DownstreamTlsContext.class);
      } catch (InvalidProtocolBufferException e) {
        throw new ResourceInvalidException("FilterChain " + filterChainName
            + " failed to unpack message", e);
      }
      downstreamTlsContext =
          EnvoyServerProtoData.DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(
              validateDownstreamTlsContext(downstreamTlsContextProto, certProviderInstances));
    }

    // Parse FilterChainMatch.
    FilterChainMatch filterChainMatch = parseFilterChainMatch(proto.getFilterChainMatch());
    // null used to skip this check for defaultFilterChain.
    if (filterChainMatchSet != null) {
      validateFilterChainMatchForUniqueness(filterChainMatchSet, filterChainMatch);
    }

    return FilterChain.create(
        filterChainName,
        filterChainMatch,
        httpConnectionManager,
        downstreamTlsContext,
        tlsContextManager
    );
  }

  @VisibleForTesting
  static DownstreamTlsContext validateDownstreamTlsContext(
      DownstreamTlsContext downstreamTlsContext, Set<String> certProviderInstances)
      throws ResourceInvalidException {
    if (downstreamTlsContext.hasCommonTlsContext()) {
      validateCommonTlsContext(downstreamTlsContext.getCommonTlsContext(), certProviderInstances,
          true);
    } else {
      throw new ResourceInvalidException(
          "common-tls-context is required in downstream-tls-context");
    }
    if (downstreamTlsContext.hasRequireSni()) {
      throw new ResourceInvalidException(
          "downstream-tls-context with require-sni is not supported");
    }
    DownstreamTlsContext.OcspStaplePolicy ocspStaplePolicy = downstreamTlsContext
        .getOcspStaplePolicy();
    if (ocspStaplePolicy != DownstreamTlsContext.OcspStaplePolicy.UNRECOGNIZED
        && ocspStaplePolicy != DownstreamTlsContext.OcspStaplePolicy.LENIENT_STAPLING) {
      throw new ResourceInvalidException(
          "downstream-tls-context with ocsp_staple_policy value " + ocspStaplePolicy.name()
              + " is not supported");
    }
    return downstreamTlsContext;
  }

  private static void validateFilterChainMatchForUniqueness(
      Set<FilterChainMatch> filterChainMatchSet,
      FilterChainMatch filterChainMatch) throws ResourceInvalidException {
    // Flattens complex FilterChainMatch into a list of simple FilterChainMatch'es.
    List<FilterChainMatch> crossProduct = getCrossProduct(filterChainMatch);
    for (FilterChainMatch cur : crossProduct) {
      if (!filterChainMatchSet.add(cur)) {
        throw new ResourceInvalidException("FilterChainMatch must be unique. "
            + "Found duplicate: " + cur);
      }
    }
  }

  private static List<FilterChainMatch> getCrossProduct(FilterChainMatch filterChainMatch) {
    // repeating fields to process:
    // prefixRanges, applicationProtocols, sourcePrefixRanges, sourcePorts, serverNames
    List<FilterChainMatch> expandedList = expandOnPrefixRange(filterChainMatch);
    expandedList = expandOnApplicationProtocols(expandedList);
    expandedList = expandOnSourcePrefixRange(expandedList);
    expandedList = expandOnSourcePorts(expandedList);
    return expandOnServerNames(expandedList);
  }

  private static List<FilterChainMatch> expandOnPrefixRange(FilterChainMatch filterChainMatch) {
    ArrayList<FilterChainMatch> expandedList = new ArrayList<>();
    if (filterChainMatch.prefixRanges().isEmpty()) {
      expandedList.add(filterChainMatch);
    } else {
      for (CidrRange cidrRange : filterChainMatch.prefixRanges()) {
        expandedList.add(FilterChainMatch.create(filterChainMatch.destinationPort(),
            ImmutableList.of(cidrRange),
            filterChainMatch.applicationProtocols(),
            filterChainMatch.sourcePrefixRanges(),
            filterChainMatch.connectionSourceType(),
            filterChainMatch.sourcePorts(),
            filterChainMatch.serverNames(),
            filterChainMatch.transportProtocol()));
      }
    }
    return expandedList;
  }

  private static List<FilterChainMatch> expandOnApplicationProtocols(
      Collection<FilterChainMatch> set) {
    ArrayList<FilterChainMatch> expandedList = new ArrayList<>();
    for (FilterChainMatch filterChainMatch : set) {
      if (filterChainMatch.applicationProtocols().isEmpty()) {
        expandedList.add(filterChainMatch);
      } else {
        for (String applicationProtocol : filterChainMatch.applicationProtocols()) {
          expandedList.add(FilterChainMatch.create(filterChainMatch.destinationPort(),
              filterChainMatch.prefixRanges(),
              ImmutableList.of(applicationProtocol),
              filterChainMatch.sourcePrefixRanges(),
              filterChainMatch.connectionSourceType(),
              filterChainMatch.sourcePorts(),
              filterChainMatch.serverNames(),
              filterChainMatch.transportProtocol()));
        }
      }
    }
    return expandedList;
  }

  private static List<FilterChainMatch> expandOnSourcePrefixRange(
      Collection<FilterChainMatch> set) {
    ArrayList<FilterChainMatch> expandedList = new ArrayList<>();
    for (FilterChainMatch filterChainMatch : set) {
      if (filterChainMatch.sourcePrefixRanges().isEmpty()) {
        expandedList.add(filterChainMatch);
      } else {
        for (EnvoyServerProtoData.CidrRange cidrRange : filterChainMatch.sourcePrefixRanges()) {
          expandedList.add(FilterChainMatch.create(filterChainMatch.destinationPort(),
              filterChainMatch.prefixRanges(),
              filterChainMatch.applicationProtocols(),
              ImmutableList.of(cidrRange),
              filterChainMatch.connectionSourceType(),
              filterChainMatch.sourcePorts(),
              filterChainMatch.serverNames(),
              filterChainMatch.transportProtocol()));
        }
      }
    }
    return expandedList;
  }

  private static List<FilterChainMatch> expandOnSourcePorts(Collection<FilterChainMatch> set) {
    ArrayList<FilterChainMatch> expandedList = new ArrayList<>();
    for (FilterChainMatch filterChainMatch : set) {
      if (filterChainMatch.sourcePorts().isEmpty()) {
        expandedList.add(filterChainMatch);
      } else {
        for (Integer sourcePort : filterChainMatch.sourcePorts()) {
          expandedList.add(FilterChainMatch.create(filterChainMatch.destinationPort(),
              filterChainMatch.prefixRanges(),
              filterChainMatch.applicationProtocols(),
              filterChainMatch.sourcePrefixRanges(),
              filterChainMatch.connectionSourceType(),
              ImmutableList.of(sourcePort),
              filterChainMatch.serverNames(),
              filterChainMatch.transportProtocol()));
        }
      }
    }
    return expandedList;
  }

  private static List<FilterChainMatch> expandOnServerNames(Collection<FilterChainMatch> set) {
    ArrayList<FilterChainMatch> expandedList = new ArrayList<>();
    for (FilterChainMatch filterChainMatch : set) {
      if (filterChainMatch.serverNames().isEmpty()) {
        expandedList.add(filterChainMatch);
      } else {
        for (String serverName : filterChainMatch.serverNames()) {
          expandedList.add(FilterChainMatch.create(filterChainMatch.destinationPort(),
              filterChainMatch.prefixRanges(),
              filterChainMatch.applicationProtocols(),
              filterChainMatch.sourcePrefixRanges(),
              filterChainMatch.connectionSourceType(),
              filterChainMatch.sourcePorts(),
              ImmutableList.of(serverName),
              filterChainMatch.transportProtocol()));
        }
      }
    }
    return expandedList;
  }

  private static FilterChainMatch parseFilterChainMatch(
      io.envoyproxy.envoy.config.listener.v3.FilterChainMatch proto)
      throws ResourceInvalidException {
    ImmutableList.Builder<CidrRange> prefixRanges = ImmutableList.builder();
    ImmutableList.Builder<CidrRange> sourcePrefixRanges = ImmutableList.builder();
    try {
      for (io.envoyproxy.envoy.config.core.v3.CidrRange range : proto.getPrefixRangesList()) {
        prefixRanges.add(
            CidrRange.create(InetAddresses.forString(range.getAddressPrefix()),
                range.getPrefixLen().getValue()));
      }
      for (io.envoyproxy.envoy.config.core.v3.CidrRange range
          : proto.getSourcePrefixRangesList()) {
        sourcePrefixRanges.add(CidrRange.create(
            InetAddresses.forString(range.getAddressPrefix()), range.getPrefixLen().getValue()));
      }
    } catch (IllegalArgumentException ex) {
      throw new ResourceInvalidException("Failed to create CidrRange", ex);
    }

    ConnectionSourceType sourceType;
    switch (proto.getSourceType()) {
      case ANY:
        sourceType = ConnectionSourceType.ANY;
        break;
      case EXTERNAL:
        sourceType = ConnectionSourceType.EXTERNAL;
        break;
      case SAME_IP_OR_LOOPBACK:
        sourceType = ConnectionSourceType.SAME_IP_OR_LOOPBACK;
        break;
      default:
        throw new ResourceInvalidException("Unknown source-type: " + proto.getSourceType());
    }
    return FilterChainMatch.create(
        proto.getDestinationPort().getValue(),
        prefixRanges.build(),
        ImmutableList.copyOf(proto.getApplicationProtocolsList()),
        sourcePrefixRanges.build(),
        sourceType,
        ImmutableList.copyOf(proto.getSourcePortsList()),
        ImmutableList.copyOf(proto.getServerNamesList()),
        proto.getTransportProtocol());
  }

  @VisibleForTesting
  static io.grpc.xds.HttpConnectionManager parseHttpConnectionManager(
      HttpConnectionManager proto, FilterRegistry filterRegistry,
      boolean isForClient, XdsResourceType.Args args) throws ResourceInvalidException {
    if (proto.getXffNumTrustedHops() != 0) {
      throw new ResourceInvalidException(
          "HttpConnectionManager with xff_num_trusted_hops unsupported");
    }
    if (!proto.getOriginalIpDetectionExtensionsList().isEmpty()) {
      throw new ResourceInvalidException("HttpConnectionManager with "
          + "original_ip_detection_extensions unsupported");
    }
    // Obtain max_stream_duration from Http Protocol Options.
    long maxStreamDuration = 0;
    if (proto.hasCommonHttpProtocolOptions()) {
      HttpProtocolOptions options = proto.getCommonHttpProtocolOptions();
      if (options.hasMaxStreamDuration()) {
        maxStreamDuration = Durations.toNanos(options.getMaxStreamDuration());
      }
    }

    // Parse http filters.
    if (proto.getHttpFiltersList().isEmpty()) {
      throw new ResourceInvalidException("Missing HttpFilter in HttpConnectionManager.");
    }
    List<Filter.NamedFilterConfig> filterConfigs = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (int i = 0; i < proto.getHttpFiltersCount(); i++) {
      io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
          httpFilter = proto.getHttpFiltersList().get(i);
      String filterName = httpFilter.getName();
      if (!names.add(filterName)) {
        throw new ResourceInvalidException(
            "HttpConnectionManager contains duplicate HttpFilter: " + filterName);
      }
      StructOrError<Filter.FilterConfig> filterConfig =
          parseHttpFilter(httpFilter, filterRegistry, isForClient);
      if ((i == proto.getHttpFiltersCount() - 1)
          && (filterConfig == null || !isTerminalFilter(filterConfig.getStruct()))) {
        throw new ResourceInvalidException("The last HttpFilter must be a terminal filter: "
            + filterName);
      }
      if (filterConfig == null) {
        continue;
      }
      if (filterConfig.getErrorDetail() != null) {
        throw new ResourceInvalidException(
            "HttpConnectionManager contains invalid HttpFilter: "
                + filterConfig.getErrorDetail());
      }
      if ((i < proto.getHttpFiltersCount() - 1) && isTerminalFilter(filterConfig.getStruct())) {
        throw new ResourceInvalidException("A terminal HttpFilter must be the last filter: "
            + filterName);
      }
      filterConfigs.add(new Filter.NamedFilterConfig(filterName, filterConfig.getStruct()));
    }

    // Parse inlined RouteConfiguration or RDS.
    if (proto.hasRouteConfig()) {
      List<VirtualHost> virtualHosts = extractVirtualHosts(
          proto.getRouteConfig(), filterRegistry, args);
      return io.grpc.xds.HttpConnectionManager.forVirtualHosts(
          maxStreamDuration, virtualHosts, filterConfigs);
    }
    if (proto.hasRds()) {
      Rds rds = proto.getRds();
      if (!rds.hasConfigSource()) {
        throw new ResourceInvalidException(
            "HttpConnectionManager contains invalid RDS: missing config_source");
      }
      if (!rds.getConfigSource().hasAds() && !rds.getConfigSource().hasSelf()) {
        throw new ResourceInvalidException(
            "HttpConnectionManager contains invalid RDS: must specify ADS or self ConfigSource");
      }
      return io.grpc.xds.HttpConnectionManager.forRdsName(
          maxStreamDuration, rds.getRouteConfigName(), filterConfigs);
    }
    throw new ResourceInvalidException(
        "HttpConnectionManager neither has inlined route_config nor RDS");
  }

  // hard-coded: currently router config is the only terminal filter.
  private static boolean isTerminalFilter(Filter.FilterConfig filterConfig) {
    return RouterFilter.ROUTER_CONFIG.equals(filterConfig);
  }

  @VisibleForTesting
  @Nullable // Returns null if the filter is optional but not supported.
  static StructOrError<Filter.FilterConfig> parseHttpFilter(
      io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
          httpFilter, FilterRegistry filterRegistry, boolean isForClient) {
    String filterName = httpFilter.getName();
    boolean isOptional = httpFilter.getIsOptional();
    if (!httpFilter.hasTypedConfig()) {
      return isOptional ? null : StructOrError.fromError(
          "HttpFilter [" + filterName + "] is not optional and has no typed config");
    }
    Message rawConfig = httpFilter.getTypedConfig();
    String typeUrl = httpFilter.getTypedConfig().getTypeUrl();

    try {
      if (typeUrl.equals(TYPE_URL_TYPED_STRUCT_UDPA)) {
        TypedStruct typedStruct = httpFilter.getTypedConfig().unpack(TypedStruct.class);
        typeUrl = typedStruct.getTypeUrl();
        rawConfig = typedStruct.getValue();
      } else if (typeUrl.equals(TYPE_URL_TYPED_STRUCT)) {
        com.github.xds.type.v3.TypedStruct newTypedStruct =
            httpFilter.getTypedConfig().unpack(com.github.xds.type.v3.TypedStruct.class);
        typeUrl = newTypedStruct.getTypeUrl();
        rawConfig = newTypedStruct.getValue();
      }
    } catch (InvalidProtocolBufferException e) {
      return StructOrError.fromError(
          "HttpFilter [" + filterName + "] contains invalid proto: " + e);
    }

    Filter.Provider provider = filterRegistry.get(typeUrl);
    if (provider == null
        || (isForClient && !provider.isClientFilter())
        || (!isForClient && !provider.isServerFilter())) {
      // Filter type not supported.
      return isOptional ? null : StructOrError.fromError(
          "HttpFilter [" + filterName + "](" + typeUrl + ") is required but unsupported for " + (
              isForClient ? "client" : "server"));
    }
    ConfigOrError<? extends FilterConfig> filterConfig = provider.parseFilterConfig(rawConfig);
    if (filterConfig.errorDetail != null) {
      return StructOrError.fromError(
          "Invalid filter config for HttpFilter [" + filterName + "]: " + filterConfig.errorDetail);
    }
    return StructOrError.fromStruct(filterConfig.config);
  }

  @AutoValue
  abstract static class LdsUpdate implements ResourceUpdate {
    // Http level api listener configuration.
    @Nullable
    abstract io.grpc.xds.HttpConnectionManager httpConnectionManager();

    // Tcp level listener configuration.
    @Nullable
    abstract EnvoyServerProtoData.Listener listener();

    static LdsUpdate forApiListener(io.grpc.xds.HttpConnectionManager httpConnectionManager) {
      checkNotNull(httpConnectionManager, "httpConnectionManager");
      return new io.grpc.xds.AutoValue_XdsListenerResource_LdsUpdate(httpConnectionManager, null);
    }

    static LdsUpdate forTcpListener(EnvoyServerProtoData.Listener listener) {
      checkNotNull(listener, "listener");
      return new io.grpc.xds.AutoValue_XdsListenerResource_LdsUpdate(null, listener);
    }
  }
}
