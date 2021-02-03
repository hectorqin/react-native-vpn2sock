import * as React from 'react';

import {
  StyleSheet,
  View,
  Text,
  Button,
  DeviceEventEmitter,
} from 'react-native';
import Vpn2sock, { SocketType, TunnelStatus } from 'react-native-vpn2sock';

const TUNNEL_ID = 'test-vpn';

const SHADOWSOCKS_SERVER = {
  name: 'TW',
  type: SocketType.SHADOWSOCKS,
  host: '59.125.11.158',
  port: 8158,
  password: 'passinfo123',
  method: 'rc4-md5',
};

const SOCKET5_SERVER = {
  name: 'Local',
  type: SocketType.SOCKS5,
  host: '192.168.0.123',
  port: 1080,
};

let SOCKET_SERVER: any;

// Use shadowsocks server
SOCKET_SERVER = SHADOWSOCKS_SERVER;
// Use socket5 server
SOCKET_SERVER = SOCKET5_SERVER;

export default function App() {
  const [statusText, setStatusText] = React.useState<string>('');
  const [isVpnStart, setIsVpnStart] = React.useState<boolean>(false);

  React.useEffect(() => {
    function setStatus(status: number) {
      setStatusText(
        status === TunnelStatus.CONNECTING
          ? '连接中'
          : status === TunnelStatus.CONNECTED
          ? '已连接'
          : status === TunnelStatus.DISCONNECTED
          ? '未连接'
          : status === TunnelStatus.RECONNECTING
          ? '重连中'
          : '无效'
      );
    }
    Vpn2sock.getTunnelStatus().then((status) => {
      setStatus(status);
    });
    const listener = DeviceEventEmitter.addListener(
      'onVPNStatusChange',
      (event) => {
        console.log('onVPNStatusChange  ', event);
        setStatus(event.status);
      }
    );
    return () => {
      listener.remove();
    };
  }, []);

  return (
    <View style={styles.container}>
      <Text>VPNStatus: {statusText}</Text>
      <Button
        title={isVpnStart ? '关闭VPN' : '开启VPN'}
        onPress={() => {
          setIsVpnStart((v) => {
            Vpn2sock.isRunning(TUNNEL_ID).then((r) => {
              console.log('isRunning  ', r);
            });
            if (v) {
              // 关闭VPN
              Vpn2sock.stopTunnel(TUNNEL_ID).then((r) => {
                console.log('stopTunnel  ', r);
              });
            } else {
              // 开启VPN
              Vpn2sock.isReachable(SOCKET_SERVER.host, SOCKET_SERVER.port).then(
                (r) => {
                  console.log('isReachable  ', r);
                }
              );
              Vpn2sock.startTunnel(TUNNEL_ID, SOCKET_SERVER).then((r) => {
                console.log('startTunnel  ', r);
                Vpn2sock.getActivedTunnelId().then((i) => {
                  console.log('getActivedTunnelId  ', i);
                });
              });
            }
            return !v;
          });
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  box: {
    width: 60,
    height: 60,
    marginVertical: 20,
  },
});
