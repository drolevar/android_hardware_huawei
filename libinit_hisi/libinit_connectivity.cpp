/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "libinit_connectivity"

#include <libinit_connectivity.h>

#include <algorithm>
#include <cctype>
#include <iterator>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#ifndef LIBINIT_CONNECTIVITY_HOST_TEST
#include <android-base/file.h>
#include <android-base/logging.h>
#include <libinit_utils.h>
#include <unistd.h>
#endif

namespace {

constexpr const char* kDefaultId = "0X00000000";

#if !defined(LIBINIT_CONNECTIVITY_HOST_TEST) && !defined(__ANDROID_RECOVERY__)
constexpr const char* kChiptypePath = "/proc/connectivity/chiptype";
constexpr const char* kDeviceTreePath = "/proc/device-tree";
constexpr const char* kCmdlinePath = "/proc/cmdline";

constexpr const char* kPropChipType = "ro.connectivity.chiptype";
constexpr const char* kPropSubChipType = "ro.connectivity.sub_chiptype";

constexpr const char* kPhonePropPaths[] = {
        "/vendor/phone.prop",
        "/odm/phone.prop",
};
#endif

// ro.telephony.default_network comes from product/vendor props,
// ro.odm.config.modem_number is SKU-selected in init.hisi.odm.rc, and
// sys.rilprops_ready is asserted on boot after modem mounts in init.ril.core.rc.
constexpr const char* kDenylistedProperties[] = {
        "ro.telephony.default_network",
        "ro.odm.config.modem_number",
        "sys.rilprops_ready",
};

std::string to_upper_ascii(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    return s;
}

bool is_denylisted(const std::string& key) {
    return std::find(std::begin(kDenylistedProperties), std::end(kDenylistedProperties), key) !=
           std::end(kDenylistedProperties);
}

}  // namespace

std::string trim_prop_value(std::string value) {
    const std::size_t nul = value.find('\0');
    if (nul != std::string::npos) {
        value.resize(nul);
    }
    const auto not_space = [](unsigned char c) { return !std::isspace(c); };
    value.erase(value.begin(), std::find_if(value.begin(), value.end(), not_space));
    value.erase(std::find_if(value.rbegin(), value.rend(), not_space).base(), value.end());
    return value;
}

std::string extract_product_id(const std::string& cmdline) {
    std::istringstream iss(cmdline);
    std::string token;
    std::string product_id;
    while (iss >> token) {
        constexpr const char kPrefix[] = "productid=";
        constexpr std::size_t kPrefixLen = sizeof(kPrefix) - 1;
        if (token.compare(0, kPrefixLen, kPrefix) == 0) {
            product_id = to_upper_ascii(trim_prop_value(token.substr(kPrefixLen)));
        }
    }
    if (product_id.empty() || product_id == kDefaultId) {
        return "";
    }
    return product_id;
}

std::vector<std::pair<std::string, std::string>> parse_phone_prop_section(
        const std::string& product_id, const std::string& content) {
    std::vector<std::pair<std::string, std::string>> result;
    const std::string upper_id = to_upper_ascii(trim_prop_value(product_id));
    if (upper_id.empty()) {
        return result;
    }

    const std::string header = "[" + upper_id + "]";
    std::istringstream iss(content);
    std::string raw_line;
    bool in_section = false;

    while (std::getline(iss, raw_line)) {
        const std::string line = trim_prop_value(raw_line);
        if (!in_section) {
            if (!line.empty() && to_upper_ascii(line) == header) {
                in_section = true;
            }
            continue;
        }
        if (line.empty() || line[0] == '[') {
            break;
        }
        if (line[0] == '#') {
            continue;
        }
        const std::size_t eq = line.find('=');
        if (eq == std::string::npos) {
            continue;
        }
        const std::string key = trim_prop_value(line.substr(0, eq));
        const std::string val = trim_prop_value(line.substr(eq + 1));
        if (!key.empty() && !is_denylisted(key)) {
            result.emplace_back(key, val);
        }
    }
    return result;
}

void load_connectivity() {
#if !defined(LIBINIT_CONNECTIVITY_HOST_TEST) && !defined(__ANDROID_RECOVERY__)
    std::string chip_type;
    if (android::base::ReadFileToString(kChiptypePath, &chip_type)) {
        chip_type = trim_prop_value(chip_type);
    }
    if (!chip_type.empty()) {
        property_override(kPropChipType, chip_type);

        std::string subchip_path;
        if (chip_type.rfind("hisi", 0) == 0) {
            subchip_path = std::string(kDeviceTreePath) + "/hi110x/hi110x,subchip_type";
            if (access(subchip_path.c_str(), F_OK) != 0) {
                subchip_path = std::string(kDeviceTreePath) + "/hi1102/name";
            }
        } else {
            subchip_path = std::string(kDeviceTreePath) + "/bcm_wifi/ic_type";
        }

        std::string sub_chip_type;
        if (android::base::ReadFileToString(subchip_path, &sub_chip_type)) {
            sub_chip_type = trim_prop_value(sub_chip_type);
            if (!sub_chip_type.empty()) {
                property_override(kPropSubChipType, sub_chip_type);
            } else {
                LOG(WARNING) << "Empty subchip type at " << subchip_path;
            }
        } else {
            LOG(ERROR) << "Unable to read subchip type from " << subchip_path;
        }
    } else {
        LOG(ERROR) << "Unable to read " << kChiptypePath;
    }

    std::string cmdline;
    if (!android::base::ReadFileToString(kCmdlinePath, &cmdline)) {
        LOG(WARNING) << "Unable to read " << kCmdlinePath;
        return;
    }
    const std::string product_id = extract_product_id(cmdline);
    if (product_id.empty()) {
        LOG(WARNING) << "No non-default productid in " << kCmdlinePath;
        return;
    }

    for (const char* path : kPhonePropPaths) {
        std::string content;
        if (!android::base::ReadFileToString(path, &content)) {
            continue;
        }
        const auto entries = parse_phone_prop_section(product_id, content);
        if (!entries.empty()) {
            for (const auto& [key, val] : entries) {
                property_override(key, val);
            }
            LOG(INFO) << "Loaded " << entries.size() << " phone properties from " << path
                      << " for " << product_id;
            return;
        }
    }
    LOG(WARNING) << "No phone.prop section found for " << product_id;
#endif
}
