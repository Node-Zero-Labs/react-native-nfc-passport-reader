import { DeviceEventEmitter, Platform } from 'react-native';
import NativeNfcPassportReader from './NativeNfcPassportReader';

const LINKING_ERROR =
  `The package 'react-native-nfc-passport-reader' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';
const NfcPassportReaderNativeModule = NativeNfcPassportReader
  ? NativeNfcPassportReader
  : new Proxy({} as any, {
      get() {
        throw new Error(LINKING_ERROR);
      },
    });
enum NfcPassportReaderEvent {
  TAG_DISCOVERED = 'onTagDiscovered',
  NFC_STATE_CHANGED = 'onNfcStateChanged',
  READ_RETRY = 'onReadRetry',
}
export type StartReadingParams = {
  bacKey: {
    documentNo: string;
    expiryDate: string;
    birthDate: string;
  };
  includeImages?: boolean; // default: false
};
export type NfcResult = {
  birthDate: string;
  placeOfBirth?: string;
  documentNo: string;
  expiryDate: string;
  firstName: string;
  gender: string;
  identityNo?: string;
  lastName: string;
  mrz: string;
  nationality: string;
  originalFacePhoto?: string; // base64
  dg1Hex?: string; // raw EF.DG1 file bytes, hex-encoded
  sodHex?: string; // raw EF.SOD file bytes, hex-encoded
};
export default class NfcPassportReader {
  static startReading(params: StartReadingParams): Promise<NfcResult> {
    return NfcPassportReaderNativeModule.startReading(params);
  }
  static stopReading() {
    if (Platform.OS === 'android') {
      NfcPassportReaderNativeModule.stopReading();
    } else {
      throw new Error('Unsupported platform');
    }
  }
  static addOnTagDiscoveredListener(callback: () => void) {
    if (Platform.OS === 'android') {
      this.addListener(NfcPassportReaderEvent.TAG_DISCOVERED, callback);
    }
  }
  static addOnNfcStateChangedListener(callback: (state: 'off' | 'on') => void) {
    if (Platform.OS === 'android') {
      this.addListener(NfcPassportReaderEvent.NFC_STATE_CHANGED, callback);
    }
  }
  // Fired when a read attempt fails with a recoverable error (e.g. brief RF
  // misalignment) and the native side is retrying instead of failing the scan.
  static addOnReadRetryListener(callback: (message: string) => void) {
    if (Platform.OS === 'android') {
      this.addListener(NfcPassportReaderEvent.READ_RETRY, callback);
    }
  }
  static isNfcEnabled(): Promise<boolean> {
    if (Platform.OS === 'android') {
      return NfcPassportReaderNativeModule.isNfcEnabled();
    } else if (Platform.OS === 'ios') {
      return NfcPassportReaderNativeModule.isNfcSupported();
    } else {
      throw new Error('Unsupported platform');
    }
  }
  static isNfcSupported(): Promise<boolean> {
    return NfcPassportReaderNativeModule.isNfcSupported();
  }
  static openNfcSettings(): Promise<boolean> {
    if (Platform.OS === 'android') {
      return NfcPassportReaderNativeModule.openNfcSettings();
    } else {
      throw new Error('Unsupported platform');
    }
  }
  private static addListener(
    event: NfcPassportReaderEvent,
    callback: (data: any) => void
  ) {
    DeviceEventEmitter.addListener(event, callback);
  }
  static removeListeners() {
    if (Platform.OS === 'android') {
      DeviceEventEmitter.removeAllListeners(
        NfcPassportReaderEvent.TAG_DISCOVERED
      );
      DeviceEventEmitter.removeAllListeners(
        NfcPassportReaderEvent.NFC_STATE_CHANGED
      );
      DeviceEventEmitter.removeAllListeners(
        NfcPassportReaderEvent.READ_RETRY
      );
    }
  }
}
