/*
 * Copyright 2020-present Open Networking Foundation
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
package nctu.pncourse.bridge;

import com.google.common.collect.ImmutableSet;
import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Properties;

import static org.onlab.util.Tools.get;

// Imported be coder
import com.google.common.collect.Maps;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onlab.packet.Ethernet;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.host.HostService;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.PortNumber;
import org.onosproject.net.ConnectPoint;


import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;

import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;

import org.onosproject.net.flowobjective.FlowObjectiveService;
import java.util.*;
// import javafx.util.Pair;

import org.onlab.packet.MacAddress;
/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true/*,
           service = {SomeInterface.class},
            property = {
               "someProperty=Some Default String Value",
           } */)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private ReactivePacketProcessor processor = new ReactivePacketProcessor();
    private ApplicationId appId;

    protected Map<DeviceId, Map<MacAddress, PortNumber>> macTables = Maps.newConcurrentMap();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    /** Some configurable property. */    
    // private String someProperty;

    // @Reference(cardinality = ReferenceCardinality.MANDATORY)
    // protected ComponentConfigService cfgService;

    @Activate
    protected void activate() {
        // cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("nctu.pncourse.demo");
        packetService.addProcessor(processor, PacketProcessor.director(2));  
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4).matchEthType(Ethernet.TYPE_ARP); 

        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
        log.info("GoGo");
    }

    @Deactivate
    protected void deactivate() {
        // cfgService.unregisterProperties(getClass(), false);
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }

    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) 
                return;

            // To simplify parameter's length
            Ethernet ethPkt = context.inPacket().parsed();
            if (ethPkt == null)
                return;
            ConnectPoint cp = context.inPacket().receivedFrom();
            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();

            // log.info("Daniel src MAC: " + srcMac);
            // log.info("Daniel dst MAC: " + dstMac);

            // Create new device if it is not in mactables
            macTables.putIfAbsent(cp.deviceId(), Maps.newConcurrentMap());
            Map<MacAddress, PortNumber> macTable = macTables.get(cp.deviceId());

            // Put input mac and its port into the current device
            // log.info("Daniel Put DeviceId Src, port: " + cp.deviceId() + " " + srcMac + " "+ cp.port());
            macTable.putIfAbsent(srcMac, cp.port());

            PortNumber outPort = macTable.get(dstMac);


            if (outPort == null) {
                if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
                    flood(context);
                    return;
                }
                else {
                    log.info("Daniel: outPort not found");
                    flood(context);
                    return;
                }
            }
            else {
                log.info("Daniel: outPort found");
                installRule(context, srcMac, dstMac, outPort);
                packetOut(context, outPort);
                return;
            }
        }
    

        // It should be correct
        public void flood(PacketContext context) {
            if (topologyService.isBroadcastPoint(topologyService.currentTopology(), context.inPacket().receivedFrom()))
                packetOut(context, PortNumber.FLOOD);
            else
                context.block();
        }

        // It should be correct
        public void packetOut(PacketContext context, PortNumber portNumber) {
            context.treatmentBuilder().setOutput(portNumber);
            context.send();
        }

        public void installRule(PacketContext context, MacAddress srcMac, MacAddress dstMac, PortNumber outPort) {

            // If output mac address match
            TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
            selectorBuilder.matchEthDst(dstMac);


            // Send to known output port
            TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(outPort).build();

            log.info("Daniel apply to: " + context.inPacket().receivedFrom().deviceId());
            // Add flow rule to device
            FlowRule fr = DefaultFlowRule.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .forDevice(context.inPacket().receivedFrom().deviceId())
                .withPriority(PacketPriority.REACTIVE.priorityValue())
                .makeTemporary(60)
                .fromApp(appId).build();
            log.info("Daniel build complete");
            flowRuleService.applyFlowRules(fr);
            log.info("Daniel apply complete");
        }
    }
}

    // @Modified
    // public void modified(ComponentContext context) {
    //     Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
    //     if (context != null) {
    //         someProperty = get(properties, "someProperty");
    //     }
    //     log.info("Reconfigured");
    // }

    // @Override
    // public void someMethod() {
    //     log.info("Invoked");
    // }

