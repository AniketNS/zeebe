/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.topology.changes;

import io.camunda.zeebe.scheduler.future.ActorFuture;
import io.camunda.zeebe.topology.state.ClusterTopology;
import io.camunda.zeebe.topology.state.TopologyChangeOperation;
import io.camunda.zeebe.util.Either;
import java.util.List;

public interface TopologyChangeCoordinator {

  /**
   * @return the current cluster topology.
   */
  ActorFuture<ClusterTopology> getTopology();

  /**
   * Applies the operations generated by the requestTransformer to the current cluster topology. If
   * no operations is returned by requestTransformer, the future completes successfully with no
   * change to the cluster topology.
   *
   * @param requestTransformer the request transformer that generates the operations to apply
   * @return a future which is completed when the topology change has started successfully.
   */
  ActorFuture<TopologyChangeResult> applyOperations(TopologyChangeRequest requestTransformer);

  record TopologyChangeResult(
      // The current topology before applying the operations.
      ClusterTopology currentTopology,
      // The expected final topology after applying the operations.
      ClusterTopology finalTopology,
      long changeId,
      // The operations that wille be applied to the current topology.
      List<TopologyChangeOperation> operations) {}

  @FunctionalInterface
  interface TopologyChangeRequest {

    /**
     * Returns a list of operations to apply to the current topology. The operations will be applied
     * in the given order in the list.
     *
     * @param currentTopology the current cluster topology
     * @return an Either with the list of operations to apply or an exception if the request is not
     *     valid.
     */
    Either<Exception, List<TopologyChangeOperation>> operations(
        final ClusterTopology currentTopology);
  }
}
