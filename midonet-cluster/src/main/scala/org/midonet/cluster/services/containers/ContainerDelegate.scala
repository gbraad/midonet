/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.services.containers

import java.util.UUID

import javax.annotation.Nullable

import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.Topology.ServiceContainer
import org.midonet.containers.Container

/**
  * Allows the implementation of custom handlers for the service containers.
  * Classes extending this trait may provide custom operations for container
  * state transitions (e.g. a container is up, a container's location has
  * changed, etc.).
  *
  * The cluster Container Management service will load from the current class
  * path all service containers that are annotated with a
  * [[Container]] annotation, indicating
  * the container type name and version.
  *
  * The lifetime of a service container is the following:
  *
  *                (1) onAllocate
  *                The container is created via the
  *                API and is being scheduled on
  *   +---------+  a specific agent.    +---------+
  *   | DELETED |---------------------->| CREATED |
  *   |         |                       |         |
  *   +---------+                       +---------+
  *        | (4) onDeallocate                | (2) onUp
  *        | The container is deleted via    | The container has been connected
  *        | API.                            | on the scheduled agent, and the
  *        |                                 | agent reported the container up.
  *   +---------+  (2) onUp             +---------+
  *   |  DOWN   |---------------------->|   UP    |
  *   |         |<----------------------|         |
  *   +---------+  (3) onDown           +---------+
  *                The container has been disconnected
  *                from the scheduled agent, because the
  *                container is being deleted, agent
  *                reported status down and it is being
  *                rescheduled on a different agent.
  */
trait ContainerDelegate {

    /**
      * Method called when the container is scheduled on the specified agent.
      */
    def onScheduled(container: ServiceContainer, hostId: UUID): Unit

    /**
      * Method called when the container namespace and veth port pair are
      * created and connected on the scheduled agent, and the agent has reported
      * the container status as UP. The method arguments will indicate the
      * [[ContainerStatus]] with the host identifier, namespace and interface
      * name where the container is connected and the container health status.
      */
    def onUp(container: ServiceContainer,  status: ContainerStatus): Unit

    /**
      * Method called when the container is no longer available on the scheduled
      * agent. This happens when (1) the agent goes offline (the host alive
      * status is false), (2) the agent encountered a problem with creating or
      * maintaining the container and reported status DOWN, or (3) the container
      * has been deleted.
      */
    def onDown(container: ServiceContainer,
               @Nullable status: ContainerStatus): Unit

    /**
      * Method called when the container has been unscheduled from a host.
      */
    def onUnscheduled(container: ServiceContainer, hostId: UUID): Unit

}
