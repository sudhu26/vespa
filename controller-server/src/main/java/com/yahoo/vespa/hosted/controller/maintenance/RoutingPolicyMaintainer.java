// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.RotationName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.log.LogLevel;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.LoadBalancer;
import com.yahoo.vespa.hosted.controller.api.integration.dns.AliasTarget;
import com.yahoo.vespa.hosted.controller.api.integration.dns.Record;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordData;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordName;
import com.yahoo.vespa.hosted.controller.application.Endpoint;
import com.yahoo.vespa.hosted.controller.application.RoutingId;
import com.yahoo.vespa.hosted.controller.application.RoutingPolicy;
import com.yahoo.vespa.hosted.controller.dns.NameServiceForwarder;
import com.yahoo.vespa.hosted.controller.dns.NameServiceQueue.Priority;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Maintains DNS records as defined by routing policies for all exclusive load balancers in this system.
 *
 * @author mortent
 * @author mpolden
 */
public class RoutingPolicyMaintainer extends Maintainer {

    private static final Logger log = Logger.getLogger(RoutingPolicyMaintainer.class.getName());

    private final NameServiceForwarder nameServiceForwarder;
    private final CuratorDb db;

    public RoutingPolicyMaintainer(Controller controller,
                                   Duration interval,
                                   JobControl jobControl,
                                   CuratorDb db) {
        super(controller, interval, jobControl);
        this.nameServiceForwarder = controller.nameServiceForwarder();
        this.db = db;
    }

    @Override
    protected void maintain() {
        Map<DeploymentId, List<LoadBalancer>> loadBalancers = findLoadBalancers();
        removeObsoleteAliases(loadBalancers);
        registerCnames(loadBalancers);
        removeObsoleteCnames(loadBalancers);
        registerAliases();
    }

