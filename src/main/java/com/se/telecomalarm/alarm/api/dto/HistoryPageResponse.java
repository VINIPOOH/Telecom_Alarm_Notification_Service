package com.se.telecomalarm.alarm.api.dto;

import java.util.List;

public record HistoryPageResponse(
        List<HistoryItemResponse> items,
        String next) {
}
