/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Compile-only header stubs for cold-tree extract-files.py compilation
 * (excluded from runtime classes2.dex; Soong also builds src/ against full platform jars).
 */

package android.os;

public class AsyncResult {
    public Object userObj;
    public Throwable exception;
    public Object result;

    public static AsyncResult forMessage(Message m, Object r, Throwable ex) {
        return null;
    }
}
