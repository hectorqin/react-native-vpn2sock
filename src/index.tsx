import { NativeModules } from 'react-native';

type SocketConfig = {
  type: number;
  host: string;
  port: number;
  username: string | null;
  password: string | null;
  method: string | null;
};

type Vpn2sockType = {
  multiply(a: number, b: number): Promise<number>;
  startTunnel(tunnelId: string, config: SocketConfig): Promise<boolean>;
  stopTunnel(tunnelId: string): Promise<boolean>;
  isRunning(tunnelId: string): Promise<boolean>;
  isReachable(host: string, port: number): Promise<boolean>;
};

const { Vpn2sock } = NativeModules;

export default Vpn2sock as Vpn2sockType;
