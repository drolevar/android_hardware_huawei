/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.huawei.cust;

import java.io.File;

public class HwCfgFilePolicy {
    private static final String[] SEARCH_ROOTS = {
        "/odm/etc",
        "/vendor/etc",
        "/product/etc",
        "/system/etc",
    };

    public static File getCfgFile(String path, int type) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String rel = path.startsWith("/") ? path.substring(1) : path;
        for (String root : SEARCH_ROOTS) {
            File candidate = new File(root, rel);
            if (candidate.isFile() && candidate.canRead()) {
                return candidate;
            }
        }
        return null;
    }
}
