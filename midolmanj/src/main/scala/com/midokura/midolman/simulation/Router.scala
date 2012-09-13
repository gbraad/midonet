/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import collection.mutable
import compat.Platform
import akka.dispatch.{ExecutionContext, Future, Promise}
import akka.dispatch.Future.flow
import akka.util.Duration
import java.util.UUID
import org.slf4j.LoggerFactory
import java.util.concurrent.{TimeUnit, TimeoutException}

import com.midokura.midolman.layer3.Route
import com.midokura.midolman.simulation.Coordinator._
import com.midokura.midolman.topology.{VirtualTopologyActor,
                RouterConfig, RoutingTableWrapper}
import com.midokura.midolman.SimulationController
import com.midokura.packets.{ARP, Ethernet, ICMP, IntIPv4, IPv4, MAC}
import com.midokura.packets.ICMP.{EXCEEDED_CODE, UNREACH_CODE}
import com.midokura.sdn.flows.WildcardMatch
import akka.actor.{Cancellable, ActorSystem}
import com.midokura.midolman.topology.VirtualTopologyActor.PortRequest
import com.midokura.midonet.cluster.client._
import com.midokura.midolman.simulation.Coordinator.ConsumedAction
import com.midokura.midolman.simulation.Coordinator.DropAction
import com.midokura.midolman.simulation.Coordinator.NotIPv4Action
import com.midokura.midolman.SimulationController.EmitGeneratedPacket
import com.midokura.midolman.state.ArpCacheEntry
import com.midokura.util.functors.Callback1

/* The ArpTable is called from the Coordinators' actors and
 * processes and schedules ARPs. */
trait ArpTable {
    def get(ip: IntIPv4, port: RouterPort[_], expiry: Long)
          (implicit ec: ExecutionContext, actorSystem: ActorSystem): Future[MAC]
    def set(ip: IntIPv4, mac: MAC)
}

