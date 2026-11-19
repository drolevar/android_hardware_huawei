/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <string>
#include <utility>
#include <vector>

std::string trim_prop_value(std::string value);
std::string extract_product_id(const std::string& cmdline);
std::vector<std::pair<std::string, std::string>> parse_phone_prop_section(
        const std::string& product_id, const std::string& content);

void load_connectivity();
