/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.topology.util;

import io.atomix.cluster.MemberId;
import io.atomix.primitive.partition.PartitionId;
import io.atomix.primitive.partition.PartitionMetadata;
import io.camunda.zeebe.topology.state.ClusterTopology;
import io.camunda.zeebe.topology.state.MemberState;
import io.camunda.zeebe.topology.state.PartitionState;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class TopologyUtil {

  private TopologyUtil() {}

  public static ClusterTopology getClusterTopologyFrom(
      final Set<PartitionMetadata> partitionDistribution) {
    final var partitionStatesByMember = new HashMap<MemberId, Map<Integer, PartitionState>>();
    for (final var partitionMetadata : partitionDistribution) {
      final var partitionId = partitionMetadata.id().id();
      for (final var member : partitionMetadata.members()) {
        final var memberPriority = partitionMetadata.getPriority(member);
        partitionStatesByMember
            .computeIfAbsent(member, k -> new HashMap<>())
            .put(partitionId, PartitionState.active(memberPriority));
      }
    }
    final var memberStates = new HashMap<MemberId, MemberState>();
    for (final var e : partitionStatesByMember.entrySet()) {
      memberStates.put(e.getKey(), MemberState.initializeAsActive(e.getValue()));
    }

    return new ClusterTopology(
        ClusterTopology.INITIAL_VERSION,
        Map.copyOf(memberStates),
        Optional.empty(),
        Optional.empty());
  }

  public static Set<PartitionMetadata> getPartitionDistributionFrom(
      final ClusterTopology clusterTopology, final String groupName) {
    if (clusterTopology.isUninitialized()) {
      throw new IllegalStateException(
          "Cannot generated partition distribution from uninitialized topology");
    }

    final var memberPriorityByPartition = new HashMap<Integer, Map<MemberId, Integer>>();
    clusterTopology
        .members()
        .forEach(
            (memberId, member) -> {
              for (final Entry<Integer, PartitionState> entry : member.partitions().entrySet()) {
                final Integer partitionId = entry.getKey();
                final PartitionState partitionState = entry.getValue();
                memberPriorityByPartition
                    .computeIfAbsent(partitionId, k -> new HashMap<>())
                    .put(memberId, partitionState.priority());
              }
            });

    return memberPriorityByPartition.entrySet().stream()
        .map(e -> getPartitionMetadata(e, groupName))
        .collect(Collectors.toSet());
  }

  private static PartitionMetadata getPartitionMetadata(
      final Entry<Integer, Map<MemberId, Integer>> e, final String groupName) {
    final Map<MemberId, Integer> memberPriorities = e.getValue();
    final var optionalPrimary = memberPriorities.entrySet().stream().max(Entry.comparingByValue());
    if (optionalPrimary.isEmpty()) {
      throw new IllegalStateException("Found partition with no members");
    }
    return new PartitionMetadata(
        partitionId(e.getKey(), groupName),
        memberPriorities.keySet(),
        memberPriorities,
        optionalPrimary.get().getValue(),
        optionalPrimary.get().getKey());
  }

  private static PartitionId partitionId(final Integer key, final String groupName) {
    return PartitionId.from(groupName, key);
  }
}
