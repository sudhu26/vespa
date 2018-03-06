package com.yahoo.vespa.hosted.node.admin.task.util.network;

import com.google.common.net.InetAddresses;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * IP addresses - IP utilities to retrieve and manipulate addresses for docker host and docker containers in a
 * multi-home environment.
 *
 * The assumption is that DNS is the source of truth for which address are assigned to the host and which
 * that belongs to the containers. Only one address should be assigned to each.
 *
 * The behavior with respect to site-local addresses are distinct for IPv4 and IPv6. For IPv4 we choose
 * the site-local address (assume the public is a NAT address not assigned to the host interface (the typical aws setup)).
 *
 * For IPv6 we disregard any site-local addresses (these are normally not in DNS anyway).
 *
 * This class also provides some utilities for prefix translation.
 *
 * @author smorgrav
 */
public interface IPAddresses {

    InetAddress[] getAddresses(String hostname);

    default List<String> getAddresses(String hostname, IPVersion ipVersion) {
        return Stream.of(getAddresses(hostname))
                .filter(inetAddress -> isOfType(inetAddress, ipVersion))
                .map(InetAddresses::toAddrString)
                .collect(Collectors.toList());
    }

    /**
     * Get the IPv6 address for the host if any.
     *
     * @throws RuntimeException if multiple addresses are found
     * @return An optional string representation of the IP address (RFC 5952 compact format)
     */
    default Optional<Inet6Address> getIPv6Address(String hostname) {
        List<InetAddress> ipv6addresses = Stream.of(getAddresses(hostname))
                .filter(inetAddress -> inetAddress instanceof Inet6Address)
                .filter(inetAddress -> !inetAddress.isLinkLocalAddress())
                .filter(inetAddress -> !inetAddress.isSiteLocalAddress())
                .collect(Collectors.toList());

        if (ipv6addresses.isEmpty()) {
            return Optional.empty();
        }

        if (ipv6addresses.size() > 1) {
            String addresses =
                    ipv6addresses.stream().map(InetAddresses::toAddrString).collect(Collectors.joining(","));
            throw new RuntimeException(
                    String.format(
                            "Multiple IPv6 addresses found: %s. Perhaps a missing DNS entry or multiple AAAA records in DNS?",
                            addresses));
        }

        return Optional.of((Inet6Address)ipv6addresses.get(0));
    }

    /**
     * Get the IPv4 address for the host if any.
     *
     * @throws RuntimeException if multiple site-local addresses are found
     * @return An optional string representation of the IP address
     */
    default Optional<Inet4Address> getIPv4Address(String hostname) {
        InetAddress[] allAddresses = getAddresses(hostname);

        List<InetAddress> ipv4Addresses = Stream.of(allAddresses)
                .filter(inetAddress -> inetAddress instanceof Inet4Address)
                .collect(Collectors.toList());

        if (ipv4Addresses.size() == 1) return Optional.of((Inet4Address)ipv4Addresses.get(0));

        if (ipv4Addresses.isEmpty()) {
            Optional.empty();
        }

        List<InetAddress> siteLocalIPv4Addresses = Stream.of(allAddresses)
                .filter(inetAddress -> inetAddress instanceof Inet4Address)
                .filter(InetAddress::isSiteLocalAddress)
                .collect(Collectors.toList());

        if (siteLocalIPv4Addresses.size() == 1) return Optional.of((Inet4Address)siteLocalIPv4Addresses.get(0));

        String addresses =
                ipv4Addresses.stream().map(InetAddresses::toAddrString).collect(Collectors.joining(","));
        throw new RuntimeException(
                String.format(
                        "Multiple IPv4 addresses found: %s. Perhaps a missing DNS entry or multiple A records in DNS?",
                        addresses));
    }

    static boolean isOfType(InetAddress address, IPVersion ipVersion) {
        if (ipVersion.equals(IPVersion.IPv4) && address instanceof Inet4Address) return true;
        if (ipVersion.equals(IPVersion.IPv6) && address instanceof Inet6Address) return true;
        return false;
    }

    /**
     * For NPTed networks we want to find the private address from a public.
     *
     * @param address    The original address to translate
     * @param prefix     The prefix address
     * @param subnetSizeInBytes in bits - e.g a /64 subnet equals 8 bytes
     * @return The translated address
     */
    static InetAddress prefixTranslate(InetAddress address, InetAddress prefix, int subnetSizeInBytes) {
        return prefixTranslate(address.getAddress(), prefix.getAddress(), subnetSizeInBytes);
    }

    static InetAddress prefixTranslate(byte[] address, byte[] prefix, int nofBytes) {
        System.arraycopy(prefix, 0, address, 0, nofBytes);
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }
}
