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

const HTTP_SERVER = {
  name: 'Local',
  type: SocketType.HTTP,
  host: '192.168.0.123',
  port: 8080,
  username: 'test',
  password: 'test123',
};

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
      console.log('getTunnelStatus  ', status);
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

  function toggleVPN(config: any) {
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
        console.log('vpn2sock config: ', config);
        // 开启VPN
        Vpn2sock.isReachable(config.host, config.port).then((r) => {
          console.log('isReachable  ', r);
        });
        Vpn2sock.startTunnel(TUNNEL_ID, config).then((r) => {
          console.log('startTunnel  ', r);
          Vpn2sock.getActivedTunnelId().then((i) => {
            console.log('getActivedTunnelId  ', i);
          });
        });
      }
      return !v;
    });
  }

  return (
    <View style={styles.container}>
      <Text>VPNStatus: {statusText}</Text>
      <View style={styles.button}>
        <Button
          title={isVpnStart ? '关闭VPN' : '开启VPN2SHADOWSOCKS'}
          onPress={() => {
            toggleVPN(SHADOWSOCKS_SERVER);
          }}
        />
      </View>
      <View style={styles.button}>
        <Button
          title={isVpnStart ? '关闭VPN' : '开启VPN2SOCKS'}
          onPress={() => {
            toggleVPN(SOCKET5_SERVER);
          }}
        />
      </View>
      <View style={styles.button}>
        <Button
          title={isVpnStart ? '关闭VPN' : '开启VPN2HTTP'}
          onPress={() => {
            toggleVPN(HTTP_SERVER);
          }}
        />
      </View>
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
  button: {
    marginTop: 10,
  },
});
