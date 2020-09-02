package me.jjbuchan.tryesper.tryesper.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
/**
 * In prod, have an abstract class that contains required fields, then extend that for individual
 * monitoring system classes.
 */
public class Metric {

  String tenantId;
  String resourceId;
  String monitorId;
  String taskId;
  String monitorType;
  String monitorScope;
  String monitorZone;
  String value;
  String metricName;
  Map<String, String> tags = new HashMap<>();
  List<String> excludedResourceIds = new ArrayList<>();

  // in prod how should we treat the different tags?
  // one large map that assigns prefixes to distinguish between system/device/metric metadata?
  // but allow esper to only look in one single place for all values?
}
