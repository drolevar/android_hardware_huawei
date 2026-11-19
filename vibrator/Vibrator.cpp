/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "android.hardware.vibrator-service.huawei"

#include "Vibrator.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/unique_fd.h>

#include <algorithm>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

namespace aidl::android::hardware::vibrator {

namespace {

int32_t baseEffectDurationMs(Effect effect) {
    switch (effect) {
        case Effect::TICK:
            return 12;
        case Effect::CLICK:
        case Effect::POP:
            return 20;
        case Effect::THUD:
            return 30;
        case Effect::HEAVY_CLICK:
            return 35;
        case Effect::DOUBLE_CLICK:
            return 45;
        default:
            return -1;
    }
}

int32_t scaleDurationForStrength(int32_t baseMs, EffectStrength strength) {
    switch (strength) {
        case EffectStrength::LIGHT:
            return std::max<int32_t>(8, (baseMs * 75) / 100);
        case EffectStrength::MEDIUM:
            return baseMs;
        case EffectStrength::STRONG:
            return (baseMs * 130) / 100;
        default:
            return -1;
    }
}

void notifyCallback(const std::shared_ptr<IVibratorCallback>& cb) {
    if (cb == nullptr) {
        return;
    }
    const ndk::ScopedAStatus status = cb->onComplete();
    if (!status.isOk()) {
        LOG(WARNING) << "IVibratorCallback::onComplete failed: " << status.getDescription();
    }
}

}  // namespace

Vibrator::Vibrator(std::string sysfsRoot)
    : mSysfsRoot(std::move(sysfsRoot)),
      mWorker(&Vibrator::workerLoop, this) {}

Vibrator::~Vibrator() {
    {
        std::lock_guard<std::mutex> lock(mMutex);
        mStopping = true;
        mHasDeadline = false;
        ++mSequence;
        mPendingCallback.reset();
    }
    mCv.notify_all();
    if (mWorker.joinable()) {
        mWorker.join();
    }
}

bool Vibrator::writeSysfsNode(const char* node, const std::string& value) const {
    const std::string path = mSysfsRoot + "/" + node;
    android::base::unique_fd fd(TEMP_FAILURE_RETRY(open(path.c_str(), O_WRONLY | O_CLOEXEC)));
    if (fd < 0) {
        PLOG(ERROR) << "Failed to open vibrator sysfs node " << path;
        return false;
    }
    if (!android::base::WriteFully(fd, value.data(), value.size())) {
        PLOG(ERROR) << "Failed to write to vibrator sysfs node " << path;
        return false;
    }
    return true;
}

void Vibrator::workerLoop() {
    std::unique_lock<std::mutex> lock(mMutex);
    while (!mStopping) {
        if (!mHasDeadline) {
            mCv.wait(lock, [this] { return mStopping || mHasDeadline; });
            continue;
        }
        const uint64_t seq = mSequence;
        const auto deadline = mDeadline;
        if (mCv.wait_until(lock, deadline,
                           [this, seq] { return mStopping || !mHasDeadline || mSequence != seq; })) {
            continue;
        }
        std::shared_ptr<IVibratorCallback> cb = std::move(mPendingCallback);
        mPendingCallback.reset();
        mHasDeadline = false;
        lock.unlock();
        notifyCallback(cb);
        lock.lock();
    }
}

ndk::ScopedAStatus Vibrator::getCapabilities(int32_t* _aidl_return) {
    *_aidl_return = IVibrator::CAP_ON_CALLBACK | IVibrator::CAP_PERFORM_CALLBACK;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::off() {
    std::shared_ptr<IVibratorCallback> prevCallback;
    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        prevCallback = std::move(mPendingCallback);
        mPendingCallback.reset();
        mHasDeadline = false;
        ++mSequence;
        ok = writeSysfsNode("activate", "0\n");
    }
    mCv.notify_all();
    notifyCallback(prevCallback);
    return ok ? ndk::ScopedAStatus::ok()
              : ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
}

ndk::ScopedAStatus Vibrator::on(int32_t timeoutMs,
                               const std::shared_ptr<IVibratorCallback>& callback) {
    if (timeoutMs <= 0) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    std::shared_ptr<IVibratorCallback> prevCallback;
    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        prevCallback = std::move(mPendingCallback);
        mPendingCallback.reset();
        mHasDeadline = false;
        ++mSequence;

        // Cancel any in-flight transient trigger so activate transitions 0 -> 1 cleanly.
        ok = writeSysfsNode("activate", "0\n") &&
             writeSysfsNode("state", "1\n") &&
             writeSysfsNode("duration", std::to_string(timeoutMs) + "\n") &&
             writeSysfsNode("activate", "1\n");
        if (ok && callback != nullptr) {
            mPendingCallback = callback;
            mHasDeadline = true;
            mDeadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
        }
    }
    mCv.notify_all();
    notifyCallback(prevCallback);
    return ok ? ndk::ScopedAStatus::ok()
              : ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
}

ndk::ScopedAStatus Vibrator::perform(Effect effect, EffectStrength strength,
                                    const std::shared_ptr<IVibratorCallback>& callback,
                                    int32_t* _aidl_return) {
    const int32_t baseMs = baseEffectDurationMs(effect);
    if (baseMs < 0) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
    const int32_t durationMs = scaleDurationForStrength(baseMs, strength);
    if (durationMs < 0) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }

    ndk::ScopedAStatus status = on(durationMs, callback);
    if (!status.isOk()) {
        return status;
    }
    *_aidl_return = durationMs;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getSupportedEffects(std::vector<Effect>* _aidl_return) {
    *_aidl_return = {
            Effect::CLICK,
            Effect::DOUBLE_CLICK,
            Effect::TICK,
            Effect::THUD,
            Effect::POP,
            Effect::HEAVY_CLICK,
    };
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setAmplitude(float /*amplitude*/) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Vibrator::setExternalControl(bool /*enabled*/) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Vibrator::getCompositionDelayMax(int32_t* maxDelayMs) {
    *maxDelayMs = 0;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getCompositionSizeMax(int32_t* maxSize) {
    *maxSize = 0;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getSupportedPrimitives(
        std::vector<CompositePrimitive>* supported) {
    supported->clear();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getPrimitiveDuration(CompositePrimitive /*primitive*/,
                                                 int32_t* /*durationMs*/) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Vibrator::compose(const std::vector<CompositeEffect>& /*composite*/,
                                    const std::shared_ptr<IVibratorCallback>& /*callback*/) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Vibrator::getSupportedAlwaysOnEffects(std::vector<Effect>* _aidl_return) {
    _aidl_return->clear();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::alwaysOnEnable(int32_t /*id*/, Effect /*effect*/,
                                           EffectStrength /*strength*/) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus Vibrator::alwaysOnDisable(int32_t /*id*/) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

}  // namespace aidl::android::hardware::vibrator
