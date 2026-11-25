//
// SPDX-FileCopyrightText: 2026 The LineageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package androidx.camera.extensions.impl;

public class ExtensionVersionImpl {
    private static final String VERSION = "1.4.0";

    public ExtensionVersionImpl() {}

    public String checkApiVersion(String version) {
        return VERSION;
    }

    public boolean isAdvancedExtenderImplemented() {
        return false;
    }
}
