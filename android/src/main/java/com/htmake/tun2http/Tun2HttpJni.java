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

package com.htmake.tun2http;

import java.util.logging.Level;
import java.util.Locale;
import java.util.logging.Logger;
import android.net.VpnService;

public class Tun2HttpJni {
  private static final String TUN2HTTP = "tun2http";
  private static final Logger LOG = Logger.getLogger(TUN2HTTP);

  static {
    System.loadLibrary(TUN2HTTP);
  }

  /**
   * Starts the tun2http native binary. Blocks until tun2http is stopped.
   *
   * @param vpnInterfaceFileDescriptor file descriptor to the VPN TUN device; used to receive
   *     traffic. Should be set to non-blocking mode. tun2Socks does *not* take ownership of the
   *     file descriptor; the caller is responsible for closing it after tun2http terminates.
   * @param transparentDNS if non-zero, will resolve DNS queries transparently.
   * @param rcode response code doesn't know usage
   * @param proxyIp HTTP proxy address
   * @param proxyPort HTTP proxy port
   */
  public static native int start(int vpnInterfaceFileDescriptor, boolean transparentDNS, int rcode, String proxyIp, int proxyPort, VpnService serviceObj, String proxyAuth, int logLevel);

  public static native int stop();

  public static native int get_mtu();
}
