//
// SPDX-FileCopyrightText: 2026 The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package androidx.camera.extensions.impl;

import android.content.Context;

import java.util.concurrent.Executor;

public class InitializerImpl {
    public static final int ERROR_UNKNOWN = 0;
    public static final int ERROR_INITIALIZE_VERSION_INCOMPATIBLE = 1;

    private InitializerImpl() {}

    public static void init(String version, Context context,
            final OnExtensionsInitializedCallback callback, Executor executor) {
        if (callback == null) {
            return;
        }
        if (executor == null) {
            callback.onSuccess();
        } else {
            executor.execute(callback::onSuccess);
        }
    }

    public static void deinit(final OnExtensionsDeinitializedCallback callback,
            Executor executor) {
        if (callback == null) {
            return;
        }
        if (executor == null) {
            callback.onSuccess();
        } else {
            executor.execute(callback::onSuccess);
        }
    }

    public interface OnExtensionsInitializedCallback {
        void onSuccess();

        void onFailure(int error);
    }

    public interface OnExtensionsDeinitializedCallback {
        void onSuccess();

        void onFailure(int error);
    }
}
