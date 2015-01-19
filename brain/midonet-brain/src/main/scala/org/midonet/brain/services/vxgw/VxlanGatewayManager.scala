/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.brain.services.vxgw

import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, Executors}

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Observer, Subscription}

import org.midonet.brain.services.vxgw
import org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.brain.southbound.vtep.VtepMAC.fromMac
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.data.ports.VxLanPort
import org.midonet.midolman.serialization.SerializationException
import org.midonet.midolman.state.Directory.DefaultTypedWatcher
import org.midonet.midolman.state.ReplicatedMap.Watcher
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.functors.makeRunnable

object VxlanGateway {
    protected[vxgw] val executor = Executors.newSingleThreadExecutor()
}

/** Represents a Logical Switch spanning N VTEPs and a Neutron Network.
  *
  * @param networkId id if the neutron network that is acting as VxLAN Gateway
  *                  by having bindings to port/vlan pairs in hardware VTEPs.
  */
class VxlanGateway(val networkId: UUID) {

    private val log = LoggerFactory.getLogger(vxgw.vxgwMgmtLog(networkId))
    private val updates = PublishSubject.create[MacLocation]()

    protected[midonet] var vni = -1

    /** The name of the Logical Switch associated to this VxGateway */
    val name = bridgeIdToLogicalSwitchName(networkId)

    /** This is the entry point to dump observables */
    def asObserver: Observer[MacLocation] = new Observer[MacLocation] {
        override def onCompleted(): Unit = updates.onCompleted()
        override def onError(e: Throwable): Unit = updates.onError(e)
        override def onNext(ml: MacLocation): Unit = {
            if (!ml.logicalSwitchName.equals(name)) {
                log.warn(s"Drops $ml (bad log. switch name)")
            } else {
                if (log.isTraceEnabled) {
                    log.trace("Learn " + ml)
                }
                updates.onNext(ml)
            }
        }
    }

    /** Get the message bus of MacLocation notifications of the Logical
      * Switch.  You're responsible to filter out your own updates. */
    def asObservable: Observable[MacLocation] = updates

    protected[midonet] def terminate(): Unit = updates.onCompleted()

    override def toString: String = "VxGW(networkId: " + networkId +
                                    ", vni: " + vni

}


/** Manages a VxLAN Gateway that connects a Neutron Network with a set of ports
  * on hardware VTEPs.  Neutron networks are bound to port/vlan pairs on VTEPs
  * in order to form a single L2 segment.  An instance of this class is able
  * to monitor a Neutron network and control the synchronization of MACs
  * among MidoNet and all hardware VTEPs that participate in the VxLAN Gateway.
  *
  * @param networkId the id of the Neutron Network with VTEP bindings to manage
  * @param dataClient to access the MidoNet backend storage
  * @param zkConnWatcher watcher to use to handle ZK connection issues
  * @param onClose callback that will be invoked when we're done monitoring the
  *                network, for any reason. Obviously, don't do anything nasty
  *                in it (blocking, heavy IO, etc.)
  */
