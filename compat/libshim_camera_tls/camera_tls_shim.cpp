/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <limits.h>
#include <pthread.h>
#include <stdlib.h>

#ifndef PTHREAD_DESTRUCTOR_ITERATIONS
#define PTHREAD_DESTRUCTOR_ITERATIONS 4
#endif

namespace {

struct TlsDtorNode {
    void (*dtor)(void*);
    void* obj;
    TlsDtorNode* next;
};

pthread_once_t g_key_once = PTHREAD_ONCE_INIT;
pthread_key_t g_tls_key;
int g_key_err = -1;

void run_tls_dtors(void* ptr) {
    TlsDtorNode* head = static_cast<TlsDtorNode*>(ptr);
    for (int iter = 0; head != nullptr && iter < PTHREAD_DESTRUCTOR_ITERATIONS; ++iter) {
        while (head != nullptr) {
            TlsDtorNode* node = head;
            head = node->next;
            void (*dtor)(void*) = node->dtor;
            void* obj = node->obj;
            free(node);
            if (dtor != nullptr) {
                dtor(obj);
            }
        }
        // Pick up any additional TLS destructors registered while tearing down earlier ones.
        if (iter + 1 < PTHREAD_DESTRUCTOR_ITERATIONS) {
            head = static_cast<TlsDtorNode*>(pthread_getspecific(g_tls_key));
            if (head != nullptr) {
                (void)pthread_setspecific(g_tls_key, nullptr);
            }
        }
    }
}

void init_tls_key() {
    g_key_err = pthread_key_create(&g_tls_key, run_tls_dtors);
}

}  // namespace

/*
 * CameraDaemon dlopen()s vendor/odm camera libraries whose .init_array constructors
 * spawn helper threads (e.g. IMX319) and wait on them while bionic linker64 holds
 * g_dl_mutex. Those helper threads call libutils::Looper::prepare(), which initializes
 * a thread_local and calls __cxa_thread_atexit(). Routing to bionic's
 * __cxa_thread_atexit_impl re-enters linker64::__loader_add_thread_local_dtor and
 * deadlocks on g_dl_mutex.
 *
 * Linked as DT_NEEDED with DF_1_GLOBAL | DF_1_NODELETE strictly for CameraDaemon,
 * whose loaded camera DSOs remain mapped for process lifetime, so
 * (void)dso_symbol is intentional and zero libdl/linker64 calls are made here.
 */
extern "C" __attribute__((visibility("default"))) int __cxa_thread_atexit(
        void (*dtor)(void*), void* obj, void* dso_symbol) {
    (void)dso_symbol;
    if (dtor == nullptr) {
        return 0;
    }

    (void)pthread_once(&g_key_once, init_tls_key);
    if (g_key_err != 0) {
        return -1;
    }

    TlsDtorNode* node = static_cast<TlsDtorNode*>(malloc(sizeof(TlsDtorNode)));
    if (node == nullptr) {
        return -1;
    }

    node->dtor = dtor;
    node->obj = obj;
    node->next = static_cast<TlsDtorNode*>(pthread_getspecific(g_tls_key));
    if (pthread_setspecific(g_tls_key, node) != 0) {
        free(node);
        return -1;
    }
    return 0;
}
