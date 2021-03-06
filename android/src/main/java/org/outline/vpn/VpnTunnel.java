// Copyright 2018 The Outline Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.outline.vpn;

import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.net.VpnService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Random;
import org.outline.tun2socks.Tun2SocksJni;
import com.htmake.tun2http.Tun2HttpJni;
import android.util.Base64;
import java.io.UnsupportedEncodingException;

/**
 * Manages the life-cycle of the system VPN, and of the tunnel that processes its traffic.
 */
public class VpnTunnel {
  private static final Logger LOG = Logger.getLogger(VpnTunnel.class.getName());

  private static final String VPN_INTERFACE_PRIVATE_LAN = "10.111.222.%s";
  private static final int VPN_INTERFACE_PREFIX_LENGTH = 24;
  private static final String VPN_INTERFACE_NETMASK = "255.255.255.0";
  private static final String VPN_IPV6_NULL = null;  // No IPv6 support.
  private static final int VPN_INTERFACE_MTU = 1500;
  private static final int VPN_INTERFACE_HTTP_MTU = 10000;
  // OpenDNS, Cloudflare, and Quad9 DNS resolvers' IP addresses.
  private static final String[] DNS_RESOLVER_IP_ADDRESSES = {
      "208.67.222.222", "208.67.220.220", "1.1.1.1", "9.9.9.9"};
  private static final String PRIVATE_LAN_BYPASS_SUBNETS_ID = "reserved_bypass_subnets";
  private static final int DNS_RESOLVER_PORT = 53;
  private static final int TRANSPARENT_DNS_ENABLED = 1;
  private static final int SOCKS5_UDP_ENABLED = 1;
  private static final int SOCKS5_UDP_DISABLED = 0;

  private final VpnTunnelService vpnService;
  private String dnsResolverAddress;
  private ParcelFileDescriptor tunFd;
  private Thread tun2socksThread = null;
  private int tunnelType = 0;
  public static final int TUNNEL_TYPE_SOCKS = 0;
  public static final int TUNNEL_TYPE_HTTP = 1;
  private List<String> tunnelRoutes = null;
  private boolean mergeDefaultRoute = true;

  /**
   * Constructor.
   *
   * @param vpnService (required) service to access system VPN APIs.
   * @throws IllegalArgumentException if |vpnService| is null.
   */
  public VpnTunnel(VpnTunnelService vpnService) {
    if (vpnService == null) {
      throw new IllegalArgumentException("Must provide a VPN service instance");
    }
    this.vpnService = vpnService;
  }

  public synchronized void setTunnelType(int type) {
    tunnelType = type;
  }

  public synchronized void setTunnelRoutes(List<String> routes) {
    tunnelRoutes = routes;
  }

  public synchronized void setMergeDefaultRoute(boolean merge) {
    mergeDefaultRoute = merge;
  }

  public synchronized boolean establishVpn() {
    String dnsServer = selectDnsResolverAddress();
    ArrayList<String> applicationPackageList = new ArrayList<>();
    applicationPackageList.add(vpnService.getPackageName());
    return this.establishVpn(dnsServer, false, applicationPackageList);
  }

  public synchronized boolean establishVpn(final String dnsServer) {
    ArrayList<String> applicationPackageList = new ArrayList<>();
    applicationPackageList.add(vpnService.getPackageName());
    return this.establishVpn(dnsServer, false, applicationPackageList);
  }

  public synchronized boolean establishVpn(boolean isAllow, List<String> applicationPackageList) {
    String dnsServer = selectDnsResolverAddress();
    return this.establishVpn(dnsServer, isAllow, applicationPackageList);
  }

