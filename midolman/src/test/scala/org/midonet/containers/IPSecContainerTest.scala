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

package org.midonet.containers

import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService

import scala.util.Random

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.CreateOp
import org.midonet.cluster.models.Neutron.IPSecSiteConnection.IPSecPolicy.{EncapsulationMode, TransformProtocol}
import org.midonet.cluster.models.Neutron.IPSecSiteConnection.IkePolicy.IkeVersion
import org.midonet.cluster.models.Neutron.IPSecSiteConnection._
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.topology.TopologyBuilder._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.containers.{ContainerHealth, ContainerPort}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.util.concurrent._


@RunWith(classOf[JUnitRunner])
class IPSecContainerTest extends MidolmanSpec with Matchers with TopologyBuilder {

    class TestIPSecContainer(vt: VirtualTopology, executor: ExecutorService)
        extends IPSecContainer(vt, executor) {

        var commands = Seq.empty[String]
        var failOn = -1
        var throwOn = -1
        override def execCmd(cmd: String): Int = {
            commands = commands :+ cmd
            if (commands.size == throwOn) throw new Exception()
            if (commands.size == failOn) -1 else 0
        }
        override def writeFile(contents: String, location: String): Unit = { }
    }

    private val random = new Random
    private var vt: VirtualTopology = _
    private val executor = new SameThreadButAfterExecutorService

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
    }

    private def createService()
    : (IPSecServiceDef, IkePolicy, IPSecPolicy, IPSecSiteConnection) = {
        val path = s"${FileUtils.getTempDirectory}/${UUID.randomUUID}"
        val service = IPSecServiceDef(random.nextString(10),
                                      path,
                                      IPv4Addr.random,
                                      MAC.random().toString,
                                      randomIPv4Subnet,
                                      IPv4Addr.random,
                                      MAC.random().toString)

        val ike = createIkePolicy(
            version = Some(IkeVersion.V1),
            lifetimeValue = Some(random.nextInt()))

        val ipsec = createIpsecPolicy(
            encapsulation = Some(EncapsulationMode.TUNNEL),
            transform = Some(TransformProtocol.ESP),
            lifetimeValue = Some(random.nextInt()))

        val conn = createIpsecSiteConnection(
            auth = Some(AuthMode.PSK),
            dpdAction = Some(DpdAction.HOLD),
            dpdInterval = Some(random.nextInt()),
            dpdTimeout = Some(random.nextInt()),
            initiator = Some(Initiator.BI_DIRECTIONAL),
            name = Some(random.nextString(10)),
            mtu = Some(random.nextInt()),
            peerAddress = Some(IPv4Addr.random.toString),
            psk = Some(random.nextString(10)),
            localCidr = Some(randomIPv4Subnet),
            peerCidrs = Seq(randomIPv4Subnet),
            ikePolicy = Some(ike),
            ipsecPolicy = Some(ipsec))

        (service, ike, ipsec, conn)
    }

    feature("IPSec container writes contents of config files") {

        scenario("Single connection, with IPSecSiteConnection admin state DOWN") {
            Given("A VPN configuration")
            val (vpn, ike, ipsec, _conn) = createService()
            val conn = _conn.toBuilder.setAdminStateUp(false).build()

            And("Expected configuration, with no connections")
            val expectedSecrets = ""
            val expectedConf =
                s"""config setup
                   |    nat_traversal=yes
                   |conn %default
                   |    ikelifetime=480m
                   |    keylife=60m
                   |    keyingtries=%forever
                   |""".stripMargin

            When("Creating a IPSec configuration")
            val conf = new IPSecConfig("vpn-helper", vpn, Seq(conn))

            Then("The configurations should match")
            expectedConf shouldBe conf.getConfigFileContents
            expectedSecrets shouldBe conf.getSecretsFileContents
        }

        scenario("Single connection") {
            Given("A VPN configuration")
            val (vpn, ike, ipsec, conn) = createService()

            And("Expected configuration")
            val expectedSecrets =
                s"""${vpn.localEndpointIp} ${conn.getPeerAddress} : PSK "${conn.getPsk}"
                   |""".stripMargin
            val expectedConf =
                s"""config setup
                   |    nat_traversal=yes
                   |conn %default
                   |    ikelifetime=480m
                   |    keylife=60m
                   |    keyingtries=%forever
                   |conn ${IPSecConfig.sanitizeName(conn.getName)}
                   |    leftnexthop=%defaultroute
                   |    rightnexthop=%defaultroute
                   |    left=${vpn.localEndpointIp}
                   |    leftid=${vpn.localEndpointIp}
                   |    auto=start
                   |    leftsubnets={ ${conn.getLocalCidr.getAddress}/${conn.getLocalCidr.getPrefixLength} }
                   |    leftupdown="ipsec _updown --route yes"
                   |    right=${conn.getPeerAddress}
                   |    rightid=${conn.getPeerAddress}
                   |    rightsubnets={ ${conn.getPeerCidrs(0).asJava } }
                   |    mtu=${conn.getMtu}
                   |    dpdaction=hold
                   |    dpddelay=${conn.getDpdInterval}
                   |    dpdtimeout=${conn.getDpdTimeout}
                   |    authby=secret
                   |    ikev2=never
                   |    ike=aes128-sha1;modp1536
                   |    ikelifetime=${ike.getLifetimeValue}s
                   |    auth=esp
                   |    phase2alg=aes128-sha1;modp1536
                   |    type=tunnel
                   |    lifetime=${ipsec.getLifetimeValue}s
                   |""".stripMargin

            When("Creating a IPSec configuration")
            val conf = new IPSecConfig("vpn-helper", vpn, Seq(conn))

            Then("The configurations should match")
            expectedConf shouldBe conf.getConfigFileContents
            expectedSecrets shouldBe conf.getSecretsFileContents
        }

        scenario("Multiple connections") {
            Given("A VPN configuration")
            val vpn = IPSecServiceDef(random.nextString(10),
                                      "/opt/stack/stuff",
                                      IPv4Addr.random,
                                      MAC.random().toString,
                                      randomIPv4Subnet,
                                      IPv4Addr.random,
                                      MAC.random().toString)

            val ike = createIkePolicy(
                version = Some(IkeVersion.V2),
                lifetimeValue = Some(random.nextInt()))

            val ipsec = createIpsecPolicy(
                encapsulation = Some(EncapsulationMode.TRANSPORT),
                transform = Some(TransformProtocol.AH_ESP),
                lifetimeValue = Some(random.nextInt()))

            val conn1 = createIpsecSiteConnection(
                auth = Some(AuthMode.PSK),
                dpdAction = Some(DpdAction.CLEAR),
                dpdInterval = Some(random.nextInt()),
                dpdTimeout = Some(random.nextInt()),
                initiator = Some(Initiator.RESPONSE_ONLY),
                name = Some(random.nextString(10)),
                mtu = Some(random.nextInt()),
                peerAddress = Some(IPv4Addr.random.toString),
                psk = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(ike),
                ipsecPolicy = Some(ipsec))
            val conn2 = createIpsecSiteConnection(
                auth = Some(AuthMode.PSK),
                dpdAction = Some(DpdAction.RESTART_BY_PEER),
                dpdInterval = Some(random.nextInt()),
                dpdTimeout = Some(random.nextInt()),
                initiator = Some(Initiator.BI_DIRECTIONAL),
                name = Some(random.nextString(10)),
                mtu = Some(random.nextInt()),
                peerAddress = Some(IPv4Addr.random.toString),
                psk = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(ike),
                ipsecPolicy = Some(ipsec))
            val conn3 = createIpsecSiteConnection(
                auth = Some(AuthMode.PSK),
                dpdAction = Some(DpdAction.RESTART),
                dpdInterval = Some(random.nextInt()),
                dpdTimeout = Some(random.nextInt()),
                initiator = Some(Initiator.BI_DIRECTIONAL),
                name = Some(random.nextString(10)),
                mtu = Some(random.nextInt()),
                peerAddress = Some(IPv4Addr.random.toString),
                psk = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(ike),
                ipsecPolicy = Some(ipsec))

            And("Expected configuration")
            val expectedSecrets =
                s"""${vpn.localEndpointIp} ${conn1.getPeerAddress} : PSK "${conn1.getPsk}"
                   |${vpn.localEndpointIp} ${conn2.getPeerAddress} : PSK "${conn2.getPsk}"
                   |${vpn.localEndpointIp} ${conn3.getPeerAddress} : PSK "${conn3.getPsk}"
                   |""".stripMargin
            val expectedConf =
                s"""config setup
                    |    nat_traversal=yes
                    |conn %default
                    |    ikelifetime=480m
                    |    keylife=60m
                    |    keyingtries=%forever
                    |conn ${IPSecConfig.sanitizeName(conn1.getName)}
                    |    leftnexthop=%defaultroute
                    |    rightnexthop=%defaultroute
                    |    left=${vpn.localEndpointIp}
                    |    leftid=${vpn.localEndpointIp}
                    |    auto=add
                    |    leftsubnets={ ${conn1.getLocalCidr.getAddress}/${conn1.getLocalCidr.getPrefixLength} }
                    |    leftupdown="ipsec _updown --route yes"
                    |    right=${conn1.getPeerAddress}
                    |    rightid=${conn1.getPeerAddress}
                    |    rightsubnets={ ${conn1.getPeerCidrs(0).asJava } }
                    |    mtu=${conn1.getMtu}
                    |    dpdaction=clear
                    |    dpddelay=${conn1.getDpdInterval}
                    |    dpdtimeout=${conn1.getDpdTimeout}
                    |    authby=secret
                    |    ikev2=insist
                    |    ike=aes128-sha1;modp1536
                    |    ikelifetime=${ike.getLifetimeValue}s
                    |    auth=ah-esp
                    |    phase2alg=aes128-sha1;modp1536
                    |    type=transport
                    |    lifetime=${ipsec.getLifetimeValue}s
                    |conn ${IPSecConfig.sanitizeName(conn2.getName)}
                    |    leftnexthop=%defaultroute
                    |    rightnexthop=%defaultroute
                    |    left=${vpn.localEndpointIp}
                    |    leftid=${vpn.localEndpointIp}
                    |    auto=start
                    |    leftsubnets={ ${conn2.getLocalCidr.getAddress}/${conn2.getLocalCidr.getPrefixLength} }
                    |    leftupdown="ipsec _updown --route yes"
                    |    right=${conn2.getPeerAddress}
                    |    rightid=${conn2.getPeerAddress}
                    |    rightsubnets={ ${conn2.getPeerCidrs(0).asJava } }
                    |    mtu=${conn2.getMtu}
                    |    dpdaction=restart-by-peer
                    |    dpddelay=${conn2.getDpdInterval}
                    |    dpdtimeout=${conn2.getDpdTimeout}
                    |    authby=secret
                    |    ikev2=insist
                    |    ike=aes128-sha1;modp1536
                    |    ikelifetime=${ike.getLifetimeValue}s
                    |    auth=ah-esp
                    |    phase2alg=aes128-sha1;modp1536
                    |    type=transport
                    |    lifetime=${ipsec.getLifetimeValue}s
                    |conn ${IPSecConfig.sanitizeName(conn3.getName)}
                    |    leftnexthop=%defaultroute
                    |    rightnexthop=%defaultroute
                    |    left=${vpn.localEndpointIp}
                    |    leftid=${vpn.localEndpointIp}
                    |    auto=start
                    |    leftsubnets={ ${conn3.getLocalCidr.getAddress}/${conn3.getLocalCidr.getPrefixLength} }
                    |    leftupdown="ipsec _updown --route yes"
                    |    right=${conn3.getPeerAddress}
                    |    rightid=${conn3.getPeerAddress}
                    |    rightsubnets={ ${conn3.getPeerCidrs(0).asJava } }
                    |    mtu=${conn3.getMtu}
                    |    dpdaction=restart
                    |    dpddelay=${conn3.getDpdInterval}
                    |    dpdtimeout=${conn3.getDpdTimeout}
                    |    authby=secret
                    |    ikev2=insist
                    |    ike=aes128-sha1;modp1536
                    |    ikelifetime=${ike.getLifetimeValue}s
                    |    auth=ah-esp
                    |    phase2alg=aes128-sha1;modp1536
                    |    type=transport
                    |    lifetime=${ipsec.getLifetimeValue}s
                    |""".stripMargin


            When("Creating a IPSec configuration")
            val conf = new IPSecConfig("vpn-helper", vpn, Seq(conn1, conn2, conn3))

            Then("The configurations should match")
            expectedConf shouldBe conf.getConfigFileContents
            expectedSecrets shouldBe conf.getSecretsFileContents
        }

        scenario("Multiple connections with different adminStateUp") {
            Given("A VPN configuration")
            val vpn = IPSecServiceDef(random.nextString(10),
                                      "/opt/stack/stuff",
                                      IPv4Addr.random,
                                      MAC.random().toString,
                                      randomIPv4Subnet,
                                      IPv4Addr.random,
                                      MAC.random().toString)

            val ike = createIkePolicy(
                version = Some(IkeVersion.V2),
                lifetimeValue = Some(random.nextInt()))

            val ipsec = createIpsecPolicy(
                encapsulation = Some(EncapsulationMode.TRANSPORT),
                transform = Some(TransformProtocol.AH_ESP),
                lifetimeValue = Some(random.nextInt()))

            val conn1 = createIpsecSiteConnection(
                auth = Some(AuthMode.PSK),
                dpdAction = Some(DpdAction.CLEAR),
                dpdInterval = Some(random.nextInt()),
                dpdTimeout = Some(random.nextInt()),
                initiator = Some(Initiator.RESPONSE_ONLY),
                name = Some(random.nextString(10)),
                mtu = Some(random.nextInt()),
                peerAddress = Some(IPv4Addr.random.toString),
                psk = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(ike),
                ipsecPolicy = Some(ipsec))
            val conn2 = createIpsecSiteConnection(
                auth = Some(AuthMode.PSK),
                adminStateUp = Some(false),
                dpdAction = Some(DpdAction.RESTART_BY_PEER),
                dpdInterval = Some(random.nextInt()),
                dpdTimeout = Some(random.nextInt()),
                initiator = Some(Initiator.BI_DIRECTIONAL),
                name = Some(random.nextString(10)),
                mtu = Some(random.nextInt()),
                peerAddress = Some(IPv4Addr.random.toString),
                psk = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(ike),
                ipsecPolicy = Some(ipsec))
            val conn3 = createIpsecSiteConnection(
                auth = Some(AuthMode.PSK),
                adminStateUp = Some(true),
                dpdAction = Some(DpdAction.RESTART),
                dpdInterval = Some(random.nextInt()),
                dpdTimeout = Some(random.nextInt()),
                initiator = Some(Initiator.BI_DIRECTIONAL),
                name = Some(random.nextString(10)),
                mtu = Some(random.nextInt()),
                peerAddress = Some(IPv4Addr.random.toString),
                psk = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(ike),
                ipsecPolicy = Some(ipsec))

            And("Expected configuration")
            val expectedSecrets =
                s"""${vpn.localEndpointIp} ${conn1.getPeerAddress} : PSK "${conn1.getPsk}"
                   |${vpn.localEndpointIp} ${conn3.getPeerAddress} : PSK "${conn3.getPsk}"
                   |""".stripMargin
            val expectedConf =
                s"""config setup
                    |    nat_traversal=yes
                    |conn %default
                    |    ikelifetime=480m
                    |    keylife=60m
                    |    keyingtries=%forever
                    |conn ${IPSecConfig.sanitizeName(conn1.getName)}
                    |    leftnexthop=%defaultroute
                    |    rightnexthop=%defaultroute
                    |    left=${vpn.localEndpointIp}
                    |    leftid=${vpn.localEndpointIp}
                    |    auto=add
                    |    leftsubnets={ ${conn1.getLocalCidr.getAddress}/${conn1.getLocalCidr.getPrefixLength} }
                    |    leftupdown="ipsec _updown --route yes"
                    |    right=${conn1.getPeerAddress}
                    |    rightid=${conn1.getPeerAddress}
                    |    rightsubnets={ ${conn1.getPeerCidrs(0).asJava } }
                    |    mtu=${conn1.getMtu}
                    |    dpdaction=clear
                    |    dpddelay=${conn1.getDpdInterval}
                    |    dpdtimeout=${conn1.getDpdTimeout}
                    |    authby=secret
                    |    ikev2=insist
                    |    ike=aes128-sha1;modp1536
                    |    ikelifetime=${ike.getLifetimeValue}s
                    |    auth=ah-esp
                    |    phase2alg=aes128-sha1;modp1536
                    |    type=transport
                    |    lifetime=${ipsec.getLifetimeValue}s
                    |conn ${IPSecConfig.sanitizeName(conn3.getName)}
                    |    leftnexthop=%defaultroute
                    |    rightnexthop=%defaultroute
                    |    left=${vpn.localEndpointIp}
                    |    leftid=${vpn.localEndpointIp}
                    |    auto=start
                    |    leftsubnets={ ${conn3.getLocalCidr.getAddress}/${conn3.getLocalCidr.getPrefixLength} }
                    |    leftupdown="ipsec _updown --route yes"
                    |    right=${conn3.getPeerAddress}
                    |    rightid=${conn3.getPeerAddress}
                    |    rightsubnets={ ${conn3.getPeerCidrs(0).asJava } }
                    |    mtu=${conn3.getMtu}
                    |    dpdaction=restart
                    |    dpddelay=${conn3.getDpdInterval}
                    |    dpdtimeout=${conn3.getDpdTimeout}
                    |    authby=secret
                    |    ikev2=insist
                    |    ike=aes128-sha1;modp1536
                    |    ikelifetime=${ike.getLifetimeValue}s
                    |    auth=ah-esp
                    |    phase2alg=aes128-sha1;modp1536
                    |    type=transport
                    |    lifetime=${ipsec.getLifetimeValue}s
                    |""".stripMargin


            When("Creating a IPSec configuration")
            val conf = new IPSecConfig("vpn-helper", vpn, Seq(conn1, conn2, conn3))

            Then("The configurations should match")
            expectedConf shouldBe conf.getConfigFileContents
            expectedSecrets shouldBe conf.getSecretsFileContents
        }

        scenario("Whitespaces in names is handled correctly") {
            Given("A VPN configuration")
            val vpn = IPSecServiceDef(random.nextString(10),
                                      "/opt/stack/stuff",
                                      IPv4Addr.random,
                                      MAC.random().toString,
                                      randomIPv4Subnet,
                                      IPv4Addr.random,
                                      MAC.random().toString)

            val ike = createIkePolicy(
                version = Some(IkeVersion.V2),
                lifetimeValue = Some(random.nextInt()))

            val ipsec = createIpsecPolicy(
                encapsulation = Some(EncapsulationMode.TRANSPORT),
                transform = Some(TransformProtocol.AH_ESP),
                lifetimeValue = Some(random.nextInt()))

            val conn1 = createIpsecSiteConnection(
                auth = Some(AuthMode.PSK),
                dpdAction = Some(DpdAction.CLEAR),
                dpdInterval = Some(random.nextInt()),
                dpdTimeout = Some(random.nextInt()),
                initiator = Some(Initiator.RESPONSE_ONLY),
                name = Some("foO Ba434 $%@ "),
                mtu = Some(random.nextInt()),
                peerAddress = Some(IPv4Addr.random.toString),
                psk = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(ike),
                ipsecPolicy = Some(ipsec))


            When("Creating a IPSec configuration")
            val conf = new IPSecConfig("vpn-helper", vpn, Seq(conn1))

            Then("The configurations should match")
            val regexp = "^conn \\w+$".r
            conf.getConfigFileContents.split("\n").count(
                _ match {
                    case regexp() => true
                    case _ => false
                }) shouldBe 1 // %default won't match
        }
    }

    feature("IPSec container script starts and stops") {
        scenario("Commands are executed successfully") {
            Given("A VPN configuration")
            val (vpn, ike, ipsec, conn) = createService()

            And("An IPSec configuration")
            val conf = new IPSecConfig("vpn-helper", vpn, Seq(conn))

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            When("Calling the setup method")
            container.setup(conf)

            Then("The container should execute the setup commands")
            container.commands(0) shouldBe s"vpn-helper prepare"
            container.commands(1) shouldBe s"vpn-helper cleanns -n ${vpn.name}"
            container.commands(2) shouldBe
                s"vpn-helper makens -n ${vpn.name} " +
                s"-g ${vpn.namespaceGatewayIp} " +
                s"-G ${vpn.namespaceGatewayMac} " +
                s"-l ${vpn.localEndpointIp} " +
                s"-i ${vpn.namespaceInterfaceIp} " +
                s"-m ${vpn.localEndpointMac}"
            container.commands(3) shouldBe
                s"vpn-helper start_service -n ${vpn.name} -p ${vpn.filepath}"
            container.commands(4) shouldBe
                s"vpn-helper init_conns -n ${vpn.name} -p ${vpn.filepath} " +
                s"-g ${vpn.namespaceGatewayIp} " +
                s"-c ${IPSecConfig.sanitizeName(conn.getName)}"

            When("Calling the cleanup method")
            container.cleanup(conf)

            Then("The container should execute the cleanup commands")
            container.commands(5) shouldBe
                s"vpn-helper stop_service -n ${vpn.name} -p ${vpn.filepath}"
            container.commands(6) shouldBe
                s"vpn-helper cleanns -n ${vpn.name}"
        }

        scenario("Changes rolled back when creating the namespace fails") {
            Given("A VPN configuration")
            val (vpn, ike, ipsec, conn) = createService()

            And("An IPSec configuration")
            val conf = new IPSecConfig("vpn-helper", vpn, Seq(conn))

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            When("Calling the setup method should fail")
            container.failOn = 3
            intercept[IPSecException] {
                container.setup(conf)
            }

            Then("The container should execute the setup commands")
            container.commands(0) shouldBe s"vpn-helper prepare"
            container.commands(1) shouldBe s"vpn-helper cleanns -n ${vpn.name}"
            container.commands(2) shouldBe
                s"vpn-helper makens -n ${vpn.name} " +
                s"-g ${vpn.namespaceGatewayIp} " +
                s"-G ${vpn.namespaceGatewayMac} " +
                s"-l ${vpn.localEndpointIp} " +
                s"-i ${vpn.namespaceInterfaceIp} " +
                s"-m ${vpn.localEndpointMac}"

            And("The container should execute the cleanup commands")
            container.commands(3) shouldBe
                s"vpn-helper cleanns -n ${vpn.name}"
        }

        scenario("Changes rolled back when starting the service fails") {
            Given("A VPN configuration")
            val (vpn, ike, ipsec, conn) = createService()

            And("An IPSec configuration")
            val conf = new IPSecConfig("vpn-helper", vpn, Seq(conn))

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            When("Calling the setup method should fail")
            container.failOn = 4
            intercept[IPSecException] {
                container.setup(conf)
            }

            Then("The container should execute the setup commands")
            container.commands(0) shouldBe s"vpn-helper prepare"
            container.commands(1) shouldBe s"vpn-helper cleanns -n ${vpn.name}"
            container.commands(2) shouldBe
                s"vpn-helper makens -n ${vpn.name} " +
                s"-g ${vpn.namespaceGatewayIp} " +
                s"-G ${vpn.namespaceGatewayMac} " +
                s"-l ${vpn.localEndpointIp} " +
                s"-i ${vpn.namespaceInterfaceIp} " +
                s"-m ${vpn.localEndpointMac}"
            container.commands(3) shouldBe
                s"vpn-helper start_service -n ${vpn.name} -p ${vpn.filepath}"

            And("The container should execute the cleanup commands")
            container.commands(4) shouldBe
                s"vpn-helper stop_service -n ${vpn.name} -p ${vpn.filepath}"
            container.commands(5) shouldBe
                s"vpn-helper cleanns -n ${vpn.name}"
        }

        scenario("Changes rolled back when initiating the connections fails") {
            Given("A VPN configuration")
            val (vpn, ike, ipsec, conn) = createService()

            And("An IPSec configuration")
            val conf = new IPSecConfig("vpn-helper", vpn, Seq(conn))

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            When("Calling the setup method should fail")
            container.failOn = 5
            intercept[IPSecException] {
                container.setup(conf)
            }

            Then("The container should execute the setup commands")
            container.commands(0) shouldBe s"vpn-helper prepare"
            container.commands(1) shouldBe s"vpn-helper cleanns -n ${vpn.name}"
            container.commands(2) shouldBe
                s"vpn-helper makens -n ${vpn.name} " +
                s"-g ${vpn.namespaceGatewayIp} " +
                s"-G ${vpn.namespaceGatewayMac} " +
                s"-l ${vpn.localEndpointIp} " +
                s"-i ${vpn.namespaceInterfaceIp} " +
                s"-m ${vpn.localEndpointMac}"
            container.commands(3) shouldBe
                s"vpn-helper start_service -n ${vpn.name} -p ${vpn.filepath}"
            container.commands(4) shouldBe
                s"vpn-helper init_conns -n ${vpn.name} -p ${vpn.filepath} " +
                s"-g ${vpn.namespaceGatewayIp} " +
                s"-c ${IPSecConfig.sanitizeName(conn.getName)}"

            And("The container should execute the cleanup commands")
            container.commands(5) shouldBe
                s"vpn-helper stop_service -n ${vpn.name} -p ${vpn.filepath}"
            container.commands(6) shouldBe
                s"vpn-helper cleanns -n ${vpn.name}"
        }
    }

    feature("IPSec container handles the container directory") {
        scenario("Directory created on setup and deleted on cleanup") {
            Given("A VPN configuration")
            val (vpn, ike, ipsec, conn) = createService()

            And("An IPSec configuration")
            val conf = new IPSecConfig("vpn-helper", vpn, Seq(conn))

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            When("Calling the setup method")
            container.setup(conf)

            Then("The directory should be created")
            val rootFile = new File(vpn.filepath)
            rootFile.exists() shouldBe true

            And("The etc directory should be created")
            val etcFile = new File(conf.confDir)
            etcFile.exists() shouldBe true

            When("Calling the cleanup method")
            container.cleanup(conf)

            Then("The directory should be deleted")
            rootFile.exists() shouldBe false
        }

        scenario("Directory cleaned if exists on setup") {
            Given("A VPN configuration")
            val (vpn, ike, ipsec, conn) = createService()

            And("An IPSec configuration")
            val conf = new IPSecConfig("vpn-helper", vpn, Seq(conn))

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            And("An existing directory with a file")
            val directory = new File(vpn.filepath)
            directory.mkdir()
            val tempFilePath = s"${vpn.filepath}/temp.file"
            val tempFile = new File(tempFilePath)
            FileUtils.writeStringToFile(tempFile, "data")

            When("Calling the setup method")
            container.setup(conf)

            Then("The directory should exist")
            val file = new File(vpn.filepath)
            file.exists() shouldBe true

            And("The temporary file should have been deleted")
            tempFile.exists() shouldBe false

            When("Calling the cleanup method")
            container.cleanup(conf)

            Then("The directory should be deleted")
            file.exists() shouldBe false
        }
    }

    feature("IPSec container implements the handler interface") {
        scenario("Container configures the namespace on create") {
            Given("A router with a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portAddress = IPv4Addr.random,
                                        portMac = MAC.random(),
                                        interfaceName = Some("if-eth"))
            vt.store.multi(Seq(CreateOp(router), CreateOp(port)))
            And("A VPN configuration")
            val vpn = createVpnService(
                routerId = Some(router.getId.asJava),
                externalIp = Some(port.getPortAddress.asIPv4Address))
            val conn = createIpsecSiteConnection(
                name = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(createIkePolicy()),
                ipsecPolicy = Some(createIpsecPolicy()),
                vpnServiceId = Some(vpn.getId.asJava))
            vt.store.multi(Seq(CreateOp(vpn), CreateOp(conn)))

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            And("A container port for the VPN service, with admin state DOWN")
            val cp = ContainerPort(portId = port.getId.asJava,
                                   hostId = null,
                                   interfaceName = "if-vpn",
                                   containerId = null,
                                   serviceType = null,
                                   groupId = null,
                                   configurationId = router.getId.asJava)

            When("Calling the create method of the container")
            container.create(cp).await()

            Then("The container should call the setup commands")
            val path =
                s"${FileUtils.getTempDirectoryPath}/${port.getInterfaceName}"
            val namespaceSubnet = new IPv4Subnet(port.getPortAddress.asIPv4Address.next,
                                                 port.getPortSubnet.getPrefixLength)

            container.commands should have size 5
            container.commands(0) shouldBe
                s"/usr/lib/midolman/vpn-helper prepare"
            container.commands(1) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"
            container.commands(2) shouldBe
                s"/usr/lib/midolman/vpn-helper makens " +
                s"-n ${port.getInterfaceName} " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-G ${port.getPortMac} " +
                s"-l ${port.getPortAddress.asIPv4Address} " +
                s"-i $namespaceSubnet " +
                s"-m ${port.getPortMac}"
            container.commands(3) shouldBe
                s"/usr/lib/midolman/vpn-helper start_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(4) shouldBe
                s"/usr/lib/midolman/vpn-helper init_conns " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-c ${IPSecConfig.sanitizeName(conn.getName)}"

            When("Calling the delete method of the container")
            val vpnServiceSubscription = container.vpnServiceSubscription
            container.delete().await()

            Then("The container should call the cleanup commands")
            container.commands(5) shouldBe
                s"/usr/lib/midolman/vpn-helper stop_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(6) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"

            And("The container should unsubscribe from the observable")
            vpnServiceSubscription.isUnsubscribed shouldBe true
            container.vpnServiceSubscription shouldBe null
        }

        scenario("Container reconfigures the namespace on update") {
            Given("A router with a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portAddress = IPv4Addr.random,
                                        portMac = MAC.random(),
                                        interfaceName = Some("if-eth"))
            vt.store.multi(Seq(CreateOp(router), CreateOp(port)))
            And("A VPN configuration")
            var vpn = createVpnService(
                routerId = Some(router.getId.asJava),
                externalIp = Some(port.getPortAddress.asIPv4Address))
            var conn = createIpsecSiteConnection(
                name = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(createIkePolicy()),
                ipsecPolicy = Some(createIpsecPolicy()),
                vpnServiceId = Some(vpn.getId.asJava))
            vt.store.multi(Seq(CreateOp(vpn), CreateOp(conn)))
            vpn = vt.store.get(classOf[VpnService], vpn.getId).await()

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            And("A container port for the VPN service")
            val cp = ContainerPort(portId = port.getId.asJava,
                                   hostId = null,
                                   interfaceName = "if-vpn",
                                   containerId = null,
                                   serviceType = null,
                                   groupId = null,
                                   configurationId = router.getId.asJava)

            When("Calling the create method of the container")
            container.create(cp).await()

            And("The container should call the setup commands")
            val path =
                s"${FileUtils.getTempDirectoryPath}/${port.getInterfaceName}"
            val namespaceSubnet = new IPv4Subnet(port.getPortAddress.asIPv4Address.next,
                                                 port.getPortSubnet.getPrefixLength)

            container.commands should have size 5

            When("Calling the update method of the container")
            container.updated(cp).await()

            Then("The container should call the cleanup and setup commands")
            container.commands should have size 12

            container.commands(5) shouldBe
                s"/usr/lib/midolman/vpn-helper stop_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(6) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"
            container.commands(7) shouldBe
                s"/usr/lib/midolman/vpn-helper prepare"
            container.commands(8) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"
            container.commands(9) shouldBe
                s"/usr/lib/midolman/vpn-helper makens " +
                s"-n ${port.getInterfaceName} " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-G ${port.getPortMac} " +
                s"-l ${port.getPortAddress.asIPv4Address} " +
                s"-i $namespaceSubnet " +
                s"-m ${port.getPortMac}"
            container.commands(10) shouldBe
                s"/usr/lib/midolman/vpn-helper start_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(11) shouldBe
                s"/usr/lib/midolman/vpn-helper init_conns " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-c ${IPSecConfig.sanitizeName(conn.getName)}"

            When("The VpnService is set to admin state DOWN")
            vt.store.update(vpn.toBuilder.setAdminStateUp(false).build())
            vpn = vt.store.get(classOf[VpnService], vpn.getId).await()

            Then("The container is torn down")
            container.commands should have size 14
            container.commands(12) shouldBe
                s"/usr/lib/midolman/vpn-helper stop_service -n ${port.getInterfaceName} -p $path"
            container.commands(13) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns -n ${port.getInterfaceName}"

            When("Resetting the VpnService to admin state UP")
            vt.store.update(vpn.toBuilder.setAdminStateUp(true).build())
            vpn = vt.store.get(classOf[VpnService], vpn.getId).await()

            Then("The container should call the setup commands")
            container.commands should have size 19
            container.commands(14) shouldBe
                s"/usr/lib/midolman/vpn-helper prepare"
            container.commands(15) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns -n ${port.getInterfaceName}"
            container.commands(16) shouldBe
                s"/usr/lib/midolman/vpn-helper makens " +
                s"-n ${port.getInterfaceName} " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-G ${port.getPortMac} " +
                s"-l ${port.getPortAddress.asIPv4Address} " +
                s"-i $namespaceSubnet " +
                s"-m ${port.getPortMac}"
            container.commands(17) shouldBe
                s"/usr/lib/midolman/vpn-helper start_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(18) shouldBe
                s"/usr/lib/midolman/vpn-helper init_conns " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-c ${IPSecConfig.sanitizeName(conn.getName)}"

            When("The ipsec site connection is set to admin state DOWN")
            vt.store.update(conn.toBuilder.setAdminStateUp(false).build())
            conn = vt.store.get(classOf[IPSecSiteConnection], conn.getId).await()

            Then("The container is torn down")
            container.commands should have size 21

            When("Resetting the connection to admin state UP")
            vt.store.update(conn.toBuilder.setAdminStateUp(true).build())
            conn = vt.store.get(classOf[IPSecSiteConnection], conn.getId).await()

            Then("The container should call the setup commands")
            container.commands should have size 26
            container.commands(21) shouldBe
                s"/usr/lib/midolman/vpn-helper prepare"
            container.commands(22) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns -n ${port.getInterfaceName}"
            container.commands(23) shouldBe
                s"/usr/lib/midolman/vpn-helper makens " +
                s"-n ${port.getInterfaceName} " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-G ${port.getPortMac} " +
                s"-l ${port.getPortAddress.asIPv4Address} " +
                s"-i $namespaceSubnet " +
                s"-m ${port.getPortMac}"
            container.commands(24) shouldBe
                s"/usr/lib/midolman/vpn-helper start_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(25) shouldBe
                s"/usr/lib/midolman/vpn-helper init_conns " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-c ${IPSecConfig.sanitizeName(conn.getName)}"

            When("Calling the delete method of the container")
            val vpnServiceSubscription = container.vpnServiceSubscription
            container.delete().await()

            Then("The container should call the cleanup commands")
            container.commands should have size 28
            container.commands(26) shouldBe
                s"/usr/lib/midolman/vpn-helper stop_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(27) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"

            And("The container should unsubscribe from the observable")
            vpnServiceSubscription.isUnsubscribed shouldBe true
            container.vpnServiceSubscription shouldBe null
        }

        scenario("Container reconfigures the namespace on update, starting " +
                 "as adminStateDown") {

            Given("A router with a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portAddress = IPv4Addr.random,
                                        portMac = MAC.random(),
                                        interfaceName = Some("if-eth"))
            vt.store.multi(Seq(CreateOp(router), CreateOp(port)))
            And("A VPN configuration")
            var vpn = createVpnService(
                routerId = Some(router.getId.asJava),
                externalIp = Some(port.getPortAddress.asIPv4Address),
                adminStateUp = Some(false))
            val conn = createIpsecSiteConnection(
                name = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(createIkePolicy()),
                ipsecPolicy = Some(createIpsecPolicy()),
                vpnServiceId = Some(vpn.getId.asJava))
            vt.store.multi(Seq(CreateOp(vpn), CreateOp(conn)))
            vpn = vt.store.get(classOf[VpnService], vpn.getId).await()

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            And("A container port for the VPN service")
            val cp = ContainerPort(portId = port.getId.asJava,
                                   hostId = null,
                                   interfaceName = "if-vpn",
                                   containerId = null,
                                   serviceType = null,
                                   groupId = null,
                                   configurationId = router.getId.asJava)

            When("Calling the create method of the container")
            val namespace = container.create(cp).await()

            Then("The namespace should NOT be created")
            namespace shouldBe None

            And("The container should call the setup commands")
            container.commands shouldBe empty

            When("The VpnService is set to admin state UP")
            vt.store.update(vpn.toBuilder.setAdminStateUp(true).build())
            vpn = vt.store.get(classOf[VpnService], vpn.getId).await()

            Then("The container should call the cleanup and setup commands")
            val path = s"${FileUtils.getTempDirectoryPath}/${port.getInterfaceName}"
            val namespaceSubnet = new IPv4Subnet(port.getPortAddress.asIPv4Address.next,
                                                 port.getPortSubnet.getPrefixLength)

            Then("The container should call the cleanup and setup commands")
            container.commands should have size 5
            container.commands(0) shouldBe
                s"/usr/lib/midolman/vpn-helper prepare"
            container.commands(1) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"
            container.commands(2) shouldBe
                s"/usr/lib/midolman/vpn-helper makens " +
                s"-n ${port.getInterfaceName} " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-G ${port.getPortMac} " +
                s"-l ${port.getPortAddress.asIPv4Address} " +
                s"-i $namespaceSubnet " +
                s"-m ${port.getPortMac}"
            container.commands(3) shouldBe
                s"/usr/lib/midolman/vpn-helper start_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(4) shouldBe
                s"/usr/lib/midolman/vpn-helper init_conns " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-c ${IPSecConfig.sanitizeName(conn.getName)}"

            When("Calling the delete method of the container")
            val vpnServiceSubscription = container.vpnServiceSubscription
            container.delete().await()

            Then("The container should call the cleanup commands")
            container.commands should have size 7

            And("The container should unsubscribe from the observable")
            vpnServiceSubscription.isUnsubscribed shouldBe true
            container.vpnServiceSubscription shouldBe null
        }

        scenario("Container reconfigures the namespace on new ipsec connection") {
            Given("A router with a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portAddress = IPv4Addr.random,
                                        portMac = MAC.random(),
                                        interfaceName = Some("if-eth"))
            vt.store.multi(Seq(CreateOp(router), CreateOp(port)))
            And("A VPN configuration with one ipsec connection")
            val vpn = createVpnService(
                routerId = Some(router.getId.asJava),
                externalIp = Some(port.getPortAddress.asIPv4Address))
            val ike = createIkePolicy()
            val ipsec = createIpsecPolicy()
            var conn1 = createIpsecSiteConnection(
                name = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(ike),
                ipsecPolicy = Some(ipsec),
                vpnServiceId = Some(vpn.getId.asJava))
            vt.store.multi(Seq(CreateOp(vpn), CreateOp(conn1)))

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            And("A container port for the VPN service")
            val cp = ContainerPort(portId = port.getId.asJava,
                                   hostId = null,
                                   interfaceName = "if-vpn",
                                   containerId = null,
                                   serviceType = null,
                                   groupId = null,
                                   configurationId = router.getId.asJava)

            When("Calling the create method of the container")
            container.create(cp).await()

            And("The container should call the setup commands")
            val path =
                s"${FileUtils.getTempDirectoryPath}/${port.getInterfaceName}"
            val namespaceSubnet = new IPv4Subnet(port.getPortAddress.asIPv4Address.next,
                                                 port.getPortSubnet.getPrefixLength)

            container.commands should have size 5

            When("Adding a new ipsec connection to the vpn service")
            val conn2 = createIpsecSiteConnection(
                name = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(ike),
                ipsecPolicy = Some(ipsec),
                vpnServiceId = Some(vpn.getId.asJava))
            vt.store.create(conn2)

            Then("The container should call the cleanup and setup commands")
            container.commands should have size 12

            container.commands(5) shouldBe
                s"/usr/lib/midolman/vpn-helper stop_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(6) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"
            container.commands(7) shouldBe
                s"/usr/lib/midolman/vpn-helper prepare"
            container.commands(8) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"
            container.commands(9) shouldBe
                s"/usr/lib/midolman/vpn-helper makens " +
                s"-n ${port.getInterfaceName} " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-G ${port.getPortMac} " +
                s"-l ${port.getPortAddress.asIPv4Address} " +
                s"-i $namespaceSubnet " +
                s"-m ${port.getPortMac}"
            container.commands(10) shouldBe
                s"/usr/lib/midolman/vpn-helper start_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(11).startsWith(
                s"/usr/lib/midolman/vpn-helper init_conns " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path " +
                s"-g ${port.getPortAddress.asIPv4Address} ") shouldBe true
            container.commands(11).contains(
                s"-c ${IPSecConfig.sanitizeName(conn1.getName)}") shouldBe true
            container.commands(11).contains(
                s"-c ${IPSecConfig.sanitizeName(conn2.getName)}") shouldBe true

            When("The first ipsec connection is set to admin state DOWN")
            vt.store.update(conn1.toBuilder.setAdminStateUp(false).build())
            conn1 = vt.store.get(classOf[IPSecSiteConnection], conn1.getId).await()

            Then("The container is torn down and setup with only one connection")
            container.commands should have size 19
            container.commands(12) shouldBe
                s"/usr/lib/midolman/vpn-helper stop_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(13) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"
            container.commands(14) shouldBe
                s"/usr/lib/midolman/vpn-helper prepare"
            container.commands(15) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"
            container.commands(16) shouldBe
                s"/usr/lib/midolman/vpn-helper makens " +
                s"-n ${port.getInterfaceName} " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-G ${port.getPortMac} " +
                s"-l ${port.getPortAddress.asIPv4Address} " +
                s"-i $namespaceSubnet " +
                s"-m ${port.getPortMac}"
            container.commands(17) shouldBe
                s"/usr/lib/midolman/vpn-helper start_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(18).startsWith(
                s"/usr/lib/midolman/vpn-helper init_conns " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path " +
                s"-g ${port.getPortAddress.asIPv4Address} ") shouldBe true
            container.commands(18).contains(
                s"-c ${IPSecConfig.sanitizeName(conn1.getName)}") shouldBe false
            container.commands(18).contains(
                s"-c ${IPSecConfig.sanitizeName(conn2.getName)}") shouldBe true

            When("Resetting the first ipsec connection to admin state UP")
            vt.store.update(conn1.toBuilder.setAdminStateUp(true).build())
            conn1 = vt.store.get(classOf[IPSecSiteConnection], conn1.getId).await()

            Then("The container is torn down and setup with both connection")
            container.commands should have size 26
            container.commands(19) shouldBe
                s"/usr/lib/midolman/vpn-helper stop_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(20) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"
            container.commands(21) shouldBe
                s"/usr/lib/midolman/vpn-helper prepare"
            container.commands(22) shouldBe
                s"/usr/lib/midolman/vpn-helper cleanns " +
                s"-n ${port.getInterfaceName}"
            container.commands(23) shouldBe
                s"/usr/lib/midolman/vpn-helper makens " +
                s"-n ${port.getInterfaceName} " +
                s"-g ${port.getPortAddress.asIPv4Address} " +
                s"-G ${port.getPortMac} " +
                s"-l ${port.getPortAddress.asIPv4Address} " +
                s"-i $namespaceSubnet " +
                s"-m ${port.getPortMac}"
            container.commands(24) shouldBe
                s"/usr/lib/midolman/vpn-helper start_service " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path"
            container.commands(25).startsWith(
                s"/usr/lib/midolman/vpn-helper init_conns " +
                s"-n ${port.getInterfaceName} " +
                s"-p $path " +
                s"-g ${port.getPortAddress.asIPv4Address} ") shouldBe true
            container.commands(25).contains(
                s"-c ${IPSecConfig.sanitizeName(conn1.getName)}") shouldBe true
            container.commands(25).contains(
                s"-c ${IPSecConfig.sanitizeName(conn2.getName)}") shouldBe true

            When("Calling the delete method of the container")
            val vpnSubscription = container.vpnServiceSubscription
            container.delete().await()

            Then("The container should call the cleanup commands")
            container.commands should have size 28

            And("The container should unsubscribe from the observable")
            vpnSubscription.isUnsubscribed shouldBe true
            container.vpnServiceSubscription shouldBe null
        }

        scenario("Container should fail if router has no external port") {
            Given("A router with a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portMac = MAC.random(),
                                        interfaceName = Some("if-eth"))
            vt.store.multi(Seq(CreateOp(router), CreateOp(port)))
            And("A VPN configuration")
            val vpn = createVpnService(
                routerId = Some(router.getId.asJava),
                externalIp = Some(IPv4Addr.random))
            val conn = createIpsecSiteConnection(
                name = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(createIkePolicy()),
                ipsecPolicy = Some(createIpsecPolicy()),
                vpnServiceId = Some(vpn.getId.asJava))
            vt.store.multi(Seq(CreateOp(vpn), CreateOp(conn)))

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            And("A container port for the VPN service")
            val cp = ContainerPort(portId = port.getId.asJava,
                                   hostId = null,
                                   interfaceName = "if-vpn",
                                   containerId = null,
                                   serviceType = null,
                                   groupId = null,
                                   configurationId = router.getId.asJava)

            When("Calling the create method of the container")
            intercept[IPSecException] {
                container.create(cp).await()
            }

            And("The container should not call the setup commands")
            container.commands should have size 0
        }

        scenario("Container should ignore delete if not started") {
            Given("A container")
            val container = new TestIPSecContainer(vt, executor)

            Then("Calling the delete method of the container should succeed")
            val vpnServiceSubscription = container.vpnServiceSubscription
            container.delete().await()

            And("The container should not call any commands")
            container.commands shouldBe empty

            And("The container should unsubscribe from the observable")
            vpnServiceSubscription shouldBe null
            container.vpnServiceSubscription shouldBe null
        }

        scenario("Container should handle errors on delete") {
            Given("A router with a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portAddress = IPv4Addr.random,
                                        portMac = MAC.random(),
                                        interfaceName = Some("if-eth"))
            vt.store.multi(Seq(CreateOp(router), CreateOp(port)))
            And("A VPN configuration")
            val vpn = createVpnService(
                routerId = Some(router.getId.asJava),
                externalIp = Some(port.getPortAddress.asIPv4Address))
            val conn = createIpsecSiteConnection(
                name = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(createIkePolicy()),
                ipsecPolicy = Some(createIpsecPolicy()),
                vpnServiceId = Some(vpn.getId.asJava))
            vt.store.multi(Seq(CreateOp(vpn), CreateOp(conn)))

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            And("A container port for the VPN service")
            val cp = ContainerPort(portId = port.getId.asJava,
                                   hostId = null,
                                   interfaceName = "if-vpn",
                                   containerId = null,
                                   serviceType = null,
                                   groupId = null,
                                   configurationId = router.getId.asJava)

            And("Calling the create method of the container")
            container.create(cp).await()

            Then("Calling the delete method while a command should fail")
            container.throwOn = 6
            val vpnServiceSubscription = container.vpnServiceSubscription
            intercept[Exception] {
                container.delete().await()
            }

            And("The container should unsubscribe from the observable")
            vpnServiceSubscription.isUnsubscribed shouldBe false
            container.vpnServiceSubscription should not be null

            And("A second attempt that does not fail should succeed")
            container.delete().await()
            container.commands should have size 8

            And("The container should unsubscribe from the observable")
            vpnServiceSubscription.isUnsubscribed shouldBe true
            container.vpnServiceSubscription shouldBe null
        }

        scenario("Container should notify the running state") {
            Given("A router with a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portAddress = IPv4Addr.random,
                                        portMac = MAC.random(),
                                        interfaceName = Some("if-eth"))
            vt.store.multi(Seq(CreateOp(router), CreateOp(port)))
            And("A VPN configuration")
            val vpn = createVpnService(
                routerId = Some(router.getId.asJava),
                externalIp = Some(port.getPortAddress.asIPv4Address))
            val conn = createIpsecSiteConnection(
                name = Some(random.nextString(10)),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(createIkePolicy()),
                ipsecPolicy = Some(createIpsecPolicy()),
                vpnServiceId = Some(vpn.getId.asJava))
            vt.store.multi(Seq(CreateOp(vpn), CreateOp(conn)))

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            And("A container port for the VPN service")
            val cp = ContainerPort(portId = port.getId.asJava,
                                   hostId = null,
                                   interfaceName = "if-vpn",
                                   containerId = null,
                                   serviceType = null,
                                   groupId = null,
                                   configurationId = router.getId.asJava)

            And("A container health observer subscribed to the container")
            val obs = new TestObserver[ContainerHealth]()
            container.health subscribe obs

            When("The container has started")
            container.create(cp).await()

            Then("The container should report running")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe ContainerHealth(Code.RUNNING, "if-eth")

            val vpnServiceSubscription = container.vpnServiceSubscription
            container.delete().await()

            Then("The container should call the cleanup commands")
            container.commands should have size 7

            And("The container should unsubscribe from the observable")
            vpnServiceSubscription.isUnsubscribed shouldBe true
            container.vpnServiceSubscription shouldBe null
        }


        scenario("Container starts, event with weird name") {
            Given("A router with a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portAddress = IPv4Addr.random,
                                        portMac = MAC.random(),
                                        interfaceName = Some("if-eth"))
            vt.store.multi(Seq(CreateOp(router), CreateOp(port)))
            And("A VPN configuration")
            var vpn = createVpnService(
                routerId = Some(router.getId.asJava),
                externalIp = Some(port.getPortAddress.asIPv4Address),
                adminStateUp = Some(true))
            val conn = createIpsecSiteConnection(
                name = Some("foo@#1z s$%^@___"),
                localCidr = Some(randomIPv4Subnet),
                peerCidrs = Seq(randomIPv4Subnet),
                ikePolicy = Some(createIkePolicy()),
                ipsecPolicy = Some(createIpsecPolicy()),
                vpnServiceId = Some(vpn.getId.asJava))
            vt.store.multi(Seq(CreateOp(vpn), CreateOp(conn)))
            vpn = vt.store.get(classOf[VpnService], vpn.getId).await()

            And("A container")
            val container = new TestIPSecContainer(vt, executor)

            And("A container port for the VPN service")
            val cp = ContainerPort(portId = port.getId.asJava,
                                   hostId = null,
                                   interfaceName = "if-vpn",
                                   containerId = null,
                                   serviceType = null,
                                   groupId = null,
                                   configurationId = router.getId.asJava)

            When("Calling the create method of the container")
            container.create(cp).await()

            Then("The container should call the cleanup and setup commands")
            container.commands should have size 5
            val regexp = "^[\\w_]+$".r
            val splits = container.commands(4).split(" ")
            splits.size shouldBe 10
            regexp.pattern.matcher(splits(9)).matches shouldBe true

            When("Calling the delete method of the container")
            val vpnServiceSubscription = container.vpnServiceSubscription
            container.delete().await()

            Then("The container should call the cleanup commands")
            container.commands should have size 7

            And("The container should unsubscribe from the observable")
            vpnServiceSubscription.isUnsubscribed shouldBe true
            container.vpnServiceSubscription shouldBe null
        }

    }
}