class VxlanGatewayManager(networkId: UUID,
                          dataClient: DataClient,
                          vtepPeerPool: VtepPool,
                          zkConnWatcher: ZookeeperConnectionWatcher,
                          onClose: () => Unit) {

    private val log = LoggerFactory.getLogger(vxgwMgmtLog(networkId))

    private var vxgw: VxlanGateway = _

    private val peerEndpoints = new ConcurrentHashMap[IPv4Addr, UUID]
    private val vxlanPorts = new ConcurrentHashMap[UUID, VxLanPort]

    private var macPortMap: MacPortMap = _
    private var arpTable: Ip4ToMacReplicatedMap = _

    private var vxgwBusObserver: BusObserver = _

    /** The name of the Logical Switch that is created on all Hardware VTEPs to
      * configure the bindings to this Neutron Network in order to implement a
      * VxLAN Gateway. */
    val lsName = bridgeIdToLogicalSwitchName(networkId)

    class NetworkNotInVxlanGatewayException(m: String)
        extends RuntimeException(m)

    /* A simple Bridge watcher that detects when a bridge is updated and applies
     * the relevant changes in state and syncing processes, or terminates the
     * manager if the bridge itself is removed. */
    private val bridgeWatcher = new DefaultTypedWatcher {
        override def pathDataChanged(path: String): Unit = {
            log.info(s"Network $networkId is updated")
            updateBridge()
        }
        override def pathDeleted(path: String): Unit = {
            log.info(s"Network $networkId is deleted, stop monitoring")
            terminate()
        }
    }

    /* Models a simple watcher on a single update of a MacPortMap entry, which
     * trigger advertisements to the VTEPs if the change was triggered from
     * MidoNet itself (but not if it comes from Hardware VTEPs)
     *
     * TODO: model this as an Observable to unify approaches with the Vtep
     *       controller. */
    private class MacPortWatcher() extends Watcher[MAC, UUID]() {
        private val log = LoggerFactory.getLogger(vxgwMacSyncingLog(networkId))
        def processChange(mac: MAC, oldPort: UUID, newPort: UUID): Unit = {
            // port is the last place where the mac was seen
            log.debug("Network {}: MAC {} moves from {} to {}",
                      networkId, mac, oldPort, newPort)
            val port = if (newPort == null) oldPort else newPort
            if (vxgw != null && isPortInMidonet(port)) {
                publishMac(mac, newPort, oldPort, onlyMido = true)
            }
        }
    }

    /* A simple watcher on the ARP table, changes trigger advertisements to the
     * VTEPs if the change was triggered from within MidoNet.
     *
     * TODO: review the logic here.
     *
     * TODO: model this using observables for consistency. */
    private class ArpTableWatcher() extends Watcher[IPv4Addr, MAC] {
        override def processChange(ip: IPv4Addr, oldMac: MAC, newMac: MAC)
        : Unit = {
            log.debug(s"IP $ip moves from $oldMac to $newMac")
            if (oldMac != null) { // The old mac needs to be removed
                macPortMap.get(oldMac) match {
                    case portId if isPortInMidonet(portId) =>
                        // If the IP was on a MidoNet port, we remove it,
                        // otherwise it's managed by some VTEP.
                        vxgw.asObserver.onNext(
                            new MacLocation(fromMac(oldMac), ip, lsName, null)
                        )
                    case _ =>
                }
            }
            if (newMac != null) {
                macPortMap.get(newMac) match {
                    case portId if isPortInMidonet(portId) =>
                        advertiseMacAndIpAt(newMac, ip, portId)
                    case _ =>
                }
            }
        }
    }

    /** Whether the Gateway Manager is actively managing the VxGW */
    private var active = false

    /** Our subscription to the VxGW update bus */
    private var busSubscription: Subscription = _

    /** Start syncing MACs from the neutron network with the bound VTEPs. */
    def start(): Unit = updateBridge()

    /** Tells whether this port is bound to a part of a MidoNet virtual topology,
      * excluding those ports that represent the bindings to a VTEP.  This is
      * used to decide whether we're responsible to manage some event's side
      * effects or not.  Events that affect a non-MidoNet port are expected
      * to be managed by the VTEPs themselves, or the VxlanPeer implementations
      * for each VTEP. */
    private def isPortInMidonet(portId: UUID): Boolean = {
        portId != null && !vxlanPorts.containsKey(portId)
    }

    /** Get a snapshot of all the known MACs of this Logical Switch */
    private def snapshot(): Iterable[MacLocation] = {
        if (!active) {
            return Seq.empty
        }
        log.debug(s"Taking snapshot of known MACs at $networkId")
        // The reasoning is that we get a SNAPSHOT based *only* on entries in
        // the replicated map because this contains also all entries from the
        // VTEPs, with their respective IPs.
        val snapshot = macPortMap.getMap.entrySet.flatMap { e =>
            val mac = e.getKey
            val port = e.getValue
            // report additions
            toMacLocations(mac, port, null, onlyMido = false).getOrElse {
                Seq.empty[MacLocation]
            }
        }
        snapshot
    }

    /* Initialize the various processes required to manage the VxLAN gateway
     * for the given Neutron Network.  This includes setting up watchers on the
     * MAC-Port and ARP tables, as well as preparing the message bus that the
     * manager and VTEP controllers will use to exchange MacLocations as they
     * appear on different points of the topology. */
    private def initialize(): Unit = {
        active = true
        vxgw = new VxlanGateway(networkId)
        if (macPortMap == null) {   // in a retry this might be loaded
            log.info(s"Starting to watch MAC-Port table in $networkId")
            macPortMap = dataClient.bridgeGetMacTable(networkId,
                                                      UNTAGGED_VLAN_ID,
                                                      false)
            macPortMap addWatcher new MacPortWatcher()
            macPortMap.setConnectionWatcher(zkConnWatcher)
            macPortMap.start()

            vxgwBusObserver = new BusObserver(dataClient, networkId,
                                                  macPortMap, zkConnWatcher,
                                                  peerEndpoints)
            busSubscription = vxgw.asObservable
                                  .observeOn(Schedulers.from(VxlanGateway.executor))
                                  .subscribe(vxgwBusObserver)

        }
        if (arpTable == null) {
            log.info(s"Starting to watch ARP table in $networkId")
            arpTable = dataClient.bridgeGetArpTable(networkId)
            arpTable addWatcher new ArpTableWatcher()
            arpTable.setConnectionWatcher(zkConnWatcher)
            arpTable.start()
        }
    }

    /** Clean up and stop monitoring */
    def terminate(): Unit = {
        log.info(s"Stop monitoring network $networkId")
        if (active) {
            active = false
            vxgw.terminate()
        }
        if (busSubscription != null) {
            busSubscription.unsubscribe()
        }
        if (macPortMap != null) {
            macPortMap.stop()
        }
        if (arpTable != null) {
            arpTable.stop()
        }
        onClose()
    }


    /** Reload the Network state and apply the new configuration */
    private def updateBridge(): Unit = {
        if (!active) {
            initialize()
        }
        loadNewBridgeState() match {
            case Success(_) =>
                log.info(s"Successfully processed update")
            case Failure(e: NetworkNotInVxlanGatewayException) =>
                log.warn("Not relevant for VxLAN gateway")
                terminate()
            case Failure(e: NoStatePathException) =>
                log.warn("Deletion while loading network config, reload",e)
                updateBridge()
            case Failure(e: SerializationException) =>
                log.error("Failed to deserialize entity", e)
                terminate()
            case Failure(e: StateAccessException) =>
                log.warn("Cannot retrieve network state", e)
                zkConnWatcher.handleError(s"Network update retry: $networkId",
                                          makeRunnable { updateBridge() } , e
                )
            case Failure(t: Throwable) =>
                log.error("Error while processing bridge update", t)
                // TODO: retry, or exponential bla bla
                terminate()
        }
    }

    /** Attempts to load all the information that concerns a single bridge and
      * return the new state without actually making it available. */
    private def loadNewBridgeState() = Try {

        // TODO: async all IO here. We need to get off the ZK event
        // notification thread early, and do this in our own thread.

        val bridge: Bridge = dataClient.bridgeGetAndWatch(networkId,
                                                          bridgeWatcher)

        val newPortIds: Seq[UUID] = if (bridge == null ||
                                        bridge.getVxLanPortIds == null) Seq()
                                    else bridge.getVxLanPortIds

        // Spot VTEPS no longer bound to this network
        vxlanPorts.keys.filter  { !newPortIds.contains(_) } // all deleted ports
            .map { vxlanPorts.remove }                      // forget them
            .foreach { port => if (port != null) {
                vtepPeerPool.fishIfExists(port.getMgmtIpAddr, port.getMgmtPort)
                            .foreach { _.abandon(vxgw) }
            }}

        if (bridge == null) {
            throw new NetworkNotInVxlanGatewayException(s"$networkId deleted")
        } else if (newPortIds.isEmpty) {
            throw new NetworkNotInVxlanGatewayException(
                "No longer bound to any VTEPs")
        }

        // Spot new VTEPs bound to this network
        newPortIds foreach { portId =>
            if (!vxlanPorts.containsKey(portId)) { // A new VTEP is bound!
                bootstrapNewVtep(portId)
            }
        }
    }

    /** A new VTEP appears on the network, which indicates bindings to a new
      * VTEP.  Load the VtepPeer and make it join the Logical Switch of this
      * network. */
    private def bootstrapNewVtep(vxPortId: UUID): Unit = {
        val vxPort = dataClient.portsGet(vxPortId)
            .asInstanceOf[VxLanPort]
        if (vxgw.vni == -1) {
            vxgw.vni = vxPort.getVni
        }
        if (vxPort.getVni != vxgw.vni) {
            // This should've been enforced at the API level!
            log.warn(s"VxLAN port $vxPortId has vni ${vxPort.getVni}, but " +
                     s"expected ${vxgw.vni}. Probable data inconsistency, " +
                     s"further bindings to VTEP at ${vxPort.getMgmtIpAddr}!" +
                      "will be ignored")
            return
        }

        log.info(s"Bindings to new VTEP at " +
                 vxPort.getMgmtIpAddr + ":" + vxPort.getMgmtPort)

        vxlanPorts.put(vxPort.getId, vxPort)
        peerEndpoints.put(vxPort.getTunnelIp, vxPort.getId)

        vtepPeerPool.fish(vxPort.getMgmtIpAddr, vxPort.getMgmtPort)
                    .join(vxgw, snapshot())
    }

    /** Converts a new MAC to port update in a bunch of MacLocations to be
      * applied on remote VTEPs. When onlyMido is true, only entries concerning
      * a MidoNet port will be translated (either moving between ports in a
      * network, or from VTEP <-> MidoNet. Otherwise also entries that point at
      * VxLAN ports will be translated (in this case using the VTEP's IP from
      * the VxLAN port at which the MAC is located)
      *
      * TODO: async this?
      */
    private def toMacLocations(mac: MAC, newPort: UUID, oldPort: UUID,
                               onlyMido: Boolean): Try[Set[MacLocation]] = Try {

        // isMido tells whether this all, or part of the changes related to
        // this update are related to a port in MidoNet, and therefore data
        // structures must be updated by us
        val isInMidonet = isPortInMidonet(oldPort) || isPortInMidonet(newPort)
        if (onlyMido && !isInMidonet) {
            return Success(Set.empty)
        }

        // The tunnel destination of the MAC, based on the newPort
        val tunnelDst = if (isInMidonet) {
                            dataClient.vxlanTunnelEndpointFor(newPort)
                        } else {
                            // it's a VTEP, use its vxlan tunnel IP
                            val p = vxlanPorts.get(newPort)
                            if (p == null) null else p.getTunnelIp
                        }

        val vMac = fromMac(mac)
        if (tunnelDst == null && newPort != null) {
            // This a typical case when the VM that has the MAC is not actually
            // at an exterior port in the Network itself, but somewhere else in
            // the virtual topology
            log.error("TODO: use real flooding proxy!!!")
            val floodingProxy = IPv4Addr("44.44.44.44")
            log.info(s"MAC at port $newPort but tunnel IP not found, I will " +
                     s"use the flooding proxy ($floodingProxy)")
            (arpTable.getByValue(mac) map { ip =>
                MacLocation(vMac, ip, lsName, floodingProxy)
            }).toSet + MacLocation(vMac, null, lsName, floodingProxy)
        } else if (tunnelDst == null) {
            log.info(s"MAC $vMac removed from $lsName")
            Set(MacLocation(vMac, null, lsName, null))
        } else {
            // We know the tunnel dst, let's also add an entry for each IP known
            // in the MAC, for ARP supression plus one for those not known
            // TODO: review this, not sure if we want to do the ARP supression
            //       bit for MACs that are not in MidoNet
            val macLocation: MacLocation = MacLocation(vMac, null, lsName,
                                                       tunnelDst)
            if (arpTable == null) {
                Set(macLocation)
            } else {
                (arpTable.getByValue(mac) map { ip =>
                    new MacLocation(vMac, ip, lsName, tunnelDst)
                }).toSet + macLocation
            }
        }
    }

    /** Publish the given location of a MAC to the given subscriber. */
    private def publishMac(mac: MAC, newPort: UUID, oldPort: UUID,
                           onlyMido: Boolean): Unit = {
        toMacLocations(mac, newPort, oldPort, onlyMido) match {
            case Success(mls) =>
                mls foreach vxgw.asObserver.onNext
            case Failure(e: NoStatePathException) =>
                log.debug(s"Node not in ZK, probably a race: ${e.getMessage}")
            case Failure(e: StateAccessException) =>
                log.warn(s"Cannot retrieve network state: ${e.getMessage}")
                if (zkConnWatcher != null) {
                    zkConnWatcher.handleError(
                        s"Retry load network: $networkId",
                        makeRunnable { newPort match {
                            case _ if !active =>
                                log.warn("Retry failed, the manager is down")
                            case null =>
                                publishMac(mac, null, oldPort, onlyMido)
                            case _ =>
                                val currPort = macPortMap.get(mac) // reload
                                publishMac(mac, currPort, oldPort, onlyMido)
                            }
                        }, e)
                }
            case Failure(t: Throwable) =>
                log.warn(s"Failure while publishing $mac on port $newPort", t)
        }
    }

    /** Reliably publish the association of MAC and IP as long as the MAC
      * remains at expectPortId. */
    private def advertiseMacAndIpAt(mac: MAC, ip: IPv4Addr, expectPortId: UUID)
    : Unit = {
        try {
            // Yes, we'll be querying this map twice on the first attempt, this
            // is not horrible, its an O(1) lookup in a local cache
            macPortMap.get(mac) match {
                case currPortId if currPortId eq expectPortId =>
                    val tunIp = dataClient.vxlanTunnelEndpointFor(currPortId)
                    vxgw.asObserver.onNext(
                        new MacLocation(fromMac(mac), ip, lsName, tunIp)
                    )
                case _ =>
            }
        } catch {
            case e: StateAccessException =>
                zkConnWatcher.handleError(
                    s"Retry removing IPs from MAC $mac",
                    makeRunnable { advertiseMacAndIpAt(mac, ip, expectPortId) },
                    e)
            case t: Throwable =>
                log.error(s"Failed to remove MAC $mac from port $expectPortId")
        }
    }

}