class Router(val id: UUID, val cfg: RouterConfig,
             val rTable: RoutingTableWrapper, val arpCache: ArpCache,
             val inFilter: Chain, val outFilter: Chain) extends Device {
    private val arpTable: ArpTable = new ArpTableImpl(arpCache)
    private val log = LoggerFactory.getLogger(classOf[Router])
    private val loadBalancer = new LoadBalancer(rTable)

    override def process(ingressMatch: WildcardMatch, packet: Ethernet,
                         pktContext: PacketContext, expiry: Long)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem): Future[Action] = {
        if (ingressMatch.getEtherType != IPv4.ETHERTYPE &&
            ingressMatch.getEtherType != ARP.ETHERTYPE)
            return Promise.successful(new NotIPv4Action)(ec)

        getRouterPort(ingressMatch.getInputPortUUID, expiry) flatMap {
            port: RouterPort[_] => port match {
                case null => Promise.successful(new DropAction)(ec)
                case inPort => preRouting(inPort, ingressMatch, packet,
                                               pktContext, expiry)
            }
        }
    }

    /* Does pre-routing and routing phases. Delegates post-routing and out
     * phases to postRouting()
     */
    private def preRouting(
                        inPort: RouterPort[_],
                        ingressMatch: WildcardMatch, packet: Ethernet,
                        pktContext: PacketContext, expiry: Long)
                       (implicit ec: ExecutionContext,
                        actorSystem: ActorSystem): Future[Action] = {

        val hwDst = ingressMatch.getEthernetDestination
        if (Ethernet.isBroadcast(hwDst)) {
            // Broadcast packet:  Handle if ARP, drop otherwise.
            if (ingressMatch.getEtherType == ARP.ETHERTYPE &&
                    ingressMatch.getNetworkProtocol == ARP.OP_REQUEST) {
                processArpRequest(packet.getPayload.asInstanceOf[ARP], inPort)
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        if (hwDst != inPort.portMac) {
            // Not addressed to us, log.warn and drop.
            log.warn("{} neither broadcast nor inPort's MAC ({})", hwDst,
                     inPort.portMac)
            return Promise.successful(new DropAction)(ec)
        }

        if (ingressMatch.getEtherType == ARP.ETHERTYPE) {
            // Non-broadcast ARP.  Handle reply, drop rest.
            if (ingressMatch.getNetworkProtocol == ARP.OP_REPLY) {
                processArpReply(packet.getPayload.asInstanceOf[ARP],
                                ingressMatch.getInputPortUUID, inPort)
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        val nwDst = ingressMatch.getNetworkDestinationIPv4
        if (nwDst == inPort.portAddr) {
            // We're the L3 destination.  Reply to ICMP echos, drop the rest.
            if (isIcmpEchoRequest(ingressMatch)) {
                sendIcmpEchoReply(ingressMatch, packet, expiry)
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        // XXX: Apply the pre-routing (ingress) chain

        if (ingressMatch.getNetworkTTL != null) {
            val ttl: Byte = ingressMatch.getNetworkTTL
            if (ttl <= 1) {
                sendIcmpError(inPort, ingressMatch, packet,
                    ICMP.TYPE_TIME_EXCEEDED, EXCEEDED_CODE.EXCEEDED_TTL)
                return Promise.successful(new DropAction)(ec)
            } else {
                ingressMatch.setNetworkTTL((ttl - 1).toByte)
            }
        }

        val rt: Route = loadBalancer.lookup(ingressMatch)
        if (rt == null) {
            // No route to network
            sendIcmpError(inPort, ingressMatch, packet,
                ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_NET)
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop == Route.NextHop.BLACKHOLE) {
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop == Route.NextHop.REJECT) {
            sendIcmpError(inPort, ingressMatch, packet,
                ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_FILTER_PROHIB)
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop != Route.NextHop.PORT) {
            log.error("Routing table lookup for {} returned invalid nextHop " +
                "of {}", nwDst, rt.nextHop)
            // TODO(jlm, pino): Should this be an exception?
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHopPort == null) {
            log.error("Routing table lookup for {} forwarded to port null.",
                nwDst)
            // TODO(pino): should we remove this route?
            return Promise.successful(new DropAction)(ec)
        }

        getRouterPort(rt.nextHopPort, expiry) flatMap {
            port: RouterPort[_] => port match {
                case null => Promise.successful(new DropAction)(ec)
                case outPort => postRouting(inPort, outPort, rt, ingressMatch,
                                              packet, pktContext, expiry)
            }
        }
    }

    private def postRouting(
                        inPort: RouterPort[_], outPort: RouterPort[_],
                        rt: Route, ingressMatch: WildcardMatch, packet: Ethernet,
                        pktContext: PacketContext, expiry: Long)
                       (implicit ec: ExecutionContext,
                        actorSystem: ActorSystem): Future[Action] = {

        // XXX: Apply post-routing (egress) chain.

        if (ingressMatch.getNetworkDestinationIPv4 == outPort.portAddr) {
            if (isIcmpEchoRequest(ingressMatch)) {
                sendIcmpEchoReply(ingressMatch, packet, expiry)
                return Promise.successful(new ConsumedAction)(ec)
            } else {
                return Promise.successful(new DropAction)(ec)
            }
        }
        var matchOut: WildcardMatch = null // ingressMatch.clone
        // Set HWSrc
        matchOut.setEthernetSource(outPort.portMac)
        // Set HWDst
        val macFuture = getNextHopMac(outPort, rt,
                            matchOut.getNetworkDestination, expiry)
        flow {
            val nextHopMac = macFuture()
            if (nextHopMac == null) {
                if (rt.nextHopGateway == 0 || rt.nextHopGateway == -1) {
                    sendIcmpError(inPort, ingressMatch, packet,
                        ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_HOST)
                } else {
                    sendIcmpError(inPort, ingressMatch, packet,
                        ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_NET)
                }
                new DropAction: Action
            } else {
                matchOut.setEthernetDestination(nextHopMac)
                new ToPortAction(rt.nextHopPort, matchOut): Action
            }
        }(ec)
    }

    private def getRouterPort(portID: UUID, expiry: Long)
            (implicit actorSystem: ActorSystem): Future[RouterPort[_]] = {
        val virtualTopologyManager = VirtualTopologyActor.getRef(actorSystem)
        expiringAsk(virtualTopologyManager, PortRequest(portID, false),
                    expiry).mapTo[RouterPort[_]] map {
            port => port match {
                case null =>
                    log.error("Can't find router port: {}", portID)
                    null
                case rtrPort => rtrPort
            }
        }
    }

    private def processArpRequest(pkt: ARP, inPort: RouterPort[_])
                                 (implicit ec: ExecutionContext,
                                  actorSystem: ActorSystem) {
        if (pkt.getProtocolType != ARP.PROTO_TYPE_IP)
            return
        val tpa = IPv4.toIPv4Address(pkt.getTargetProtocolAddress)
        if (tpa != inPort.portAddr.addressAsInt())
            return
        // TODO - spoofL2Network check ... discuss.

        val arp = new ARP()
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
        arp.setProtocolType(ARP.PROTO_TYPE_IP)
        arp.setHardwareAddressLength(6)
        arp.setProtocolAddressLength(4)
        arp.setOpCode(ARP.OP_REPLY)
        arp.setSenderHardwareAddress(inPort.portMac)
        arp.setSenderProtocolAddress(pkt.getTargetProtocolAddress)
        arp.setTargetHardwareAddress(pkt.getSenderHardwareAddress)
        arp.setTargetProtocolAddress(pkt.getSenderProtocolAddress)
        val spa = IPv4.toIPv4Address(pkt.getSenderProtocolAddress)

        log.debug("replying to ARP request from {} for {} with own mac {}",
            Array[Object] (IPv4.fromIPv4Address(spa),
                IPv4.fromIPv4Address(tpa), inPort.portMac))

        val eth = new Ethernet()
        eth.setPayload(arp)
        eth.setSourceMACAddress(inPort.portMac)
        eth.setDestinationMACAddress(pkt.getSenderHardwareAddress)
        eth.setEtherType(ARP.ETHERTYPE)
        SimulationController.getRef(actorSystem) ! EmitGeneratedPacket(
            inPort.id, eth)
    }

    private def processArpReply(pkt: ARP, portID: UUID,
                                rtrPort: RouterPort[_]) {
        // Verify the reply:  It's addressed to our MAC & IP, and is about
        // the MAC for an IPv4 address.
        if (pkt.getHardwareType != ARP.HW_TYPE_ETHERNET ||
                pkt.getProtocolType != ARP.PROTO_TYPE_IP) {
            log.debug("Router {} ignoring ARP reply on port {} because hwtype "+
                      "wasn't Ethernet or prototype wasn't IPv4.", id, portID)
            return
        }
        val tpa: Int = IPv4.toIPv4Address(pkt.getTargetProtocolAddress)
        val tha: MAC = pkt.getTargetHardwareAddress
        if (tpa != rtrPort.portAddr.addressAsInt || tha != rtrPort.portMac) {
            log.debug("Router {} ignoring ARP reply on port {} because tpa or "+
                      "tha doesn't match.", id, portID)
            return
        }
        // Question:  Should we check if the ARP reply disagrees with an
        // existing cache entry and make noise if so?

        val sha: MAC = pkt.getSenderHardwareAddress
        val spa = new IntIPv4(pkt.getSenderProtocolAddress)
        arpTable.set(spa, sha)
    }

    private def isIcmpEchoRequest(mmatch: WildcardMatch): Boolean = {
        mmatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER &&
            (mmatch.getTransportSource & 0xff) == ICMP.TYPE_ECHO_REQUEST &&
            (mmatch.getTransportDestination & 0xff) == ICMP.CODE_NONE
    }

    private def sendIcmpEchoReply(ingressMatch: WildcardMatch, packet: Ethernet,
                                  expiry: Long)
                    (implicit ec: ExecutionContext, actorSystem: ActorSystem) {
        val echo = packet.getPayload match {
            case ip: IPv4 =>
                ip.getPayload match {
                    case icmp: ICMP => icmp
                    case _ => null
                }
            case _ => null
        }
        if (echo == null)
            return

        val reply = new ICMP()
        reply.setEchoReply(echo.getIdentifier, echo.getSequenceNum, echo.getData)
        val ip = new IPv4()
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        ip.setDestinationAddress(ingressMatch.getNetworkSource)
        ip.setSourceAddress(ingressMatch.getNetworkDestination)
        ip.setPayload(reply)

        sendIPPacket(ip, expiry)
    }

    private def getPeerMac(rtrPort: InteriorRouterPort, expiry: Long)
                          (implicit ec: ExecutionContext,
                           actorSystem: ActorSystem): Future[MAC] = {
        val virtualTopologyManager = VirtualTopologyActor.getRef(actorSystem)
        val peerPortFuture = expiringAsk(virtualTopologyManager,
                PortRequest(rtrPort.peerID, false),
                expiry).mapTo[Port[_]]
        peerPortFuture map {
            port => port match {
                case rp: RouterPort[_] => rp.portMac
                case null =>
                    log.error("getPeerMac: cannot get port {}", rtrPort.peerID)
                    null
                case _ => null
            }
        }
    }

    private def getMacForIP(port: RouterPort[_], nextHopIP: Int, expiry: Long)
                           (implicit ec: ExecutionContext,
                            actorSystem: ActorSystem): Future[MAC] = {
        val nwAddr = new IntIPv4(nextHopIP)
        port match {
            case mPortConfig: ExteriorRouterPort =>
                val shift = 32 - mPortConfig.nwLength
                // Shifts by 32 in java are no-ops (see
                // http://www.janeg.ca/scjp/oper/shift.html), so special case
                // nwLength=0 <=> shift=32 to always match.
                if ((nextHopIP >>> shift) !=
                        (mPortConfig.nwAddr.addressAsInt >>> shift) &&
                        shift != 32) {
                    log.warn("getMacForIP: cannot get MAC for {} - address " +
                        "not in network segment of port {} ({}/{})",
                        Array[Object](nwAddr, port.id,
                            mPortConfig.nwAddr.toString,
                            mPortConfig.nwLength.toString))
                    return Promise.successful(null)(ec)
                }
            case _ => /* Fall through */
        }
        arpTable.get(nwAddr, port, expiry)
    }

    /**
     * Given a route and a destination address, return the MAC address of
     * the next hop (or the destination's if it's a link-local destination)
     *
     * @param rt Route that the packet will be sent through
     * @param ipv4Dest Final destination of the packet to be sent
     * @param expiry
     * @param ec
     * @return
     */
    private def getNextHopMac(outPort: RouterPort[_], rt: Route,
                              ipv4Dest: Int, expiry: Long)
                             (implicit ec: ExecutionContext,
                              actorSystem: ActorSystem): Future[MAC] = {
        if (outPort == null)
            return Promise.successful(null)(ec)
        var peerMacFuture: Future[MAC] = null
        outPort match {
            case interior: InteriorRouterPort =>
                if (interior.peerID == null) {
                    log.warn("Packet sent to dangling logical port {}",
                        rt.nextHopPort)
                    return Promise.successful(null)(ec)
                }
                peerMacFuture = getPeerMac(interior, expiry)
            case _ => /* Fall through to ARP'ing below. */
                peerMacFuture = Promise.successful(null)(ec)
        }

        var nextHopIP: Int = rt.nextHopGateway
        if (nextHopIP == 0 || nextHopIP == -1) {  /* Last hop */
            nextHopIP = ipv4Dest
        }

        flow {
            val nextMac = peerMacFuture()
            if (nextMac == null)
                getMacForIP(outPort, nextHopIP, expiry)(ec, actorSystem)()
            else
                nextMac
        }(ec)
    }

    /**
     * Send a locally generated IP packet
     *
     * CAVEAT: this method may block, so it is suitable only for use in
     * the context of processing packets that result in a CONSUMED action.
     *
     * XXX (pino, guillermo): should we add the ability to queue simulation of
     * this device starting at a specific step? In this case it would be the
     * routing step.
     *
     * The logic here is roughly the same as that found in process() except:
     *      + the ingress and prerouting steps are skipped. We do:
     *          - forwarding
     *          - post routing (empty right now)
     *          - emit new packet
     *      + drop actions in process() are an empty return here (we just don't
     *        emit the packet)
     *      + no wildcard match cloning or updating.
     *      + it does not return an action but, instead sends it emits the
     *        packet for simulation if successful.
     */
    def sendIPPacket(packet: IPv4, expiry: Long)
                    (implicit ec: ExecutionContext, actorSystem: ActorSystem) {
        def _sendIPPacket(outPort: RouterPort[_], rt: Route) {
            if (packet.getDestinationAddress == outPort.portAddr.addressAsInt) {
                /* should never happen: it means we are trying to send a packet
                 * to ourselves, probably means that somebody sent an ip packet
                 * with source and dest addresses belonging to this router.
                 */
                return
            }

            val eth = (new Ethernet()).setEtherType(IPv4.ETHERTYPE)
            eth.setPayload(packet)
            eth.setDestinationMACAddress(outPort.portMac)

            val macFuture = getNextHopMac(outPort, rt,
                                packet.getDestinationAddress, expiry)
            macFuture onSuccess {
                case null =>
                    log.error("Failed to get MAC address to emit local packet")
                case mac =>
                    eth.setDestinationMACAddress(mac)
                    SimulationController.getRef(actorSystem).tell(
                        EmitGeneratedPacket(rt.nextHopPort, eth))
            }
        }

        val ipMatch = (new WildcardMatch()).
                setNetworkDestination(packet.getDestinationAddress).
                setNetworkSource(packet.getSourceAddress)
        val rt: Route = loadBalancer.lookup(ipMatch)
        if (rt == null || rt.nextHop != Route.NextHop.PORT)
            return
        if (rt.nextHopPort == null)
            return

        // XXX: Apply post-routing (egress) chain.

        getRouterPort(rt.nextHopPort, expiry) onSuccess {
            case null => log.error("Failed to get port to emit local packet")
            case outPort => _sendIPPacket(outPort, rt)
        }
    }

    /**
     * Determine whether a packet can trigger an ICMP error.  Per RFC 1812 sec.
     * 4.3.2.7, some packets should not trigger ICMP errors:
     *   1) Other ICMP errors.
     *   2) Invalid IP packets.
     *   3) Destined to IP bcast or mcast address.
     *   4) Destined to a link-layer bcast or mcast.
     *   5) With source network prefix zero or invalid source.
     *   6) Second and later IP fragments.
     *
     * @param ethPkt
     *            We wish to know whether this packet may trigger an ICMP error
     *            message.
     * @param egressPortId
     *            If known, this is the port that would have emitted the packet.
     *            It's used to determine whether the packet was addressed to an
     *            IP (local subnet) broadcast address.
     * @return True if-and-only-if the packet meets none of the above conditions
     *         - i.e. it can trigger an ICMP error message.
     */
     def canSendIcmp(ethPkt: Ethernet, outPort: RouterPort[_]) : Boolean = {
        var ipPkt: IPv4 = null
        ethPkt.getPayload match {
            case ip: IPv4 => ipPkt = ip
            case _ => return false
        }

        // Ignore ICMP errors.
        if (ipPkt.getProtocol() == ICMP.PROTOCOL_NUMBER) {
            ipPkt.getPayload match {
                case icmp: ICMP if icmp.isError =>
                    log.debug("Skipping generation of ICMP error for " +
                            "ICMP error packet")
                    return false
                case _ =>
            }
        }
        // TODO(pino): check the IP packet's validity - RFC1812 sec. 5.2.2
        // Ignore packets to IP mcast addresses.
        if (ipPkt.isMcast()) {
            log.debug("Not generating ICMP Unreachable for packet to an IP "
                    + "multicast address.")
            return false
        }
        // Ignore packets sent to the local-subnet IP broadcast address of the
        // intended egress port.
        if (null != outPort) {
            if (ipPkt.isSubnetBcast(
                        outPort.portAddr.addressAsInt, outPort.nwLength)) {
                log.debug("Not generating ICMP Unreachable for packet to "
                        + "the subnet local broadcast address.")
                return false
            }
        }
        // Ignore packets to Ethernet broadcast and multicast addresses.
        if (ethPkt.isMcast()) {
            log.debug("Not generating ICMP Unreachable for packet to "
                    + "Ethernet broadcast or multicast address.")
            return false
        }
        // Ignore packets with source network prefix zero or invalid source.
        // TODO(pino): See RFC 1812 sec. 5.3.7
        if (ipPkt.getSourceAddress() == 0xffffffff
                || ipPkt.getDestinationAddress() == 0xffffffff) {
            log.debug("Not generating ICMP Unreachable for all-hosts broadcast "
                    + "packet")
            return false
        }
        // TODO(pino): check this fragment offset
        // Ignore datagram fragments other than the first one.
        if (0 != (ipPkt.getFragmentOffset() & 0x1fff)) {
            log.debug("Not generating ICMP Unreachable for IP fragment packet")
            return false
        }
        return true
    }

    private def buildIcmpError(icmpType: Char, icmpCode: Any,
                   forMatch: WildcardMatch, forPacket: Ethernet) : ICMP = {
        val pktHere = forMatch.apply(forPacket)
        var ipPkt: IPv4 = null
        pktHere.getPayload match {
            case ip: IPv4 => ipPkt = ip
            case _ => return null
        }

        icmpCode match {
            case c: ICMP.EXCEEDED_CODE if icmpType == ICMP.TYPE_TIME_EXCEEDED =>
                val icmp = new ICMP()
                icmp.setTimeExceeded(c, ipPkt)
                return icmp
            case c: ICMP.UNREACH_CODE if icmpType == ICMP.TYPE_UNREACH =>
                val icmp = new ICMP()
                icmp.setUnreachable(c, ipPkt)
                return icmp
            case _ =>
                return null
        }
    }

    /**
     * Send an ICMP error message.
     *
     * @param ingressMatch
     *            The wildcard match that caused the message to be generated
     * @param packet
     *            The original packet that started the simulation
     */
     def sendIcmpError(inPort: RouterPort[_], ingressMatch: WildcardMatch,
                       packet: Ethernet, icmpType: Char, icmpCode: Any)
                      (implicit ec: ExecutionContext,
                       actorSystem: ActorSystem) {
        // Check whether the original packet is allowed to trigger ICMP.
        if (inPort == null)
            return
        if (!canSendIcmp(packet, inPort))
            return
        // Build the ICMP packet from inside-out: ICMP, IPv4, Ethernet headers.
        val icmp = buildIcmpError(icmpType, icmpCode, ingressMatch, packet)
        if (icmp == null)
            return

        val ip = new IPv4()
        ip.setPayload(icmp)
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        // The nwDst is the source of triggering IPv4 as seen by this router.
        ip.setDestinationAddress(ingressMatch.getNetworkSource)
        // The nwSrc is the address of the ingress port.
        ip.setSourceAddress(inPort.portAddr.addressAsInt)
        val eth = new Ethernet()
        eth.setPayload(ip)
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setSourceMACAddress(inPort.portMac)
        eth.setDestinationMACAddress(ingressMatch.getEthernetSource)

        /* log.debug("sendIcmpError from port {}, {} to {}", new Object[] {
                ingressMatch.getInputPortUUID,
                IPv4.fromIPv4Address(ip.getSourceAddress()),
                IPv4.fromIPv4Address(ip.getDestinationAddress()) }) */
        SimulationController.getRef(actorSystem) ! EmitGeneratedPacket(
            ingressMatch.getInputPortUUID, eth)
    }

    private class ArpTableImpl(arpCache: ArpCache) extends ArpTable {
        private val ARP_RETRY_MILLIS = 10 * 1000
        private val ARP_TIMEOUT_MILLIS = 60 * 1000
        private val ARP_STALE_MILLIS = 1800 * 1000
        private val ARP_EXPIRATION_MILLIS = 3600 * 1000
        private val arpWaiters = new mutable.HashMap[IntIPv4,
                                                 mutable.Set[Promise[MAC]]] with
                                     mutable.MultiMap[IntIPv4, Promise[MAC]]

        // TODO -- subscribe to ArpCache changes

        /**
         * Schedule promise to fail with a TimeoutException at a given time.
         *
         * @param promise
         * @param expiry
         * @param onExpire If the promise is expired, onExpire will be executed
         *                 with the promise as an argument.
         * @param actorSystem
         * @tparam T
         * @return
         */
        private def promiseOnExpire[T](promise: Promise[T], expiry: Long,
                            onExpire: (Promise[T]) => Unit)
                           (implicit actorSystem: ActorSystem): Future[T] = {
            val now = Platform.currentTime
            if (now >= expiry) {
                if (promise tryComplete Left(new TimeoutException()))
                    onExpire(promise)
                return promise
            }
            val when = Duration.create(expiry - now, TimeUnit.MILLISECONDS)
            val exp = actorSystem.scheduler.scheduleOnce(when) {
                if (promise tryComplete Left(new TimeoutException()))
                    onExpire(promise)
            }
            promise onSuccess { case _ => exp.cancel() }
            promise
        }

        private def fetchArpCacheEntry(ip: IntIPv4, expiry: Long)
            (implicit ec: ExecutionContext) : Future[ArpCacheEntry] = {
            val promise = Promise[ArpCacheEntry]()(ec)
            arpCache.get(ip, new Callback1[ArpCacheEntry] {
                def call(value: ArpCacheEntry) {
                    promise.success(value)
                }
            }, expiry)
            promise
        }

        private def removeWatcher(ip: IntIPv4, future: Future[MAC]) {
            arpWaiters.get(ip) match {
                case Some(waiters) => future match {
                    case promise: Promise[MAC] => waiters remove promise
                    case _ =>
                }
                case None =>
            }
        }

        def waitForArpEntry(ip: IntIPv4, expiry: Long)
                           (implicit ec: ExecutionContext,
                            actorSystem: ActorSystem) : Future[MAC] = {
            val promise = Promise[MAC]()(ec)
            arpWaiters.addBinding(ip, promise)
            promiseOnExpire[MAC](promise, expiry, p => removeWatcher(ip, p))
        }

        def get(ip: IntIPv4, port: RouterPort[_], expiry: Long)
               (implicit ec: ExecutionContext,
                actorSystem: ActorSystem): Future[MAC] = {
            val entryFuture = fetchArpCacheEntry(ip, expiry)
            val now = Platform.currentTime
            flow {
                val entry = entryFuture()
                if (entry == null || entry.stale < now || entry.macAddr == null)
                    arpForAddress(ip, entry, port)
                if (entry != null && entry.expiry >= now)
                    entry.macAddr
                else {
                    // There's no arpCache entry, or it's expired.
                    // Wait for the arpCache to become populated by an ARP reply
                    waitForArpEntry(ip, expiry).apply()
                }
            }(ec)
        }

        def set(ip: IntIPv4, mac: MAC) {
            arpWaiters.remove(ip) match {
                    case Some(waiters) => waiters map { _ success mac}
                    case None =>
            }
            val now = Platform.currentTime
            val entry = new ArpCacheEntry(mac, now + ARP_STALE_MILLIS,
                                          now + ARP_EXPIRATION_MILLIS, 0)
            arpCache.add(ip, entry)
        }

        private def makeArpRequest(srcMac: MAC, srcIP: IntIPv4, dstIP: IntIPv4):
                        Ethernet = {
            val arp = new ARP()
            arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
            arp.setProtocolType(ARP.PROTO_TYPE_IP)
            arp.setHardwareAddressLength(6.asInstanceOf[Byte])
            arp.setProtocolAddressLength(4.asInstanceOf[Byte])
            arp.setOpCode(ARP.OP_REQUEST)
            arp.setSenderHardwareAddress(srcMac)
            arp.setTargetHardwareAddress(MAC.fromString("00:00:00:00:00:00"))
            arp.setSenderProtocolAddress(
                IPv4.toIPv4AddressBytes(srcIP.addressAsInt))
            arp.setTargetProtocolAddress(
                IPv4.toIPv4AddressBytes(dstIP.addressAsInt))
            val pkt: Ethernet = new Ethernet
            pkt.setPayload(arp)
            pkt.setSourceMACAddress(srcMac)
            pkt.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
            pkt.setEtherType(ARP.ETHERTYPE)
        }

        // XXX
        // cancel scheduled expires when an entry is refreshed?
        // what happens if this node crashes before
        // expiration, is there a global clean-up task?
        private def expireCacheEntry(ip: IntIPv4)
                                    (implicit ec: ExecutionContext) {
            val now = Platform.currentTime
            val entryFuture = fetchArpCacheEntry(ip, now + ARP_RETRY_MILLIS)
            entryFuture onSuccess {
                case entry if entry != null =>
                    val now = Platform.currentTime
                    if (entry.expiry <= now) {
                        arpWaiters.remove(ip) match {
                            case Some(waiters) => waiters map { _ success null }
                            case None =>
                        }
                        arpCache.remove(ip)
                    }
            }
        }

        private def arpForAddress(ip: IntIPv4, entry: ArpCacheEntry,
                                  port: RouterPort[_])
                                 (implicit ec: ExecutionContext,
                                  actorSystem: ActorSystem) {
            def retryLoopBottomHalf(cacheEntry: ArpCacheEntry, arp: Ethernet,
                                    previous: Long) {
                val now = Platform.currentTime
                // expired, no retries left.
                if (cacheEntry == null || cacheEntry.expiry <= now) {
                    arpWaiters.remove(ip) match {
                        case Some(waiters) => waiters map { _ success null}
                        case None =>
                    }
                    return
                }
                // another node took over, give up. Waiters will be notified.
                // XXX - what will happen to our waiters if another node takes
                // over and we time out??
                // We should do arpCache.set() with mac = null
                if (previous > 0 && cacheEntry.lastArp != previous)
                    return
                // now up to date, entry was updated while the top half
                // of the retry loop waited for the arp cache entry, waiters
                // will be notified naturally, do nothing.
                if (cacheEntry.macAddr != null && cacheEntry.stale > now)
                    return

                cacheEntry.lastArp = now
                arpCache.add(ip, cacheEntry)
                log.debug("generateArpRequest: sending {}", arp)
                SimulationController.getRef(actorSystem) !
                    EmitGeneratedPacket(port.id, arp)
                waitForArpEntry(ip, now + ARP_RETRY_MILLIS) onComplete {
                    case Left(ex: TimeoutException) =>
                        retryLoopTopHalf(arp, now)
                    case Left(ex) =>
                        log.error("waitForArpEntry unexpected exception {}", ex)
                    case Right(mac) =>
                }
            }

            def retryLoopTopHalf(arp: Ethernet, previous: Long) {
                val now = Platform.currentTime
                val entryFuture = fetchArpCacheEntry(ip, now + ARP_RETRY_MILLIS)
                entryFuture onSuccess {
                    case null => retryLoopBottomHalf(null, arp, previous)
                    case e => retryLoopBottomHalf(e.clone(), arp, previous)
                }
            }

            val now = Platform.currentTime
            // XXX this is open to races with other nodes
            // for now we take over sending ARPs if no ARP has been sent
            // in RETRY_INTERVAL * 2. And, obviously, the MAC is null or stale.
            //
            // But when that happens (for some reason a node stopped following
            // through with the retries) other nodes will race to take over.
            if (entry != null && entry.lastArp + ARP_RETRY_MILLIS*2 > now)
                return

            var newEntry: ArpCacheEntry = null
            if (entry == null) {
                newEntry = new ArpCacheEntry(null, now + ARP_TIMEOUT_MILLIS,
                                             now + ARP_RETRY_MILLIS, now)
                val when = Duration.create(ARP_EXPIRATION_MILLIS,
                            TimeUnit.MILLISECONDS)
                actorSystem.scheduler.scheduleOnce(when){ expireCacheEntry(ip) }
            } else {
                newEntry = entry.clone()
            }

            val pkt = makeArpRequest(port.portMac, port.portAddr, ip)
            retryLoopBottomHalf(newEntry, pkt, 0)
        }

    }
}