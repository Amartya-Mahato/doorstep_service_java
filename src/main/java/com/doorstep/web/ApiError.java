package com.doorstep.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Error body for every failed request; details is only present for field validation errors. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String timestamp, int status, String code, String message, String path, List<String> details) {
}
