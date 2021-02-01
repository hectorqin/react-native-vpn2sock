import { NativeModules } from 'react-native';

type Vpn2sockType = {
  multiply(a: number, b: number): Promise<number>;
};

const { Vpn2sock } = NativeModules;

export default Vpn2sock as Vpn2sockType;