  /**
   * Establishes a system-wide VPN that routes all device traffic to its TUN interface. Randomly
   * selects between OpenDNS and Dyn resolvers to set the VPN's DNS resolvers.
   *
   * @return boolean indicating whether the VPN was successfully established.
   */
  public synchronized boolean establishVpn(final String dnsServer, boolean isAllow, List<String> applicationPackageList) {
    LOG.info("Establishing the VPN.");
    try {
      dnsResolverAddress = dnsServer;
      VpnService.Builder builder =
          vpnService.newBuilder()
              .setSession(vpnService.getApplicationName())
              .setMtu(tunnelType == TUNNEL_TYPE_SOCKS ? VPN_INTERFACE_MTU : VPN_INTERFACE_HTTP_MTU)
              .addAddress(String.format(Locale.ROOT, VPN_INTERFACE_PRIVATE_LAN, "1"),
                  VPN_INTERFACE_PREFIX_LENGTH)
              .addDnsServer(dnsResolverAddress);
              // .addDisallowedApplication(vpnService.getPackageName());

      final String currentPackageName = vpnService.getPackageName();
      boolean isGlobal = true;
      for(String packageName : applicationPackageList) {
        if (!packageName.equals(currentPackageName)) {
          isGlobal = false;
          if (isAllow) {
            LOG.info(String.format(Locale.ROOT, "addAllowedApplication: %s", packageName));
            builder.addAllowedApplication(packageName);
          } else {
            LOG.info(String.format(Locale.ROOT, "addDisallowedApplication: %s", packageName));
            builder.addDisallowedApplication(packageName);
          }
        }
      }

      if (!isAllow) {
        LOG.info(String.format(Locale.ROOT, "addDisallowedApplication: %s", currentPackageName));
        builder.addDisallowedApplication(currentPackageName);
      }

      if (isGlobal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        final Network activeNetwork =
            vpnService.getSystemService(ConnectivityManager.class).getActiveNetwork();
        builder.setUnderlyingNetworks(new Network[] {activeNetwork});
      }
      // In absence of an API to remove routes, instead of adding the default route (0.0.0.0/0),
      // retrieve the list of subnets that excludes those reserved for special use.
      final ArrayList<Subnet> reservedBypassSubnets = getReservedBypassSubnets();
      for (Subnet subnet : reservedBypassSubnets) {
        builder.addRoute(subnet.address, subnet.prefix);
      }
      tunFd = builder.establish();
      return tunFd != null;
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Failed to establish the VPN", e);
    }
    return false;
  }

  /* Stops routing device traffic through the VPN. */
  public synchronized void tearDownVpn() {
    LOG.info("Tearing down the VPN.");
    if (tunFd == null) {
      return;
    }
    try {
      tunFd.close();
    } catch (IOException e) {
      LOG.severe("Failed to close the VPN interface file descriptor.");
    } finally {
      tunFd = null;
    }
  }

  /**
   * Connects a tunnel between a HTTPProxy server and the VPN TUN interface, by using the tun2http
   * native library.
   *
   * @param proxyIp IP address of the HTTPProxy server.
   * @param proxyPort port of the HTTPProxy server.
   * @param remoteUdpForwardingEnabled whether the remote server supports UDP forwarding.
   * @throws IllegalArgumentException if |socksServerAddress| is null.
   * @throws IllegalStateException if the VPN has not been established, or the tunnel is already
   *     connected.
   */
  public synchronized void connectTunnel(
      final String proxyIp, int proxyPort, boolean remoteUdpForwardingEnabled, String username, String password) {
    LOG.info("Connecting the tunnel.");
    if (proxyIp == null) {
      throw new IllegalArgumentException("Must provide an IP address to a HTTPProxy server.");
    }
    if (proxyPort <= 0) {
      throw new IllegalArgumentException("HTTPProxy server port is invalid.");
    }
    if (tunFd == null) {
      throw new IllegalStateException("Must establish the VPN before connecting the tunnel.");
    }

    LOG.fine("Starting tun2http thread");

    String proxyAuth = "";
    if (username != null && password != null) {
      // try {
        proxyAuth = String.format(Locale.ROOT, "%s:%s", username, password);
        // proxyAuth = Base64.encodeToString(proxyAuth.getBytes("UTF-8"), Base64.DEFAULT);
      // } catch (UnsupportedEncodingException e) {
      //   e.printStackTrace();
      // }
    }

    Tun2HttpJni.start(tunFd.getFd(), remoteUdpForwardingEnabled, 3, proxyIp, proxyPort, vpnService, proxyAuth, vpnService.isApkInDebug() ? -1 : 0);
  }

