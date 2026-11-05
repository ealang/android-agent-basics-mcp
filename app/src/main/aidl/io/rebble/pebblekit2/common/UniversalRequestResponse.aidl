// The PebbleKit 2 binder contract, copied unchanged from PebbleKitAndroid2
// (https://github.com/pebble-dev/PebbleKitAndroid2, common/src/main/aidl,
// Apache License 2.0). Spoken directly rather than through the client
// library; PebbleKit2.kt says why.
package io.rebble.pebblekit2.common;

import io.rebble.pebblekit2.common.SendDataCallback;

interface UniversalRequestResponse {
    void request(in Bundle data, in SendDataCallback callback);
}
