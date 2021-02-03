# react-native-vpn2sock

vpn2socks package for react-native. Not support IOS currently.

## Installation

```sh
npm install react-native-vpn2sock
```

## Usage

```js
import Vpn2sock from "react-native-vpn2sock";

// ...

Vpn2sock.isRunning(TUNNEL_ID).then((r) => {
  console.log('isRunning  ', r);
});
// 关闭VPN
Vpn2sock.stopTunnel(TUNNEL_ID).then((r) => {
  console.log('stopTunnel  ', r);
});
// host 能否访问
Vpn2sock.isReachable(SOCKET_SERVER.host, SOCKET_SERVER.port).then(
  (r) => {
    console.log('isReachable  ', r);
  }
);
// 开启VPN
Vpn2sock.startTunnel(TUNNEL_ID, SOCKET_SERVER).then((r) => {
  console.log('startTunnel  ', r);
});
```

## Contributing

See the [contributing guide](CONTRIBUTING.md) to learn how to contribute to the repository and the development workflow.

## License

MIT
