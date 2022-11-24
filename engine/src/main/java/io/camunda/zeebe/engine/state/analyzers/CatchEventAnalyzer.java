/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.state.analyzers;

import io.camunda.zeebe.engine.processing.common.Failure;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableActivity;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableCatchEvent;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableProcess;
import io.camunda.zeebe.engine.state.immutable.ElementInstanceState;
import io.camunda.zeebe.engine.state.immutable.ProcessState;
import io.camunda.zeebe.engine.state.instance.ElementInstance;
import io.camunda.zeebe.protocol.record.value.ErrorType;
import io.camunda.zeebe.util.Either;
import io.camunda.zeebe.util.buffer.BufferUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.agrona.DirectBuffer;

/**
 * Helper class that analyzes a process instance at runtime. It provides information about the
 * existence of catch events. The information is derived from {@link ProcessState} and {@link
 * ElementInstanceState}.
 */
public final class CatchEventAnalyzer {

  private final CatchEventTuple catchEventTuple = new CatchEventTuple();

  private final ProcessState processState;
  private final ElementInstanceState elementInstanceState;

  public CatchEventAnalyzer(
      final ProcessState processState, final ElementInstanceState elementInstanceState) {
    this.processState = processState;
    this.elementInstanceState = elementInstanceState;
  }

  public Either<Failure, CatchEventTuple> findErrorCatchEvent(
      final DirectBuffer errorCode,
      ElementInstance instance,
      final Optional<DirectBuffer> jobErrorMessage) {
    // assuming that error events are used rarely
    // - just walk through the scope hierarchy and look for a matching catch event

    final ArrayList<DirectBuffer> availableCatchEvents = new ArrayList<>();
    while (instance != null && instance.isActive()) {
      final var instanceRecord = instance.getValue();
      final var process = getProcess(instanceRecord.getProcessDefinitionKey());

      final var found = findErrorCatchEventInProcess(errorCode, process, instance);
      if (found.isRight()) {
        return Either.right(found.get());
      } else {
        availableCatchEvents.addAll(found.getLeft());
      }

      // find in parent process instance if exists
      final var parentElementInstanceKey = instanceRecord.getParentElementInstanceKey();
      instance = elementInstanceState.getInstance(parentElementInstanceKey);
    }

    final String incidentErrorMessage =
        String.format(
            "Expected to throw an error event with the code '%s'%s, but it was not caught.%s",
            BufferUtil.bufferAsString(errorCode),
            jobErrorMessage.isPresent() && jobErrorMessage.get().capacity() > 0
                ? String.format(
                    " with message '%s'", BufferUtil.bufferAsString(jobErrorMessage.get()))
                : "",
            availableCatchEvents.isEmpty()
                ? " No error events are available in the scope."
                : String.format(
                    " Available error events are [%s]",
                    availableCatchEvents.stream()
                        .map(BufferUtil::bufferAsString)
                        .collect(Collectors.joining(", "))));

    // no matching catch event found
    return Either.left(new Failure(incidentErrorMessage, ErrorType.UNHANDLED_ERROR_EVENT));
  }

  private Either<List<DirectBuffer>, CatchEventTuple> findErrorCatchEventInProcess(
      final DirectBuffer errorCode, final ExecutableProcess process, ElementInstance instance) {

    final Either<List<DirectBuffer>, CatchEventTuple> availableCatchEvents =
        Either.left(new ArrayList<>());
    while (instance != null && instance.isActive() && !instance.isInterrupted()) {
      final var found = findErrorCatchEventInScope(errorCode, process, instance);
      if (found.isRight()) {
        return found;
      } else {
        availableCatchEvents.getLeft().addAll(found.getLeft());
      }

      // find in parent scope if exists
      final var instanceParentKey = instance.getParentKey();
      instance = elementInstanceState.getInstance(instanceParentKey);
    }

    return availableCatchEvents;
  }