  /**
   * Connects a tunnel between a SOCKS server and the VPN TUN interface, by using the tun2socks
   * native library.
   *
   * @param socksServerAddress IP address of the SOCKS server.
   * @param remoteUdpForwardingEnabled whether the remote server supports UDP forwarding.
   * @throws IllegalArgumentException if |socksServerAddress| is null.
   * @throws IllegalStateException if the VPN has not been established, or the tunnel is already
   *     connected.
   */
  public synchronized void connectTunnel(
      final String socksServerAddress, boolean remoteUdpForwardingEnabled) {
    LOG.info("Connecting the tunnel.");
    if (socksServerAddress == null) {
      throw new IllegalArgumentException("Must provide an IP address to a SOCKS server.");
    }
    if (tunFd == null) {
      throw new IllegalStateException("Must establish the VPN before connecting the tunnel.");
    }
    if (tun2socksThread != null) {
      throw new IllegalStateException("Tunnel already connected");
    }

    LOG.fine("Starting tun2socks thread");
    tun2socksThread =
        new Thread() {
          public void run() {
            Tun2SocksJni.start(tunFd.getFd(), VPN_INTERFACE_MTU,
                String.format(Locale.ROOT, VPN_INTERFACE_PRIVATE_LAN, "2"), // Router IP address
                VPN_INTERFACE_NETMASK, VPN_IPV6_NULL, socksServerAddress,
                remoteUdpForwardingEnabled ? socksServerAddress : null, // UDP relay IP address
                remoteUdpForwardingEnabled
                    ? String.format(Locale.ROOT, "%s:%d", dnsResolverAddress, DNS_RESOLVER_PORT)
                    : null,
                TRANSPARENT_DNS_ENABLED,
                remoteUdpForwardingEnabled ? SOCKS5_UDP_ENABLED : SOCKS5_UDP_DISABLED);
          }
        };
    tun2socksThread.start();
  }

  /* Disconnects a tunnel created by a previous call to |connectTunnel|. */
  public synchronized void disconnectTunnel() {
    LOG.info("Disconnecting the tunnel.");
    if (tunnelType == TUNNEL_TYPE_HTTP) {
      // 断开 tun2http
      Tun2HttpJni.stop();
      return;
    }
    if (tun2socksThread == null) {
      return;
    }
    try {
      Tun2SocksJni.stop();
      tun2socksThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      tun2socksThread = null;
    }
  }

  /* Returns a random IP address from |DNS_RESOLVER_IP_ADDRESSES|. */
  private String selectDnsResolverAddress() {
    return DNS_RESOLVER_IP_ADDRESSES[new Random().nextInt(DNS_RESOLVER_IP_ADDRESSES.length)];
  }

  private ArrayList<Subnet> getRouteSubnets() {
    ArrayList<Subnet> subnets = new ArrayList<>();
    for (final String route : tunnelRoutes) {
      try {
        subnets.add(Subnet.parse(route));
      } catch (Exception e) {
        LOG.warning(String.format(Locale.ROOT, "Failed to parse subnet: %s", route));
      }
    }

    if (mergeDefaultRoute) {
      final String[] subnetStrings = vpnService.getResources().getStringArray(
        vpnService.getResourceId(PRIVATE_LAN_BYPASS_SUBNETS_ID, "array"));
      for (final String subnetString : subnetStrings) {
        try {
          subnets.add(Subnet.parse(subnetString));
        } catch (Exception e) {
          LOG.warning(String.format(Locale.ROOT, "Failed to parse subnet: %s", subnetString));
        }
      }
    }

    if (subnets.size() == 0) {
      // 默认所有流量都走VPN
      subnets.add(Subnet.parse("0.0.0.0/0"));
    }

    return subnets;
  }

  /* Returns a subnet list that excludes reserved subnets. */
  private ArrayList<Subnet> getReservedBypassSubnets() {
    final String[] subnetStrings = vpnService.getResources().getStringArray(
        vpnService.getResourceId(PRIVATE_LAN_BYPASS_SUBNETS_ID, "array"));
    ArrayList<Subnet> subnets = new ArrayList<>(subnetStrings.length);
    for (final String subnetString : subnetStrings) {
      try {
        subnets.add(Subnet.parse(subnetString));
      } catch (Exception e) {
        LOG.warning(String.format(Locale.ROOT, "Failed to parse subnet: %s", subnetString));
      }
    }
    return subnets;
  }

  /* Represents an IP subnet. */
  private static class Subnet {
    public String address;
    public int prefix;

    public Subnet(String address, int prefix) {
      this.address = address;
      this.prefix = prefix;
    }

    /* Parses a subnet in CIDR format. */
    public static Subnet parse(final String subnet) throws IllegalArgumentException {
      if (subnet == null) {
        throw new IllegalArgumentException("Must provide a subnet string");
      }
      final String[] components = subnet.split("/", 2);
      if (components.length != 2) {
        throw new IllegalArgumentException("Malformed subnet string");
      }
      return new Subnet(components[0], Integer.parseInt(components[1]));
    }
  }
}