    /** Find all exclusive load balancers in this system, grouped by deployment */
    private Map<DeploymentId, List<LoadBalancer>> findLoadBalancers() {
        Map<DeploymentId, List<LoadBalancer>> result = new LinkedHashMap<>();
        for (ZoneId zone : controller().zoneRegistry().zones().controllerUpgraded().ids()) {
            List<LoadBalancer> loadBalancers = controller().applications().configServer().getLoadBalancers(zone);
            for (LoadBalancer loadBalancer : loadBalancers) {
                DeploymentId deployment = new DeploymentId(loadBalancer.application(), zone);
                result.compute(deployment, (k, existing) -> {
                    if (existing == null) {
                        existing = new ArrayList<>();
                    }
                    existing.add(loadBalancer);
                    return existing;
                });
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** Create aliases (global rotations) for all current routing policies */
    private void registerAliases() {
        try (Lock lock = db.lockRoutingPolicies()) {
            Map<RoutingId, List<RoutingPolicy>> routingTable = routingTableFrom(db.readRoutingPolicies());

            // Create DNS record for each routing ID
            for (Map.Entry<RoutingId, List<RoutingPolicy>> route : routingTable.entrySet()) {
                Endpoint endpoint = RoutingPolicy.endpointOf(route.getKey().application(), route.getKey().rotation(),
                                                             controller().system());
                Set<AliasTarget> targets = route.getValue()
                                                .stream()
                                                .filter(policy -> policy.dnsZone().isPresent())
                                                .map(policy -> new AliasTarget(policy.canonicalName(),
                                                                               policy.dnsZone().get(),
                                                                               policy.zone()))
                                                .collect(Collectors.toSet());
                try {
                    nameServiceForwarder.createAlias(RecordName.from(endpoint.dnsName()), targets, Priority.normal);
                } catch (Exception e) {
                    log.log(LogLevel.WARNING, "Failed to create or update DNS record for global rotation " +
                                              endpoint.dnsName() + ". Retrying in " + maintenanceInterval(), e);
                }
            }
        }
    }

    /** Create CNAME records for each individual load balancers */
    private void registerCnames(Map<DeploymentId, List<LoadBalancer>> loadBalancers) {
        for (Map.Entry<DeploymentId, List<LoadBalancer>> entry : loadBalancers.entrySet()) {
            ApplicationId application = entry.getKey().applicationId();
            ZoneId zone = entry.getKey().zoneId();
            try (Lock lock = db.lockRoutingPolicies()) {
                Set<RoutingPolicy> policies = new LinkedHashSet<>(db.readRoutingPolicies(application));
                for (LoadBalancer loadBalancer : entry.getValue()) {
                    try {
                        RoutingPolicy policy = registerCname(application, zone, loadBalancer);
                        if (!policies.add(policy)) {
                            policies.remove(policy);
                            policies.add(policy);
                        }
                    } catch (Exception e) {
                        log.log(LogLevel.WARNING, "Failed to create or update DNS record for load balancer " +
                                                  loadBalancer.hostname() + ". Retrying in " + maintenanceInterval(),
                                e);
                    }
                }
                db.writeRoutingPolicies(application, policies);
            }
        }
    }

    /** Register DNS alias for given load balancer */
    private RoutingPolicy registerCname(ApplicationId application, ZoneId zone, LoadBalancer loadBalancer) {
        RoutingPolicy routingPolicy = new RoutingPolicy(application, loadBalancer.cluster(), zone,
                                                        loadBalancer.hostname(), loadBalancer.dnsZone(),
                                                        loadBalancer.rotations());
        RecordName name = RecordName.from(routingPolicy.endpointIn(controller().system()).dnsName());
        RecordData data = RecordData.fqdn(loadBalancer.hostname().value());
        nameServiceForwarder.createCname(name, data, Priority.normal);
        return routingPolicy;
    }

    /** Remove all DNS records that point to non-existing load balancers */
    private void removeObsoleteCnames(Map<DeploymentId, List<LoadBalancer>> loadBalancers) {
        try (Lock lock = db.lockRoutingPolicies()) {
            List<RoutingPolicy> removalCandidates = new ArrayList<>(db.readRoutingPolicies());
            Set<HostName> activeLoadBalancers = loadBalancers.values().stream()
                                                             .flatMap(Collection::stream)
                                                             .map(LoadBalancer::hostname)
                                                             .collect(Collectors.toSet());

            // Remove any active load balancers
            removalCandidates.removeIf(policy -> activeLoadBalancers.contains(policy.canonicalName()));
            for (RoutingPolicy policy : removalCandidates) {
                String dnsName = policy.endpointIn(controller().system()).dnsName();
                nameServiceForwarder.removeRecords(Record.Type.CNAME, RecordName.from(dnsName), Priority.normal);
            }
        }
    }

    /** Remove global rotations that are not referenced by given load balancers */
    private void removeObsoleteAliases(Map<DeploymentId, List<LoadBalancer>> loadBalancers) {
        try (Lock lock = db.lockRoutingPolicies()) {
            Set<RoutingId> removalCandidates = routingTableFrom(db.readRoutingPolicies()).keySet();
            Set<RoutingId> activeRoutingIds = routingIdsFrom(loadBalancers);
            removalCandidates.removeAll(activeRoutingIds);
            for (RoutingId id : removalCandidates) {
                Endpoint endpoint = RoutingPolicy.endpointOf(id.application(), id.rotation(), controller().system());
                nameServiceForwarder.removeRecords(Record.Type.ALIAS, RecordName.from(endpoint.dnsName()), Priority.normal);
            }
        }
    }

    /** Compute routing IDs from given load balancers */
    private static Set<RoutingId> routingIdsFrom(Map<DeploymentId, List<LoadBalancer>> loadBalancers) {
        Set<RoutingId> routingIds = new LinkedHashSet<>();
        for (List<LoadBalancer> values : loadBalancers.values()) {
            for (LoadBalancer loadBalancer : values) {
                for (RotationName rotation : loadBalancer.rotations()) {
                    routingIds.add(new RoutingId(loadBalancer.application(), rotation));
                }
            }
        }
        return Collections.unmodifiableSet(routingIds);
    }

    /** Compute a routing table from given policies. */
    private static Map<RoutingId, List<RoutingPolicy>> routingTableFrom(Set<RoutingPolicy> routingPolicies) {
        Map<RoutingId, List<RoutingPolicy>> routingTable = new LinkedHashMap<>();
        for (RoutingPolicy policy : routingPolicies) {
            for (RotationName rotation : policy.rotations()) {
                RoutingId id = new RoutingId(policy.owner(), rotation);
                routingTable.compute(id, (k, policies) -> {
                    if (policies == null) {
                        policies = new ArrayList<>();
                    }
                    policies.add(policy);
                    return policies;
                });
            }
        }
        return routingTable;
    }

}