  private Either<List<DirectBuffer>, CatchEventTuple> findErrorCatchEventInScope(
      final DirectBuffer errorCode,
      final ExecutableProcess process,
      final ElementInstance instance) {

    final Either<List<DirectBuffer>, CatchEventTuple> availableCatchEvents =
        Either.left(new ArrayList<>());
    final var processInstanceRecord = instance.getValue();
    final var elementId = processInstanceRecord.getElementIdBuffer();
    final var elementType = processInstanceRecord.getBpmnElementType();

    final var element = process.getElementById(elementId, elementType, ExecutableActivity.class);

    for (final ExecutableCatchEvent catchEvent : element.getEvents()) {
      if (catchEvent.isError()) {
        final Optional<DirectBuffer> errorCodeOptional = catchEvent.getError().getErrorCode();
        // Because a catch event can not contain an expression, we ignore it if not set.
        if (errorCodeOptional.isPresent()) {
          final var errorCodeBuffer = errorCodeOptional.get();
          availableCatchEvents.getLeft().add(errorCodeBuffer);
          if (errorCodeBuffer.equals(errorCode)) {

            catchEventTuple.instance = instance;
            catchEventTuple.catchEvent = catchEvent;
            return Either.right(catchEventTuple);
          }
        }
      }
    }

    return availableCatchEvents;
  }

  public Optional<CatchEventTuple> findEscalationCatchEvent(
      final DirectBuffer escalationCode, final ElementInstance instance) {
    // walk through the scope hierarchy and look for a matching catch event
    final var instanceRecord = instance.getValue();
    final var process = getProcess(instanceRecord.getProcessDefinitionKey());

    return findEscalationCatchEventInProcess(escalationCode, process, instance)
        .or(
            () -> {
              // find in parent process instance if exists
              final ElementInstance parentElementInstance =
                  elementInstanceState.getInstance(instanceRecord.getParentElementInstanceKey());
              if (parentElementInstance != null && parentElementInstance.isActive()) {
                return findEscalationCatchEvent(escalationCode, parentElementInstance);
              } else {
                return Optional.empty();
              }
            });
  }

  private Optional<CatchEventTuple> findEscalationCatchEventInProcess(
      final DirectBuffer escalationCode,
      final ExecutableProcess process,
      final ElementInstance instance) {
    return findEscalationCatchEventInScope(escalationCode, process, instance)
        .or(
            () -> {
              // find in parent scope if exists
              final ElementInstance parentElementInstance =
                  elementInstanceState.getInstance(instance.getParentKey());
              if (parentElementInstance != null
                  && instance.isActive()
                  && !instance.isInterrupted()) {
                return findEscalationCatchEventInProcess(
                    escalationCode, process, parentElementInstance);
              } else {
                return Optional.empty();
              }
            });
  }

  private Optional<CatchEventTuple> findEscalationCatchEventInScope(
      final DirectBuffer escalationCode,
      final ExecutableProcess process,
      final ElementInstance instance) {
    final var processInstanceRecord = instance.getValue();
    final var elementId = processInstanceRecord.getElementIdBuffer();
    final var elementType = processInstanceRecord.getBpmnElementType();

    final var element = process.getElementById(elementId, elementType, ExecutableActivity.class);
    final Optional<ExecutableCatchEvent> catchEvent =
        element.getEvents().stream()
            .filter(ExecutableCatchEvent::isEscalation)
            .filter(event -> matchesEscalationCode(event, escalationCode))
            .findFirst();

    if (catchEvent.isPresent()) {
      catchEventTuple.instance = instance;
      catchEventTuple.catchEvent = catchEvent.get();
      return Optional.of(catchEventTuple);
    }

    // no matching catch event found
    return Optional.empty();
  }

  public boolean matchesEscalationCode(
      final ExecutableCatchEvent catchEvent, final DirectBuffer escalationCode) {
    final var eventEscalationCode = catchEvent.getEscalation().getEscalationCode();
    return eventEscalationCode.capacity() == 0 || eventEscalationCode.equals(escalationCode);
  }

  private ExecutableProcess getProcess(final long processDefinitionKey) {

    final var deployedProcess = processState.getProcessByKey(processDefinitionKey);
    if (deployedProcess == null) {
      throw new IllegalStateException(
          String.format(
              "Expected process with key '%d' to be deployed but not found", processDefinitionKey));
    }

    return deployedProcess.getProcess();
  }

  public static final class CatchEventTuple {
    private ExecutableCatchEvent catchEvent;
    private ElementInstance instance;

    public ExecutableCatchEvent getCatchEvent() {
      return catchEvent;
    }

    public ElementInstance getElementInstance() {
      return instance;
    }
  }
}
