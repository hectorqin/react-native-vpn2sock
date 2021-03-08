import { NativeModules } from 'react-native';

type SocketConfig = {
  name?: string;
  type: number;
  host: string;
  port: number;
  username?: string;
  password?: string;
  method?: string;
  udpRelay?: boolean; // 是否支持 udp relay
  dnsServer?: string; // DNS server
  vpnMode?: number; // VPN 模式，分为全局，白名单，黑名单
  applications?: Array<string>; // 应用 packageName 列表，白名单或者黑名单
};

export const APPLICATION_MODE = {
  GLOBAL_MODE: 0,
  WHITELIST_MODE: 1,
  BLACKLIST_MODE: 2,
};

export const ErrorCode = {
  NO_ERROR: 0,
  UNEXPECTED: 1,
  VPN_PERMISSION_NOT_GRANTED: 2,
  INVALID_SERVER_CREDENTIALS: 3,
  UDP_RELAY_NOT_ENABLED: 4,
  SERVER_UNREACHABLE: 5,
  VPN_START_FAILURE: 6,
  ILLEGAL_SERVER_CONFIGURATION: 7,
  SHADOWSOCKS_START_FAILURE: 8,
  CONFIGURE_SYSTEM_PROXY_FAILURE: 9,
  NO_ADMIN_PERMISSIONS: 10,
  UNSUPPORTED_ROUTING_TABLE: 11,
  SYSTEM_MISCONFIGURED: 12,
};

export const TunnelStatus = {
  INVALID: -1, // Internal use only.
  DISCONNECTED: 0,
  CONNECTING: 1,
  CONNECTED: 2,
  RECONNECTING: 3,
};

export const SocketType = {
  SHADOWSOCKS: 0,
  SOCKS5: 1,
};

type Vpn2sockType = {
  startTunnel(tunnelId: string, config: SocketConfig): Promise<boolean>;
  stopTunnel(tunnelId: string): Promise<boolean>;
  getActivedTunnelId(): Promise<string>;
  getTunnelStatus(): Promise<number>;
  isRunning(tunnelId: string): Promise<boolean>;
  isReachable(host: string, port: number): Promise<boolean>;
  getPackageList(): Promise<Array<Object>>;
};

const { Vpn2sock } = NativeModules;

export default Vpn2sock as Vpn2sockType;
